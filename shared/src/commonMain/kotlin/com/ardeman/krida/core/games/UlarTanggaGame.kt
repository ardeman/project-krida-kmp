package com.ardeman.krida.core.games

import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.session.GameRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Rule options the host picks before the game. The defaults are the standard Krida rules;
 * each option switches on a common alternative.
 */
@Serializable
data class UlarTanggaOptions(
    /** A roll past 100 walks back down the extra squares instead of the piece staying put. */
    val bounceBack: Boolean = false,
    /** A piece needs a 6 to enter the board; that 6 puts it on square 1. */
    val sixToEnter: Boolean = false,
    /** The game ends when the first player reaches 100, instead of when one player is left. */
    val firstFinishEnds: Boolean = false,
    /** A 6 rolls again. */
    val sixRollsAgain: Boolean = true,
) {
    /** For Swift, which does not see Kotlin default arguments. */
    constructor() : this(false, false, false, true)
}

/**
 * Ular tangga (snakes and ladders). [positions] holds one square per seat: 0 is off the
 * board (the start), 1..100 are squares. [finishOrder] lists the seats that reached 100, in
 * order (first entry = 1st place). [firstTurn] is the seat that won the opening dice-off,
 * and [openingRolls] its rounds so guests can animate it: one list per round with a roll
 * per seat, 0 for a seat that sat the round out (it lost an earlier one). Every seat throws
 * in the first round; the highest rolls tied go on to the next, until one is left.
 * [lastRoll] and [lastSeat] describe the move that produced this state, so guests can
 * animate it; both are absent before the first roll.
 */
@Serializable
data class UlarTanggaState(
    val positions: List<Int>,
    val turn: Int,
    val firstTurn: Int = 0,
    val openingRolls: List<List<Int>> = emptyList(),
    val finishOrder: List<Int> = emptyList(),
    val finished: Boolean = false,
    val lastRoll: Int? = null,
    val lastSeat: Int? = null,
    val options: UlarTanggaOptions = UlarTanggaOptions(),
) {
    /** 1st place, or null while nobody has reached 100. */
    val winner: Int? get() = finishOrder.firstOrNull()
}

/** The only intent: roll the die. The host rolls it. */
@Serializable
class UlarTanggaMove {
    override fun equals(other: Any?) = other is UlarTanggaMove
    override fun hashCode() = 0
}

/**
 * Result of one roll, for animation: the piece walks [steps] one square at a time (a
 * bounce off 100 walks back down), then slides or climbs from the last step to [jumpTo].
 * [steps] is empty when the piece could not move (overshoot, or no 6 to enter).
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
 * Standard rules (2–4 players, one fixed board), each changeable in [UlarTanggaOptions]:
 * - Everyone throws the die and the highest goes first; tied players throw again until one
 *   is left. Turns then go round in seat order.
 * - Everyone starts off the board (square 0); a roll of n moves to square n.
 * - Landing on the foot of a ladder climbs it; landing on a snake's head slides to its tail.
 * - You must land on 100 exactly: a roll that would pass it leaves the piece where it is.
 * - A 6 rolls again, unless that roll brought the piece to 100.
 * - Players who reach 100 leave the board in finishing order; the game ends when one
 *   player is left, who takes last place.
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

    override fun initialState(players: Int, random: Random) =
        initialState(players, UlarTanggaOptions(), random)

    /** A new game; the host rolls the opening dice-off with [random]. */
    fun initialState(players: Int, options: UlarTanggaOptions, random: Random): UlarTanggaState {
        require(players in minPlayers..maxPlayers) { "players must be $minPlayers..$maxPlayers" }
        val rounds = diceOff(players, random)
        val first = diceOffWinner(rounds)
        return UlarTanggaState(
            positions = List(players) { 0 },
            turn = first,
            firstTurn = first,
            openingRolls = rounds,
            options = options,
        )
    }

    /**
     * The opening dice-off: every seat throws, the seats tied on the highest roll throw again,
     * until one seat has the highest roll alone. Each round has a roll per seat, 0 for a seat
     * that is out.
     */
    fun diceOff(players: Int, random: Random): List<List<Int>> {
        val rounds = ArrayList<List<Int>>()
        var throwing = (0 until players).toList()
        while (throwing.size > 1) {
            val rolls = List(players) { if (it in throwing) random.nextInt(1, 7) else 0 }
            rounds += rolls
            val best = rolls.max()
            throwing = throwing.filter { rolls[it] == best }
        }
        return rounds
    }

    /** The seat with the highest roll in the last round of a dice-off. */
    fun diceOffWinner(rounds: List<List<Int>>): Int = rounds.last().let { it.indexOf(it.max()) }

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
        val o = state.options
        val start = state.positions[seat]
        val steps = ArrayList<Int>(roll)
        when {
            start == 0 && o.sixToEnter -> if (roll == 6) steps += 1
            start + roll > LAST && !o.bounceBack -> Unit
            else -> {
                var pos = start
                var dir = 1
                repeat(roll) {
                    if (pos == LAST) dir = -1
                    pos += dir
                    steps += pos
                }
            }
        }
        val landed = steps.lastOrNull() ?: start
        val jumpTo = if (steps.isEmpty()) null else jump(landed)
        val end = jumpTo ?: landed
        val positions = state.positions.toMutableList().also { it[seat] = end }

        val reached = end == LAST
        val order = if (reached) state.finishOrder + seat else state.finishOrder
        val finished = reached && (o.firstFinishEnds || order.size >= positions.size - 1)
        val extraTurn = roll == 6 && o.sixRollsAgain && !reached
        val next = when {
            finished || extraTurn -> seat
            else -> nextPlaying(positions, order, seat)
        }
        val newState = state.copy(
            positions = positions,
            turn = next,
            finishOrder = order,
            finished = finished,
            lastRoll = roll,
            lastSeat = seat,
        )
        return UlarTanggaPlay(newState, seat, roll, steps, jumpTo, extraTurn)
    }

    /** The next seat after [seat] that has not reached 100. */
    private fun nextPlaying(positions: List<Int>, order: List<Int>, seat: Int): Int {
        var s = seat
        do s = (s + 1) % positions.size while (s in order)
        return s
    }

    /**
     * Final ranking once the game is over: the finishers in order, then everyone else by how
     * far along they are (ties keep seat order). Before the end, the current standing.
     */
    fun ranking(state: UlarTanggaState): List<Int> =
        state.finishOrder + state.positions.indices
            .filter { it !in state.finishOrder }
            .sortedByDescending { state.positions[it] }
}

/**
 * Ular tangga with the options chosen before the game: [UlarTanggaGame] in every respect,
 * except that new games (and restarts) start with [options]. Give this to the host's
 * `HostSession`; guests read the options from the state, so they need nothing.
 */
class UlarTanggaRules(val options: UlarTanggaOptions) :
    GameRules<UlarTanggaState, UlarTanggaMove> by UlarTanggaGame {
    override fun initialState(players: Int, random: Random) =
        UlarTanggaGame.initialState(players, options, random)
}

/** There is nothing to decide in ular tangga, so the computer just rolls. */
object UlarTanggaBot : Bot<UlarTanggaState, UlarTanggaMove> {
    override fun chooseMove(state: UlarTanggaState, seat: Int, random: Random) = UlarTanggaMove()
}
