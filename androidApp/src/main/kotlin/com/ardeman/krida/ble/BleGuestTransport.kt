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
import com.ardeman.krida.ble.BleHostTransport.Companion.ROOM
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
 * and writes frames. Finds Android hosts (room code in service data) and iOS hosts
 * (room code in the local name "Krida-ABCD", or the readable room characteristic when
 * the name was dropped from a full advertising packet).
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
    private var pendingNotify: BluetoothGattCharacteristic? = null
    private var mtu = Chunker.MIN_MTU
    private var scanning = false
    private var ready = false
    /** Advertisement already matched [roomCode]; skip the post-connect room check. */
    private var codeVerified = false
    /** Addresses we tentatively connected to and rejected (wrong room). */
    private val rejected = mutableSetOf<String>()

    private val outbox = ArrayDeque<ByteArray>()
    private var writing = false
    private val chunker = Chunker()
    private val reassembler = Reassembler()

    override fun setListener(listener: TransportListener?) { sink = listener }

    fun start() {
        rejected.clear()
        startScan()
    }

    private fun startScan() {
        val scanner = manager.adapter?.bluetoothLeScanner ?: return
        if (scanning) return
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
        codeVerified = false
        pendingNotify = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        outbox.clear()
        rejected.clear()
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

    /**
     * Room code from an advertisement: Android hosts put it in service data; iOS hosts in the
     * local name. [ScanRecord.deviceName] can be null even when the AD bytes still carry it.
     */
    private fun roomOf(result: ScanResult): String? {
        val record = result.scanRecord ?: return null
        record.serviceData[ParcelUuid(SERVICE)]?.let { data ->
            RoomCode.parse(data.decodeToString())?.let { return it }
        }
        sequenceOf(record.deviceName, localNameFrom(record.bytes), result.device.name)
            .firstNotNullOfOrNull { name -> name?.let(RoomCode::parse) }
            ?.let { return it }
        return null
    }

    /** Complete (0x09) or shortened (0x08) local name from raw advertising bytes. */
    private fun localNameFrom(bytes: ByteArray?): String? {
        if (bytes == null) return null
        var i = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xff
            if (len == 0 || i + len >= bytes.size) break
            val type = bytes[i + 1].toInt() and 0xff
            if (type == 0x08 || type == 0x09) {
                return bytes.decodeToString(i + 2, i + 1 + len)
            }
            i += len + 1
        }
        return null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning || gatt != null) return@post
                val address = result.device.address
                if (address in rejected) return@post
                when (val code = roomOf(result)) {
                    roomCode -> {
                        stopScan()
                        connect(result.device, verified = true)
                    }
                    // Service UUID matched the filter but the name was dropped from the packet
                    // (common for iOS hosts): connect and read the room characteristic.
                    null -> {
                        stopScan()
                        connect(result.device, verified = false)
                    }
                    else -> Unit // another Krida room
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private fun connect(device: BluetoothDevice, verified: Boolean) {
        codeVerified = verified
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Wrong room after a tentative connect: drop it and keep looking. */
    private fun reject(g: BluetoothGatt) {
        val address = g.device.address
        rejected += address
        codeVerified = false
        pendingNotify = null
        g.disconnect()
        g.close()
        if (gatt == g) gatt = null
        startScan()
    }

    private fun enableNotify(g: BluetoothGatt, notify: BluetoothGattCharacteristic) {
        pendingNotify = null
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD) ?: run {
            reject(g)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun verifyRoomValue(g: BluetoothGatt, raw: ByteArray?) {
        val parsed = raw?.decodeToString()?.let(RoomCode::parse)
        if (parsed != roomCode) {
            Log.i(TAG, "Rejecting ${g.device.address}: room=$parsed want=$roomCode")
            reject(g)
            return
        }
        codeVerified = true
        val notify = pendingNotify ?: g.getService(SERVICE)?.getCharacteristic(TO_GUEST)
        if (notify == null) {
            reject(g)
            return
        }
        enableNotify(g, notify)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(Krida.BLE_PREFERRED_MTU)
                BluetoothProfile.STATE_DISCONNECTED -> main.post {
                    val wasReady = ready
                    val needsRescan = !codeVerified && gatt == g
                    ready = false
                    writing = false
                    pendingNotify = null
                    outbox.clear()
                    reassembler.clear(TransportListener.HOST_PEER)
                    g.close()
                    if (gatt == g) gatt = null
                    if (wasReady) {
                        sink?.onPeerDisconnected(TransportListener.HOST_PEER)
                    } else if (needsRescan) {
                        startScan()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleGuestTransport.mtu = mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE) ?: run {
                main.post { reject(g) }
                return
            }
            toHost = service.getCharacteristic(TO_HOST)
            val notify = service.getCharacteristic(TO_GUEST) ?: run {
                main.post { reject(g) }
                return
            }
            if (!codeVerified) {
                val roomChar = service.getCharacteristic(ROOM)
                if (roomChar != null) {
                    pendingNotify = notify
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.readCharacteristic(roomChar)
                    } else {
                        @Suppress("DEPRECATION")
                        g.readCharacteristic(roomChar)
                    }
                    if (!ok) main.post { reject(g) }
                    return
                }
                // Older host without the room characteristic: try the GAP name.
                val parsed = g.device.name?.let(RoomCode::parse)
                if (parsed != roomCode) {
                    Log.i(TAG, "Rejecting ${g.device.address}: name=${g.device.name} want=$roomCode")
                    main.post { reject(g) }
                    return
                }
                codeVerified = true
            }
            enableNotify(g, notify)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid != ROOM) return
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reject(g)
                    return@post
                }
                verifyRoomValue(g, value)
            }
        }

        @Deprecated("Deprecated in Android 13")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid != ROOM) return
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reject(g)
                    return@post
                }
                verifyRoomValue(g, characteristic.value)
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

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            receive(c, value.copyOf())
        }

        @Deprecated("Deprecated in Android 13")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
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
