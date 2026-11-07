package com.ardeman.krida.core.session

import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.protocol.InvalidReason
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.LastMove
import com.ardeman.krida.core.protocol.Message
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The authoritative side of a room. Lives on the host device.
 *
 * - Local players (the host player, pass-and-play) act through [submitLocal].
 * - Remote players connect through [transport] and send intents.
 * - Every applied move bumps [seq] and broadcasts a full snapshot.
 */
class HostSession<S, M>(
    private val rules: GameRules<S, M>,
    private val transport: Transport,
    val roomCode: String = RoomCode.generate(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = defaultClock(),
) : TransportListener {

    /** Swift-friendly constructor (Kotlin default arguments are not visible from Swift). */
    constructor(rules: GameRules<S, M>, transport: Transport, roomCode: String) :
        this(rules, transport, roomCode, Random.Default, defaultClock())

    private class Seat(
        var name: String,
        val platform: Platform,
        var peer: String?,          // null = local player
        val token: String,
        var connected: Boolean = true,
        var disconnectedAt: Long = 0,
    )

    private val seats = mutableListOf<Seat>()

    var started: Boolean = false
        private set
    var seq: Int = 0
        private set
    var state: S? = null
        private set

    /** Seat and move that produced [state] (null before the first move). Read in [onStateChanged]. */
    var lastSeat: Int? = null
        private set
    var lastMove: M? = null
        private set

    /** UI hooks. Called on the transport's thread. */
    var onPlayersChanged: ((List<PlayerInfo>) -> Unit)? = null
    var onStateChanged: ((S) -> Unit)? = null

    init {
        transport.setListener(this)
    }

    val players: List<PlayerInfo>
        get() = seats.mapIndexed { i, s ->
            PlayerInfo(seat = i, name = s.name, platform = s.platform, connected = s.connected, local = s.peer == null)
        }

    /** Adds a player sitting at this device. Returns the seat, or null if full/started. */
    fun addLocalPlayer(name: String, platform: Platform): Int? {
        if (started || seats.size >= capacity()) return null
        seats += Seat(name, platform, peer = null, token = newToken())
        lobbyChanged()
        return seats.lastIndex
    }

    fun canStart(): Boolean = !started && seats.size >= rules.minPlayers

    fun start() {
        check(canStart()) { "Need at least ${rules.minPlayers} players" }
        started = true
        state = rules.initialState(seats.size, random)
        lastSeat = null
        lastMove = null
        seq = 1
        broadcastState()
    }

    /** New game with the same seats, once the current one is finished. [seq] keeps rising. */
    fun restart() {
        val current = state
        check(started && current != null && rules.isFinished(current)) { "Game is not finished" }
        state = rules.initialState(seats.size, random)
        lastSeat = null
        lastMove = null
        seq++
        broadcastState()
    }

    /** A local player's move. Returns null when applied, otherwise an [InvalidReason] / rules reason. */
    fun submitLocal(seat: Int, move: M): String? {
        val s = seats.getOrNull(seat) ?: return InvalidReason.NOT_SEATED
        if (s.peer != null) return InvalidReason.NOT_SEATED
        return submit(seat, move)
    }

    // ---- TransportListener -------------------------------------------------

    override fun onPeerConnected(peer: String) {
        // Wait for Hello.
    }

    override fun onPeerDisconnected(peer: String) {
        val index = seats.indexOfFirst { it.peer == peer }
        if (index < 0) return
        if (!started) {
            seats.removeAt(index)
            lobbyChanged()
        } else {
            seats[index].connected = false
            seats[index].disconnectedAt = clock()
            broadcastState()
        }
    }

    override fun onMessage(peer: String, data: ByteArray) {
        when (val msg = Codec.decode(data) ?: return) {
            is Message.Hello -> handleHello(peer, msg)
            is Message.Intent -> handleIntent(peer, msg)
            is Message.Ping -> send(peer, Message.Pong(msg.t))
            is Message.Bye -> onPeerDisconnected(peer)
            else -> Unit // host ignores host->guest messages
        }
    }

    // ---- internals -----------------------------------------------------------

    private fun handleHello(peer: String, hello: Message.Hello) {
        if (hello.protocol != Krida.PROTOCOL_VERSION) return reject(peer, RejectReason.VERSION)

        // Rejoin with a token we issued earlier.
        val rejoin = hello.rejoinToken?.let { t -> seats.indexOfFirst { it.token == t && it.peer != null } }
        if (rejoin != null && rejoin >= 0) {
            val seat = seats[rejoin]
            val withinGrace = seat.connected || clock() - seat.disconnectedAt <= Krida.RECONNECT_GRACE_MS
            if (withinGrace) {
                seat.peer = peer
                seat.connected = true
                send(peer, Message.Welcome(rejoin, roomCode, rules.id, seat.token))
                if (started) broadcastState() else lobbyChanged()
                return
            }
        }

        if (started) return reject(peer, RejectReason.STARTED)
        if (seats.size >= capacity()) return reject(peer, RejectReason.FULL)

        val seat = Seat(hello.name.take(24), hello.platform, peer, newToken())
        seats += seat
        send(peer, Message.Welcome(seats.lastIndex, roomCode, rules.id, seat.token))
        lobbyChanged()
    }

    private fun handleIntent(peer: String, intent: Message.Intent) {
        val seat = seats.indexOfFirst { it.peer == peer && it.connected }
        if (seat < 0) return send(peer, Message.Invalid(intent.seq, InvalidReason.NOT_SEATED))
        val move = try {
            Codec.json.decodeFromJsonElement(rules.moveSerializer, intent.move)
        } catch (e: Exception) {
            return send(peer, Message.Invalid(intent.seq, InvalidReason.BAD_MOVE))
        }
        val reason = submit(seat, move)
        if (reason != null) send(peer, Message.Invalid(intent.seq, reason))
    }

    private fun submit(seat: Int, move: M): String? {
        val current = state ?: return InvalidReason.NOT_STARTED
        if (rules.isFinished(current)) return InvalidReason.FINISHED
        if (rules.currentTurn(current) != seat) return InvalidReason.NOT_YOUR_TURN
        rules.validate(current, seat, move)?.let { return it }
        state = rules.apply(current, seat, move, random)
        lastSeat = seat
        lastMove = move
        seq++
        broadcastState()
        return null
    }

    private fun broadcastState() {
        val s = state ?: return
        val msg = Message.State(
            seq = seq,
            turn = rules.currentTurn(s),
            finished = rules.isFinished(s),
            state = Codec.json.encodeToJsonElement(rules.stateSerializer, s),
            players = players,
            last = lastMove?.let { m ->
                LastMove(lastSeat!!, Codec.json.encodeToJsonElement(rules.moveSerializer, m))
            },
        )
        val bytes = Codec.encode(msg)
        remotePeers().forEach { transport.send(it, bytes) }
        onPlayersChanged?.invoke(players)
        onStateChanged?.invoke(s)
    }

    private fun lobbyChanged() {
        val list = players
        seats.forEachIndexed { i, s ->
            val peer = s.peer
            if (peer != null && s.connected) send(peer, Message.Lobby(list, you = i))
        }
        onPlayersChanged?.invoke(list)
    }

    private fun reject(peer: String, reason: String) {
        send(peer, Message.Reject(reason))
        transport.disconnect(peer)
    }

    private fun remotePeers(): List<String> = seats.filter { it.connected }.mapNotNull { it.peer }

    private fun send(peer: String, message: Message) = transport.send(peer, Codec.encode(message))

    private fun capacity(): Int = minOf(rules.maxPlayers, Krida.MAX_PLAYERS)

    private fun newToken(): String =
        buildString { repeat(16) { append("0123456789abcdef"[random.nextInt(16)]) } }

    companion object {
        fun defaultClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
