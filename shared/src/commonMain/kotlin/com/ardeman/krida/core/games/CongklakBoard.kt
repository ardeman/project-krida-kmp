package com.ardeman.krida.core.games

import com.ardeman.krida.core.voxel.VoxelMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The congklak board as both platforms draw it, in world units (y up). Seat 0 sits on the
 * near side (+z), seat 1 across. As in traditional congklak, sowing runs clockwise seen from
 * above and each player's store is on their left:
 *
 * ```
 *         8  9 10 11 12 13 14          (seat 1)
 *    7                         15
 *         6  5  4  3  2  1  0          (seat 0)
 * ```
 *
 * Pure geometry, so the Android (Filament) and iOS (RealityKit) boards are identical.
 */
object CongklakBoard {
    const val SEED_COUNT = CongklakGame.HOLES * CongklakGame.SEEDS * 2
    const val SEED_VARIANTS = 3

    /** Camera, shared so both platforms frame the board the same way. */
    val cameraEye = floatArrayOf(0f, 21f, 17f)
    val cameraTarget = floatArrayOf(0f, 0f, 0.6f)
    const val CAMERA_HORIZONTAL_FOV_DEGREES = 62f


    private const val CELL = 0.5f
    private const val HALF_X = 15f
    private const val HALF_Z = 5f
    private const val BOARD_LAYERS = 3
    private const val PIT_LAYERS = 1
    private const val HOLE_RADIUS = 1.05f
    private const val STORE_RX = 1.6f
    private const val STORE_RZ = 3.8f
    private const val SEED_SIZE = 0.36f

    /** Store rows fill from the middle outward, so a few seeds sit in the center. */
    private val STORE_ROWS = intArrayOf(3, 4, 2, 5, 1, 6, 0, 7)

    private val SEED_COLORS = arrayOf(
        floatArrayOf(0.95f, 0.90f, 0.78f),
        floatArrayOf(0.86f, 0.80f, 0.68f),
        floatArrayOf(0.82f, 0.62f, 0.40f),
    )
    private val WOOD = floatArrayOf(0.64f, 0.42f, 0.24f)
    private val WOOD_DARK = floatArrayOf(0.44f, 0.27f, 0.15f)
    private val GOLD = floatArrayOf(0.95f, 0.72f, 0.29f)
    private const val RING_HEIGHT = 0.18f

    val boardTop: Float = BOARD_LAYERS * CELL
    private val pitFloor = PIT_LAYERS * CELL

    private val centers: Array<FloatArray> = Array(CongklakGame.PITS) { pit ->
        val seat = CongklakGame.ownerOf(pit)
        val hole = pit % (CongklakGame.HOLES + 1)
        when {
            CongklakGame.isStore(pit) -> floatArrayOf(if (seat == 0) -12.5f else 12.5f, 0f)
            seat == 0 -> floatArrayOf((3 - hole) * 3f, 2f)
            else -> floatArrayOf((hole - 3) * 3f, -2f)
        }
    }

    /** Point on the board surface at the middle of [pit], for tap targets and labels: x, y, z. */
    fun pitAnchor(pit: Int): FloatArray = floatArrayOf(centers[pit][0], boardTop - 0.5f, centers[pit][1])

    fun seedVariant(seed: Int) = seed % SEED_VARIANTS

    /** Stable yaw per seed, in radians. */
    fun yaw(seed: Int): Float = jitter(seed * 7 + 3) * 0.6f

    /** Resting position of the [k]-th seed in [pit], with a small stable jitter: x, y, z. */
    fun slot(pit: Int, k: Int): FloatArray {
        val cx = centers[pit][0]
        val cz = centers[pit][1]
        val j = jitter(pit * 131 + k)
        return if (CongklakGame.isStore(pit)) {
            val layer = k / 24
            val i = k % 24
            floatArrayOf(
                cx + (i % 3 - 1) * 0.5f + j * 0.08f,
                pitFloor + SEED_SIZE / 2 + layer * (SEED_SIZE - 0.02f),
                cz + (STORE_ROWS[i / 3] - 3.5f) * 0.5f - j * 0.06f,
            )
        } else {
            val layer = k / 7
            val i = k % 7
            val r = if (i == 0) 0f else 0.48f
            val a = (i * 60 + layer * 30) * PI.toFloat() / 180f
            floatArrayOf(
                cx + r * cos(a) + j * 0.06f,
                pitFloor + SEED_SIZE / 2 + layer * (SEED_SIZE - 0.02f),
                cz + r * sin(a) - j * 0.05f,
            )
        }
    }

    /** Where the [k]-th seed in the sowing hand floats, above [pit]: x, y, z. */
    fun handSlot(pit: Int, k: Int): FloatArray {
        val a = k * 2.4f
        val r = 0.25f + (k % 5) * 0.08f
        return floatArrayOf(centers[pit][0] + r * cos(a), boardTop + 1.6f + (k / 5) * 0.3f, centers[pit][1] + r * sin(a))
    }

    /** Where to place [ringMesh] to circle [pit]: x, y, z. */
    fun ringOrigin(pit: Int): FloatArray = floatArrayOf(centers[pit][0], boardTop, centers[pit][1])

    /**
     * A gold voxel ring around a hole, centered on the origin and sitting on y = 0. Marks the
     * holes the player may start from; place it with [ringOrigin]. It is a half-cell outline
     * of the hole's voxel edge (hole centers sit on cell corners), thin enough that rings on
     * neighboring holes stay apart.
     */
    fun ringMesh(): VoxelMesh {
        val half = CELL / 2
        // A half cell belongs to the hole when the board cell containing it does.
        fun inHole(i: Int, k: Int): Boolean {
            val cx = (floorDiv(i, 2) + 0.5f) * CELL
            val cz = (floorDiv(k, 2) + 0.5f) * CELL
            return cx * cx + cz * cz <= HOLE_RADIUS * HOLE_RADIUS
        }
        val mesh = VoxelMesh()
        val reach = 6
        for (i in -reach until reach) for (k in -reach until reach) {
            if (inHole(i, k)) continue
            val edge = (-1..1).any { di -> (-1..1).any { dk -> inHole(i + di, k + dk) } }
            if (!edge) continue
            val x = i * half
            val z = k * half
            mesh.box(floatArrayOf(x, 0f, z), floatArrayOf(x + half, RING_HEIGHT, z + half), GOLD)
        }
        return mesh
    }

    private fun floorDiv(a: Int, b: Int): Int = if (a >= 0) a / b else -((-a + b - 1) / b)

    fun seedMesh(variant: Int): VoxelMesh {
        val h = SEED_SIZE / 2
        return VoxelMesh().apply { box(floatArrayOf(-h, -h, -h), floatArrayOf(h, h, h), SEED_COLORS[variant], bottom = true) }
    }

    fun boardMesh(): VoxelMesh {
        val nx = (2 * HALF_X / CELL).toInt()
        val nz = (2 * HALF_Z / CELL).toInt()
        val height = Array(nx) { IntArray(nz) }
        val pit = Array(nx) { BooleanArray(nz) }

        for (i in 0 until nx) for (k in 0 until nz) {
            val x = -HALF_X + (i + 0.5f) * CELL
            val z = -HALF_Z + (k + 0.5f) * CELL
            if (!insideBoat(x, z)) continue
            val inPit = centers.indices.any { p -> inPit(p, x, z) }
            height[i][k] = if (inPit) PIT_LAYERS else BOARD_LAYERS
            pit[i][k] = inPit
        }

        val mesh = VoxelMesh()
        fun h(i: Int, k: Int) = if (i in 0 until nx && k in 0 until nz) height[i][k] else 0
        fun isPit(i: Int, k: Int) = i in 0 until nx && k in 0 until nz && pit[i][k]
        val p = VoxelMesh::p

        for (i in 0 until nx) for (k in 0 until nz) {
            val hi = height[i][k]
            if (hi == 0) continue
            val x0 = -HALF_X + i * CELL
            val z0 = -HALF_Z + k * CELL
            val x1 = x0 + CELL
            val z1 = z0 + CELL
            val top = hi * CELL
            val base = if (pit[i][k]) WOOD_DARK else WOOD
            // Quantized so the iOS palette texture stays small.
            val n = 1f + (jitter(i * 977 + k) * 3).toInt() * 0.02f
            val col = floatArrayOf(base[0] * n, base[1] * n, base[2] * n)
            mesh.quad(arrayOf(p(x0, top, z0), p(x0, top, z1), p(x1, top, z1), p(x1, top, z0)), col)

            // Side walls down to each lower neighbor. Walls into a pit use the dark wood.
            fun wall(nh: Int, nPit: Boolean, shade: Float, corners: (Float) -> Array<FloatArray>) {
                if (nh >= hi) return
                val c = if (nPit) WOOD_DARK else base
                mesh.quad(corners(nh * CELL), floatArrayOf(c[0] * shade, c[1] * shade, c[2] * shade))
            }
            wall(h(i + 1, k), isPit(i + 1, k), 0.78f) { y -> arrayOf(p(x1, y, z0), p(x1, top, z0), p(x1, top, z1), p(x1, y, z1)) }
            wall(h(i - 1, k), isPit(i - 1, k), 0.78f) { y -> arrayOf(p(x0, y, z1), p(x0, top, z1), p(x0, top, z0), p(x0, y, z0)) }
            wall(h(i, k + 1), isPit(i, k + 1), 0.88f) { y -> arrayOf(p(x0, y, z1), p(x1, y, z1), p(x1, top, z1), p(x0, top, z1)) }
            wall(h(i, k - 1), isPit(i, k - 1), 0.70f) { y -> arrayOf(p(x1, y, z0), p(x0, y, z0), p(x0, top, z0), p(x1, top, z0)) }
        }
        return mesh
    }

    /** Boat outline: a long board with rounded ends. */
    private fun insideBoat(x: Float, z: Float): Boolean {
        val straight = HALF_X - 4f
        val ax = abs(x)
        if (ax <= straight) return abs(z) <= HALF_Z
        val dx = (ax - straight) / 4f
        val dz = z / HALF_Z
        return dx * dx + dz * dz <= 1f
    }

    private fun inPit(p: Int, x: Float, z: Float): Boolean {
        val dx = x - centers[p][0]
        val dz = z - centers[p][1]
        return if (CongklakGame.isStore(p)) {
            val ex = dx / STORE_RX
            val ez = dz / STORE_RZ
            ex * ex + ez * ez <= 1f
        } else {
            dx * dx + dz * dz <= HOLE_RADIUS * HOLE_RADIUS
        }
    }

    /** Deterministic value in -1..1. */
    private fun jitter(n: Int): Float {
        var h = n * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 32767.5f - 1f
    }
}

/** One animation step of a move: lift every seed out of [pit], or drop one seed into it. */
data class SowStep(val pickUp: Boolean, val pit: Int)

/**
 * Which seed sits where, so seeds keep their identity while they move. Drive it with
 * [steps], then [settle] to the rules' final board (captures, end-of-game sweep).
 */
class CongklakSeeds(pits: List<Int>) {
    private val piles = Array(CongklakGame.PITS) { ArrayList<Int>() }
    private val hand = ArrayList<Int>()
    private var handPit = 0

    init { reset(pits) }

    fun reset(pits: List<Int>) {
        piles.forEach { it.clear() }
        hand.clear()
        var next = 0
        pits.forEachIndexed { pit, n -> repeat(n) { piles[pit] += next++ } }
    }

    fun counts(): List<Int> = piles.map { it.size }

    /** Seeds in the sowing hand right now. */
    fun handCount(): Int = hand.size

    fun apply(step: SowStep) {
        handPit = step.pit
        if (step.pickUp) {
            hand += piles[step.pit]
            piles[step.pit].clear()
        } else {
            piles[step.pit] += hand.removeAt(hand.lastIndex)
        }
    }

    /** Moves surplus seeds (and anything left in hand) to pits that are short. */
    fun settle(pits: List<Int>) {
        val loose = ArrayList(hand).also { hand.clear() }
        piles.forEachIndexed { pit, pile -> while (pile.size > pits[pit]) loose += pile.removeAt(pile.lastIndex) }
        piles.forEachIndexed { pit, pile -> while (pile.size < pits[pit] && loose.isNotEmpty()) pile += loose.removeAt(loose.lastIndex) }
    }

    /** Target position of every seed, flattened: x, y, z for seed 0, then seed 1, … */
    fun targets(): FloatArray {
        val out = FloatArray(CongklakBoard.SEED_COUNT * 3)
        fun put(seed: Int, xyz: FloatArray) = xyz.copyInto(out, seed * 3)
        piles.forEachIndexed { pit, pile -> pile.forEachIndexed { k, seed -> put(seed, CongklakBoard.slot(pit, k)) } }
        hand.forEachIndexed { k, seed -> put(seed, CongklakBoard.handSlot(handPit, k)) }
        return out
    }

    companion object {
        /**
         * The pick-ups and drops that make up a move from [startPit] along [path], replayed
         * on the board [before] the move. A relay pick-up happens whenever the hand runs out.
         */
        fun steps(before: List<Int>, startPit: Int, path: List<Int>): List<SowStep> {
            val counts = before.toMutableList()
            val out = ArrayList<SowStep>()
            var hand = counts[startPit].also { counts[startPit] = 0 }
            out += SowStep(pickUp = true, pit = startPit)
            path.forEachIndexed { i, pit ->
                if (hand == 0) {
                    val from = path[i - 1]
                    hand = counts[from].also { counts[from] = 0 }
                    out += SowStep(pickUp = true, pit = from)
                }
                counts[pit]++
                hand--
                out += SowStep(pickUp = false, pit = pit)
            }
            return out
        }
    }
}
