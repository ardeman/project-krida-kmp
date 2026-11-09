package com.ardeman.krida.ulartangga

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.core.games.UlarTanggaBot
import com.ardeman.krida.core.games.UlarTanggaMove
import com.ardeman.krida.core.games.UlarTanggaState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LocalTransport
import com.ardeman.krida.core.transport.Transport

/** A new snapshot; [fresh] when it follows a roll that can be animated from the one before. */
class UlarTanggaUpdate(val state: UlarTanggaState, val fresh: Boolean)

/**
 * What the ular tangga screen plays on: pass-and-play or against computers on this device,
 * hosting a nearby room, or a guest in one. Session callbacks arrive on the main thread.
 */
abstract class UlarTanggaTable {
    /** This device's seat; null for pass-and-play (whoever's turn it is). */
    abstract val mySeat: Int?
    abstract val canRestart: Boolean

    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        protected set

    /** A string resource to show under the status (disconnects, refusals), or null. */
    var notice by mutableStateOf<Int?>(null)
        protected set

    /** Latest snapshot; the screen starts from it. */
    lateinit var latest: UlarTanggaState
        private set

    /** Receives every snapshot after [latest], in order. */
    var onUpdate: ((UlarTanggaUpdate) -> Unit)? = null

    private var seq = 0

    /** Seats the computer plays (including dropped guests handed to it). */
    val botSeats: Set<Int> get() = players.filter { it.bot }.map { it.seat }.toSet()

    protected fun first(state: UlarTanggaState, seq: Int) {
        latest = state
        this.seq = seq
    }

    protected fun deliver(state: UlarTanggaState, seq: Int) {
        // Rebroadcasts keep seq, and a restart's first state has no throw yet; only a throw
        // or roll right after the shown state animates.
        val thrown = state.lastRoll != null || state.openingRolls.any { round -> round.any { it != 0 } }
        val fresh = seq == this.seq + 1 && thrown
        this.seq = seq
        latest = state
        onUpdate?.invoke(UlarTanggaUpdate(state, fresh))
    }

    fun name(seat: Int) = players.firstOrNull { it.seat == seat }?.name ?: "${seat + 1}"

    /** The seat can be played from this device (own seat, or any person in pass-and-play). */
    fun isLocal(seat: Int) = seat !in botSeats && (mySeat == null || mySeat == seat)

    /** Rolls for [seat]. Returns false when nothing was sent. */
    abstract fun roll(seat: Int): Boolean

    /** Lets a computer roll if it is its turn. Called once the board is idle. */
    open fun stepBot() = Unit

    abstract fun restart()
    abstract fun leave()
}

/** The authoritative table: on this device only, or hosting a nearby room. */
class UlarTanggaHostTable(
    private val session: HostSession<UlarTanggaState, UlarTanggaMove>,
    override val mySeat: Int?,
    private val transport: Transport,
) : UlarTanggaTable() {
    override val canRestart = true

    private val main = Handler(Looper.getMainLooper())

    /** Hands guests gone past the reconnect grace to the computer, so nobody waits on them. */
    private val watch = object : Runnable {
        override fun run() {
            session.expiredSeats().forEach { session.replaceWithBot(it, UlarTanggaBot) }
            main.postDelayed(this, WATCH_MS)
        }
    }

    init {
        players = session.players
        first(checkNotNull(session.state), session.seq)
        session.onPlayersChanged = { list ->
            players = list
            notice = if (list.any { !it.connected }) R.string.ular_player_lost else null
        }
        session.onStateChanged = { state ->
            players = session.players
            notice = if (players.any { !it.connected }) R.string.ular_player_lost else null
            deliver(state, session.seq)
        }
        if (transport !is LocalTransport) main.postDelayed(watch, WATCH_MS)
    }

    override fun roll(seat: Int) = session.submitLocal(seat, UlarTanggaMove()) == null

    override fun stepBot() {
        session.stepBot()
    }

    override fun restart() {
        if (latest.finished) session.restart()
    }

    override fun leave() {
        main.removeCallbacks(watch)
        transport.close()
    }

    companion object {
        private const val WATCH_MS = 5_000L

        fun passAndPlay(names: List<String>): UlarTanggaTable {
            val transport = LocalTransport()
            val session = HostSession(UlarTanggaSettings.rules, transport, roomCode = "LOCAL")
            names.forEach { session.addLocalPlayer(it, Platform.ANDROID) }
            session.start()
            return UlarTanggaHostTable(session, mySeat = null, transport = transport)
        }

        /** You at seat 0 against [computers] (one name per computer seat). */
        fun vsComputer(you: String, computers: List<String>): UlarTanggaTable {
            val transport = LocalTransport()
            val session = HostSession(UlarTanggaSettings.rules, transport, roomCode = "LOCAL")
            session.addLocalPlayer(you, Platform.ANDROID)
            computers.forEach { session.addBot(it, Platform.ANDROID, UlarTanggaBot) }
            session.start()
            return UlarTanggaHostTable(session, mySeat = 0, transport = transport)
        }
    }
}

/** A guest in a nearby room: rolls go to the host, snapshots come back. */
class UlarTanggaGuestTable(
    private val session: GuestSession<UlarTanggaState, UlarTanggaMove>,
) : UlarTanggaTable() {
    override val mySeat: Int? get() = session.seat
    override val canRestart = false

    init {
        players = session.players
        first(checkNotNull(session.state), session.stateSeq)
        session.onState = { state ->
            players = session.players
            notice = if (players.any { !it.connected }) R.string.ular_player_lost else null
            deliver(state, session.stateSeq)
        }
        session.onPlayers = { list ->
            players = list
            notice = if (list.any { !it.connected }) R.string.ular_player_lost else null
        }
        session.onInvalid = { notice = R.string.nearby_move_refused }
        session.onDisconnected = { notice = R.string.nearby_host_lost }
    }

    override fun roll(seat: Int) = seat == session.seat && session.sendMove(UlarTanggaMove()) != null

    override fun restart() = Unit

    override fun leave() = session.leave()
}
