package com.ardeman.krida.nearby

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.ble.BleGuestTransport
import com.ardeman.krida.ble.BleHostTransport
import com.ardeman.krida.congklak.CongklakSettings
import com.ardeman.krida.congklak.CongklakTable
import com.ardeman.krida.congklak.GuestTable
import com.ardeman.krida.congklak.HostTable
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.UlarTanggaBot
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.GuestJoin
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.core.session.SavedRoom
import com.ardeman.krida.core.session.SeatColorRules
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.online.OnlineGuestTransport
import com.ardeman.krida.online.OnlineHostTransport
import com.ardeman.krida.online.OnlineTransport
import com.ardeman.krida.ulartangga.UlarTanggaGuestTable
import com.ardeman.krida.ulartangga.UlarTanggaHostTable
import com.ardeman.krida.ulartangga.UlarTanggaSettings
import com.ardeman.krida.ulartangga.UlarTanggaTable

/**
 * A room over BLE ("Nearby") or through the online relay ("Online"), from hosting or joining
 * until the game starts. Then [congklak] or [ular] takes over the session and that game's
 * screen shows it. A guest joins by code and learns the game from the host's Welcome.
 */
class NearbyRoom private constructor(
    val code: String,
    val isHost: Boolean,
    /** Through the relay rather than over Bluetooth. */
    val online: Boolean,
    private val transport: Transport,
) {
    /**
     * Where the room stands; a guest goes SEARCHING → CONNECTING → LOBBY, or stops at a
     * failure. OFFLINE: the relay couldn't be reached (online only, host or guest).
     */
    enum class Stage { SEARCHING, CONNECTING, LOBBY, FULL, STARTED, VERSION, NOT_FOUND, LOST, UNKNOWN_GAME, REFUSED, OFFLINE }

    /** The room's game id (`GameRules.id`); null for a guest until the host welcomes it. */
    var game by mutableStateOf<String?>(null)
        private set
    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        private set
    var maxPlayers by mutableStateOf(2)
        private set
    var canStart by mutableStateOf(false)
        private set
    var stage by mutableStateOf(if (isHost) Stage.LOBBY else Stage.SEARCHING)
        private set

    /** This device's seat: 0 for the host, the host's choice for a guest (null until then). */
    var mySeat by mutableStateOf<Int?>(if (isHost) 0 else null)
        private set

    /** Pawn colours to pick from in the lobby (ular tangga); 0 when the game has none. */
    var colorCount by mutableStateOf(0)
        private set

    /** The host can fill empty seats with computers (ular tangga only). */
    var canAddBots by mutableStateOf(false)
        private set

    var congklak by mutableStateOf<CongklakTable?>(null)
        private set
    var ular by mutableStateOf<UlarTanggaTable?>(null)
        private set

    val failed: Boolean get() = stage !in listOf(Stage.SEARCHING, Stage.CONNECTING, Stage.LOBBY)

    /** What the host chose, for [retry]. */
    private var hostGame: Game? = null

    private var begin: (() -> Unit)? = null
    private var guest: GuestSession<*, *>? = null
    private var join: GuestJoin? = null
    private var pickColor: ((Int) -> Unit)? = null
    private var addBot: ((String) -> Unit)? = null
    private var removeBot: ((Int) -> Unit)? = null
    private var quickMatchTarget = 0
    private var quickMatchExpectedHumans = 0
    private var quickMatchHumans = 0
    private var quickMatchBots = 0
    private var quickMatchBotName = ""

    fun configureQuickMatch(humans: Int, bots: Int, botName: String) {
        quickMatchTarget = humans + bots
        quickMatchExpectedHumans = humans
        canAddBots = false
        canStart = false
        quickMatchHumans = humans
        quickMatchBots = bots
        quickMatchBotName = botName
        fillQuickMatchSeats()
    }

    private fun fillQuickMatchSeats() {
        if (quickMatchHumans == 0 || players.count { !it.bot } < quickMatchHumans) return
        val missing = quickMatchBots - players.count { it.bot }
        // Clear before adding: addBot synchronously calls onPlayersChanged again.
        quickMatchHumans = 0
        repeat(missing.coerceAtLeast(0)) { addBot?.invoke(quickMatchBotName) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val notFound = Runnable {
        if (stage == Stage.SEARCHING) {
            // Only the relay's NO_ROOM response proves an online room is missing.
            stage = if (online) Stage.OFFLINE else Stage.NOT_FOUND
            leave()
        }
    }

    fun start() {
        if (canStart) begin?.invoke()
    }

    /** Asks for pawn colour [color]: the host takes it at once, a guest asks the host. */
    fun pickColor(color: Int) {
        pickColor?.invoke(color)
    }

    /** Seats a computer called [name] in the next empty seat (host, ular tangga). */
    fun addBot(name: String) {
        if (quickMatchTarget == 0) addBot?.invoke(name)
    }

    fun removeBot(seat: Int) {
        if (quickMatchTarget == 0) removeBot?.invoke(seat)
    }

    fun leave() {
        main.removeCallbacks(notFound)
        // A guest leaving the lobby gives up its seat; leaving a running game (the tables
        // handle that) or a search keeps it to come back to.
        if (!isHost && congklak == null && ular == null && stage == Stage.LOBBY) LastRoom.clear(code)
        when {
            congklak != null -> congklak?.leave()
            ular != null -> ular?.leave()
            guest != null -> guest?.leave()
            join != null -> join?.leave()
            else -> transport.close()
        }
    }

    /** Leaves and opens the same kind of room again: a new one for a host, the same code for a guest. */
    fun retry(context: Context, name: String): NearbyRoom {
        leave()
        val game = hostGame
        return if (isHost && game != null) host(context, name, game, online) else join(context, code, name, online)
    }

    private fun <S, M> hostSession(rules: GameRules<S, M>, name: String, transport: Transport): HostSession<S, M> {
        game = rules.id
        maxPlayers = rules.maxPlayers
        val session = HostSession(rules, transport, roomCode = code)
        colorCount = session.colorCount
        session.onPlayersChanged = { list ->
            players = list
            fillQuickMatchSeats()
            canStart = session.canStart() && (quickMatchTarget == 0 ||
                players.size == quickMatchTarget && players.count { !it.bot } >= quickMatchExpectedHumans)
        }
        session.addLocalPlayer(name, Platform.ANDROID)
        pickColor = { session.setColor(0, it) }
        return session
    }

    private fun <S, M> guestSession(rules: GameRules<S, M>, join: GuestJoin): GuestSession<S, M> {
        game = rules.id
        maxPlayers = rules.maxPlayers
        colorCount = (rules as? SeatColorRules<*>)?.colorCount ?: 0
        val session = GuestSession(rules, join)
        session.onLobby = { list ->
            players = list
            mySeat = session.seat
            stage = Stage.LOBBY
        }
        session.onRejected = ::rejected
        session.onDisconnected = ::lost
        pickColor = session::pickColor
        guest = session
        return session
    }

    private fun rejected(reason: String) {
        main.removeCallbacks(notFound)
        LastRoom.clear(code)
        stage = when (reason) {
            RejectReason.VERSION -> Stage.VERSION
            RejectReason.FULL -> Stage.FULL
            RejectReason.STARTED -> Stage.STARTED
            else -> Stage.REFUSED
        }
    }

    /** The relay closed a guest's socket for good; the game's table shows drops once it runs. */
    private fun relayClosed(closeCode: Int) {
        main.removeCallbacks(notFound)
        if (failed || congklak != null || ular != null) return
        if (closeCode != OnlineTransport.GAVE_UP) LastRoom.clear(code)
        stage = when (closeCode) {
            OnlineTransport.NO_ROOM -> Stage.NOT_FOUND
            OnlineTransport.FULL -> Stage.FULL
            OnlineTransport.KICKED -> Stage.REFUSED
            OnlineTransport.GAVE_UP -> if (stage == Stage.SEARCHING) Stage.OFFLINE else Stage.LOST
            else -> Stage.LOST
        }
    }

    private fun lost() {
        main.removeCallbacks(notFound)
        if (!failed) stage = Stage.LOST
    }

    enum class Game { CONGKLAK, ULAR_TANGGA }

    companion object {
        /** A guest that hasn't reached the host by then gives up; online may be unreachable. */
        private const val NOT_FOUND_MS = 20_000L

        fun host(context: Context, name: String, game: Game, online: Boolean = false, code: String? = null, onOnlineReady: (() -> Unit)? = null): NearbyRoom {
            val roomCode = code ?: if (online) RoomCode.generateOnline() else RoomCode.generate()
            val transport: Transport = if (online) OnlineHostTransport(roomCode) else BleHostTransport(context, roomCode)
            val room = NearbyRoom(roomCode, isHost = true, online = online, transport = transport)
            room.hostGame = game
            when (game) {
                Game.CONGKLAK -> {
                    val session = room.hostSession(CongklakSettings.rules, name, transport)
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.congklak = HostTable(session, mySeat = 0, transport = transport)
                        }
                    }
                }
                Game.ULAR_TANGGA -> {
                    val session = room.hostSession(UlarTanggaSettings.rules, name, transport)
                    room.canAddBots = true
                    room.addBot = { botName -> session.addBot(botName, Platform.ANDROID, UlarTanggaBot) }
                    room.removeBot = { seat -> session.removeBot(seat) }
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.ular = UlarTanggaHostTable(session, mySeat = 0, transport = transport)
                        }
                    }
                }
            }
            when (transport) {
                is BleHostTransport -> transport.start()
                is OnlineHostTransport -> {
                    // Only before the game: once it runs, the table shows the drop.
                    transport.onReady = onOnlineReady
                    transport.onClosed = { if (room.congklak == null && room.ular == null) room.stage = Stage.OFFLINE }
                    transport.start()
                }
            }
            return room
        }

        fun join(context: Context, code: String, name: String, online: Boolean = false): NearbyRoom {
            val transport: Transport = if (online) OnlineGuestTransport(code) else BleGuestTransport(context, code)
            val room = NearbyRoom(code, isHost = false, online = online, transport = transport)
            // Joining a room this device has a seat in asks for that seat back.
            val join = GuestJoin(transport, name, Platform.ANDROID, LastRoom.tokenFor(code, online))
            room.join = join
            join.onConnected = { if (room.stage == Stage.SEARCHING) room.stage = Stage.CONNECTING }
            join.onRejected = room::rejected
            join.onDisconnected = room::lost
            join.onGame = { game ->
                join.rejoinToken?.let { LastRoom.save(SavedRoom(code, online, game, it, System.currentTimeMillis())) }
                when (game) {
                    CongklakGame.id -> room.guestSession(CongklakGame, join).also { s ->
                        s.onState = { _ -> room.congklak = GuestTable(s) }
                        s.replay()
                    }
                    UlarTanggaGame.id -> room.guestSession(UlarTanggaGame, join).also { s ->
                        s.onState = { _ -> room.ular = UlarTanggaGuestTable(s) }
                        s.replay()
                    }
                    else -> room.stage = Stage.UNKNOWN_GAME
                }
            }
            room.main.postDelayed(room.notFound, NOT_FOUND_MS)
            when (transport) {
                is BleGuestTransport -> transport.start()
                is OnlineGuestTransport -> {
                    transport.onClosed = { closeCode -> room.relayClosed(closeCode) }
                    transport.start()
                }
            }
            return room
        }
    }
}
