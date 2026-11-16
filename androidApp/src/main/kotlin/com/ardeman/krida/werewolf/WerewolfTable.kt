package com.ardeman.krida.werewolf

import com.ardeman.krida.ui.EmoteEvent
import com.ardeman.krida.ui.plusEmote
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.R
import com.ardeman.krida.core.games.WerewolfBot
import com.ardeman.krida.core.games.WerewolfGame
import com.ardeman.krida.core.games.WerewolfMove
import com.ardeman.krida.core.games.WerewolfState
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

class WerewolfUpdate(val state: WerewolfState, val fresh: Boolean)

/**
 * Werewolf table bridge. The host session keeps full state; this device's UI always gets
 * [WerewolfGame.viewFor] for [mySeat]. Guests receive a filtered state from the relay.
 */
abstract class WerewolfTable {
    abstract val mySeat: Int
    abstract val isHost: Boolean

    var players by mutableStateOf<List<PlayerInfo>>(emptyList())
        protected set
    var notice by mutableStateOf<Int?>(null)
        protected set
    var waiting by mutableStateOf<Waiting?>(null)
        protected set
    var emotes by mutableStateOf<List<EmoteEvent>>(emptyList())
        protected set

    lateinit var latest: WerewolfState
        private set

    var onUpdate: ((WerewolfUpdate) -> Unit)? = null

    private var seq = 0

    open val canEmote: Boolean get() = false
    open val canRestart: Boolean get() = false

    protected var active = true
        private set

    protected fun first(state: WerewolfState, seq: Int) {
        latest = state
        this.seq = seq
    }

    protected fun deliver(state: WerewolfState, seq: Int) {
        if (!active) return
        val fresh = seq == this.seq + 1
        this.seq = seq
        latest = state
        onUpdate?.invoke(WerewolfUpdate(state, fresh))
    }

    protected fun showEmote(seat: Int, id: String) {
        if (active) emotes = emotes.plusEmote(seat, id)
    }

    val botSeats: Set<Int> get() = players.filter { it.bot }.map { it.seat }.toSet()

    fun name(seat: Int) = players.firstOrNull { it.seat == seat }?.name ?: "${seat + 1}"

    abstract fun play(move: WerewolfMove): Boolean
    abstract fun playLocal(seat: Int, move: WerewolfMove): Boolean
    abstract fun advanceDiscuss(): Boolean
    open fun stepBot(): Boolean = false
    abstract fun restart()
    open fun leave() {
        active = false
        onUpdate = null
        emotes = emptyList()
    }

    open fun sendEmote(id: String) = Unit
}

class WerewolfHostTable(
    private val session: HostSession<WerewolfState, WerewolfMove>,
    override val mySeat: Int,
    private val transport: Transport,
) : WerewolfTable() {
    override val isHost = true
    override val canRestart = true

    private val ticker = SecondTicker {
        session.expiredSeats().forEach { session.replaceWithBot(it, WerewolfSettings.bot()) }
        waiting = session.waiting()
    }

    init {
        players = session.players
        first(view(checkNotNull(session.state)), session.seq)
        session.onPlayersChanged = { list ->
            players = list
            notice = if (list.any { !it.connected }) R.string.werewolf_player_lost else null
        }
        session.onStateChanged = { state ->
            players = session.players
            notice = if (players.any { !it.connected }) R.string.werewolf_player_lost else null
            deliver(view(state), session.seq)
        }
        session.onEmote = { seat, id -> showEmote(seat, id) }
        if (transport !is LocalTransport) ticker.start()
    }

    override val canEmote: Boolean get() = transport !is LocalTransport

    private fun view(state: WerewolfState) = WerewolfGame.viewFor(state, mySeat)

    override fun play(move: WerewolfMove) = playLocal(mySeat, move)

    override fun playLocal(seat: Int, move: WerewolfMove) =
        active && session.submitLocal(seat, move) == null

    override fun advanceDiscuss() =
        active && session.submitLocal(0, WerewolfMove(advance = true)) == null

    override fun stepBot(): Boolean = active && session.stepBot()

    override fun restart() {
        if (active && WerewolfGame.isFinished(latest)) session.restart()
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
        /** You in seat 0 against enough computers to reach [players] (5–8). */
        fun vsComputer(
            you: String,
            players: Int = WerewolfSettings.targetSeats,
            computerName: (Int) -> String,
        ): WerewolfHostTable {
            val total = players.coerceIn(WerewolfGame.minPlayers, WerewolfGame.maxPlayers)
            val transport = LocalTransport()
            val session = HostSession(WerewolfSettings.rules, transport, roomCode = "LOCAL")
            session.addLocalPlayer(you, Platform.ANDROID)
            for (i in 1 until total) {
                session.addBot(computerName(i), Platform.ANDROID, WerewolfSettings.bot())
            }
            session.start()
            return WerewolfHostTable(session, mySeat = 0, transport = transport)
        }
    }
}

class WerewolfGuestTable(
    private val session: GuestSession<WerewolfState, WerewolfMove>,
) : WerewolfTable() {
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
            notice = if (players.any { !it.connected }) R.string.werewolf_player_lost else null
            deliver(state, session.stateSeq)
        }
        session.onPlayers = { list ->
            players = list
            away.players(list)
            notice = if (list.any { !it.connected }) R.string.werewolf_player_lost else null
        }
        session.onInvalid = { notice = R.string.nearby_move_refused }
        session.onDisconnected = { away.hostLost() }
        session.onEmote = { seat, id -> showEmote(seat, id) }
        ticker.start()
    }

    override val canEmote: Boolean get() = true

    override fun play(move: WerewolfMove) = active && session.sendMove(move) != null

    override fun playLocal(seat: Int, move: WerewolfMove) =
        active && seat == mySeat && session.sendMove(move) != null

    override fun advanceDiscuss() = false

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