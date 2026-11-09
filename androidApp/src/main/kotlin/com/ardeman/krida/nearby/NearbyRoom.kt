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
import com.ardeman.krida.core.session.SeatColorRules
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.ulartangga.UlarTanggaGuestTable
import com.ardeman.krida.ulartangga.UlarTanggaHostTable
import com.ardeman.krida.ulartangga.UlarTanggaSettings
import com.ardeman.krida.ulartangga.UlarTanggaTable

/**
 * A room over BLE ("Teman dekat"), from hosting or joining until the game starts. Then
 * [congklak] or [ular] takes over the session and that game's screen shows it. A guest joins
 * by code and learns the game from the host's Welcome.
 */
class NearbyRoom private constructor(
    val code: String,
    val isHost: Boolean,
    private val transport: Transport,
) {
    /** Where the room stands; a guest goes SEARCHING → CONNECTING → LOBBY, or stops at a failure. */
    enum class Stage { SEARCHING, CONNECTING, LOBBY, FULL, STARTED, VERSION, NOT_FOUND, LOST, UNKNOWN_GAME, REFUSED }

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

    private var begin: (() -> Unit)? = null
    private var guest: GuestSession<*, *>? = null
    private var join: GuestJoin? = null
    private var pickColor: ((Int) -> Unit)? = null
    private var addBot: ((String) -> Unit)? = null
    private var removeBot: ((Int) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val notFound = Runnable {
        if (stage == Stage.SEARCHING) {
            stage = Stage.NOT_FOUND
            leave()
        }
    }

    fun start() {
        begin?.invoke()
    }

    /** Asks for pawn colour [color]: the host takes it at once, a guest asks the host. */
    fun pickColor(color: Int) {
        pickColor?.invoke(color)
    }

    /** Seats a computer called [name] in the next empty seat (host, ular tangga). */
    fun addBot(name: String) {
        addBot?.invoke(name)
    }

    fun removeBot(seat: Int) {
        removeBot?.invoke(seat)
    }

    fun leave() {
        main.removeCallbacks(notFound)
        when {
            congklak != null -> congklak?.leave()
            ular != null -> ular?.leave()
            guest != null -> guest?.leave()
            join != null -> join?.leave()
            else -> transport.close()
        }
    }

    private fun <S, M> hostSession(rules: GameRules<S, M>, name: String, ble: BleHostTransport): HostSession<S, M> {
        game = rules.id
        maxPlayers = rules.maxPlayers
        val session = HostSession(rules, ble, roomCode = code)
        colorCount = session.colorCount
        session.onPlayersChanged = { list ->
            players = list
            canStart = session.canStart()
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
        stage = when (reason) {
            RejectReason.VERSION -> Stage.VERSION
            RejectReason.FULL -> Stage.FULL
            RejectReason.STARTED -> Stage.STARTED
            else -> Stage.REFUSED
        }
    }

    private fun lost() {
        main.removeCallbacks(notFound)
        if (!failed) stage = Stage.LOST
    }

    enum class Game { CONGKLAK, ULAR_TANGGA }

    companion object {
        /** A guest that hasn't reached the host by then gives up with "Room tidak ketemu". */
        private const val NOT_FOUND_MS = 20_000L

        fun host(context: Context, name: String, game: Game): NearbyRoom {
            val code = RoomCode.generate()
            val ble = BleHostTransport(context, code)
            val room = NearbyRoom(code, isHost = true, transport = ble)
            when (game) {
                Game.CONGKLAK -> {
                    val session = room.hostSession(CongklakSettings.rules, name, ble)
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.congklak = HostTable(session, mySeat = 0, transport = ble)
                        }
                    }
                }
                Game.ULAR_TANGGA -> {
                    val session = room.hostSession(UlarTanggaSettings.rules, name, ble)
                    room.canAddBots = true
                    room.addBot = { botName -> session.addBot(botName, Platform.ANDROID, UlarTanggaBot) }
                    room.removeBot = { seat -> session.removeBot(seat) }
                    room.begin = {
                        if (session.canStart()) {
                            session.start()
                            room.ular = UlarTanggaHostTable(session, mySeat = 0, transport = ble)
                        }
                    }
                }
            }
            ble.start()
            return room
        }

        fun join(context: Context, code: String, name: String): NearbyRoom {
            val ble = BleGuestTransport(context, code)
            val room = NearbyRoom(code, isHost = false, transport = ble)
            val join = GuestJoin(ble, name, Platform.ANDROID)
            room.join = join
            join.onConnected = { if (room.stage == Stage.SEARCHING) room.stage = Stage.CONNECTING }
            join.onRejected = room::rejected
            join.onDisconnected = room::lost
            join.onGame = { game ->
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
            ble.start()
            return room
        }
    }
}
