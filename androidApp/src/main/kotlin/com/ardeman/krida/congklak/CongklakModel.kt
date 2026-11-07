package com.ardeman.krida.congklak

import com.ardeman.krida.core.games.CongklakGame
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Voxel congklak board in world units (y up). Seat 0 sits on the near side (+z), seat 1 on
 * the far side; sowing runs counter-clockwise seen from above:
 *
 * ```
 *        14 13 12 11 10  9  8          (seat 1)
 *   15                          7
 *         0  1  2  3  4  5  6          (seat 0)
 * ```
 */
object CongklakModel {
    const val SEED_COUNT = CongklakGame.HOLES * CongklakGame.SEEDS * 2

    /** Seed mesh variants, to break up the pile a little. */
    private val SEED_COLORS = arrayOf(
        floatArrayOf(0.95f, 0.90f, 0.78f),
        floatArrayOf(0.86f, 0.80f, 0.68f),
        floatArrayOf(0.82f, 0.62f, 0.40f),
    )

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

    val boardTop = BOARD_LAYERS * CELL
    private val pitFloor = PIT_LAYERS * CELL

    /** Pit centers (x, z) by pit index. */
    val pitCenters: Array<FloatArray> = Array(CongklakGame.PITS) { pit ->
        val seat = CongklakGame.ownerOf(pit)
        val hole = pit % (CongklakGame.HOLES + 1)
        when {
            CongklakGame.isStore(pit) -> floatArrayOf(if (seat == 0) 12.5f else -12.5f, 0f)
            seat == 0 -> floatArrayOf((hole - 3) * 3f, 2f)
            else -> floatArrayOf((3 - hole) * 3f, -2f)
        }
    }

    fun buildGlb(): ByteBuffer {
        val glb = GlbWriter()
        glb.node("board", glb.mesh(boardMesh()))
        val seedMeshes = SEED_COLORS.map { c ->
            val h = SEED_SIZE / 2
            glb.mesh(VoxelMesh().apply { box(floatArrayOf(-h, -h, -h), floatArrayOf(h, h, h), c, bottom = true) })
        }
        repeat(SEED_COUNT) { glb.node(seedName(it), seedMeshes[it % seedMeshes.size]) }
        return glb.build()
    }

    fun seedName(i: Int) = "seed$i"

    /** Resting position of the [k]-th seed in [pit], with a small stable jitter. */
    fun slot(pit: Int, k: Int): FloatArray {
        val (cx, cz) = pitCenters[pit]
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

    /** Where a seed in the sowing hand floats, above [pit]. */
    fun handSlot(pit: Int, k: Int): FloatArray {
        val (cx, cz) = pitCenters[pit]
        val a = k * 2.4f
        val r = 0.25f + (k % 5) * 0.08f
        return floatArrayOf(cx + r * cos(a), boardTop + 1.6f + (k / 5) * 0.3f, cz + r * sin(a))
    }

    /** Stable yaw per seed, in radians. */
    fun yaw(seed: Int) = jitter(seed * 7 + 3) * 0.6f

    /** Deterministic value in -1..1. */
    private fun jitter(n: Int): Float {
        var h = n * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 32767.5f - 1f
    }

    // ---- Board mesh ---------------------------------------------------------

    private val WOOD = floatArrayOf(0.64f, 0.42f, 0.24f)
    private val WOOD_DARK = floatArrayOf(0.44f, 0.27f, 0.15f)

    private fun boardMesh(): VoxelMesh {
        val nx = (2 * HALF_X / CELL).toInt()
        val nz = (2 * HALF_Z / CELL).toInt()
        val height = Array(nx) { IntArray(nz) }
        val pit = Array(nx) { BooleanArray(nz) }

        for (i in 0 until nx) for (k in 0 until nz) {
            val x = -HALF_X + (i + 0.5f) * CELL
            val z = -HALF_Z + (k + 0.5f) * CELL
            if (!insideBoat(x, z)) continue
            val inPit = pitCenters.indices.any { p -> inPit(p, x, z) }
            height[i][k] = if (inPit) PIT_LAYERS else BOARD_LAYERS
            pit[i][k] = inPit
        }

        val mesh = VoxelMesh()
        fun h(i: Int, k: Int) = if (i in 0 until nx && k in 0 until nz) height[i][k] else 0

        for (i in 0 until nx) for (k in 0 until nz) {
            val hi = height[i][k]
            if (hi == 0) continue
            val x0 = -HALF_X + i * CELL
            val z0 = -HALF_Z + k * CELL
            val x1 = x0 + CELL
            val z1 = z0 + CELL
            val top = hi * CELL
            val base = if (pit[i][k]) WOOD_DARK else WOOD
            val n = 1f + jitter(i * 977 + k) * 0.05f
            val col = floatArrayOf(base[0] * n, base[1] * n, base[2] * n)
            val f = VoxelMesh::f
            mesh.quad(arrayOf(f(x0, top, z0), f(x0, top, z1), f(x1, top, z1), f(x1, top, z0)), col)

            // Side walls down to each lower neighbor. Walls into a pit use the dark wood.
            fun wall(nh: Int, nPit: Boolean, shade: Float, corners: (Float) -> Array<FloatArray>) {
                if (nh >= hi) return
                val c = if (nPit) WOOD_DARK else col
                mesh.quad(corners(nh * CELL), floatArrayOf(c[0] * shade, c[1] * shade, c[2] * shade))
            }
            val isPit = { a: Int, b: Int -> a in 0 until nx && b in 0 until nz && pit[a][b] }
            wall(h(i + 1, k), isPit(i + 1, k), 0.78f) { y -> arrayOf(f(x1, y, z0), f(x1, top, z0), f(x1, top, z1), f(x1, y, z1)) }
            wall(h(i - 1, k), isPit(i - 1, k), 0.78f) { y -> arrayOf(f(x0, y, z1), f(x0, top, z1), f(x0, top, z0), f(x0, y, z0)) }
            wall(h(i, k + 1), isPit(i, k + 1), 0.88f) { y -> arrayOf(f(x0, y, z1), f(x1, y, z1), f(x1, top, z1), f(x0, top, z1)) }
            wall(h(i, k - 1), isPit(i, k - 1), 0.70f) { y -> arrayOf(f(x1, y, z0), f(x0, y, z0), f(x0, top, z0), f(x1, top, z0)) }
        }
        return mesh
    }

    /** Boat outline: a long board with rounded ends. */
    private fun insideBoat(x: Float, z: Float): Boolean {
        val straight = HALF_X - 4f
        val ax = kotlin.math.abs(x)
        if (ax <= straight) return kotlin.math.abs(z) <= HALF_Z
        val dx = (ax - straight) / 4f
        val dz = z / HALF_Z
        return dx * dx + dz * dz <= 1f
    }

    private fun inPit(p: Int, x: Float, z: Float): Boolean {
        val (cx, cz) = pitCenters[p]
        return if (CongklakGame.isStore(p)) {
            val dx = (x - cx) / STORE_RX
            val dz = (z - cz) / STORE_RZ
            dx * dx + dz * dz <= 1f
        } else {
            val dx = x - cx
            val dz = z - cz
            dx * dx + dz * dz <= HOLE_RADIUS * HOLE_RADIUS
        }
    }
}
