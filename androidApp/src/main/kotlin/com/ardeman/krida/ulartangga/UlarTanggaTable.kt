package com.ardeman.krida.ulartangga

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
import com.ardeman.krida.online.GuestWaiting
import com.ardeman.krida.online.SecondTicker
import com.ardeman.krida.online.Waiting
import com.ardeman.krida.online.waiting

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

    /** A dropped player the game waits for, with the seconds left; shown instead of [notice]. */
    var waiting by mutableStateOf<Waiting?>(null)
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

    /** Counts down a dropped guest's wait, then lets the computer play that seat. */
    private val ticker = SecondTicker {
        session.expiredSeats().forEach { session.replaceWithBot(it, UlarTanggaBot) }
        waiting = session.waiting()
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
        if (transport !is LocalTransport) ticker.start()
    }

    override fun roll(seat: Int) = session.submitLocal(seat, UlarTanggaMove()) == null

    override fun stepBot() {
        session.stepBot()
    }

    override fun restart() {
        if (latest.finished) session.restart()
    }

    override fun leave() {
        ticker.stop()
        transport.close()
    }

    companion object {
        /**
         * A game on this phone: one seat per [players] entry (a name, and whether a computer
         * plays it) with that entry's pawn colour; the colours must rise with the seat. With a
         * single person their seat is "you";
         * with more the phone goes round.
         */
        fun local(players: List<LocalPlayer>): UlarTanggaTable {
            val transport = LocalTransport()
            val session = HostSession(UlarTanggaSettings.rules, transport, roomCode = "LOCAL")
            players.forEach {
                if (it.computer) session.addBot(it.name, Platform.ANDROID, UlarTanggaBot) else session.addLocalPlayer(it.name, Platform.ANDROID)
            }
            // Seat i joined with colour i. The chosen colours rise with the seat (each tile has
            // its own colour, empty tiles are skipped), so setting them from the last seat down
            // never asks for a colour another seat still holds.
            players.indices.reversed().forEach { session.setColor(it, players[it].color) }
            session.start()
            val people = players.indices.filter { !players[it].computer }
            return UlarTanggaHostTable(session, mySeat = people.singleOrNull(), transport = transport)
        }
    }
}

/** A guest in a nearby room: rolls go to the host, snapshots come back. */
class UlarTanggaGuestTable(
    private val session: GuestSession<UlarTanggaState, UlarTanggaMove>,
) : UlarTanggaTable() {
    override val mySeat: Int? get() = session.seat
    override val canRestart = false

    private val away = GuestWaiting()

    /** The host's and other guests' waits; past the host's wait the game is over ("host left"). */
    private val ticker = SecondTicker {
        val w = away.current(players)
        waiting = w?.takeIf { it.seconds > 0 }
        if (w?.host == true && w.seconds == 0) notice = R.string.nearby_host_lost
    }

    init {
        players = session.players
        away.players(players)
        first(checkNotNull(session.state), session.stateSeq)
        session.onState = { state ->
            players = session.players
            away.players(players)
            notice = if (players.any { !it.connected }) R.string.ular_player_lost else null
            deliver(state, session.stateSeq)
        }
        session.onPlayers = { list ->
            players = list
            away.players(list)
            notice = if (list.any { !it.connected }) R.string.ular_player_lost else null
        }
        session.onInvalid = { notice = R.string.nearby_move_refused }
        session.onDisconnected = { away.hostLost() }
        ticker.start()
    }

    override fun roll(seat: Int) = seat == session.seat && session.sendMove(UlarTanggaMove()) != null

    override fun restart() = Unit

    override fun leave() {
        ticker.stop()
        session.leave()
    }
}

/** One seat of a game on this phone, for [UlarTanggaHostTable.local]. */
data class LocalPlayer(val name: String, val computer: Boolean, val color: Int)
