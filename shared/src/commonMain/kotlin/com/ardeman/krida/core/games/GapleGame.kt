package com.ardeman.krida.core.games

import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.LobbyOptionRules
import com.ardeman.krida.core.session.PrivateStateRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Host options picked before the game. */
@Serializable
data class GapleOptions(
    /** With 4 seats, seats across (0 + 2, 1 + 3) play as a team. Ignored with 2–3 seats. */
    val partners: Boolean = false,
) {
    /** For Swift, which does not see Kotlin default arguments. */
    constructor() : this(false)
}

/** A double-six tile, stored with [a] ≤ [b]. */
@Serializable
data class GapleTile(val a: Int, val b: Int) {
    val pips: Int get() = a + b
    val isDouble: Boolean get() = a == b

    fun fits(end: Int): Boolean = a == end || b == end
}

/** A tile in the line of play, turned so [left] faces the left end and [right] the right. */
@Serializable
data class GaplePlaced(val left: Int, val right: Int) {
    val isDouble: Boolean get() = left == right
}

/**
 * Gaple (Indonesian block dominoes, double-six). The host deals and keeps every hand; each
 * seat receives [GapleGame.viewFor], which hides the other hands until the round ends.
 */
@Serializable
data class GapleState(
    val players: Int,
    /** Each seat's hand. In a seat's view, other hands are empty until [roundOver]. */
    val hands: List<List<GapleTile>>,
    /** Tiles left in each hand; always public. */
    val handCounts: List<Int>,
    /** Tiles set aside face down with 2–3 seats; empty in every view. */
    val aside: List<GapleTile> = emptyList(),
    /** The line of play, left end first. */
    val line: List<GaplePlaced> = emptyList(),
    /** Index in [line] of the round's first tile (the layout grows both ways from it). */
    val origin: Int = 0,
    val turn: Int = 0,
    /** The tile the opener must play (round 1: [6|6] or the highest double); null after. */
    val mustPlay: GapleTile? = null,
    val round: Int = 1,
    /** Match totals per seat. With partners both teammates hold the same team total. */
    val scores: List<Int>,
    val partners: Boolean = false,
    /** End values each seat has passed on this round (public; the hard bot reads it). */
    val passedOn: List<List<Int>>,
    val roundOver: Boolean = false,
    /** "domino" (a hand emptied) or "gaple" (nobody can play); null while the round runs. */
    val roundEnd: String? = null,
    /** Seat that won the round (went out, or lowest pips). */
    val roundWinner: Int? = null,
    /** Pips left in each hand at the round end. */
    val roundPips: List<Int> = emptyList(),
    /** Points each seat gained this round. */
    val roundPoints: List<Int> = emptyList(),
    val finished: Boolean = false,
    /** Winning seat (with partners, either teammate's seat: the round winner's). */
    val winner: Int? = null,
    val lastSeat: Int? = null,
    val lastTile: GaplePlaced? = null,
    /** 0 = left end, 1 = right end; null for the first tile of a round. */
    val lastEnd: Int? = null,
    /** Seats skipped after the last play because no tile fitted, in turn order. */
    val lastPasses: List<Int> = emptyList(),
    val options: GapleOptions = GapleOptions(),
) {
    val leftEnd: Int? get() = line.firstOrNull()?.left
    val rightEnd: Int? get() = line.lastOrNull()?.right

    /** Team of [seat]: 0 or 1 with partners, otherwise the seat itself. */
    fun team(seat: Int): Int = if (partners) seat % 2 else seat
}

/**
 * Intent: play [tile] on [end] (0 left, 1 right; needed only when it fits both ends of a
 * line), or [next] to deal the next round (host seat 0, after a round ends). There is no
 * pass move: the host skips a seat that has no fitting tile.
 */
@Serializable
data class GapleMove(
    val tile: GapleTile? = null,
    val end: Int? = null,
    val next: Boolean = false,
) {
    constructor() : this(null, null, false)
}

object GapleGame : GameRules<GapleState, GapleMove>, PrivateStateRules<GapleState, GapleMove> {
    const val HAND = 7
    const val TARGET = 100
    const val LEFT = 0
    const val RIGHT = 1

    /** All 28 tiles of a double-six set. */
    val SET: List<GapleTile> = (0..6).flatMap { a -> (a..6).map { b -> GapleTile(a, b) } }

    override val id = "gaple"
    override val minPlayers = 2
    override val maxPlayers = 4
    override val stateSerializer: KSerializer<GapleState> = GapleState.serializer()
    override val moveSerializer: KSerializer<GapleMove> = GapleMove.serializer()

    override fun initialState(players: Int, random: Random): GapleState =
        initialState(players, GapleOptions(), random)

    fun initialState(players: Int, options: GapleOptions, random: Random): GapleState {
        require(players in minPlayers..maxPlayers) { "gaple is 2–4 players" }
        val partners = options.partners && players == 4
        val base = GapleState(
            players = players,
            hands = List(players) { emptyList() },
            handCounts = List(players) { 0 },
            scores = List(players) { 0 },
            partners = partners,
            passedOn = List(players) { emptyList() },
            options = options,
        )
        return deal(base, round = 1, leader = null, random)
    }

    override fun currentTurn(state: GapleState): Int = if (state.roundOver) 0 else state.turn

    override fun actingSeats(state: GapleState): List<Int> =
        if (state.finished) emptyList() else listOf(currentTurn(state))

    override fun validate(state: GapleState, seat: Int, move: GapleMove): String? {
        if (state.finished) return "finished"
        if (seat !in 0 until state.players) return "bad_seat"
        if (move.next) {
            return when {
                !state.roundOver -> "round_on"
                seat != 0 -> "not_host"
                move.tile != null -> "bad_move"
                else -> null
            }
        }
        if (state.roundOver) return "round_over"
        if (seat != state.turn) return "not_your_turn"
        val tile = move.tile ?: return "bad_move"
        if (tile !in state.hands[seat]) return "not_in_hand"
        state.mustPlay?.let { if (tile != it) return "must_play" }
        if (state.line.isEmpty()) return null
        val ends = endsFor(state, tile)
        return when {
            ends.isEmpty() -> "no_fit"
            move.end == null -> if (ends.size == 1) null else "pick_end"
            move.end !in ends -> "bad_end"
            else -> null
        }
    }

    override fun apply(state: GapleState, seat: Int, move: GapleMove, random: Random): GapleState =
        if (move.next) deal(state, state.round + 1, state.roundWinner, random)
        else play(state, seat, move.tile!!, move.end)

    override fun isFinished(state: GapleState) = state.finished

    override fun viewFor(state: GapleState, seat: Int): GapleState =
        state.copy(
            hands = if (state.roundOver) state.hands
            else state.hands.mapIndexed { i, hand -> if (i == seat) hand else emptyList() },
            aside = emptyList(),
        )

    /** Every play is public once made. */
    override fun viewMove(move: GapleMove, actor: Int, viewer: Int): GapleMove = move

    /** Ends (LEFT / RIGHT) [tile] can go on; both for the first tile of a round. */
    fun endsFor(state: GapleState, tile: GapleTile): List<Int> {
        val left = state.leftEnd ?: return listOf(LEFT, RIGHT)
        val right = state.rightEnd!!
        return buildList {
            if (tile.fits(left)) add(LEFT)
            if (tile.fits(right)) add(RIGHT)
        }
    }

    /** Tiles in [seat]'s hand that may be played now (only the opener's tile on a first move). */
    fun playable(state: GapleState, seat: Int): List<GapleTile> {
        val hand = state.hands.getOrNull(seat) ?: return emptyList()
        state.mustPlay?.let { must -> return if (must in hand) listOf(must) else emptyList() }
        if (state.line.isEmpty()) return hand
        return hand.filter { endsFor(state, it).isNotEmpty() }
    }

    fun pips(hand: List<GapleTile>): Int = hand.sumOf { it.pips }

    /**
     * What the phone plays for [seat] when its turn timer ([com.ardeman.krida.core.protocol.Krida.PLAY_TURN_MS])
     * runs out: what the easy computer ([GapleBot.Level.EASY]) would play, any fitting tile on
     * any end it fits, drawn from [random]; at a round end, the next deal. The phone of the
     * player who timed out picks it and sends it like a tap. Null when [seat] has nothing to do now.
     */
    fun timeoutMove(state: GapleState, seat: Int, random: Random): GapleMove? {
        if (state.finished) return null
        if (state.roundOver) return if (seat == 0) GapleMove(next = true) else null
        if (seat != state.turn || playable(state, seat).isEmpty()) return null
        return GapleBot(GapleBot.Level.EASY).chooseMove(state, seat, random)
    }

    /** Team (or seat) totals for display: one entry per team with partners, else per seat. */
    fun totals(state: GapleState): List<Int> =
        if (state.partners) listOf(state.scores[0], state.scores[1]) else state.scores

    private fun deal(state: GapleState, round: Int, leader: Int?, random: Random): GapleState {
        val n = state.players
        val shuffled = SET.shuffled(random)
        val hands = List(n) { i -> shuffled.subList(i * HAND, (i + 1) * HAND).sortedWith(TILE_ORDER) }
        val aside = shuffled.drop(n * HAND)
        val (turn, mustPlay) = if (leader != null) leader to null else opener(hands)
        return state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            aside = aside,
            line = emptyList(),
            origin = 0,
            turn = turn,
            mustPlay = mustPlay,
            round = round,
            passedOn = List(n) { emptyList() },
            roundOver = false,
            roundEnd = null,
            roundWinner = null,
            roundPips = emptyList(),
            roundPoints = emptyList(),
            lastSeat = null,
            lastTile = null,
            lastEnd = null,
            lastPasses = emptyList(),
        )
    }

    /**
     * First round's opener: [6|6], else the highest double, else (no doubles dealt, only
     * possible with 2–3 seats) the heaviest tile.
     */
    private fun opener(hands: List<List<GapleTile>>): Pair<Int, GapleTile> {
        val all = hands.flatMapIndexed { seat, hand -> hand.map { seat to it } }
        val doubles = all.filter { it.second.isDouble }
        val pick = doubles.maxByOrNull { it.second.a }
            ?: all.maxWith(compareBy({ it.second.pips }, { it.second.b }))
        return pick
    }

    private fun play(state: GapleState, seat: Int, tile: GapleTile, end: Int?): GapleState {
        val hands = state.hands.toMutableList().also { it[seat] = it[seat] - tile }
        val first = state.line.isEmpty()
        val side = if (first) null else end ?: endsFor(state, tile).single()
        val placed: GaplePlaced
        val line: List<GaplePlaced>
        var origin = state.origin
        when (side) {
            null -> {
                placed = GaplePlaced(tile.a, tile.b)
                line = listOf(placed)
            }
            LEFT -> {
                val v = state.leftEnd!!
                placed = if (tile.b == v) GaplePlaced(tile.a, tile.b) else GaplePlaced(tile.b, tile.a)
                line = listOf(placed) + state.line
                origin += 1
            }
            else -> {
                val v = state.rightEnd!!
                placed = if (tile.a == v) GaplePlaced(tile.a, tile.b) else GaplePlaced(tile.b, tile.a)
                line = state.line + placed
            }
        }
        var next = state.copy(
            hands = hands,
            handCounts = hands.map { it.size },
            line = line,
            origin = origin,
            mustPlay = null,
            lastSeat = seat,
            lastTile = placed,
            lastEnd = side,
            lastPasses = emptyList(),
        )
        if (hands[seat].isEmpty()) return endRound(next, "domino", seat)
        if ((0 until state.players).none { playable(next, it).isNotEmpty() }) {
            return endRound(next, "gaple", lowestSeat(next, seat))
        }
        // Skip seats with no fitting tile; someone can play, so this stops.
        var turn = (seat + 1) % state.players
        val passes = ArrayList<Int>()
        val passedOn = next.passedOn.toMutableList()
        while (playable(next, turn).isEmpty()) {
            passes += turn
            passedOn[turn] = (passedOn[turn] + listOf(next.leftEnd!!, next.rightEnd!!)).distinct()
            turn = (turn + 1) % state.players
        }
        next = next.copy(turn = turn, lastPasses = passes, passedOn = passedOn)
        return next
    }

    /** Lowest pips in hand; ties go to the first seat after [lastPlacer], clockwise. */
    private fun lowestSeat(state: GapleState, lastPlacer: Int): Int {
        val n = state.players
        val order = (1..n).map { (lastPlacer + it) % n }
        return order.minBy { pips(state.hands[it]) } // minBy keeps the first of equals
    }

    private fun endRound(state: GapleState, how: String, winner: Int): GapleState {
        val n = state.players
        val pipsLeft = state.hands.map { pips(it) }
        val gained = (0 until n).filter { state.team(it) != state.team(winner) }.sumOf { pipsLeft[it] }
        val points = List(n) { if (state.team(it) == state.team(winner)) gained else 0 }
        val scores = state.scores.mapIndexed { i, s -> s + points[i] }
        val done = scores.any { it >= TARGET }
        val matchWinner = if (done) {
            val best = scores.max()
            if (scores[winner] == best) winner else scores.indexOf(best)
        } else null
        return state.copy(
            scores = scores,
            roundOver = true,
            roundEnd = how,
            roundWinner = winner,
            roundPips = pipsLeft,
            roundPoints = points,
            finished = done,
            winner = matchWinner,
            lastPasses = emptyList(),
        )
    }

    private val TILE_ORDER = compareBy<GapleTile>({ it.a }, { it.b })
}

/** [GapleGame] with the host's [options]; what a host session is built with. */
class GapleRules(val options: GapleOptions) :
    GameRules<GapleState, GapleMove> by GapleGame,
    PrivateStateRules<GapleState, GapleMove> by GapleGame,
    LobbyOptionRules {

    override val lobbyOptions = Codec.json.encodeToJsonElement(GapleOptions.serializer(), options)

    override fun initialState(players: Int, random: Random) =
        GapleGame.initialState(players, options, random)
}

/**
 * Computer player. [Level.EASY] plays a random fitting tile; [Level.NORMAL] the heaviest;
 * [Level.HARD] also keeps ends it can follow and blocks ends the next seat passed on.
 * Deals the next round when it is somehow seat 0 at a round end.
 */
class GapleBot(val level: Level = Level.NORMAL) : Bot<GapleState, GapleMove> {

    enum class Level { EASY, NORMAL, HARD }

    /** Swift-friendly. */
    constructor() : this(Level.NORMAL)

    override fun chooseMove(state: GapleState, seat: Int, random: Random): GapleMove {
        if (state.roundOver) return GapleMove(next = true)
        val options = GapleGame.playable(state, seat).flatMap { tile ->
            if (state.line.isEmpty()) listOf(GapleMove(tile, null))
            else GapleGame.endsFor(state, tile).map { GapleMove(tile, it) }
        }
        require(options.isNotEmpty()) { "No fitting tile for seat $seat" }
        if (level == Level.EASY) return options.random(random)
        var best = Int.MIN_VALUE
        val chosen = ArrayList<GapleMove>()
        for (option in options) {
            val value = score(state, seat, option)
            if (value > best) {
                best = value
                chosen.clear()
            }
            if (value == best) chosen += option
        }
        return chosen.random(random)
    }

    private fun score(state: GapleState, seat: Int, move: GapleMove): Int {
        val tile = move.tile!!
        if (level == Level.NORMAL) return tile.pips
        val rest = state.hands[seat] - tile
        if (rest.isEmpty()) return 1_000
        // Only this seat's hand and public information: the ends after this play.
        val (left, right) = when {
            state.line.isEmpty() -> tile.a to tile.b
            move.end == GapleGame.LEFT -> other(tile, state.leftEnd!!) to state.rightEnd!!
            else -> state.leftEnd!! to other(tile, state.rightEnd!!)
        }
        val follow = rest.count { it.fits(left) || it.fits(right) }
        val nextSeat = (seat + 1) % state.players
        val blocked = if (state.partners && state.team(nextSeat) == state.team(seat)) 0
        else listOf(left, right).count { it in state.passedOn[nextSeat] }
        return follow * 10 + blocked * 15 + tile.pips
    }

    /** The pip [tile] leaves open after its [matched] half joins the line. */
    private fun other(tile: GapleTile, matched: Int): Int = if (tile.a == matched) tile.b else tile.a
}
