package com.ardeman.krida.core.games

import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.LobbyOptionRules
import com.ardeman.krida.core.session.SeatColorRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Rule options the host picks before the game. Defaults are the common Indonesian / app
 * Ludo rules; each option switches on a familiar alternative.
 */
@Serializable
data class LudoOptions(
    /** A token needs a 6 to leave the yard onto its start square. */
    val sixToEnter: Boolean = true,
    /** A 6 (after a legal move, or an entry) rolls again. */
    val sixRollsAgain: Boolean = true,
    /** Capturing a lone opponent grants another roll. */
    val captureRollsAgain: Boolean = true,
    /** The game ends when the first player has all four tokens home. */
    val firstFinishEnds: Boolean = false,
) {
    /** For Swift, which does not see Kotlin default arguments. */
    constructor() : this(true, true, true, false)
}

/**
 * One seat's four tokens. Positions are relative to that seat's colour:
 * `-1` in the yard, `0..[TRACK_LAST]` on the main ring from the colour start,
 * `[HOME_START]..[FINISH]` on the home stretch (FINISH = all the way in).
 */
@Serializable
data class LudoState(
    val tokens: List<List<Int>>,
    val turn: Int,
    /** Pending die face while the player still has to pick a token; null means roll first. */
    val roll: Int? = null,
    /** Sixes rolled in a row this turn, before the next move resolves. */
    val sixes: Int = 0,
    val finishOrder: List<Int> = emptyList(),
    val finished: Boolean = false,
    val lastRoll: Int? = null,
    val lastSeat: Int? = null,
    val lastPawn: Int? = null,
    val lastFrom: Int? = null,
    val lastTo: Int? = null,
    /** Opponent seats whose tokens were sent home by [lastPawn]. */
    val lastCaptures: List<LudoCapture> = emptyList(),
    val burnedSix: Boolean = false,
    val options: LudoOptions = LudoOptions(),
    val colors: List<Int> = emptyList(),
) {
    fun color(seat: Int): Int = colors.getOrNull(seat) ?: seat

    val winner: Int? get() = finishOrder.firstOrNull()
}

@Serializable
data class LudoCapture(val seat: Int, val pawn: Int)

/**
 * Intent on a player's turn: [pawn] null rolls the die; `0..3` moves that token with the
 * pending roll.
 */
@Serializable
data class LudoMove(val pawn: Int? = null) {
    constructor() : this(null)
}

/** One resolved action, for animation and tests. */
data class LudoPlay(
    val state: LudoState,
    val seat: Int,
    val roll: Int?,
    val pawn: Int?,
    val from: Int?,
    val to: Int?,
    val path: List<Int>,
    val captures: List<LudoCapture>,
    val extraTurn: Boolean,
    val burnedSix: Boolean,
)

/**
 * Ludo / Permainan umpet (2–4 players, four tokens each). The host rolls; the player then
 * picks a legal token when more than one can move.
 *
 * Defaults ([LudoOptions]):
 * - A 6 leaves the yard onto the colour start; that 6 does not walk further.
 * - Tokens walk clockwise around a 52-square ring, then up a 6-step home stretch; the
 *   finish must be exact.
 * - Colour starts, the starred safe squares and the home stretch are safe; two of the same
 *   colour on one ring square form a block (safe, and others cannot land there).
 * - Capturing a lone opponent sends it to the yard and rolls again; a 6 also rolls again.
 * - Three 6s in a row burn the third roll and end the turn.
 * - Play continues until one player is left unfinished, unless [LudoOptions.firstFinishEnds].
 */
object LudoGame : GameRules<LudoState, LudoMove>, SeatColorRules<LudoState> {
    const val PAWNS = 4
    const val TRACK = 52
    /** Last relative index still on the ring (0 = colour start). */
    const val TRACK_LAST = TRACK - 2 // 50; 51 steps from start reach the home entrance
    const val HOME_START = TRACK_LAST + 1 // 51
    const val HOME_LEN = 6
    const val FINISH = HOME_START + HOME_LEN - 1 // 56
    const val YARD = -1

    /**
     * Absolute ring index of each colour's start square (red, blue, green, yellow).
     * Clockwise from red at the bottom: blue, yellow, green — so green is 39 and yellow 26.
     */
    val STARTS = intArrayOf(0, 13, 39, 26)

    /**
     * Starred safe squares on the ring (nine steps past each colour start), matching a
     * classic Ludo board. Tokens here cannot be captured.
     */
    val STARS = intArrayOf(9, 22, 35, 48)

    const val COLORS = 4

    override val id = "ludo"
    override val minPlayers = 2
    override val maxPlayers = 4
    override val stateSerializer: KSerializer<LudoState> = LudoState.serializer()
    override val moveSerializer: KSerializer<LudoMove> = LudoMove.serializer()
    override val colorCount = COLORS

    override fun initialState(players: Int, random: Random) =
        initialState(players, LudoOptions(), emptyList(), random)

    override fun initialState(players: Int, colors: List<Int>, random: Random) =
        initialState(players, LudoOptions(), colors, random)

    fun initialState(players: Int, options: LudoOptions, random: Random) =
        initialState(players, options, emptyList(), random)

    fun initialState(players: Int, options: LudoOptions, colors: List<Int>, random: Random): LudoState {
        require(players in minPlayers..maxPlayers) { "players must be $minPlayers..$maxPlayers" }
        return LudoState(
            tokens = List(players) { List(PAWNS) { YARD } },
            turn = 0,
            options = options,
            colors = seatColors(players, colors),
        )
    }

    fun seatColors(players: Int, wanted: List<Int>): List<Int> {
        val taken = ArrayList<Int>()
        for (c in wanted) if (taken.size < players && c in 0 until COLORS && c !in taken) taken += c
        val free = (0 until COLORS).filter { it !in taken }.iterator()
        while (taken.size < players) taken += free.next()
        return taken
    }

    override fun currentTurn(state: LudoState) = state.turn

    override fun validate(state: LudoState, seat: Int, move: LudoMove): String? = when {
        state.finished -> "finished"
        move.pawn == null -> if (state.roll != null) "must_move" else null
        state.roll == null -> "must_roll"
        move.pawn !in 0 until PAWNS -> "bad_pawn"
        move.pawn !in legalPawns(state, seat, state.roll) -> "illegal_pawn"
        else -> null
    }

    override fun apply(state: LudoState, seat: Int, move: LudoMove, random: Random): LudoState =
        when (move.pawn) {
            null -> roll(state, seat, random.nextInt(1, 7)).state
            else -> movePawn(state, seat, move.pawn, state.roll!!).state
        }

    override fun isFinished(state: LudoState) = state.finished

    /** Absolute ring square for [seat]'s token at relative [pos], or null off the ring. */
    fun absolute(state: LudoState, seat: Int, pos: Int): Int? {
        if (pos !in 0..TRACK_LAST) return null
        return (STARTS[state.color(seat)] + pos) % TRACK
    }

    fun isSafeAbsolute(square: Int): Boolean = square in STARTS || square in STARS

    fun isFinishedSeat(state: LudoState, seat: Int): Boolean =
        state.tokens[seat].all { it == FINISH }

    /**
     * Tokens [seat] may move with [roll]. Empty when the roll cannot be used (or was burned).
     */
    fun legalPawns(state: LudoState, seat: Int, roll: Int): List<Int> =
        (0 until PAWNS).filter { destination(state, seat, it, roll) != null }

    /**
     * Where [pawn] lands after [roll], or null when the move is illegal (overshoot, blocked
     * by an opponent stack, or cannot leave the yard).
     */
    fun destination(state: LudoState, seat: Int, pawn: Int, roll: Int): Int? {
        require(roll in 1..6)
        val from = state.tokens[seat][pawn]
        if (from == FINISH) return null
        if (from == YARD) {
            if (state.options.sixToEnter && roll != 6) return null
            val to = if (state.options.sixToEnter) 0 else roll - 1
            return to.takeIf { landingOk(state, seat, pawn, it) }
        }
        val to = from + roll
        if (to > FINISH) return null
        return to.takeIf { landingOk(state, seat, pawn, it) }
    }

    private fun landingOk(state: LudoState, seat: Int, pawn: Int, to: Int): Boolean {
        if (to in HOME_START..FINISH || to == YARD) return true
        val square = absolute(state, seat, to) ?: return true
        // An opponent block of two or more stops landing; a single token may be captured.
        return occupants(state, square)
            .filter { it.seat != seat }
            .groupBy { it.seat }
            .values
            .none { group -> group.size >= 2 }
    }

    data class Occupant(val seat: Int, val pawn: Int)

    /** Tokens on absolute ring [square] (home stretch and yard are ignored). */
    fun occupants(state: LudoState, square: Int): List<Occupant> {
        val out = ArrayList<Occupant>()
        for (seat in state.tokens.indices) {
            for (pawn in 0 until PAWNS) {
                val pos = state.tokens[seat][pawn]
                if (absolute(state, seat, pos) == square) out += Occupant(seat, pawn)
            }
        }
        return out
    }

    /** Applies [roll] for [seat]. Public so tests can pick the face. */
    fun roll(state: LudoState, seat: Int, roll: Int): LudoPlay {
        require(roll in 1..6) { "roll must be 1..6" }
        require(state.roll == null) { "a roll is already pending" }
        val sixes = if (roll == 6) state.sixes + 1 else 0
        if (roll == 6 && sixes >= 3) {
            val next = nextPlaying(state, seat)
            val after = state.copy(
                turn = next,
                roll = null,
                sixes = 0,
                lastRoll = roll,
                lastSeat = seat,
                lastPawn = null,
                lastFrom = null,
                lastTo = null,
                lastCaptures = emptyList(),
                burnedSix = true,
            )
            return LudoPlay(after, seat, roll, null, null, null, emptyList(), emptyList(), false, true)
        }
        val legal = legalPawns(state.copy(sixes = sixes), seat, roll)
        if (legal.isEmpty()) {
            val keep = roll == 6 && state.options.sixRollsAgain
            val after = state.copy(
                turn = if (keep) seat else nextPlaying(state, seat),
                roll = null,
                sixes = if (keep) sixes else 0,
                lastRoll = roll,
                lastSeat = seat,
                lastPawn = null,
                lastFrom = null,
                lastTo = null,
                lastCaptures = emptyList(),
                burnedSix = false,
            )
            return LudoPlay(after, seat, roll, null, null, null, emptyList(), emptyList(), keep, false)
        }
        if (legal.size == 1) {
            return movePawn(
                state.copy(roll = roll, sixes = sixes, burnedSix = false),
                seat,
                legal[0],
                roll,
            )
        }
        val after = state.copy(
            roll = roll,
            sixes = sixes,
            lastRoll = roll,
            lastSeat = seat,
            lastPawn = null,
            lastFrom = null,
            lastTo = null,
            lastCaptures = emptyList(),
            burnedSix = false,
        )
        return LudoPlay(after, seat, roll, null, null, null, emptyList(), emptyList(), false, false)
    }

    /** Moves [pawn] with [roll]. Public so tests can pick both. */
    fun movePawn(state: LudoState, seat: Int, pawn: Int, roll: Int): LudoPlay {
        val to = destination(state, seat, pawn, roll)
            ?: error("illegal pawn $pawn with roll $roll")
        val from = state.tokens[seat][pawn]
        val path = walk(from, to)
        val tokens = state.tokens.map { it.toMutableList() }
        tokens[seat][pawn] = to

        val captures = ArrayList<LudoCapture>()
        if (to in 0..TRACK_LAST) {
            val square = absolute(state, seat, to)!!
            if (!isSafeAbsolute(square)) {
                val victims = occupants(state, square).filter { it.seat != seat }
                // Only a lone token of a seat is captured; blocks are already illegal to land on.
                for ((other, group) in victims.groupBy { it.seat }) {
                    if (group.size == 1) {
                        val hit = group[0]
                        tokens[other][hit.pawn] = YARD
                        captures += LudoCapture(other, hit.pawn)
                    }
                }
            }
        }

        val nextTokens = tokens.map { it.toList() }
        val justFinished = nextTokens[seat].all { it == FINISH } && !isFinishedSeat(state, seat)
        val order = if (justFinished) state.finishOrder + seat else state.finishOrder
        val done = justFinished && (state.options.firstFinishEnds || order.size >= state.tokens.size - 1)
        val extra = !done && (
            (roll == 6 && state.options.sixRollsAgain) ||
                (captures.isNotEmpty() && state.options.captureRollsAgain)
            )
        val after = state.copy(
            tokens = nextTokens,
            turn = when {
                done -> seat
                extra -> seat
                else -> nextPlaying(state.copy(finishOrder = order, tokens = nextTokens), seat)
            },
            roll = null,
            sixes = if (extra && roll == 6) state.sixes else 0,
            finishOrder = order,
            finished = done,
            lastRoll = roll,
            lastSeat = seat,
            lastPawn = pawn,
            lastFrom = from,
            lastTo = to,
            lastCaptures = captures,
            burnedSix = false,
        )
        return LudoPlay(after, seat, roll, pawn, from, to, path, captures, extra, false)
    }

    /** Relative positions walked from [from] to [to], inclusive of [to], exclusive of [from]. */
    fun walk(from: Int, to: Int): List<Int> {
        if (from == YARD) return listOf(to)
        if (to <= from) return listOf(to)
        return ((from + 1)..to).toList()
    }

    private fun nextPlaying(state: LudoState, seat: Int): Int {
        val n = state.tokens.size
        var i = seat
        repeat(n) {
            i = (i + 1) % n
            if (i !in state.finishOrder && !isFinishedSeat(state, i)) return i
        }
        return seat
    }

    /**
     * Final ranking once the game is over: finishers in order, then everyone else by how
     * many tokens are home and how far the rest have walked.
     */
    fun ranking(state: LudoState): List<Int> {
        val rest = state.tokens.indices
            .filter { it !in state.finishOrder }
            .sortedWith(
                compareByDescending<Int> { seat -> state.tokens[seat].count { it == FINISH } }
                    .thenByDescending { seat -> state.tokens[seat].sum() },
            )
        return state.finishOrder + rest
    }
}

/**
 * Ludo with the host's pre-game choices. Give this to `HostSession`; guests read options
 * from the lobby and from the state.
 */
class LudoRules(val options: LudoOptions) :
    GameRules<LudoState, LudoMove> by LudoGame, SeatColorRules<LudoState>, LobbyOptionRules {

    override val colorCount = LudoGame.COLORS

    override val lobbyOptions = Codec.json.encodeToJsonElement(LudoOptions.serializer(), options)

    override fun initialState(players: Int, random: Random) =
        LudoGame.initialState(players, options, emptyList(), random)

    override fun initialState(players: Int, colors: List<Int>, random: Random) =
        LudoGame.initialState(players, options, colors, random)
}

/**
 * Computer player. [Level.EASY] picks any legal intent; the others score entry, captures,
 * progress and finishing, with a shallow look-ahead on [Level.HARD].
 */
class LudoBot(val level: Level = Level.NORMAL) : Bot<LudoState, LudoMove> {

    enum class Level { EASY, NORMAL, HARD }

    override fun chooseMove(state: LudoState, seat: Int, random: Random): LudoMove {
        if (state.roll == null) return LudoMove(null)
        val pawns = LudoGame.legalPawns(state, seat, state.roll)
        require(pawns.isNotEmpty()) { "No legal pawn for seat $seat" }
        if (level == Level.EASY) return LudoMove(pawns.random(random))
        var best = Int.MIN_VALUE
        val chosen = ArrayList<Int>()
        for (pawn in pawns) {
            val next = LudoGame.movePawn(state, seat, pawn, state.roll).state
            val value = if (level == Level.HARD) look(next, seat, 1) else score(next, seat)
            if (value > best) {
                best = value
                chosen.clear()
            }
            if (value == best) chosen += pawn
        }
        return LudoMove(chosen.random(random))
    }

    private fun look(state: LudoState, me: Int, depth: Int): Int {
        if (state.finished || depth == 0) return score(state, me)
        if (state.roll != null) {
            val pawns = LudoGame.legalPawns(state, state.turn, state.roll)
            if (pawns.isEmpty()) return score(state, me)
            val values = pawns.map { look(LudoGame.movePawn(state, state.turn, it, state.roll).state, me, depth - 1) }
            return if (state.turn == me) values.max() else values.min()
        }
        // Pending roll belongs to the host's RNG; evaluate the static position instead.
        return score(state, me)
    }

    private fun score(state: LudoState, me: Int): Int {
        var value = 0
        for (seat in state.tokens.indices) {
            val sign = if (seat == me) 1 else -1
            for (pos in state.tokens[seat]) {
                value += sign * when {
                    pos == LudoGame.FINISH -> 200
                    pos >= LudoGame.HOME_START -> 80 + (pos - LudoGame.HOME_START) * 10
                    pos >= 0 -> 20 + pos
                    else -> 0
                }
            }
            if (seat in state.finishOrder) value += sign * 500
        }
        return value
    }
}
