package com.ardeman.krida.gaple

import com.ardeman.krida.ui.EmoteEvent
import com.ardeman.krida.ui.plusEmote
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.core.games.GapleBot
import com.ardeman.krida.core.games.GapleMove
import com.ardeman.krida.core.games.GapleState
import com.ardeman.krida.core.games.GapleTile
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

/** A new snapshot; [fresh] when it directly follows a tile being played, so it can animate. */
class GapleUpdate(val state: GapleState, val fresh: Boolean)

/**
 * What the gaple screen plays on: this phone (you against computers), hosting a room, or a
 * guest. Session callbacks arrive on the main thread. [latest] is what this phone may see: a
 * host's full state is shown through the screen, which only draws [mySeat]'s hand.
 */
abstract class GapleTable {
    abstract val mySeat: Int
    /** Deals the next round and restarts a finished match (the host). */
    abstract val isHost: Boolean

    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        protected set
    var notice by mutableStateOf<Int?>(null)
        protected set
    var waiting by mutableStateOf<Waiting?>(null)
        protected set
    var emotes by mutableStateOf<List<EmoteEvent>>(emptyList())
        protected set

    lateinit var latest: GapleState
        private set

    var onUpdate: ((GapleUpdate) -> Unit)? = null

    private var seq = 0

    open val canEmote: Boolean get() = false

    /** Everyone else is a computer on this device, so nobody waits through an ad. */
    open val isLocal: Boolean get() = false

    protected var active = true
        private set

    protected fun first(state: GapleState, seq: Int) {
        latest = state
        this.seq = seq
    }

    protected fun deliver(state: GapleState, seq: Int) {
        if (!active) return
        val fresh = seq == this.seq + 1 && state.lastTile != null && state.round == latest.round
        this.seq = seq
        latest = state
        onUpdate?.invoke(GapleUpdate(state, fresh))
    }

    protected fun showEmote(seat: Int, id: String) {
        if (active) emotes = emotes.plusEmote(seat, id)
    }

    val botSeats: Set<Int> get() = players.filter { it.bot }.map { it.seat }.toSet()

    fun name(seat: Int) = players.firstOrNull { it.seat == seat }?.name ?: "${seat + 1}"

    abstract fun play(tile: GapleTile, end: Int?): Boolean
    abstract fun nextRound(): Boolean
    open fun stepBot() = Unit
    abstract fun restart()
    open fun leave() { active = false; onUpdate = null; emotes = emptyList() }
    open fun sendEmote(id: String) = Unit
}

class GapleHostTable(
    private val session: HostSession<GapleState, GapleMove>,
    override val mySeat: Int,
    private val transport: Transport,
) : GapleTable() {
    override val isHost = true

    private val ticker = SecondTicker {
        session.expiredSeats().forEach { session.replaceWithBot(it, GapleBot()) }
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

    override val isLocal: Boolean get() = transport is LocalTransport

    override fun play(tile: GapleTile, end: Int?) =
        active && session.submitLocal(mySeat, GapleMove(tile, end)) == null

    override fun nextRound() =
        active && session.submitLocal(mySeat, GapleMove(next = true)) == null

    override fun stepBot() {
        if (active) session.stepBot()
    }

    override fun restart() {
        if (active && latest.finished) session.restart()
    }

    override fun sendEmote(id: String) {
        if (active && canEmote) session.sendEmote(mySeat, id)
    }

    override fun leave() {
        if (!active) return
        super.leave()
        ticker.stop()
        transport.close()
    }

    companion object {
        /** You in seat 0 against [computers] computers, named by [computerName] (seat 1..). */
        fun vsComputer(you: String, computers: Int, computerName: (Int) -> String): GapleTable {
            val transport = LocalTransport()
            val session = HostSession(GapleSettings.rules, transport, roomCode = "LOCAL")
            session.addLocalPlayer(you, Platform.ANDROID)
            for (i in 1..computers) session.addBot(computerName(i), Platform.ANDROID, GapleBot())
            session.start()
            return GapleHostTable(session, mySeat = 0, transport = transport)
        }
    }
}

class GapleGuestTable(
    private val session: GuestSession<GapleState, GapleMove>,
) : GapleTable() {
    override val mySeat: Int get() = session.seat ?: 0
    override val isHost = false

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

    override fun play(tile: GapleTile, end: Int?) =
        active && session.sendMove(GapleMove(tile, end)) != null

    override fun nextRound() = false

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
