package com.ardeman.krida.nearby

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.ble.BleGuestTransport
import com.ardeman.krida.ble.BleHostTransport
import com.ardeman.krida.lan.LanGuestTransport
import com.ardeman.krida.lan.LanHostTransport
import com.ardeman.krida.congklak.CongklakSettings
import com.ardeman.krida.congklak.CongklakTable
import com.ardeman.krida.congklak.GuestTable
import com.ardeman.krida.congklak.HostTable
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.GapleBot
import com.ardeman.krida.core.games.GapleGame
import com.ardeman.krida.core.games.LudoBot
import com.ardeman.krida.core.games.LudoGame
import com.ardeman.krida.core.games.PerangKapalBot
import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.gaple.GapleGuestTable
import com.ardeman.krida.gaple.GapleHostTable
import com.ardeman.krida.gaple.GapleSettings
import com.ardeman.krida.gaple.GapleTable
import com.ardeman.krida.ludo.LudoGuestTable
import com.ardeman.krida.ludo.LudoHostTable
import com.ardeman.krida.ludo.LudoSettings
import com.ardeman.krida.ludo.LudoTable
import com.ardeman.krida.perangkapal.PerangKapalGuestTable
import com.ardeman.krida.perangkapal.PerangKapalHostTable
import com.ardeman.krida.perangkapal.PerangKapalTable
import com.ardeman.krida.core.games.StandardRules
import com.ardeman.krida.core.games.UlarTanggaBot
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.GuestJoin
import com.ardeman.krida.core.session.GuestSession
import kotlinx.serialization.json.JsonElement
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
 * A room over BLE or LAN ("Nearby") or through the online relay ("Online"), from hosting or
 * joining until the game starts. Then a game table ([congklak], [ludo], [gaple]…) takes over the session and
 * that game's screen shows it. A guest joins by code and learns the game from the host's Welcome.
 */
class NearbyRoom private constructor(
    val code: String,
    val isHost: Boolean,
    /** Through the relay rather than over Bluetooth or Wi‑Fi. */
    val online: Boolean,
    /** Nearby over Wi‑Fi/hotspot instead of BLE; ignored when [online]. */
    val lan: Boolean = false,
    private val transport: Transport,
    val publicRoom: Boolean = false,
    private val hostSeats: Int? = null,
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

    /** The host's rule options, for a guest's lobby (read with `LobbyOptions`); null from older hosts. */
    var lobbyOptions by mutableStateOf<JsonElement?>(null)
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
    var ludo by mutableStateOf<LudoTable?>(null)
        private set
    var perangKapal by mutableStateOf<PerangKapalTable?>(null)
        private set
    var gaple by mutableStateOf<GapleTable?>(null)
        private set

    /** A game table has taken over the session. */
    private val playing: Boolean get() = congklak != null || ular != null || ludo != null || perangKapal != null || gaple != null

    /** Ids of the friend invites whose guests hold a seat now (host only). */
    var joinedInvites by mutableStateOf<List<String>>(emptyList())
        private set

    /** The online room's host key, which sending a friend invite needs; null otherwise. */
    val hostKey: String? get() = (transport as? OnlineHostTransport)?.key

    /** The host of an online room may invite friends; a quick match fills its own seats. */
    val canInvite: Boolean get() = isHost && online && hostKey != null && quickMatchTarget == 0

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
        if (canStart) {
            (transport as? OnlineHostTransport)?.updateLobby(players.count { it.bot }, started = true)
            begin?.invoke()
        }
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
        if (!isHost && !playing && stage == Stage.LOBBY) LastRoom.clear(code)
        when {
            congklak != null -> congklak?.leave()
            ular != null -> ular?.leave()
            ludo != null -> ludo?.leave()
            perangKapal != null -> perangKapal?.leave()
            gaple != null -> gaple?.leave()
            guest != null -> guest?.leave()
            join != null -> join?.leave()
            else -> transport.close()
        }
    }

    /** Leaves and opens the same kind of room again: a new one for a host, the same code for a guest. */
    fun retry(context: Context, name: String, avatar: String? = null): NearbyRoom {
        leave()
        val game = hostGame
        return if (isHost && game != null) {
            host(context, name, game, online, lan = lan, publicRoom = publicRoom, seats = hostSeats, avatar = avatar)
        } else {
            join(context, code, name, online, lan = lan, avatar = avatar)
        }
    }

    private fun <S, M> hostSession(rules: GameRules<S, M>, name: String, transport: Transport, avatar: String?): HostSession<S, M> {
        game = rules.id
        maxPlayers = hostSeats ?: rules.maxPlayers
        val session = HostSession(rules, transport, roomCode = code, maxSeats = maxPlayers)
        colorCount = session.colorCount
        session.onPlayersChanged = { list ->
            players = list
            joinedInvites = session.joinedInvites
            (transport as? OnlineHostTransport)?.updateLobby(list.count { it.bot })
            fillQuickMatchSeats()
            canStart = session.canStart() && (quickMatchTarget == 0 ||
                players.size == quickMatchTarget && players.count { !it.bot } >= quickMatchExpectedHumans)
        }
        session.addLocalPlayer(name, Platform.ANDROID, avatar)
        pickColor = { session.setColor(0, it) }
        return session
    }

    private fun <S, M> guestSession(rules: GameRules<S, M>, join: GuestJoin): GuestSession<S, M> {
        game = rules.id
        maxPlayers = rules.maxPlayers
        colorCount = (rules as? SeatColorRules<*>)?.colorCount ?: 0
        val session = GuestSession(rules, join)
        session.onLobby = { list ->
            maxPlayers = session.lobbyCapacity
            lobbyOptions = session.lobbyOptions
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
        // STARTED means the room is mid-game; keep the saved seat so Rejoin still works
        // (a join without the token hits this screen — clearing would strand the player).
        if (reason != RejectReason.STARTED) LastRoom.clear(code)
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
        if (failed || playing) return // table owns the drop once the game runs
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

    enum class Game { CONGKLAK, ULAR_TANGGA, LUDO, PERANG_KAPAL, GAPLE }

    companion object {
        /** A guest that hasn't reached the host by then gives up; online may be unreachable. */
        private const val NOT_FOUND_MS = 20_000L

        fun host(context: Context, name: String, game: Game, online: Boolean = false, lan: Boolean = false, code: String? = null, onOnlineReady: (() -> Unit)? = null, publicRoom: Boolean = false, seats: Int? = null, standardRules: Boolean = false, avatar: String? = null): NearbyRoom {
            val roomCode = code ?: if (online) RoomCode.generateOnline() else RoomCode.generate()
            val gameId = when (game) {
                Game.ULAR_TANGGA -> UlarTanggaGame.id
                Game.LUDO -> LudoGame.id
                Game.PERANG_KAPAL -> PerangKapalGame.id
                Game.GAPLE -> GapleGame.id
                Game.CONGKLAK -> CongklakGame.id
            }
            val total = when (game) {
                Game.CONGKLAK, Game.PERANG_KAPAL -> 2
                else -> seats?.coerceIn(2, 4) ?: 4
            }
            val useLan = lan && !online
            val transport: Transport = when {
                online -> OnlineHostTransport(roomCode, game = gameId, seats = total, publicRoom = publicRoom)
                useLan -> LanHostTransport(context, roomCode)
                else -> BleHostTransport(context, roomCode)
            }
            val room = NearbyRoom(roomCode, isHost = true, online = online, lan = useLan, transport = transport, publicRoom = online && publicRoom, hostSeats = if (online) total else null)
            room.hostGame = game
            when (game) {
                Game.CONGKLAK -> {
                    val session = room.hostSession(if (standardRules) StandardRules.congklak else CongklakSettings.rules, name, transport, avatar)
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.congklak = HostTable(session, mySeat = 0, transport = transport)
                        }
                    }
                }
                Game.ULAR_TANGGA -> {
                    val session = room.hostSession(if (standardRules) StandardRules.ularTangga else UlarTanggaSettings.rules, name, transport, avatar)
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
                Game.LUDO -> {
                    val session = room.hostSession(if (standardRules) StandardRules.ludo else LudoSettings.rules, name, transport, avatar)
                    room.canAddBots = true
                    room.addBot = { botName -> session.addBot(botName, Platform.ANDROID, LudoBot()) }
                    room.removeBot = { seat -> session.removeBot(seat) }
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.ludo = LudoHostTable(session, mySeat = 0, transport = transport)
                        }
                    }
                }
                Game.PERANG_KAPAL -> {
                    val session = room.hostSession(StandardRules.perangKapal, name, transport, avatar)
                    room.canAddBots = true
                    room.addBot = { botName -> session.addBot(botName, Platform.ANDROID, PerangKapalBot()) }
                    room.removeBot = { seat -> session.removeBot(seat) }
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.perangKapal = PerangKapalHostTable(session, mySeat = 0, transport = transport)
                        }
                    }
                }
                Game.GAPLE -> {
                    val session = room.hostSession(if (standardRules) StandardRules.gaple else GapleSettings.rules, name, transport, avatar)
                    room.canAddBots = true
                    room.addBot = { botName -> session.addBot(botName, Platform.ANDROID, GapleBot()) }
                    room.removeBot = { seat -> session.removeBot(seat) }
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.gaple = GapleHostTable(session, mySeat = 0, transport = transport)
                        }
                    }
                }
            }
            when (transport) {
                is BleHostTransport -> transport.start()
                is LanHostTransport -> transport.start()
                is OnlineHostTransport -> {
                    // Only before the game: once it runs, the table shows the drop.
                    transport.onReady = onOnlineReady
                    transport.onClosed = {
                        if (!room.playing) {
                            room.stage = Stage.OFFLINE
                        }
                    }
                    transport.start()
                }
            }
            return room
        }

        /** [invite]: the id of the friend invite this join answers, so the host sees "Joined". */
        fun join(
            context: Context,
            code: String,
            name: String,
            online: Boolean = false,
            lan: Boolean = false,
            reservation: String? = null,
            invite: String? = null,
            avatar: String? = null,
            /** Emulator e2e: TCP host (e.g. 10.0.2.2) when NSD is unavailable. */
            lanHost: String? = null,
            lanPort: Int = 0,
        ): NearbyRoom {
            val useLan = lan && !online
            val transport: Transport = when {
                online -> OnlineGuestTransport(code, reservation)
                useLan -> LanGuestTransport(context, code, lanHost, lanPort)
                else -> BleGuestTransport(context, code)
            }
            val room = NearbyRoom(code, isHost = false, online = online, lan = useLan, transport = transport)
            // Joining a room this device has a seat in asks for that seat back.
            val join = GuestJoin(transport, name, Platform.ANDROID, LastRoom.tokenFor(code, online), avatar)
            room.join = join
            join.invite = invite
            join.onConnected = { if (room.stage == Stage.SEARCHING) room.stage = Stage.CONNECTING }
            join.onRejected = room::rejected
            join.onDisconnected = room::lost
            join.onGame = { game, token ->
                LastRoom.save(SavedRoom(code, online, game, token, System.currentTimeMillis()))
                when (game) {
                    CongklakGame.id -> room.guestSession(CongklakGame, join).also { s ->
                        s.onState = { _ -> room.congklak = GuestTable(s) }
                        s.replay()
                    }
                    UlarTanggaGame.id -> room.guestSession(UlarTanggaGame, join).also { s ->
                        s.onState = { _ -> room.ular = UlarTanggaGuestTable(s) }
                        s.replay()
                    }
                    LudoGame.id -> room.guestSession(LudoGame, join).also { s ->
                        s.onState = { _ -> room.ludo = LudoGuestTable(s) }
                        s.replay()
                    }
                    PerangKapalGame.id -> room.guestSession(PerangKapalGame, join).also { s ->
                        s.onState = { _ -> room.perangKapal = PerangKapalGuestTable(s) }
                        s.replay()
                    }
                    GapleGame.id -> room.guestSession(GapleGame, join).also { s ->
                        s.onState = { _ -> room.gaple = GapleGuestTable(s) }
                        s.replay()
                    }
                    else -> room.stage = Stage.UNKNOWN_GAME
                }
            }
            room.main.postDelayed(room.notFound, NOT_FOUND_MS)
            when (transport) {
                is BleGuestTransport -> transport.start()
                is LanGuestTransport -> transport.start()
                is OnlineGuestTransport -> {
                    transport.onClosed = { closeCode -> room.relayClosed(closeCode) }
                    transport.start()
                }
            }
            return room
        }
    }
}
