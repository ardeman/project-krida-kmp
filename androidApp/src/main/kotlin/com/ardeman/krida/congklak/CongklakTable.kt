package com.ardeman.krida.congklak

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.core.games.CongklakBot
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.CongklakRules
import com.ardeman.krida.core.games.CongklakMove
import com.ardeman.krida.core.games.CongklakState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LocalTransport
import com.ardeman.krida.core.transport.Transport

/** A new snapshot, plus the move that produced it when it can be animated. */
class CongklakUpdate(val state: CongklakState, val seat: Int?, val move: CongklakMove?)

/**
 * What the congklak screen plays against: pass-and-play or vs computer on this device, a
 * BLE host, or a BLE guest. Session callbacks arrive on the main thread, so this needs no locks.
 */
abstract class CongklakTable {
    /** Seat whose side faces this device; null for pass-and-play (whoever's turn it is). */
    abstract val mySeat: Int?
    abstract val canRestart: Boolean

    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        protected set

    /** A string resource to show under the score (disconnects, refusals), or null. */
    var notice by mutableStateOf<Int?>(null)
        protected set

    /** The computer's difficulty, shown next to its name; null without a computer player. */
    var botLevel: String? = null
        protected set

    /** Latest snapshot; the screen starts from it. */
    var latest: CongklakState? = null
        private set

    /** Receives every snapshot after [latest], in order. */
    var onUpdate: ((CongklakUpdate) -> Unit)? = null

    protected fun deliver(state: CongklakState, seat: Int?, move: CongklakMove?) {
        latest = state
        onUpdate?.invoke(CongklakUpdate(state, seat, move))
    }

    /** Sow from [hole] for [seat]. Returns false when nothing was sent. */
    abstract fun play(seat: Int, hole: Int): Boolean
    abstract fun restart()
    abstract fun leave()

    /** Lets a computer player move if it is its turn. Called once the board is idle. */
    open fun stepBot() = Unit
}

/** The authoritative table: pass-and-play, vs computer (no transport peers) or hosting a BLE room. */
class HostTable(
    private val session: HostSession<CongklakState, CongklakMove>,
    override val mySeat: Int?,
    private val transport: Transport,
) : CongklakTable() {
    private var seq = session.seq

    override val canRestart = true

    init {
        players = session.players
        session.onPlayersChanged = { list ->
            players = list
            notice = if (list.any { !it.connected }) R.string.nearby_player_lost else null
        }
        session.onStateChanged = { state ->
            // Rebroadcasts (e.g. after a disconnect) keep seq; only animate a fresh move.
            val fresh = session.seq == seq + 1
            seq = session.seq
            deliver(state, session.lastSeat.takeIf { fresh }, session.lastMove.takeIf { fresh })
        }
        session.state?.let { deliver(it, null, null) }
    }

    override fun play(seat: Int, hole: Int) = session.submitLocal(seat, CongklakMove(hole)) == null

    override fun restart() {
        val s = session.state ?: return
        if (CongklakGame.isFinished(s)) session.restart()
    }

    override fun stepBot() {
        session.stepBot()
    }

    override fun leave() = transport.close()

    companion object {
        fun passAndPlay(names: List<String>): HostTable {
            val transport = LocalTransport()
            val session = HostSession(CongklakRules(tembakAfterStore = true), transport, roomCode = "LOCAL")
            names.forEach { session.addLocalPlayer(it, Platform.ANDROID) }
            session.start()
            return HostTable(session, mySeat = null, transport = transport)
        }

        /** You (seat 0, moving first) against a [CongklakBot] on this device. */
        fun vsComputer(you: String, computer: String, level: CongklakBot.Level, levelName: String): HostTable {
            val transport = LocalTransport()
            val session = HostSession(CongklakRules(tembakAfterStore = true), transport, roomCode = "LOCAL")
            session.addLocalPlayer(you, Platform.ANDROID)
            session.addBot(computer, Platform.ANDROID, CongklakBot(level))
            session.start()
            return HostTable(session, mySeat = 0, transport = transport).apply { botLevel = levelName }
        }
    }
}

/** A remote player's table: moves go to the host, snapshots come back. */
class GuestTable(private val session: GuestSession<CongklakState, CongklakMove>) : CongklakTable() {
    override val mySeat: Int? get() = session.seat
    override val canRestart = false

    init {
        players = session.players
        session.onState = { state ->
            players = session.players
            notice = null
            deliver(state, session.lastSeat, session.lastMove)
        }
        session.onInvalid = { notice = R.string.nearby_move_refused }
        session.onDisconnected = { notice = R.string.nearby_host_lost }
        session.state?.let { deliver(it, null, null) }
    }

    override fun play(seat: Int, hole: Int) = session.sendMove(CongklakMove(hole)) != null

    override fun restart() = Unit

    override fun leave() = session.leave()
}
