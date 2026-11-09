package com.ardeman.krida.core.session

import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.Message
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener

/**
 * Joining a room by its code before knowing which game it plays. Says Hello when the host
 * connects and reports the game id from Welcome through [onGame]; the app then creates a
 * `GuestSession(rules, join)` for that game, which takes over the transport and replays the
 * messages received so far.
 */
class GuestJoin(
    val transport: Transport,
    val name: String,
    val platform: Platform,
) : TransportListener {
    private val received = mutableListOf<Pair<String, ByteArray>>()
    private var handedOver = false

    /** The room's game id (`GameRules.id`), once the host has welcomed this device. */
    var game: String? = null
        private set

    var onGame: ((String) -> Unit)? = null
    var onRejected: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    init {
        transport.setListener(this)
    }

    fun leave() {
        transport.send(null, Codec.encode(Message.Bye))
        transport.close()
    }

    /** Called by the GuestSession constructor: hands the transport over and replays. */
    internal fun handOver(listener: TransportListener) {
        check(!handedOver) { "Already handed over" }
        handedOver = true
        transport.setListener(listener)
        received.forEach { (peer, data) -> listener.onMessage(peer, data) }
        received.clear()
    }

    override fun onPeerConnected(peer: String) {
        transport.send(null, Codec.encode(Message.Hello(Krida.PROTOCOL_VERSION, name, platform, null)))
    }

    override fun onPeerDisconnected(peer: String) {
        onDisconnected?.invoke()
    }

    override fun onMessage(peer: String, data: ByteArray) {
        val msg = Codec.decode(data)
        if (msg !is Message.Ping) received += peer to data
        when (msg) {
            is Message.Welcome -> if (game == null) {
                game = msg.game
                onGame?.invoke(msg.game)
            }
            is Message.Reject -> onRejected?.invoke(msg.reason)
            is Message.Ping -> transport.send(null, Codec.encode(Message.Pong(msg.t)))
            else -> Unit
        }
    }
}
