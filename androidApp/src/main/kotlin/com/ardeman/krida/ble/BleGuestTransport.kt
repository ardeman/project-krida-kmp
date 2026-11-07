package com.ardeman.krida.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.ardeman.krida.ble.BleHostTransport.Companion.CCCD
import com.ardeman.krida.ble.BleHostTransport.Companion.SERVICE
import com.ardeman.krida.ble.BleHostTransport.Companion.TO_GUEST
import com.ardeman.krida.ble.BleHostTransport.Companion.TO_HOST
import com.ardeman.krida.core.protocol.Chunker
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.Reassembler
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener

/**
 * BLE guest: scans for a Krida host with the given room code, connects, subscribes,
 * and writes frames. Finds both Android hosts (room code in service data) and iOS hosts
 * (room code in the advertised local name "Krida-ABCD").
 */
@SuppressLint("MissingPermission") // permissions are requested in MainActivity before start()
class BleGuestTransport(
    private val context: Context,
    private val roomCode: String,
) : Transport {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var sink: TransportListener? = null
    private var gatt: BluetoothGatt? = null
    private var toHost: BluetoothGattCharacteristic? = null
    private var mtu = Chunker.MIN_MTU
    private var scanning = false
    private var ready = false

    private val outbox = ArrayDeque<ByteArray>()
    private var writing = false
    private val chunker = Chunker()
    private val reassembler = Reassembler()

    override fun setListener(listener: TransportListener?) { sink = listener }

    fun start() {
        val scanner = manager.adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner.startScan(filters, settings, scanCallback)
    }

    override fun send(peer: String?, data: ByteArray) {
        if (!ready) return
        outbox.addAll(chunker.split(data, mtu))
        pump()
    }

    override fun disconnect(peer: String) {}

    override fun close() {
        stopScan()
        ready = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        outbox.clear()
    }

    private fun stopScan() {
        if (scanning) manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    private fun pump() {
        if (writing) return
        val frame = outbox.removeFirstOrNull() ?: return
        val g = gatt ?: return
        val c = toHost ?: return
        writing = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private fun roomOf(result: ScanResult): String? {
        val record = result.scanRecord ?: return null
        record.serviceData[ParcelUuid(SERVICE)]?.let { return RoomCode.parse(it.decodeToString()) }
        record.deviceName?.let { return RoomCode.parse(it) }
        return null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning || roomOf(result) != roomCode) return@post
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(Krida.BLE_PREFERRED_MTU)
                BluetoothProfile.STATE_DISCONNECTED -> main.post {
                    val wasReady = ready
                    ready = false
                    writing = false
                    outbox.clear()
                    reassembler.clear(TransportListener.HOST_PEER)
                    g.close()
                    if (gatt == g) gatt = null
                    if (wasReady) sink?.onPeerDisconnected(TransportListener.HOST_PEER)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleGuestTransport.mtu = mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE) ?: return
            toHost = service.getCharacteristic(TO_HOST)
            val notify = service.getCharacteristic(TO_GUEST) ?: return
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD || status != BluetoothGatt.GATT_SUCCESS) return
            main.post {
                ready = true
                sink?.onPeerConnected(TransportListener.HOST_PEER)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                writing = false
                pump()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            receive(c, value.copyOf())
        }

        // Android 8–12
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                receive(c, c.value?.copyOf() ?: return)
            }
        }
    }

    private fun receive(c: BluetoothGattCharacteristic, frame: ByteArray) {
        if (c.uuid != TO_GUEST) return
        main.post {
            reassembler.accept(TransportListener.HOST_PEER, frame)?.let {
                sink?.onMessage(TransportListener.HOST_PEER, it)
            }
        }
    }

    companion object {
        private const val TAG = "KridaBleGuest"
    }
}
