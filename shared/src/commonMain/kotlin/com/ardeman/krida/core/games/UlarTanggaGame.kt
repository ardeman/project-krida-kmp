package com.ardeman.krida.core.games

import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.session.GameRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Ular tangga (snakes and ladders). [positions] holds one square per seat: 0 is off the
 * board (the start), 1..100 are squares. [lastRoll] and [lastSeat] describe the move that
 * produced this state, so guests can animate it; both are absent before the first roll.
 */
@Serializable
data class UlarTanggaState(
    val positions: List<Int>,
    val turn: Int,
    val finished: Boolean = false,
    val winner: Int? = null,
    val lastRoll: Int? = null,
    val lastSeat: Int? = null,
)

/** The only intent: roll the die. The host rolls it. */
@Serializable
class UlarTanggaMove {
    override fun equals(other: Any?) = other is UlarTanggaMove
    override fun hashCode() = 0
}

/**
 * Result of one roll, for animation: the piece walks [steps] one square at a time (a
 * bounce off 100 walks back down), then slides or climbs from the last step to [jumpTo].
 */
data class UlarTanggaPlay(
    val state: UlarTanggaState,
    val seat: Int,
    val roll: Int,
    val steps: List<Int>,
    val jumpTo: Int?,
    val extraTurn: Boolean,
)

/**
 * Rule variant (2–4 players, one fixed board):
 * - Everyone starts off the board (square 0); a roll of n moves to square n.
 * - Roll one die and move that many squares.
 * - Landing on the foot of a ladder climbs it; landing on a snake's head slides to its tail.
 * - You must land on 100 exactly: the rest of a roll that overshoots walks back down
 *   (from 98, a 5 ends on 97). Snakes and ladders still apply where you end.
 * - A 6 rolls again (unless it wins).
 * - The first to reach 100 wins, and the game ends.
 */
object UlarTanggaGame : GameRules<UlarTanggaState, UlarTanggaMove> {
    const val SIZE = 10
    const val LAST = SIZE * SIZE

    /** Ladder foot to top. */
    val LADDERS: Map<Int, Int> = mapOf(
        4 to 25, 13 to 46, 33 to 49, 42 to 63, 50 to 69, 62 to 81, 74 to 92,
    )

    /** Snake head to tail. */
    val SNAKES: Map<Int, Int> = mapOf(
        27 to 5, 40 to 3, 43 to 18, 54 to 31, 66 to 45, 76 to 58, 89 to 53, 97 to 61, 99 to 41,
    )

    override val id = "ular_tangga"
    override val minPlayers = 2
    override val maxPlayers = 4
    override val stateSerializer: KSerializer<UlarTanggaState> = UlarTanggaState.serializer()
    override val moveSerializer: KSerializer<UlarTanggaMove> = UlarTanggaMove.serializer()

    override fun initialState(players: Int, random: Random): UlarTanggaState {
        require(players in minPlayers..maxPlayers) { "players must be $minPlayers..$maxPlayers" }
        return UlarTanggaState(positions = List(players) { 0 }, turn = 0)
    }

    override fun currentTurn(state: UlarTanggaState) = state.turn

    override fun validate(state: UlarTanggaState, seat: Int, move: UlarTanggaMove): String? =
        if (state.finished) "finished" else null

    override fun apply(state: UlarTanggaState, seat: Int, move: UlarTanggaMove, random: Random) =
        play(state, seat, random.nextInt(1, 7)).state

    override fun isFinished(state: UlarTanggaState) = state.finished

    /** Where a snake or ladder at [square] leads, or null. */
    fun jump(square: Int): Int? = LADDERS[square] ?: SNAKES[square]

    /**
     * Grid cell of [square] (1..100) as column and row from the bottom-left, both 0..9.
     * Rows run boustrophedon: square 1 is bottom-left, 10 bottom-right, 11 above 10.
     */
    fun column(square: Int): Int {
        val i = square - 1
        val row = i / SIZE
        return if (row % 2 == 0) i % SIZE else SIZE - 1 - i % SIZE
    }

    fun row(square: Int) = (square - 1) / SIZE

    /** Applies [roll] (1..6) for [seat]. Public so tests and replays can pick the roll. */
    fun play(state: UlarTanggaState, seat: Int, roll: Int): UlarTanggaPlay {
        require(roll in 1..6) { "roll must be 1..6" }
        val steps = ArrayList<Int>(roll)
        var pos = state.positions[seat]
        var dir = 1
        repeat(roll) {
            if (pos == LAST) dir = -1
            pos += dir
            steps += pos
        }
        val jumpTo = jump(pos)
        val end = jumpTo ?: pos
        val positions = state.positions.toMutableList().also { it[seat] = end }
        val won = end == LAST
        val extraTurn = roll == 6 && !won
        val next = if (won || extraTurn) seat else (seat + 1) % positions.size
        val newState = UlarTanggaState(
            positions = positions,
            turn = next,
            finished = won,
            winner = if (won) seat else null,
            lastRoll = roll,
            lastSeat = seat,
        )
        return UlarTanggaPlay(newState, seat, roll, steps, jumpTo, extraTurn)
    }
}

/** There is nothing to decide in ular tangga, so the computer just rolls. */
object UlarTanggaBot : Bot<UlarTanggaState, UlarTanggaMove> {
    override fun chooseMove(state: UlarTanggaState, seat: Int, random: Random) = UlarTanggaMove()
}
