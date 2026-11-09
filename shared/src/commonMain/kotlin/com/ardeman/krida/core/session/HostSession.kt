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
 * - Bots ([addBot]) move when the host app calls [stepBot], so it can pace them to its
 *   animations; the session itself has no timers.
 * - Remote players connect through [transport] and send intents. When one drops out mid-game,
 *   the others see the seat disconnected; after [Krida.HANDOFF_WAIT_MS] the host app hands it
 *   to a bot ([expiredSeats], [replaceWithBot]) so nobody is stuck on that turn. The player
 *   can still rejoin within the reconnect grace and takes the seat back from the bot.
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

    private inner class Seat(
        var name: String,
        val platform: Platform,
        var peer: String?,          // null = local player or bot
        val token: String,
        var bot: Bot<S, M>? = null,
        var connected: Boolean = true,
        var disconnectedAt: Long = 0,
        var color: Int = -1,        // -1 = the game has no colours
    )

    private val seats = mutableListOf<Seat>()

    @Suppress("UNCHECKED_CAST")
    private val colorRules = rules as? SeatColorRules<S>

    /** Number of pawn colours players pick from in the lobby; 0 when the game has none. */
    val colorCount: Int get() = colorRules?.colorCount ?: 0

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
            PlayerInfo(seat = i, name = s.name, platform = s.platform, connected = s.connected || s.bot != null,
                local = s.peer == null && s.bot == null, bot = s.bot != null,
                color = s.color.takeIf { it >= 0 })
        }

    /** Adds a player sitting at this device. Returns the seat, or null if full/started. */
    fun addLocalPlayer(name: String, platform: Platform): Int? {
        if (started || seats.size >= capacity()) return null
        seat(Seat(name, platform, peer = null, token = newToken()))
        lobbyChanged()
        return seats.lastIndex
    }

    /** Adds a computer player run by this device. Returns the seat, or null if full/started. */
    fun addBot(name: String, platform: Platform, bot: Bot<S, M>): Int? {
        if (started || seats.size >= capacity()) return null
        seat(Seat(name, platform, peer = null, token = newToken(), bot = bot))
        lobbyChanged()
        return seats.lastIndex
    }

    /** Removes a computer player before the game starts; later seats move up one. */
    fun removeBot(seat: Int): Boolean {
        if (started || seats.getOrNull(seat)?.bot == null) return false
        seats.removeAt(seat)
        lobbyChanged()
        return true
    }

    /**
     * Gives a seat at this device (a local player or a bot) another pawn colour before the
     * game starts. Returns false when the game has no colours, [color] is out of range or
     * another seat holds it. Guests pick their own with `GuestSession.pickColor`.
     */
    fun setColor(seat: Int, color: Int): Boolean {
        val s = seats.getOrNull(seat) ?: return false
        if (s.peer != null) return false
        return changeColor(s, color)
    }

    /** Seat of the bot whose turn it is, or null when a person is to move (or no game is on). */
    fun botToMove(): Int? {
        val current = state ?: return null
        if (rules.isFinished(current)) return null
        val seat = rules.currentTurn(current)
        return if (seats.getOrNull(seat)?.bot != null) seat else null
    }

    /**
     * Plays one move for the bot whose turn it is. Returns false when no bot is to move.
     * Call it after each state change (once the previous move has animated) until it returns
     * false; a bot that earns another turn simply moves again on the next call.
     */
    fun stepBot(): Boolean {
        val seat = botToMove() ?: return false
        val move = seats[seat].bot!!.chooseMove(state!!, seat, random)
        val reason = submit(seat, move)
        check(reason == null) { "Bot at seat $seat chose an illegal move: $reason" }
        return true
    }

    /**
     * Remote seats whose player dropped out of a started game more than [Krida.HANDOFF_WAIT_MS]
     * ago and that no bot plays yet. The host app checks this now and then (the session has no
     * timers) and passes each seat to [replaceWithBot].
     */
    fun expiredSeats(): List<Int> {
        if (!started) return emptyList()
        return seats.indices.filter { waiting(seats[it]) && handOffInMs(it) == 0L }
    }

    /**
     * Milliseconds until a dropped player's seat is due for a bot (0 when due), or -1 when
     * [seat] is not waiting for its player. For the host's "Waiting for Ayu… 15" countdown;
     * guests count down on their own from the `state` that shows the seat disconnected.
     */
    fun handOffInMs(seat: Int): Long {
        val s = seats.getOrNull(seat) ?: return -1
        if (!started || !waiting(s)) return -1
        return (Krida.HANDOFF_WAIT_MS - (clock() - s.disconnectedAt)).coerceAtLeast(0)
    }

    /**
     * Lets [bot] play a disconnected remote seat. The seat keeps its name and is shown with
     * `bot` set. Its player can still rejoin within the reconnect grace (counted from the
     * drop) and takes the seat back; after that the bot keeps it for the rest of the game and
     * any restart. Returns false when [seat] is not a disconnected remote seat without a bot.
     * Call [stepBot] afterwards as usual, in case it is that seat's turn.
     */
    fun replaceWithBot(seat: Int, bot: Bot<S, M>): Boolean {
        val s = seats.getOrNull(seat) ?: return false
        if (!started || !waiting(s)) return false
        s.bot = bot
        broadcastState()
        return true
    }

    /** A remote player dropped out and no bot stands in yet. */
    private fun waiting(s: Seat): Boolean = s.peer != null && !s.connected && s.bot == null

    fun canStart(): Boolean = !started && seats.size >= rules.minPlayers

    fun start() {
        check(canStart()) { "Need at least ${rules.minPlayers} players" }
        started = true
        state = newGame()
        lastSeat = null
        lastMove = null
        seq = 1
        broadcastState()
    }

    /** New game with the same seats, once the current one is finished. [seq] keeps rising. */
    fun restart() {
        val current = state
        check(started && current != null && rules.isFinished(current)) { "Game is not finished" }
        state = newGame()
        lastSeat = null
        lastMove = null
        seq++
        broadcastState()
    }

    /** A local player's move. Returns null when applied, otherwise an [InvalidReason] / rules reason. */
    fun submitLocal(seat: Int, move: M): String? {
        val s = seats.getOrNull(seat) ?: return InvalidReason.NOT_SEATED
        if (s.peer != null || s.bot != null) return InvalidReason.NOT_SEATED
        return submit(seat, move)
    }

    // ---- TransportListener -------------------------------------------------

    override fun onPeerConnected(peer: String) {
        // Wait for Hello.
    }

    override fun onPeerDisconnected(peer: String) {
        val index = seats.indexOfFirst { it.peer == peer }
        if (index < 0 || !seats[index].connected) return
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
            is Message.PickColor -> handlePickColor(peer, msg)
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
                seat.bot = null   // takes the seat back from a stand-in bot
                send(peer, Message.Welcome(rejoin, roomCode, rules.id, seat.token))
                if (started) broadcastState() else lobbyChanged()
                return
            }
        }

        if (started) return reject(peer, RejectReason.STARTED)
        if (seats.size >= capacity()) return reject(peer, RejectReason.FULL)

        val seat = seat(Seat(hello.name.take(24), hello.platform, peer, newToken()))
        send(peer, Message.Welcome(seats.lastIndex, roomCode, rules.id, seat.token))
        lobbyChanged()
    }

    private fun handlePickColor(peer: String, pick: Message.PickColor) {
        val s = seats.firstOrNull { it.peer == peer && it.connected } ?: return
        if (!changeColor(s, pick.color)) {
            // Refused: resend the lobby so the guest's picker shows the colour it still has.
            if (!started) send(peer, Message.Lobby(players, you = seats.indexOf(s)))
        }
    }

    private fun changeColor(s: Seat, color: Int): Boolean {
        if (started || color !in 0 until colorCount) return false
        if (seats.any { it !== s && it.color == color }) return false
        if (s.color != color) {
            s.color = color
            lobbyChanged()
        }
        return true
    }

    /** Adds [s] to the seats with the first free colour (if the game has colours). */
    private fun seat(s: Seat): Seat {
        s.color = (0 until colorCount).firstOrNull { c -> seats.none { it.color == c } } ?: -1
        seats += s
        return s
    }

    private fun newGame(): S =
        colorRules?.initialState(seats.size, seats.map { it.color }, random)
            ?: rules.initialState(seats.size, random)

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
