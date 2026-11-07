package com.ardeman.krida.core.transport

/**
 * Pass-and-play on one device: every player is local, so nothing is ever sent.
 * Pair it with [com.ardeman.krida.core.session.HostSession.addLocalPlayer] for each player.
 */
class LocalTransport : Transport {
    override fun setListener(listener: TransportListener?) {}
    override fun send(peer: String?, data: ByteArray) {}
    override fun disconnect(peer: String) {}
    override fun close() {}
}
