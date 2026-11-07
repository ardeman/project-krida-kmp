package com.ardeman.krida.core.transport

/**
 * Moves whole encoded messages between devices. Implemented natively per platform
 * (BLE, LAN, online). Plain callbacks, no coroutines, so Swift can implement it directly.
 *
 * Transports must call the listener on ONE thread (e.g. the main thread) so sessions
 * need no locking.
 */
interface Transport {
    fun setListener(listener: TransportListener?)

    /**
     * Host side: [peer] = a guest id, or null to send to every connected guest.
     * Guest side: [peer] is ignored; everything goes to the host.
     */
    fun send(peer: String?, data: ByteArray)

    /** Host side: drop one guest (e.g. after a reject). Guest side: no-op. */
    fun disconnect(peer: String)

    fun close()
}

interface TransportListener {
    /** Guest side reports the host as peer id [HOST_PEER]. */
    fun onPeerConnected(peer: String)
    fun onPeerDisconnected(peer: String)
    fun onMessage(peer: String, data: ByteArray)

    companion object {
        const val HOST_PEER = "host"
    }
}
