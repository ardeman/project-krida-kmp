package com.ardeman.krida.core.games

import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.LobbyOptionRules
import com.ardeman.krida.core.session.SeatColorRules
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
 * order (first entry = 1st place).
 *
 * A game opens with a dice-off that sets the play order: while [opening] is true each seat
 * throws in turn ([turn] is the seat to throw) and [openingRolls] holds the rounds, one list
 * per round with a roll per seat, 0 for a seat that does not throw that round (or has not
 * thrown yet). Everyone throws in the first round; seats whose throws so far are all equal
 * throw another round between themselves, until every seat's throws differ. Higher throws
 * go earlier. [order] is the resulting play order (empty means seat order) and [firstTurn]
 * its first seat. [lastRoll] and [lastSeat] describe the move that produced this state, so
 * guests can animate it; both are absent until the first move after the dice-off. [colors] is each seat's pawn color
 * (0 red, 1 blue, 2 green, 3 yellow, all different); empty means seat order.
 */
@Serializable
data class UlarTanggaState(
    val positions: List<Int>,
    val turn: Int,
    val firstTurn: Int = 0,
    val openingRolls: List<List<Int>> = emptyList(),
    val opening: Boolean = false,
    val order: List<Int> = emptyList(),
    val finishOrder: List<Int> = emptyList(),
    val finished: Boolean = false,
    val lastRoll: Int? = null,
    val lastSeat: Int? = null,
    val options: UlarTanggaOptions = UlarTanggaOptions(),
    val colors: List<Int> = emptyList(),
) {
    /** [seat]'s pawn color, 0..3. */
    fun color(seat: Int): Int = colors.getOrNull(seat) ?: seat

    /** Seats in play order. */
    val playOrder: List<Int> get() = order.ifEmpty { positions.indices.toList() }

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
 * Deterministic dice animation parameters, seeded by [seat] and [roll] so all players see the same
 * trajectory and rotation on the board. The seed ensures the same pseudo-random values on all platforms.
 *
 * @param landX horizontal landing position in board units (deterministic within landing area)
 * @param landZ depth landing position in board units (deterministic within landing area)
 * @param spinYaw yaw rotation in radians (deterministic, affects which face is shown on landing)
 */
data class UlarTanggaDiceAnimation(
    val landX: Float,
    val landZ: Float,
    val spinYaw: Float,
) {
    companion object {
        /**
         * Compute deterministic animation parameters for a dice throw from [seat] rolling [value].
         * All clients produce the same parameters from these inputs alone, so the dice animation
         * is consistent across players without any protocol changes.
         */
        fun compute(seat: Int, value: Int, isLongToss: Boolean): UlarTanggaDiceAnimation {
            // Seed the RNG with seat and roll value to get consistent randomness across all clients
            val seed = (seat.toLong() shl 32) or (value.toLong() and 0xFFFFFFFFL)
            val rng = Random(seed)

            val area = UlarTanggaBoard.dieLanding
            val long = isLongToss
            val midX = (area[0] + area[2]) / 2

            // Landing position within the board area, using seeded random
            val landX = if (long) {
                area[0] + rng.nextFloat() * (midX - area[0])
            } else {
                midX + rng.nextFloat() * (area[2] - midX)
            }
            val landZ = area[1] + rng.nextFloat() * (area[3] - area[1])

            // Spin rotation: quantized to 90-degree increments with some variation
            val yaw = (rng.nextInt(4) * 90f + (rng.nextFloat() - 0.5f) * 40f) * kotlin.math.PI.toFloat() / 180f

            return UlarTanggaDiceAnimation(landX, landZ, yaw)
        }
    }
}

/**
 * Standard rules (2–4 players, one fixed board), each changeable in [UlarTanggaOptions]:
 * - Everyone throws the die to set the play order, highest first; tied players throw again
 *   between themselves. Turns then go round in that order.
 * - Everyone starts off the board (square 0); a roll of n moves to square n.
 * - Landing on the foot of a ladder climbs it; landing on a snake's head slides to its tail.
 * - You must land on 100 exactly: a roll that would pass it leaves the piece where it is.
 * - A 6 rolls again, unless that roll brought the piece to 100.
 * - Players who reach 100 leave the board in finishing order; the game ends when one
 *   player is left, who takes last place.
 */
object UlarTanggaGame : GameRules<UlarTanggaState, UlarTanggaMove>, SeatColorRules<UlarTanggaState> {
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

    /** Pawn colors: red, blue, green, yellow. */
    const val COLORS = 4

    override val colorCount = COLORS

    override fun initialState(players: Int, random: Random) =
        initialState(players, UlarTanggaOptions(), emptyList(), random)

    override fun initialState(players: Int, colors: List<Int>, random: Random) =
        initialState(players, UlarTanggaOptions(), colors, random)

    fun initialState(players: Int, options: UlarTanggaOptions, random: Random) =
        initialState(players, options, emptyList(), random)

    /**
     * A new game, waiting for seat 0's first throw of the dice-off. [colors] are the pawn
     * colors the players chose, by seat (see [seatColors]).
     */
    fun initialState(players: Int, options: UlarTanggaOptions, colors: List<Int>, random: Random): UlarTanggaState {
        require(players in minPlayers..maxPlayers) { "players must be $minPlayers..$maxPlayers" }
        return UlarTanggaState(
            positions = List(players) { 0 },
            turn = 0,
            openingRolls = listOf(List(players) { 0 }),
            opening = true,
            options = options,
            colors = seatColors(players, colors),
        )
    }

    /**
     * A pawn color for each of [players] seats: the [wanted] colors in seat order, skipping
     * any that is out of range or already taken, and the free colors in order for the rest.
     */
    fun seatColors(players: Int, wanted: List<Int>): List<Int> {
        val taken = ArrayList<Int>()
        for (c in wanted) if (taken.size < players && c in 0 until COLORS && c !in taken) taken += c
        val free = (0 until COLORS).filter { it !in taken }.iterator()
        while (taken.size < players) taken += free.next()
        return taken
    }

    /**
     * Seats that throw in the dice-off round after [rounds] (all of them complete): everyone
     * for the first round, then the seats whose throws so far equal another seat's. Empty
     * when the order is settled.
     */
    fun diceOffThrowers(rounds: List<List<Int>>, players: Int): List<Int> {
        if (rounds.isEmpty()) return (0 until players).toList()
        return (0 until players).groupBy { seat -> rounds.map { it[seat] } }.values
            .filter { it.size > 1 }.flatten().sorted()
    }

    /** Play order from the dice-off [rounds]: higher throws first, compared round by round. */
    fun diceOffOrder(rounds: List<List<Int>>, players: Int): List<Int> =
        (0 until players).sortedWith { a, b ->
            rounds.firstOrNull { it[a] != it[b] }?.let { it[b].compareTo(it[a]) } ?: a.compareTo(b)
        }

    /** Applies [roll] (1..6) as [seat]'s dice-off throw. Public so tests and replays can pick the roll. */
    fun diceOffThrow(state: UlarTanggaState, seat: Int, roll: Int): UlarTanggaState {
        require(state.opening) { "the dice-off is over" }
        require(roll in 1..6) { "roll must be 1..6" }
        val n = state.positions.size
        val earlier = state.openingRolls.dropLast(1)
        val round = state.openingRolls.last().toMutableList().also { it[seat] = roll }
        val rounds = earlier + listOf(round)
        diceOffThrowers(earlier, n).firstOrNull { round[it] == 0 }?.let {
            return state.copy(openingRolls = rounds, turn = it)
        }
        diceOffThrowers(rounds, n).firstOrNull()?.let {
            return state.copy(openingRolls = rounds + listOf(List(n) { 0 }), turn = it)
        }
        val order = diceOffOrder(rounds, n)
        return state.copy(openingRolls = rounds, opening = false, order = order, turn = order[0], firstTurn = order[0])
    }

    /**
     * The dice-off throw that turned [before] into [after], as seat to roll, or null when
     * [after] did not come from one. For animation.
     */
    fun diceOffThrowBetween(before: UlarTanggaState, after: UlarTanggaState): Pair<Int, Int>? {
        if (!before.opening) return null
        val i = before.openingRolls.lastIndex
        val was = before.openingRolls.getOrNull(i) ?: return null
        val now = after.openingRolls.getOrNull(i) ?: return null
        val seat = was.indices.firstOrNull { was[it] == 0 && now.getOrElse(it) { 0 } != 0 } ?: return null
        return seat to now[seat]
    }

    override fun currentTurn(state: UlarTanggaState) = state.turn

    override fun validate(state: UlarTanggaState, seat: Int, move: UlarTanggaMove): String? =
        if (state.finished) "finished" else null

    override fun apply(state: UlarTanggaState, seat: Int, move: UlarTanggaMove, random: Random) =
        if (state.opening) diceOffThrow(state, seat, random.nextInt(1, 7))
        else play(state, seat, random.nextInt(1, 7)).state

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
        require(!state.opening) { "the dice-off is still on" }
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
            else -> nextPlaying(state.playOrder, order, seat)
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

    /** The next seat after [seat] in [playOrder] that has not reached 100. */
    private fun nextPlaying(playOrder: List<Int>, finished: List<Int>, seat: Int): Int {
        var i = playOrder.indexOf(seat)
        do i = (i + 1) % playOrder.size while (playOrder[i] in finished)
        return playOrder[i]
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
 * Ular tangga with the choices made before the game: [UlarTanggaGame] in every respect,
 * except that new games (and restarts) start with [options]. As [SeatColorRules] it takes the
 * pawn colours the players picked in the host's `HostSession` lobby.
 * Give this to the host's `HostSession`; guests read both from the state (and [options] from the lobby).
 */
class UlarTanggaRules(val options: UlarTanggaOptions) :
    GameRules<UlarTanggaState, UlarTanggaMove> by UlarTanggaGame, SeatColorRules<UlarTanggaState>, LobbyOptionRules {

    override val colorCount = UlarTanggaGame.COLORS

    override val lobbyOptions = Codec.json.encodeToJsonElement(UlarTanggaOptions.serializer(), options)

    override fun initialState(players: Int, random: Random) =
        UlarTanggaGame.initialState(players, options, emptyList(), random)

    override fun initialState(players: Int, colors: List<Int>, random: Random) =
        UlarTanggaGame.initialState(players, options, colors, random)
}

/** There is nothing to decide in ular tangga, so the computer just rolls. */
object UlarTanggaBot : Bot<UlarTanggaState, UlarTanggaMove> {
    override fun chooseMove(state: UlarTanggaState, seat: Int, random: Random) = UlarTanggaMove()
}
