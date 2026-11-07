package com.ardeman.krida.core.transport

/**
 * In-memory network: one host end, any number of guest ends. Delivery is synchronous.
 * Used by tests and for debugging multiplayer on a single device.
 */
class LoopbackNetwork {
    private val guests = LinkedHashMap<String, GuestEnd>()
    private val hostEnd = HostEnd()

    val host: Transport get() = hostEnd

    /** Creates a guest end. Call setListener on it, then [connect]. */
    fun guest(id: String): Transport = guests.getOrPut(id) { GuestEnd(id) }

    fun connect(id: String) {
        val g = guests[id] ?: error("Unknown guest $id")
        g.connected = true
        hostEnd.sink?.onPeerConnected(id)
        g.sink?.onPeerConnected(TransportListener.HOST_PEER)
    }

    fun drop(id: String) {
        val g = guests[id] ?: return
        if (!g.connected) return
        g.connected = false
        hostEnd.sink?.onPeerDisconnected(id)
        g.sink?.onPeerDisconnected(TransportListener.HOST_PEER)
    }

    private inner class HostEnd : Transport {
        var sink: TransportListener? = null
        override fun setListener(listener: TransportListener?) { sink = listener }
        override fun send(peer: String?, data: ByteArray) {
            val targets = if (peer == null) guests.values.filter { it.connected } else listOfNotNull(guests[peer])
            targets.filter { it.connected }.forEach { it.sink?.onMessage(TransportListener.HOST_PEER, data.copyOf()) }
        }
        override fun disconnect(peer: String) = drop(peer)
        override fun close() { guests.keys.toList().forEach(::drop) }
    }

    private inner class GuestEnd(val id: String) : Transport {
        var sink: TransportListener? = null
        var connected = false
        override fun setListener(listener: TransportListener?) { sink = listener }
        override fun send(peer: String?, data: ByteArray) {
            if (connected) hostEnd.sink?.onMessage(id, data.copyOf())
        }
        override fun disconnect(peer: String) {}
        override fun close() = drop(id)
    }
}
