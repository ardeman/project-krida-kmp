package com.ardeman.krida.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.ardeman.krida.core.protocol.Chunker
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.Reassembler
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener
import java.util.UUID

/**
 * BLE host: GATT server + advertiser. Guests (Android or iOS) connect, subscribe to
 * `toGuest`, and write frames to `toHost`. Peer id = device MAC address.
 *
 * Room code goes in the scan response as service data (Android can't set a custom
 * advertised name without renaming the whole phone).
 *
 * All callbacks are hopped to the main thread before touching state or the listener.
 */
@SuppressLint("MissingPermission") // permissions are requested in MainActivity before start()
class BleHostTransport(
    private val context: Context,
    private val roomCode: String,
) : Transport {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var sink: TransportListener? = null
    private var server: BluetoothGattServer? = null
    private var toGuest: BluetoothGattCharacteristic? = null

    private val guests = HashMap<String, BluetoothDevice>()   // subscribed guests
    private val mtus = HashMap<String, Int>()
    private val queues = HashMap<String, ArrayDeque<ByteArray>>()
    private val inFlight = HashSet<String>()                  // Android allows one notify in flight per device
    private val chunker = Chunker()
    private val reassembler = Reassembler()

    override fun setListener(listener: TransportListener?) { sink = listener }

    fun start() {
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val toHost = BluetoothGattCharacteristic(
            TO_HOST,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            TO_GUEST,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        service.addCharacteristic(toHost)
        service.addCharacteristic(notify)
        toGuest = notify

        server = manager.openGattServer(context, callback)
        server?.addService(service) // advertising starts in onServiceAdded
    }

    override fun send(peer: String?, data: ByteArray) {
        val targets = if (peer == null) guests.keys.toList() else listOf(peer).filter { it in guests }
        for (address in targets) {
            val frames = chunker.split(data, mtus[address] ?: Chunker.MIN_MTU)
            queues.getOrPut(address) { ArrayDeque() }.addAll(frames)
            pump(address)
        }
    }

    override fun disconnect(peer: String) {
        guests[peer]?.let { server?.cancelConnection(it) }
    }

    override fun close() {
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        guests.values.forEach { server?.cancelConnection(it) }
        server?.close()
        server = null
        guests.clear()
        queues.clear()
        inFlight.clear()
    }

    private fun pump(address: String) {
        if (address in inFlight) return
        val frame = queues[address]?.removeFirstOrNull() ?: return
        val device = guests[address] ?: return
        val characteristic = toGuest ?: return
        inFlight += address
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server?.notifyCharacteristicChanged(device, characteristic, false, frame)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BLE advertising not supported on this device")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SERVICE), roomCode.encodeToByteArray())
            .setIncludeDeviceName(false)
            .build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post { if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) main.post { dropGuest(device.address) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { mtus[device.address] = mtu }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (descriptor.uuid != CCCD) return
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            main.post {
                if (enabled && device.address !in guests) {
                    guests[device.address] = device
                    sink?.onPeerConnected(device.address)
                } else if (!enabled) {
                    dropGuest(device.address)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid != TO_HOST) return
            val frame = value.copyOf()
            main.post {
                reassembler.accept(device.address, frame)?.let { sink?.onMessage(device.address, it) }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                inFlight -= device.address
                pump(device.address)
            }
        }
    }

    private fun dropGuest(address: String) {
        val wasGuest = guests.remove(address) != null
        mtus.remove(address)
        queues.remove(address)
        inFlight.remove(address)
        reassembler.clear(address)
        if (wasGuest) sink?.onPeerDisconnected(address)
    }

    companion object {
        private const val TAG = "KridaBleHost"
        val SERVICE: UUID = UUID.fromString(Krida.BLE_SERVICE_UUID)
        val TO_HOST: UUID = UUID.fromString(Krida.BLE_TO_HOST_UUID)
        val TO_GUEST: UUID = UUID.fromString(Krida.BLE_TO_GUEST_UUID)
        val CCCD: UUID = UUID.fromString(Krida.BLE_CCCD_UUID)
    }
}
