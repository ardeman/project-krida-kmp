package com.ardeman.krida.core.games

import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.PrivateStateRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * One ship on a seat's ocean. [cells] are board indices `0 until SIZE*SIZE` (row-major,
 * row 0 nearest the camera), sorted ascending. [kind] indexes [FLEET].
 */
@Serializable
data class PerangKapalShip(val kind: Int, val cells: List<Int>) {
    /** First cell (lowest index): the left end across, the near end up the grid. */
    val bow: Int get() = cells.min()

    /** Lies along a row; false when it runs up the grid. */
    val horizontal: Boolean get() = cells.size < 2 || cells.sorted().let { it[1] - it[0] == 1 }
}

/** A shot this seat fired onto the opponent's ocean. */
@Serializable
data class PerangKapalShot(
    val cell: Int,
    val hit: Boolean,
    /** Ship kind sunk by this shot, if any. */
    val sunk: Int? = null,
)

/**
 * Perang kapal (Battleship). Two seats place a classic fleet, then alternate one shot each.
 * The host keeps both fleets; guests receive [viewFor] so opponent ships stay hidden until sunk.
 */
@Serializable
data class PerangKapalState(
    /** True while either seat still places or has not tapped Ready. */
    val placing: Boolean = true,
    val turn: Int = 0,
    val fleets: List<List<PerangKapalShip>> = listOf(emptyList(), emptyList()),
    val ready: List<Boolean> = listOf(false, false),
    /** Shots fired by each seat, in order. */
    val shots: List<List<PerangKapalShot>> = listOf(emptyList(), emptyList()),
    val finished: Boolean = false,
    val winner: Int? = null,
    val lastSeat: Int? = null,
    val lastCell: Int? = null,
    val lastHit: Boolean? = null,
    val lastSunk: Int? = null,
)

/**
 * Intent: place [kind] with bow at [cell] ([horizontal] along the row), set the whole
 * [fleet] at once (bots), mark [ready], or [fire] at a cell. One action per move.
 */
@Serializable
data class PerangKapalMove(
    val kind: Int? = null,
    val cell: Int? = null,
    val horizontal: Boolean = true,
    val ready: Boolean = false,
    val fire: Int? = null,
    val fleet: List<PerangKapalShip>? = null,
) {
    constructor() : this(null, null, true, false, null, null)
}

object PerangKapalGame :
    GameRules<PerangKapalState, PerangKapalMove>,
    PrivateStateRules<PerangKapalState, PerangKapalMove> {

    const val SIZE = 10
    const val CELLS = SIZE * SIZE

    /** Length of each ship kind (Kapal induk … perusak). */
    val FLEET = intArrayOf(5, 4, 3, 3, 2)
    const val KINDS = 5

    override val id = "perang_kapal"
    override val minPlayers = 2
    override val maxPlayers = 2
    override val stateSerializer: KSerializer<PerangKapalState> = PerangKapalState.serializer()
    override val moveSerializer: KSerializer<PerangKapalMove> = PerangKapalMove.serializer()

    override fun initialState(players: Int, random: Random): PerangKapalState {
        require(players == 2) { "perang kapal is 2 players" }
        return PerangKapalState(turn = random.nextInt(2))
    }

    override fun currentTurn(state: PerangKapalState): Int =
        if (state.placing) state.ready.indexOfFirst { !it }.let { if (it < 0) 0 else it }
        else state.turn

    override fun actingSeats(state: PerangKapalState): List<Int> =
        if (state.finished) emptyList()
        else if (state.placing) listOf(0, 1).filter { !state.ready[it] }
        else listOf(state.turn)

    override fun validate(state: PerangKapalState, seat: Int, move: PerangKapalMove): String? = when {
        state.finished -> "finished"
        seat !in 0..1 -> "bad_seat"
        move.ready -> when {
            !state.placing -> "not_placing"
            state.ready[seat] -> "already_ready"
            !fleetComplete(state.fleets[seat]) -> "fleet_incomplete"
            move.fleet != null || move.fire != null || move.kind != null -> "bad_move"
            else -> null
        }
        move.fleet != null -> when {
            !state.placing -> "not_placing"
            state.ready[seat] -> "already_ready"
            move.kind != null -> "bad_move"
            !legalFleet(move.fleet) -> "bad_fleet"
            else -> null
        }
        move.fire != null -> when {
            state.placing -> "still_placing"
            move.kind != null -> "bad_move"
            move.fire !in 0 until CELLS -> "bad_cell"
            state.shots[seat].any { it.cell == move.fire } -> "already_shot"
            else -> null
        }
        move.kind != null && move.cell != null -> when {
            !state.placing -> "not_placing"
            state.ready[seat] -> "already_ready"
            move.kind !in 0 until KINDS -> "bad_kind"
            cellsFor(move.cell, FLEET[move.kind], move.horizontal) == null -> "off_board"
            overlaps(state.fleets[seat], move.kind, cellsFor(move.cell, FLEET[move.kind], move.horizontal)!!) ->
                "overlap"
            else -> null
        }
        else -> "bad_move"
    }

    override fun apply(
        state: PerangKapalState,
        seat: Int,
        move: PerangKapalMove,
        random: Random,
    ): PerangKapalState = when {
        move.ready -> {
            val ready = state.ready.toMutableList().also { it[seat] = true }
            state.copy(
                placing = !ready.all { it },
                ready = ready,
                lastSeat = seat,
                lastCell = null,
                lastHit = null,
                lastSunk = null,
            )
        }
        move.fleet != null -> {
            val fleets = state.fleets.toMutableList().also { it[seat] = move.fleet.map { s -> s.copy(cells = s.cells.sorted()) } }
            state.copy(fleets = fleets, lastSeat = seat, lastCell = null, lastHit = null, lastSunk = null)
        }
        move.fire != null -> fire(state, seat, move.fire)
        else -> place(state, seat, move.kind!!, move.cell!!, move.horizontal)
    }

    override fun isFinished(state: PerangKapalState) = state.finished

    override fun viewFor(state: PerangKapalState, seat: Int): PerangKapalState {
        val fleets = state.fleets.mapIndexed { i, fleet ->
            when {
                i == seat -> fleet
                state.placing -> emptyList()
                else -> fleet.filter { ship -> sunk(state, seat, ship) }
            }
        }
        // While placing, the last cell is the bow of a ship; only its owner may see it.
        val lastCell = state.lastCell.takeIf { !state.placing || state.lastSeat == seat }
        return state.copy(fleets = fleets, lastCell = lastCell)
    }

    override fun viewMove(move: PerangKapalMove, actor: Int, viewer: Int): PerangKapalMove? =
        when {
            move.fire != null || move.ready -> move
            actor == viewer -> move
            else -> null // place / fleet would leak coordinates
        }

    fun legalFleet(fleet: List<PerangKapalShip>): Boolean {
        if (!fleetComplete(fleet)) return false
        val seen = HashSet<Int>()
        for (ship in fleet) {
            if (ship.cells.size != FLEET[ship.kind]) return false
            if (!contiguous(ship.cells)) return false
            for (c in ship.cells) {
                if (c !in 0 until CELLS || c in seen) return false
                seen += c
            }
        }
        return true
    }

    /** Cells form a straight orthogonal run. */
    fun contiguous(cells: List<Int>): Boolean {
        if (cells.isEmpty()) return false
        val sorted = cells.sorted()
        if (sorted.size == 1) return true
        val horizontal = sorted.zipWithNext().all { (a, b) -> b == a + 1 && a / SIZE == b / SIZE }
        val vertical = sorted.zipWithNext().all { (a, b) -> b == a + SIZE }
        return horizontal || vertical
    }

    fun cellsFor(bow: Int, length: Int, horizontal: Boolean): List<Int>? {
        if (bow !in 0 until CELLS || length < 1) return null
        val col = bow % SIZE
        val row = bow / SIZE
        return if (horizontal) {
            if (col + length > SIZE) null
            else (0 until length).map { bow + it }
        } else {
            if (row + length > SIZE) null
            else (0 until length).map { bow + it * SIZE }
        }
    }

    /**
     * Bow cell that puts ship [kind] with its middle on [cell], pushed back onto the board
     * when it would hang over the edge. What a tap or a drop on the ocean places.
     */
    fun bowFor(cell: Int, kind: Int, horizontal: Boolean): Int {
        val length = FLEET[kind]
        val col = cell % SIZE
        val row = cell / SIZE
        return if (horizontal) row * SIZE + (col - length / 2).coerceIn(0, SIZE - length)
        else (row - length / 2).coerceIn(0, SIZE - length) * SIZE + col
    }

    /** Bow cell after turning [ship] a quarter around its middle cell, kept on the board. */
    fun turnedBow(ship: PerangKapalShip): Int =
        bowFor(ship.cells.sorted()[ship.cells.size / 2], ship.kind, !ship.horizontal)

    /**
     * Bow cell for turning [ship] a quarter in [fleet] (what a tap on a placed ship does), or
     * null when it cannot turn anywhere. Around its middle cell first; when that would overlap
     * another ship or hang over the edge, the turned ship may pivot on the [tapped] cell, then
     * on any other of its cells (nearest the middle first), sliding along its new line, so a
     * ship in a crowded corner still turns wherever it fits.
     */
    fun turnedBow(fleet: List<PerangKapalShip>, ship: PerangKapalShip, tapped: Int): Int? {
        val sorted = ship.cells.sorted()
        val length = sorted.size
        val middle = sorted[length / 2]
        val across = !ship.horizontal
        val step = if (across) 1 else SIZE
        val pivots = (listOf(middle, tapped) + sorted.sortedBy { kotlin.math.abs(sorted.indexOf(it) - length / 2) })
            .distinct().filter { it in sorted }
        val shifts = (0 until length).sortedBy { kotlin.math.abs(it - length / 2) }
        for (pivot in pivots) for (shift in shifts) {
            val back = if (across) pivot % SIZE else pivot / SIZE
            if (shift > back) continue
            val bow = pivot - shift * step
            val cells = cellsFor(bow, length, across) ?: continue
            if (!overlaps(fleet, ship.kind, cells)) return bow
        }
        return null
    }

    fun fleetComplete(fleet: List<PerangKapalShip>): Boolean =
        fleet.size == KINDS && fleet.map { it.kind }.toSet() == (0 until KINDS).toSet() &&
            fleet.all { it.cells.size == FLEET[it.kind] }

    fun occupied(fleet: List<PerangKapalShip>): Set<Int> = fleet.flatMap { it.cells }.toSet()

    fun openCells(state: PerangKapalState, seat: Int): List<Int> {
        val shot = state.shots[seat].map { it.cell }.toSet()
        return (0 until CELLS).filter { it !in shot }
    }

    /** Random legal layout for [FLEET]; used by the bot and tests. */
    fun randomFleet(random: Random): List<PerangKapalShip> {
        for (attempt in 0 until 200) {
            val ships = ArrayList<PerangKapalShip>(KINDS)
            var ok = true
            for (kind in 0 until KINDS) {
                val length = FLEET[kind]
                val options = ArrayList<Pair<Int, Boolean>>()
                for (cell in 0 until CELLS) for (horizontal in listOf(true, false)) {
                    val cells = cellsFor(cell, length, horizontal) ?: continue
                    if (cells.any { it in occupied(ships) }) continue
                    options += cell to horizontal
                }
                if (options.isEmpty()) {
                    ok = false
                    break
                }
                val (bow, horizontal) = options.random(random)
                ships += PerangKapalShip(kind, cellsFor(bow, length, horizontal)!!)
            }
            if (ok && fleetComplete(ships)) return ships
        }
        error("Could not place a random fleet")
    }

    /**
     * What the phone sets for [seat] when the placement timer
     * ([com.ardeman.krida.core.protocol.Krida.PLACE_FLEET_MS]) runs out, before it taps Ready:
     * the ships already on the ocean stay, the rest are placed at random as the easy computer
     * would. If they cannot all fit around the placed ones, a whole random fleet. Null when
     * [seat] has nothing left to place (already ready, battle started or game over).
     */
    fun timeoutFleet(state: PerangKapalState, seat: Int, random: Random): List<PerangKapalShip>? {
        if (state.finished || !state.placing || state.ready.getOrElse(seat) { true }) return null
        val placed = state.fleets[seat]
        if (fleetComplete(placed)) return placed
        for (attempt in 0 until 50) {
            val ships = placed.toMutableList()
            var ok = true
            for (kind in 0 until KINDS) {
                if (ships.any { it.kind == kind }) continue
                val taken = occupied(ships)
                val options = ArrayList<List<Int>>()
                for (cell in 0 until CELLS) for (horizontal in listOf(true, false)) {
                    val cells = cellsFor(cell, FLEET[kind], horizontal) ?: continue
                    if (cells.none { it in taken }) options += cells
                }
                if (options.isEmpty()) {
                    ok = false
                    break
                }
                ships += PerangKapalShip(kind, options.random(random))
            }
            if (ok && legalFleet(ships)) return ships
        }
        return randomFleet(random)
    }

    /**
     * What the phone fires for [seat] when its turn timer
     * ([com.ardeman.krida.core.protocol.Krida.PLAY_TURN_MS]) runs out: the easy computer's shot
     * ([PerangKapalBot.Level.EASY]), drawn from [random]. It only reads [seat]'s own shots, so a
     * guest's view is enough; the phone sends it like a tap. Null when [seat] may not fire now.
     */
    fun timeoutMove(state: PerangKapalState, seat: Int, random: Random): PerangKapalMove? {
        if (state.finished || state.placing || seat != state.turn) return null
        if (openCells(state, seat).isEmpty()) return null
        return PerangKapalBot(PerangKapalBot.Level.EASY).chooseMove(state, seat, random)
    }

    private fun place(
        state: PerangKapalState,
        seat: Int,
        kind: Int,
        bow: Int,
        horizontal: Boolean,
    ): PerangKapalState {
        val cells = cellsFor(bow, FLEET[kind], horizontal)!!
        val fleet = state.fleets[seat].filter { it.kind != kind } + PerangKapalShip(kind, cells)
        val fleets = state.fleets.toMutableList().also { it[seat] = fleet }
        return state.copy(
            fleets = fleets,
            lastSeat = seat,
            lastCell = bow,
            lastHit = null,
            lastSunk = null,
        )
    }

    private fun fire(state: PerangKapalState, seat: Int, cell: Int): PerangKapalState {
        val foe = 1 - seat
        val hit = cell in occupied(state.fleets[foe])
        val ship = if (hit) state.fleets[foe].first { cell in it.cells } else null
        val prior = state.shots[seat]
        val sunkKind = if (ship != null && ship.cells.all { c -> c == cell || prior.any { it.cell == c && it.hit } }) {
            ship.kind
        } else null
        val shot = PerangKapalShot(cell, hit, sunkKind)
        val shots = state.shots.toMutableList().also { it[seat] = prior + shot }
        val next = state.copy(
            shots = shots,
            turn = foe,
            lastSeat = seat,
            lastCell = cell,
            lastHit = hit,
            lastSunk = sunkKind,
        )
        val allSunk = state.fleets[foe].all { sunk(next, seat, it) }
        return if (allSunk) next.copy(finished = true, winner = seat, turn = seat) else next
    }

    private fun sunk(state: PerangKapalState, attacker: Int, ship: PerangKapalShip): Boolean =
        ship.cells.all { c -> state.shots[attacker].any { it.cell == c && it.hit } }

    private fun overlaps(fleet: List<PerangKapalShip>, kind: Int, cells: List<Int>): Boolean {
        val others = occupied(fleet.filter { it.kind != kind })
        return cells.any { it in others }
    }
}

/**
 * Computer player. During placement it lays a random legal fleet then readies. In battle it
 * uses hunt / target; [Level] only changes how often it picks a pure-random open cell.
 */
class PerangKapalBot(val level: Level = Level.NORMAL) : Bot<PerangKapalState, PerangKapalMove> {

    enum class Level { EASY, NORMAL, HARD }

    /** Swift-friendly. */
    constructor() : this(Level.NORMAL)

    override fun chooseMove(state: PerangKapalState, seat: Int, random: Random): PerangKapalMove {
        if (state.placing) {
            if (state.ready[seat]) error("Bot $seat already ready")
            if (!PerangKapalGame.fleetComplete(state.fleets[seat])) {
                return PerangKapalMove(fleet = PerangKapalGame.randomFleet(random))
            }
            return PerangKapalMove(ready = true)
        }
        val open = PerangKapalGame.openCells(state, seat)
        require(open.isNotEmpty()) { "No open cells for seat $seat" }
        val target = targetCell(state, seat, open, random)
        return PerangKapalMove(fire = target)
    }

    private fun targetCell(
        state: PerangKapalState,
        seat: Int,
        open: List<Int>,
        random: Random,
    ): Int {
        val wander = when (level) {
            Level.EASY -> 0.55
            Level.NORMAL -> 0.20
            Level.HARD -> 0.05
        }
        if (random.nextDouble() < wander) return open.random(random)
        val hits = state.shots[seat].filter { it.hit && it.sunk == null }.map { it.cell }
        val candidates = LinkedHashSet<Int>()
        for (h in hits) {
            for (n in neighbors(h)) if (n in open) candidates += n
        }
        if (candidates.isNotEmpty()) return candidates.random(random)
        // Parity hunt on hard: only every other cell until a hit.
        if (level == Level.HARD) {
            val parity = open.filter { (it % PerangKapalGame.SIZE + it / PerangKapalGame.SIZE) % 2 == 0 }
            if (parity.isNotEmpty()) return parity.random(random)
        }
        return open.random(random)
    }

    private fun neighbors(cell: Int): List<Int> {
        val col = cell % PerangKapalGame.SIZE
        val row = cell / PerangKapalGame.SIZE
        val out = ArrayList<Int>(4)
        if (col > 0) out += cell - 1
        if (col < PerangKapalGame.SIZE - 1) out += cell + 1
        if (row > 0) out += cell - PerangKapalGame.SIZE
        if (row < PerangKapalGame.SIZE - 1) out += cell + PerangKapalGame.SIZE
        return out
    }
}
