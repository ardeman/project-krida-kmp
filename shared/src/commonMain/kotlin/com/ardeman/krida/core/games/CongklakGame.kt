package com.ardeman.krida.core.games

import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.LobbyOptionRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Congklak board, 16 pits sown in increasing index order (wrapping):
 *
 * ```
 *  0..6   seat 0 holes      7  seat 0 store (lumbung)
 *  8..14  seat 1 holes      15 seat 1 store
 * ```
 *
 * Hole `i` on seat 0 faces hole `14 - i` on seat 1.
 */
@Serializable
data class CongklakState(
    val pits: List<Int>,
    val turn: Int,
    val finished: Boolean = false,
    /**
     * The Indonesian tembak rule, chosen by the host before the game: a tembak only counts
     * once this turn's sowing has passed the mover's own store. Absent (false) means tembak
     * on any move, which is also what hosts from before this field played.
     */
    val tembakAfterStore: Boolean = false,
)

/** [hole] is 0..6, counted from the mover's own side (index 0 = first hole after the opponent's store). */
@Serializable
data class CongklakMove(val hole: Int)

/** Result of one move, with every pit a seed was dropped into, in order, for animation. */
data class CongklakPlay(
    val state: CongklakState,
    val path: List<Int>,
    val capturedFrom: Int?,
    val extraTurn: Boolean,
)

/**
 * Rule variant (one round, 2 players):
 * - 7 holes and 1 store per player, 7 seeds per hole.
 * - Sow one seed per pit, including your own store, skipping the opponent's store.
 * - Last seed in a non-empty hole (either side): pick all of them up and keep sowing.
 * - Last seed in your own store: you move again.
 * - Last seed in an empty hole on your side (tembak): move it plus everything in the facing
 *   hole into your store. If the facing hole is empty, the lone seed still goes to your
 *   store. Turn ends. With [CongklakState.tembakAfterStore] (the Indonesian rule) this only
 *   counts once this turn's sowing has passed your own store; before that the seed just
 *   stays there and the turn ends.
 * - Last seed in an empty hole on the opponent's side: turn ends.
 * - If the player to move has no seeds in their holes, the game ends and the opponent
 *   collects what is left on their own side. Most seeds in store wins.
 */
object CongklakGame : GameRules<CongklakState, CongklakMove> {
    const val HOLES = 7
    const val SEEDS = 7
    const val PITS = 2 * (HOLES + 1)

    /** Guards against a pathological endless relay; never hit in real play. */
    private const val MAX_SOWN = 100_000

    override val id = "congklak"
    override val minPlayers = 2
    override val maxPlayers = 2
    override val stateSerializer: KSerializer<CongklakState> = CongklakState.serializer()
    override val moveSerializer: KSerializer<CongklakMove> = CongklakMove.serializer()

    fun store(seat: Int) = seat * (HOLES + 1) + HOLES
    fun pit(seat: Int, hole: Int) = seat * (HOLES + 1) + hole
    fun opposite(pit: Int) = 2 * HOLES - pit
    fun ownerOf(pit: Int) = pit / (HOLES + 1)
    fun isStore(pit: Int) = pit % (HOLES + 1) == HOLES

    override fun initialState(players: Int, random: Random) = initialState(tembakAfterStore = false)

    fun initialState(tembakAfterStore: Boolean) = CongklakState(
        pits = List(PITS) { if (isStore(it)) 0 else SEEDS },
        turn = 0,
        tembakAfterStore = tembakAfterStore,
    )

    override fun currentTurn(state: CongklakState) = state.turn

    override fun validate(state: CongklakState, seat: Int, move: CongklakMove): String? = when {
        move.hole !in 0 until HOLES -> "bad_hole"
        state.pits[pit(seat, move.hole)] == 0 -> "empty_hole"
        else -> null
    }

    override fun apply(state: CongklakState, seat: Int, move: CongklakMove, random: Random) =
        play(state, seat, move).state

    override fun isFinished(state: CongklakState) = state.finished

    fun score(state: CongklakState, seat: Int) = state.pits[store(seat)]

    /** Winning seat, -1 for a draw, null while playing. */
    fun winner(state: CongklakState): Int? {
        if (!state.finished) return null
        val a = score(state, 0)
        val b = score(state, 1)
        return if (a == b) -1 else if (a > b) 0 else 1
    }

    /**
     * What the phone plays for [seat] when its turn timer ([com.ardeman.krida.core.protocol.Krida.PLAY_TURN_MS])
     * runs out: what the easy computer ([CongklakBot.Level.EASY]) would pick, any hole with
     * seeds, drawn from [random]. The phone of the player who timed out picks it and sends it
     * like a tap, so host and guest need not agree on the draw. Null when it is not [seat]'s
     * turn or the game is over.
     */
    fun timeoutMove(state: CongklakState, seat: Int, random: Random): CongklakMove? {
        if (state.finished || seat != state.turn) return null
        if ((0 until HOLES).none { state.pits[pit(seat, it)] > 0 }) return null
        return CongklakBot(CongklakBot.Level.EASY).chooseMove(state, seat, random)
    }

    /** Applies a validated move and reports the sowing path. */
    fun play(state: CongklakState, seat: Int, move: CongklakMove): CongklakPlay {
        val pits = state.pits.toMutableList()
        val path = ArrayList<Int>()
        val skip = store(1 - seat)
        val own = store(seat)

        var pos = pit(seat, move.hole)
        var hand = pits[pos].also { pits[pos] = 0 }
        var capturedFrom: Int? = null
        var extraTurn = false
        /** This turn has dropped a seed in the mover's own store: tembak is allowed. */
        var passedOwn = false

        while (true) {
            while (hand > 0) {
                pos = (pos + 1) % PITS
                if (pos == skip) continue
                pits[pos]++
                hand--
                path += pos
                if (pos == own) passedOwn = true
            }
            when {
                pos == own -> { extraTurn = true; break }
                pits[pos] > 1 && path.size < MAX_SOWN -> { hand = pits[pos]; pits[pos] = 0 }
                ownerOf(pos) == seat && pits[pos] == 1 && (passedOwn || !state.tembakAfterStore) -> {
                    val facing = opposite(pos)
                    if (pits[facing] > 0) capturedFrom = facing
                    pits[own] += pits[facing] + 1
                    pits[facing] = 0
                    pits[pos] = 0
                    break
                }
                else -> break
            }
        }

        val next = if (extraTurn) seat else 1 - seat
        return CongklakPlay(settle(pits, next).copy(tembakAfterStore = state.tembakAfterStore), path, capturedFrom, extraTurn)
    }

    /** Ends the game when [next] cannot move; the opponent sweeps their own side. */
    private fun settle(pits: MutableList<Int>, next: Int): CongklakState {
        val canMove = (0 until HOLES).any { pits[pit(next, it)] > 0 }
        if (canMove) return CongklakState(pits, next)
        val other = 1 - next
        for (h in 0 until HOLES) {
            pits[store(other)] += pits[pit(other, h)]
            pits[pit(other, h)] = 0
        }
        return CongklakState(pits, next, finished = true)
    }
}

/** The congklak rule options guests see in the lobby (see `LobbyOptions`). */
@Serializable
data class CongklakOptions(val tembakAfterStore: Boolean = false) {
    /** For Swift, which does not see Kotlin default arguments. */
    constructor() : this(false)
}

/** Who moves first in a new game: [ME] is seat 0 (this device's player, or the host). */
enum class CongklakStarter { ME, OPPONENT, RANDOM }

/**
 * Congklak with options chosen before the game: [CongklakGame] in every respect, except
 * that new games (and restarts) start with [tembakAfterStore] and the [starter]'s turn
 * ([CongklakStarter.RANDOM] draws from the host's `Random` for each game). Give this to the
 * host's `HostSession`; guests read the rule and the turn from the state (and the rule from the lobby).
 */
class CongklakRules(
    val tembakAfterStore: Boolean,
    val starter: CongklakStarter,
) : GameRules<CongklakState, CongklakMove> by CongklakGame, LobbyOptionRules {
    /** Seat 0 moves first, as before the starter option. */
    constructor(tembakAfterStore: Boolean) : this(tembakAfterStore, CongklakStarter.ME)

    override val lobbyOptions = Codec.json.encodeToJsonElement(CongklakOptions.serializer(), CongklakOptions(tembakAfterStore))

    override fun initialState(players: Int, random: Random): CongklakState {
        val turn = when (starter) {
            CongklakStarter.ME -> 0
            CongklakStarter.OPPONENT -> 1
            CongklakStarter.RANDOM -> random.nextInt(2)
        }
        return CongklakGame.initialState(tembakAfterStore).copy(turn = turn)
    }
}
