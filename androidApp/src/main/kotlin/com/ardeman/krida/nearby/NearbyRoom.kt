package com.ardeman.krida.nearby

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.ble.BleGuestTransport
import com.ardeman.krida.ble.BleHostTransport
import com.ardeman.krida.congklak.CongklakTable
import com.ardeman.krida.congklak.GuestTable
import com.ardeman.krida.congklak.HostTable
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.congklak.CongklakSettings
import com.ardeman.krida.core.games.CongklakMove
import com.ardeman.krida.core.games.CongklakState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.core.transport.Transport

/**
 * A congklak room over BLE ("Main bareng dekat"), from hosting or joining until the game
 * starts. Then [table] takes over the session and the board screen shows it.
 */
class NearbyRoom private constructor(
    val code: String,
    val isHost: Boolean,
    private val transport: Transport,
) {
    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        private set
    var canStart by mutableStateOf(false)
        private set

    /** String resource describing where the room stands. */
    var status by mutableStateOf(R.string.nearby_waiting)
        private set
    var table by mutableStateOf<CongklakTable?>(null)
        private set

    private var host: HostSession<CongklakState, CongklakMove>? = null

    fun start() {
        val h = host ?: return
        if (!h.canStart()) return
        h.start()
        table = HostTable(h, mySeat = 0, transport = transport)
    }

    fun leave() {
        table?.leave() ?: transport.close()
    }

    companion object {
        fun host(context: Context, name: String): NearbyRoom {
            val code = RoomCode.generate()
            val ble = BleHostTransport(context, code)
            val room = NearbyRoom(code, isHost = true, transport = ble)
            val session = HostSession(CongklakSettings.rules, ble, roomCode = code)
            session.onPlayersChanged = { list ->
                room.players = list
                room.canStart = session.canStart()
            }
            session.addLocalPlayer(name, Platform.ANDROID)
            room.host = session
            ble.start()
            return room
        }

        fun join(context: Context, code: String, name: String): NearbyRoom {
            val ble = BleGuestTransport(context, code)
            val room = NearbyRoom(code, isHost = false, transport = ble)
            val session = GuestSession(CongklakGame, ble, name, Platform.ANDROID)
            room.status = R.string.nearby_searching
            session.onLobby = { list ->
                room.players = list
                room.status = R.string.nearby_joined
            }
            session.onState = { _ -> room.table = GuestTable(session) }
            session.onRejected = { reason ->
                room.status = when (reason) {
                    RejectReason.VERSION -> R.string.nearby_rejected_version
                    RejectReason.FULL -> R.string.nearby_rejected_full
                    RejectReason.STARTED -> R.string.nearby_rejected_started
                    else -> R.string.nearby_rejected
                }
            }
            session.onDisconnected = { room.status = R.string.nearby_host_lost }
            ble.start()
            return room
        }
    }
}
