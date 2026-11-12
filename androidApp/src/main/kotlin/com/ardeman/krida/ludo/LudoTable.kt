package com.ardeman.krida.ludo

import com.ardeman.krida.ui.EmoteEvent
import com.ardeman.krida.ui.plusEmote
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.core.games.LudoBot
import com.ardeman.krida.core.games.LudoMove
import com.ardeman.krida.core.games.LudoState
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
import com.ardeman.krida.ulartangga.LocalPlayer

/** A new snapshot; [fresh] when it follows a roll or pawn move that can be animated. */
class LudoUpdate(val state: LudoState, val fresh: Boolean)

/**
 * What the ludo screen plays on: this phone, hosting a room, or a guest. Session callbacks
 * arrive on the main thread.
 */
abstract class LudoTable {
    abstract val mySeat: Int?
    abstract val canRestart: Boolean

    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        protected set
    var notice by mutableStateOf<Int?>(null)
        protected set
    var waiting by mutableStateOf<Waiting?>(null)
        protected set
    var emotes by mutableStateOf<List<EmoteEvent>>(emptyList())
        protected set

    lateinit var latest: LudoState
        private set

    var onUpdate: ((LudoUpdate) -> Unit)? = null

    private var seq = 0

    val botSeats: Set<Int> get() = players.filter { it.bot }.map { it.seat }.toSet()

    open val canEmote: Boolean get() = false

    protected fun first(state: LudoState, seq: Int) {
        latest = state
        this.seq = seq
    }

    protected var active = true
        private set

    protected fun deliver(state: LudoState, seq: Int) {
        if (!active) return
        val fresh = seq == this.seq + 1 && state.lastRoll != null
        this.seq = seq
        latest = state
        onUpdate?.invoke(LudoUpdate(state, fresh))
    }

    protected fun showEmote(seat: Int, id: String) {
        if (active) emotes = emotes.plusEmote(seat, id)
    }

    fun name(seat: Int) = players.firstOrNull { it.seat == seat }?.name ?: "${seat + 1}"

    fun isLocal(seat: Int) = seat !in botSeats && (mySeat == null || mySeat == seat)

    abstract fun roll(seat: Int): Boolean
    abstract fun movePawn(seat: Int, pawn: Int): Boolean
    open fun stepBot() = Unit
    abstract fun restart()
    open fun leave() { active = false; onUpdate = null; emotes = emptyList() }
    open fun sendEmote(id: String) = Unit
}

class LudoHostTable(
    private val session: HostSession<LudoState, LudoMove>,
    override val mySeat: Int?,
    private val transport: Transport,
) : LudoTable() {
    override val canRestart = true

    private val ticker = SecondTicker {
        session.expiredSeats().forEach { session.replaceWithBot(it, LudoBot()) }
        waiting = session.waiting()
    }

    init {
        players = session.players
        first(checkNotNull(session.state), session.seq)
        session.onPlayersChanged = { list ->
            players = list
            notice = if (list.any { !it.connected }) R.string.ludo_player_lost else null
        }
        session.onStateChanged = { state ->
            players = session.players
            notice = if (players.any { !it.connected }) R.string.ludo_player_lost else null
            deliver(state, session.seq)
        }
        session.onEmote = { seat, id -> showEmote(seat, id) }
        if (transport !is LocalTransport) ticker.start()
    }

    override val canEmote: Boolean get() = transport !is LocalTransport

    override fun roll(seat: Int) = active && session.submitLocal(seat, LudoMove(null)) == null

    override fun movePawn(seat: Int, pawn: Int) = active && session.submitLocal(seat, LudoMove(pawn)) == null

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
        fun local(players: List<LocalPlayer>): LudoTable {
            val transport = LocalTransport()
            val session = HostSession(LudoSettings.rules, transport, roomCode = "LOCAL")
            players.forEach {
                if (it.computer) session.addBot(it.name, Platform.ANDROID, LudoBot())
                else session.addLocalPlayer(it.name, Platform.ANDROID)
            }
            players.indices.reversed().forEach { session.setColor(it, players[it].color) }
            session.start()
            val people = players.indices.filter { !players[it].computer }
            return LudoHostTable(session, mySeat = people.singleOrNull(), transport = transport)
        }
    }
}

class LudoGuestTable(
    private val session: GuestSession<LudoState, LudoMove>,
) : LudoTable() {
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
            notice = if (players.any { !it.connected }) R.string.ludo_player_lost else null
            deliver(state, session.stateSeq)
        }
        session.onPlayers = { list ->
            players = list
            away.players(list)
            notice = if (list.any { !it.connected }) R.string.ludo_player_lost else null
        }
        session.onInvalid = { notice = R.string.nearby_move_refused }
        session.onDisconnected = { away.hostLost() }
        session.onEmote = { seat, id -> showEmote(seat, id) }
        ticker.start()
    }

    override val canEmote: Boolean get() = true

    override fun roll(seat: Int) =
        active && seat == session.seat && session.sendMove(LudoMove(null)) != null

    override fun movePawn(seat: Int, pawn: Int) =
        active && seat == session.seat && session.sendMove(LudoMove(pawn)) != null

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
