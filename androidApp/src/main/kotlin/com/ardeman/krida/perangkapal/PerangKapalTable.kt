package com.ardeman.krida.perangkapal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.core.games.PerangKapalBot
import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.PerangKapalMove
import com.ardeman.krida.core.games.PerangKapalShip
import com.ardeman.krida.core.games.PerangKapalState
import com.ardeman.krida.core.games.StandardRules
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LocalTransport
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.nearby.LastRoom
import com.ardeman.krida.online.GuestWaiting
import com.ardeman.krida.online.SecondTicker
import com.ardeman.krida.online.Waiting
import com.ardeman.krida.online.waiting

class PerangKapalUpdate(val state: PerangKapalState, val fresh: Boolean)

/**
 * What the perang kapal screen plays on. Session callbacks arrive on the main thread.
 * Host UI receives a per-seat fog-of-war view; the session keeps the full state for bots.
 */
abstract class PerangKapalTable {
    /** This device's seat; null when two people pass one phone (whoever's turn it is). */
    abstract val mySeat: Int?
    abstract val canRestart: Boolean

    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        protected set
    var notice by mutableStateOf<Int?>(null)
        protected set
    var waiting by mutableStateOf<Waiting?>(null)
        protected set
    var emote by mutableStateOf<Pair<Int, String>?>(null)
        protected set

    lateinit var latest: PerangKapalState
        private set

    var onUpdate: ((PerangKapalUpdate) -> Unit)? = null

    private var seq = 0

    val botSeats: Set<Int> get() = players.filter { it.bot }.map { it.seat }.toSet()

    open val canEmote: Boolean get() = false

    protected fun first(state: PerangKapalState, seq: Int) {
        latest = state
        this.seq = seq
    }

    protected var active = true
        private set

    protected fun deliver(state: PerangKapalState, seq: Int) {
        if (!active) return
        val fresh = seq == this.seq + 1
        this.seq = seq
        latest = state
        onUpdate?.invoke(PerangKapalUpdate(state, fresh))
    }

    protected fun showEmote(seat: Int, id: String) {
        if (active) emote = seat to id
    }

    fun name(seat: Int) = players.firstOrNull { it.seat == seat }?.name ?: "${seat + 1}"

    fun isLocal(seat: Int) = seat !in botSeats && (mySeat == null || mySeat == seat)

    abstract fun place(seat: Int, kind: Int, cell: Int, horizontal: Boolean): Boolean
    abstract fun setFleet(seat: Int, fleet: List<PerangKapalShip>): Boolean
    abstract fun ready(seat: Int): Boolean
    abstract fun fire(seat: Int, cell: Int): Boolean
    open fun stepBot() = Unit
    abstract fun restart()
    open fun leave() {
        active = false
        onUpdate = null
        emote = null
    }

    open fun sendEmote(id: String) = Unit
}

class PerangKapalHostTable(
    private val session: HostSession<PerangKapalState, PerangKapalMove>,
    override val mySeat: Int?,
    private val transport: Transport,
) : PerangKapalTable() {
    override val canRestart = true

    private val ticker = SecondTicker {
        session.expiredSeats().forEach { session.replaceWithBot(it, PerangKapalBot()) }
        waiting = session.waiting()
    }

    init {
        players = session.players
        first(view(checkNotNull(session.state)), session.seq)
        session.onPlayersChanged = { list ->
            players = list
            notice = if (list.any { !it.connected }) R.string.perang_kapal_player_lost else null
        }
        session.onStateChanged = { state ->
            players = session.players
            notice = if (players.any { !it.connected }) R.string.perang_kapal_player_lost else null
            deliver(view(state), session.seq)
        }
        session.onEmote = { seat, id -> showEmote(seat, id) }
        if (transport !is LocalTransport) ticker.start()
    }

    override val canEmote: Boolean get() = transport !is LocalTransport

    /** With two people on one phone the screen gets everything and hides per turn itself. */
    private fun view(state: PerangKapalState) =
        mySeat?.let { PerangKapalGame.viewFor(state, it) } ?: state

    override fun place(seat: Int, kind: Int, cell: Int, horizontal: Boolean) =
        active && session.submitLocal(seat, PerangKapalMove(kind = kind, cell = cell, horizontal = horizontal)) == null

    override fun setFleet(seat: Int, fleet: List<PerangKapalShip>) =
        active && session.submitLocal(seat, PerangKapalMove(fleet = fleet)) == null

    override fun ready(seat: Int) =
        active && session.submitLocal(seat, PerangKapalMove(ready = true)) == null

    override fun fire(seat: Int, cell: Int) =
        active && session.submitLocal(seat, PerangKapalMove(fire = cell)) == null

    override fun stepBot() {
        if (active) session.stepBot()
    }

    override fun restart() {
        if (active && latest.finished) session.restart()
    }

    override fun sendEmote(id: String) {
        val seat = mySeat ?: return
        if (active && canEmote) session.sendEmote(seat, id)
    }

    override fun leave() {
        if (!active) return
        super.leave()
        ticker.stop()
        transport.close()
    }

    companion object {
        /** This phone: you vs the computer. */
        fun vsComputer(you: String, computer: String): PerangKapalTable {
            val transport = LocalTransport()
            val session = HostSession(StandardRules.perangKapal, transport, roomCode = "LOCAL")
            session.addLocalPlayer(you, Platform.ANDROID)
            session.addBot(computer, Platform.ANDROID, PerangKapalBot())
            session.start()
            return PerangKapalHostTable(session, mySeat = 0, transport = transport)
        }

        /** This phone: two people, passing it for each turn. */
        fun passAndPlay(names: List<String>): PerangKapalTable {
            val transport = LocalTransport()
            val session = HostSession(StandardRules.perangKapal, transport, roomCode = "LOCAL")
            names.forEach { session.addLocalPlayer(it, Platform.ANDROID) }
            session.start()
            return PerangKapalHostTable(session, mySeat = null, transport = transport)
        }
    }
}

class PerangKapalGuestTable(
    private val session: GuestSession<PerangKapalState, PerangKapalMove>,
) : PerangKapalTable() {
    override val mySeat: Int? get() = session.seat
    override val canRestart = false

    private val away = GuestWaiting()

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
            notice = if (players.any { !it.connected }) R.string.perang_kapal_player_lost else null
            deliver(state, session.stateSeq)
        }
        session.onPlayers = { list ->
            players = list
            away.players(list)
            notice = if (list.any { !it.connected }) R.string.perang_kapal_player_lost else null
        }
        session.onInvalid = { notice = R.string.nearby_move_refused }
        session.onDisconnected = { away.hostLost() }
        session.onEmote = { seat, id -> showEmote(seat, id) }
        ticker.start()
    }

    override val canEmote: Boolean get() = true

    override fun place(seat: Int, kind: Int, cell: Int, horizontal: Boolean) =
        active && seat == session.seat &&
            session.sendMove(PerangKapalMove(kind = kind, cell = cell, horizontal = horizontal)) != null

    override fun setFleet(seat: Int, fleet: List<PerangKapalShip>) =
        active && seat == session.seat && session.sendMove(PerangKapalMove(fleet = fleet)) != null

    override fun ready(seat: Int) =
        active && seat == session.seat && session.sendMove(PerangKapalMove(ready = true)) != null

    override fun fire(seat: Int, cell: Int) =
        active && seat == session.seat && session.sendMove(PerangKapalMove(fire = cell)) != null

    override fun restart() = Unit

    override fun sendEmote(id: String) {
        if (active) session.sendEmote(id)
    }

    override fun leave() {
        if (!active) return
        super.leave()
        ticker.stop()
        if (session.finished) session.roomCode?.let(LastRoom::clear)
        session.leave()
    }
}
