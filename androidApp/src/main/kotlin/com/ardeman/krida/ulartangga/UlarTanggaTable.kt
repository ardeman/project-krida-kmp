package com.ardeman.krida.ulartangga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.UlarTanggaBot
import com.ardeman.krida.core.games.UlarTanggaMove
import com.ardeman.krida.core.games.UlarTanggaState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LocalTransport

/** A new snapshot; [fresh] when it follows a roll that can be animated from the one before. */
class UlarTanggaUpdate(val state: UlarTanggaState, val fresh: Boolean)

/**
 * What the ular tangga screen plays on: pass-and-play or against computers on this device.
 * (Nearby rooms come with the 4-player lobby.) Session callbacks arrive on the main thread.
 */
class UlarTanggaTable private constructor(
    private val session: HostSession<UlarTanggaState, UlarTanggaMove>,
    /** This device's seat against computers; null for pass-and-play (whoever's turn it is). */
    val mySeat: Int?,
    /** Seats the computer plays. */
    val botSeats: Set<Int>,
    private val transport: LocalTransport,
) {
    private var seq = session.seq

    var players by mutableStateOf<List<PlayerInfo>>(session.players)
        private set

    /** Latest snapshot; the screen starts from it. */
    var latest: UlarTanggaState = checkNotNull(session.state)
        private set

    /** Receives every snapshot after [latest], in order. */
    var onUpdate: ((UlarTanggaUpdate) -> Unit)? = null

    init {
        session.onPlayersChanged = { players = it }
        session.onStateChanged = { state ->
            // A restart's first state has no roll; only a roll right after the shown state animates.
            val fresh = session.seq == seq + 1 && state.lastRoll != null
            seq = session.seq
            latest = state
            onUpdate?.invoke(UlarTanggaUpdate(state, fresh))
        }
    }

    fun name(seat: Int) = players.firstOrNull { it.seat == seat }?.name ?: "${seat + 1}"

    /** The seat can be played from this device (own seat, or any person in pass-and-play). */
    fun isLocal(seat: Int) = seat !in botSeats && (mySeat == null || mySeat == seat)

    /** Rolls for [seat]. Returns false when nothing happened. */
    fun roll(seat: Int) = session.submitLocal(seat, UlarTanggaMove()) == null

    /** Lets a computer roll if it is its turn. Called once the board is idle. */
    fun stepBot() {
        session.stepBot()
    }

    fun restart() {
        if (latest.finished) session.restart()
    }

    fun leave() = transport.close()

    companion object {
        fun passAndPlay(names: List<String>): UlarTanggaTable {
            val transport = LocalTransport()
            val session = HostSession(UlarTanggaSettings.rules, transport, roomCode = "LOCAL")
            names.forEach { session.addLocalPlayer(it, Platform.ANDROID) }
            session.start()
            return UlarTanggaTable(session, mySeat = null, botSeats = emptySet(), transport = transport)
        }

        /** You at seat 0 against [computers] (one name per computer seat). */
        fun vsComputer(you: String, computers: List<String>): UlarTanggaTable {
            val transport = LocalTransport()
            val session = HostSession(UlarTanggaSettings.rules, transport, roomCode = "LOCAL")
            session.addLocalPlayer(you, Platform.ANDROID)
            computers.forEach { session.addBot(it, Platform.ANDROID, UlarTanggaBot) }
            session.start()
            return UlarTanggaTable(session, mySeat = 0, botSeats = (1..computers.size).toSet(), transport = transport)
        }
    }
}
