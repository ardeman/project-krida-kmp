package com.ardeman.krida.nearby

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.ble.BleGuestTransport
import com.ardeman.krida.ble.BleHostTransport
import com.ardeman.krida.congklak.CongklakSettings
import com.ardeman.krida.congklak.CongklakTable
import com.ardeman.krida.congklak.GuestTable
import com.ardeman.krida.congklak.HostTable
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.GuestJoin
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.session.RoomCode
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
    /** The room's game id (`GameRules.id`); null for a guest until the host welcomes it. */
    var game by mutableStateOf<String?>(null)
        private set
    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        private set
    var maxPlayers by mutableStateOf(2)
        private set
    var canStart by mutableStateOf(false)
        private set

    /** String resource describing where the room stands. */
    var status by mutableStateOf(R.string.nearby_waiting)
        private set
    var congklak by mutableStateOf<CongklakTable?>(null)
        private set
    var ular by mutableStateOf<UlarTanggaTable?>(null)
        private set

    private var begin: (() -> Unit)? = null
    private var guest: GuestSession<*, *>? = null
    private var join: GuestJoin? = null

    fun start() {
        begin?.invoke()
    }

    fun leave() {
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
        session.onPlayersChanged = { list ->
            players = list
            canStart = session.canStart()
        }
        session.addLocalPlayer(name, Platform.ANDROID)
        return session
    }

    private fun <S, M> guestSession(rules: GameRules<S, M>, join: GuestJoin): GuestSession<S, M> {
        game = rules.id
        maxPlayers = rules.maxPlayers
        val session = GuestSession(rules, join)
        session.onLobby = { list ->
            players = list
            status = R.string.nearby_joined
        }
        session.onRejected = ::rejected
        session.onDisconnected = { status = R.string.nearby_host_lost }
        guest = session
        return session
    }

    private fun rejected(reason: String) {
        status = when (reason) {
            RejectReason.VERSION -> R.string.nearby_rejected_version
            RejectReason.FULL -> R.string.nearby_rejected_full
            RejectReason.STARTED -> R.string.nearby_rejected_started
            else -> R.string.nearby_rejected
        }
    }

    enum class Game { CONGKLAK, ULAR_TANGGA }

    companion object {
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
            room.status = R.string.nearby_searching
            join.onRejected = room::rejected
            join.onDisconnected = { room.status = R.string.nearby_host_lost }
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
                    else -> room.status = R.string.nearby_unknown_game
                }
            }
            ble.start()
            return room
        }
    }
}
