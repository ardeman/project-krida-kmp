package com.ardeman.krida.core.games

import com.ardeman.krida.core.session.GameRules
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
 * - Last seed in an empty hole on your side: capture it plus the facing hole into your
 *   store, if the facing hole has seeds (tembak). Otherwise the seed stays. Turn ends.
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

    override fun initialState(players: Int, random: Random) = CongklakState(
        pits = List(PITS) { if (isStore(it)) 0 else SEEDS },
        turn = 0,
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

        while (true) {
            while (hand > 0) {
                pos = (pos + 1) % PITS
                if (pos == skip) continue
                pits[pos]++
                hand--
                path += pos
            }
            when {
                pos == own -> { extraTurn = true; break }
                pits[pos] > 1 && path.size < MAX_SOWN -> { hand = pits[pos]; pits[pos] = 0 }
                ownerOf(pos) == seat && pits[pos] == 1 -> {
                    val facing = opposite(pos)
                    if (pits[facing] > 0) {
                        pits[own] += pits[facing] + 1
                        pits[facing] = 0
                        pits[pos] = 0
                        capturedFrom = facing
                    }
                    break
                }
                else -> break
            }
        }

        val next = if (extraTurn) seat else 1 - seat
        return CongklakPlay(settle(pits, next), path, capturedFrom, extraTurn)
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
