package com.ardeman.krida.core.session

import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.Message
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener

/**
 * A remote player's view of a room. Sends intents, renders whatever the host says.
 * Says Hello automatically when the transport reports the host connected.
 */
class GuestSession<S, M>(
    private val rules: GameRules<S, M>,
    private val transport: Transport,
    private val name: String,
    private val platform: Platform,
    /** Pass the token from a previous Welcome to reclaim the same seat. */
    rejoinToken: String? = null,
) : TransportListener {

    var seat: Int? = null
        private set
    var roomCode: String? = null
        private set
    var rejoinToken: String? = rejoinToken
        private set
    var players: List<PlayerInfo> = emptyList()
        private set
    var state: S? = null
        private set
    var turn: Int = -1
        private set
    var finished: Boolean = false
        private set
    var stateSeq: Int = 0
        private set

    /**
     * Seat and move that produced [state], when it directly follows the previous snapshot
     * (null after a gap, a rejoin, or for the first snapshot). Read in [onState] to animate.
     */
    var lastSeat: Int? = null
        private set
    var lastMove: M? = null
        private set

    private var intentSeq = 0

    var onLobby: ((List<PlayerInfo>) -> Unit)? = null
    var onState: ((S) -> Unit)? = null
    /** The player list changed during a game without a move (connected / bot flags). */
    var onPlayers: ((List<PlayerInfo>) -> Unit)? = null
    var onRejected: ((String) -> Unit)? = null
    var onInvalid: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var join: GuestJoin? = null

    /**
     * Takes over a [GuestJoin] once its game is known: the Hello is already sent, and the
     * Welcome and anything after it are replayed here. Set the callbacks, then call [replay].
     */
    constructor(rules: GameRules<S, M>, join: GuestJoin) :
        this(rules, join.transport, join.name, join.platform, null) {
        this.join = join
    }

    init {
        transport.setListener(this)
    }

    /** After the [GuestJoin] constructor: delivers the messages the join received so far. */
    fun replay() {
        join?.handOver(this)
        join = null
    }

    val isMyTurn: Boolean get() = seat != null && seat == turn && !finished

    /** Returns the intent seq (matches a later Invalid), or null if not seated. */
    fun sendMove(move: M): Int? {
        if (seat == null) return null
        val seq = ++intentSeq
        val json = Codec.json.encodeToJsonElement(rules.moveSerializer, move)
        transport.send(null, Codec.encode(Message.Intent(seq, json)))
        return seq
    }

    fun leave() {
        transport.send(null, Codec.encode(Message.Bye))
        transport.close()
    }

    override fun onPeerConnected(peer: String) {
        val hello = Message.Hello(Krida.PROTOCOL_VERSION, name, platform, rejoinToken)
        transport.send(null, Codec.encode(hello))
    }

    override fun onPeerDisconnected(peer: String) {
        onDisconnected?.invoke()
    }

    override fun onMessage(peer: String, data: ByteArray) {
        when (val msg = Codec.decode(data) ?: return) {
            is Message.Welcome -> {
                seat = msg.seat
                roomCode = msg.roomCode
                rejoinToken = msg.rejoinToken
            }
            is Message.Lobby -> {
                seat = msg.you
                players = msg.players
                onLobby?.invoke(players)
            }
            is Message.State -> {
                if (msg.seq < stateSeq) return // stale
                if (msg.seq == stateSeq) {
                    // Same game state, new player list (someone dropped out, rejoined, or
                    // was handed to the computer).
                    players = msg.players
                    onPlayers?.invoke(players)
                    return
                }
                val decoded = try {
                    Codec.json.decodeFromJsonElement(rules.stateSerializer, msg.state)
                } catch (e: Exception) {
                    return
                }
                val follows = msg.seq == stateSeq + 1
                val last = msg.last?.takeIf { follows }?.let { l ->
                    try {
                        l.seat to Codec.json.decodeFromJsonElement(rules.moveSerializer, l.move)
                    } catch (e: Exception) {
                        null
                    }
                }
                lastSeat = last?.first
                lastMove = last?.second
                stateSeq = msg.seq
                turn = msg.turn
                finished = msg.finished
                players = msg.players
                state = decoded
                onState?.invoke(decoded)
            }
            is Message.Reject -> onRejected?.invoke(msg.reason)
            is Message.Invalid -> onInvalid?.invoke(msg.reason)
            is Message.Ping -> transport.send(null, Codec.encode(Message.Pong(msg.t)))
            else -> Unit
        }
    }
}
