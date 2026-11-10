package com.ardeman.krida.core.games

import com.ardeman.krida.core.voxel.VoxelMesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ludo board layout in world units (y up, camera on +z). A 15×15 cell cross: the outer ring
 * is the 52-square track, each colour has a yard quadrant and a 6-step home stretch into the
 * centre. Pure geometry and meshes so Android and iOS place and draw tokens the same way.
 *
 * Colour order matches [LudoGame.STARTS]: 0 red (near), 1 blue (left/−x), 2 green (far),
 * 3 yellow (right/+x).
 */
object LudoBoard {
    const val GRID = 15
    const val CELL = 1.2f
    private const val HALF = GRID * CELL / 2
    private const val GAP = 0.08f
    private const val FRAME = 0.45f
    private const val RIM = 0.5f

    /** Camera shared so both platforms frame the board the same way. */
    val cameraEye = floatArrayOf(0f, 28f, 18f)
    val cameraTarget = floatArrayOf(0f, 0f, 0f)
    const val CAMERA_VERTICAL_FOV_DEGREES = 40f

    const val TOP = 0.5f
    private const val MARK = TOP + 0.02f
    private const val PIECE_SCALE = 0.95f

    private val WOOD = floatArrayOf(0.64f, 0.42f, 0.24f)
    private val CREAM = floatArrayOf(0.99f, 0.95f, 0.85f)
    private val WHITE = floatArrayOf(1f, 1f, 1f)
    private val INK = floatArrayOf(0.18f, 0.12f, 0.08f)

    /** Pawn colours, sRGB: red, blue, green, yellow. */
    val PIECE_COLORS = arrayOf(
        floatArrayOf(0.90f, 0.27f, 0.24f),
        floatArrayOf(0.24f, 0.50f, 0.90f),
        floatArrayOf(0.26f, 0.70f, 0.34f),
        floatArrayOf(0.98f, 0.78f, 0.18f),
    )

    fun pieceColor(color: Int): FloatArray = PIECE_COLORS[color % PIECE_COLORS.size]
    /**
     * Absolute ring square `0..51` → board cell (column, row) from the far-left corner
     * (row 0 = far, row 14 = near). Square 0 is the red start; then clockwise. Matches
     * [LudoGame.STARTS]: red 0, blue 13, green 26, yellow 39.
     */
    private val TRACK_CELLS: Array<IntArray> = run {
        val cells = ArrayList<IntArray>(52)
        fun add(c: Int, r: Int) { cells += intArrayOf(c, r) }
        add(6, 13) // 0 red start
        for (r in 12 downTo 9) add(6, r) // 1..4
        add(5, 8); add(4, 8); add(3, 8); add(2, 8); add(1, 8) // 5..9
        add(0, 8); add(0, 7); add(0, 6) // 10..12
        add(1, 6) // 13 blue start
        for (c in 2..5) add(c, 6) // 14..17
        for (r in 5 downTo 1) add(6, r) // 18..22
        add(6, 0); add(7, 0); add(8, 0) // 23..25
        add(8, 1) // 26 green start
        for (r in 2..5) add(8, r) // 27..30
        for (c in 9..13) add(c, 6) // 31..35
        add(14, 6); add(14, 7); add(14, 8) // 36..38
        add(13, 8) // 39 yellow start
        for (c in 12 downTo 9) add(c, 8) // 40..43
        for (r in 9..13) add(8, r) // 44..48
        add(8, 14); add(7, 14); add(6, 14) // 49..51
        check(cells.size == LudoGame.TRACK) { "track has ${cells.size} cells" }
        cells.toTypedArray()
    }

    /** Home-stretch cells for each colour, from entrance to the finish (6 cells). */
    private val HOME_CELLS = arrayOf(
        arrayOf(intArrayOf(7, 13), intArrayOf(7, 12), intArrayOf(7, 11), intArrayOf(7, 10), intArrayOf(7, 9), intArrayOf(7, 8)),
        arrayOf(intArrayOf(1, 7), intArrayOf(2, 7), intArrayOf(3, 7), intArrayOf(4, 7), intArrayOf(5, 7), intArrayOf(6, 7)),
        arrayOf(intArrayOf(7, 1), intArrayOf(7, 2), intArrayOf(7, 3), intArrayOf(7, 4), intArrayOf(7, 5), intArrayOf(7, 6)),
        arrayOf(intArrayOf(13, 7), intArrayOf(12, 7), intArrayOf(11, 7), intArrayOf(10, 7), intArrayOf(9, 7), intArrayOf(8, 7)),
    )

    /** Yard slot cells for each colour's four tokens (2×2). */
    private val YARD_CELLS = arrayOf(
        arrayOf(intArrayOf(2, 11), intArrayOf(3, 11), intArrayOf(2, 12), intArrayOf(3, 12)),
        arrayOf(intArrayOf(2, 2), intArrayOf(3, 2), intArrayOf(2, 3), intArrayOf(3, 3)),
        arrayOf(intArrayOf(11, 2), intArrayOf(12, 2), intArrayOf(11, 3), intArrayOf(12, 3)),
        arrayOf(intArrayOf(11, 11), intArrayOf(12, 11), intArrayOf(11, 12), intArrayOf(12, 12)),
    )

    /** World XZ of grid cell ([column], [row]). */
    fun cellCenter(column: Int, row: Int): FloatArray = floatArrayOf(
        (column + 0.5f) * CELL - HALF,
        (GRID - 1 - row + 0.5f) * CELL - HALF,
    )

    /** World position (x, y, z) for [seat]'s [pawn] at relative [pos]. */
    fun tokenAnchor(color: Int, pawn: Int, pos: Int): FloatArray {
        val cell = when {
            pos == LudoGame.YARD -> YARD_CELLS[color][pawn]
            pos >= LudoGame.HOME_START -> HOME_CELLS[color][pos - LudoGame.HOME_START]
            else -> TRACK_CELLS[(LudoGame.STARTS[color] + pos) % LudoGame.TRACK]
        }
        val xz = cellCenter(cell[0], cell[1])
        return floatArrayOf(xz[0], TOP, xz[1])
    }

    /** World position of absolute ring [square]. */
    fun trackAnchor(square: Int): FloatArray {
        val cell = TRACK_CELLS[square]
        val xz = cellCenter(cell[0], cell[1])
        return floatArrayOf(xz[0], TOP, xz[1])
    }

    fun trackCell(square: Int): IntArray = TRACK_CELLS[square].copyOf()

    fun homeCell(color: Int, step: Int): IntArray = HOME_CELLS[color][step].copyOf()

    fun yardCell(color: Int, pawn: Int): IntArray = YARD_CELLS[color][pawn].copyOf()

    /** Eight corners of the board volume for camera fitting. */
    fun bounds(): List<List<FloatArray>> {
        val e = HALF + RIM
        val top = TOP + 1.2f * PIECE_SCALE
        return listOf(
            listOf(0f, top).flatMap { y ->
                listOf(
                    floatArrayOf(-e, y, -e), floatArrayOf(e, y, -e),
                    floatArrayOf(-e, y, e), floatArrayOf(e, y, e),
                )
            },
        )
    }

    /** Voxel pawn in [color] (0..3), standing on the origin. */
    fun pieceMesh(color: Int): VoxelMesh {
        val c = pieceColor(color)
        val dark = floatArrayOf(c[0] * 0.75f, c[1] * 0.75f, c[2] * 0.75f)
        val k = PIECE_SCALE
        return VoxelMesh().apply {
            box(floatArrayOf(-0.38f * k, 0f, -0.38f * k), floatArrayOf(0.38f * k, 0.2f * k, 0.38f * k), dark, bottom = true)
            box(floatArrayOf(-0.26f * k, 0.2f * k, -0.26f * k), floatArrayOf(0.26f * k, 0.78f * k, 0.26f * k), c)
            box(floatArrayOf(-0.32f * k, 0.78f * k, -0.32f * k), floatArrayOf(0.32f * k, 1.3f * k, 0.32f * k), c)
            box(floatArrayOf(-0.24f * k, 1.12f * k, 0.26f * k), floatArrayOf(-0.1f * k, 1.24f * k, 0.34f * k), WHITE)
        }
    }

    /** Wood frame, cream track, coloured yards, home stretches, stars, start arrows and centre. */
    fun boardMesh(): VoxelMesh {
        val mesh = VoxelMesh()
        val e = HALF + RIM
        mesh.box(floatArrayOf(-e, 0f, -e), floatArrayOf(e, FRAME, e), WOOD)
        val h = CELL / 2 - GAP / 2
        fun tile(column: Int, row: Int, rgb: FloatArray) {
            val c = cellCenter(column, row)
            mesh.box(
                floatArrayOf(c[0] - h, FRAME, c[1] - h),
                floatArrayOf(c[0] + h, TOP, c[1] + h),
                rgb,
            )
        }
        // Coloured yard quadrants (6×6 corners), then a cream pad under the four slots.
        val yards = arrayOf(
            intArrayOf(0, 5, 9, 14), // red near-left
            intArrayOf(0, 5, 0, 5), // blue far-left
            intArrayOf(9, 14, 0, 5), // green far-right
            intArrayOf(9, 14, 9, 14), // yellow near-right
        )
        for (color in 0 until LudoGame.COLORS) {
            val c0 = yards[color][0]; val c1 = yards[color][1]
            val r0 = yards[color][2]; val r1 = yards[color][3]
            val rgb = pieceColor(color)
            for (column in c0..c1) for (row in r0..r1) tile(column, row, rgb)
            // Inner cream square behind the four slot tiles.
            for (column in c0 + 1..c1 - 1) for (row in r0 + 1..r1 - 1) tile(column, row, CREAM)
        }
        // Cross arms as cream filler, then coloured specials on top of the same cells.
        for (column in 0 until GRID) for (row in 0 until GRID) {
            val onCross = column in 6..8 || row in 6..8
            if (onCross) tile(column, row, CREAM)
        }
        for (square in 0 until LudoGame.TRACK) {
            val cell = TRACK_CELLS[square]
            val startColor = LudoGame.STARTS.indexOfFirst { it == square }
            tile(cell[0], cell[1], if (startColor >= 0) pieceColor(startColor) else CREAM)
        }
        for (color in 0 until LudoGame.COLORS) {
            for (pawn in 0 until LudoGame.PAWNS) {
                val cell = YARD_CELLS[color][pawn]
                tile(cell[0], cell[1], pieceColor(color))
                // Raised disc so the yard slots read as classic circles.
                disc(mesh, cellCenter(cell[0], cell[1]), CELL * 0.28f, MARK, pieceColor(color))
            }
            for (step in 0 until LudoGame.HOME_LEN) {
                val cell = HOME_CELLS[color][step]
                tile(cell[0], cell[1], pieceColor(color))
            }
        }
        // Centre finish: four coloured triangles pointing toward each home stretch.
        val mid = cellCenter(7, 7)
        val half = CELL
        val y = TOP + 0.04f
        val n = floatArrayOf(mid[0], y, mid[1] + half) // near (+z), red
        val ePt = floatArrayOf(mid[0] + half, y, mid[1]) // +x, yellow
        val s = floatArrayOf(mid[0], y, mid[1] - half) // far (−z), green
        val w = floatArrayOf(mid[0] - half, y, mid[1]) // −x, blue
        val c = floatArrayOf(mid[0], y, mid[1])
        // Each triangle as a degenerate quad (centre repeated) so VoxelMesh can draw it.
        fun tri(a: FloatArray, b: FloatArray, rgb: FloatArray) {
            mesh.quad(arrayOf(c, a, b, c), rgb)
        }
        tri(n, w, pieceColor(0))
        tri(w, s, pieceColor(1))
        tri(s, ePt, pieceColor(2))
        tri(ePt, n, pieceColor(3))
        // Start arrows (travel direction) and starred safe squares.
        for (color in 0 until LudoGame.COLORS) {
            val start = LudoGame.STARTS[color]
            val cell = TRACK_CELLS[start]
            val next = TRACK_CELLS[(start + 1) % LudoGame.TRACK]
            arrow(mesh, cellCenter(cell[0], cell[1]), cellCenter(next[0], next[1]), pieceColor(color))
        }
        for (square in LudoGame.STARS) {
            val cell = TRACK_CELLS[square]
            star(mesh, cellCenter(cell[0], cell[1]), CELL * 0.32f, INK)
        }
        return mesh
    }

    /** Flat five-point star on the tile top, like the classic safe-square mark. */
    private fun star(mesh: VoxelMesh, at: FloatArray, radius: Float, rgb: FloatArray) {
        val y = MARK
        val c = floatArrayOf(at[0], y, at[1])
        fun tip(i: Int, r: Float): FloatArray {
            val a = (-PI / 2 + i * PI / 5).toFloat()
            return floatArrayOf(at[0] + cos(a) * r, y, at[1] + sin(a) * r)
        }
        for (i in 0 until 5) {
            val outer = tip(i * 2, radius)
            val before = tip((i * 2 + 9) % 10, radius * 0.4f)
            val after = tip(i * 2 + 1, radius * 0.4f)
            mesh.quad(arrayOf(c, before, outer, after), rgb)
        }
    }

    /** Small triangle on a start square, pointing toward [toward]. */
    private fun arrow(mesh: VoxelMesh, from: FloatArray, toward: FloatArray, rgb: FloatArray) {
        val dx = toward[0] - from[0]
        val dz = toward[1] - from[1]
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.001f)
        val fx = dx / len
        val fz = dz / len
        val px = -fz
        val pz = fx
        val y = MARK
        val tip = floatArrayOf(from[0] + fx * CELL * 0.28f, y, from[1] + fz * CELL * 0.28f)
        val left = floatArrayOf(from[0] - fx * CELL * 0.18f + px * CELL * 0.2f, y, from[1] - fz * CELL * 0.18f + pz * CELL * 0.2f)
        val right = floatArrayOf(from[0] - fx * CELL * 0.18f - px * CELL * 0.2f, y, from[1] - fz * CELL * 0.18f - pz * CELL * 0.2f)
        mesh.quad(arrayOf(tip, left, right, tip), rgb)
    }

    /** Low disc on a yard slot. */
    private fun disc(mesh: VoxelMesh, at: FloatArray, radius: Float, y: Float, rgb: FloatArray) {
        val n = 10
        val c = floatArrayOf(at[0], y, at[1])
        fun pt(i: Int) = floatArrayOf(
            at[0] + cos(i * 2 * PI.toFloat() / n) * radius,
            y,
            at[1] + sin(i * 2 * PI.toFloat() / n) * radius,
        )
        for (i in 0 until n) {
            mesh.quad(arrayOf(c, pt(i), pt((i + 1) % n), c), rgb)
        }
    }

    /** Where a thrown die may land: min x, min z, max x, max z. */
    val dieLanding: FloatArray = floatArrayOf(-CELL * 1.5f, -CELL * 1.5f, CELL * 1.5f, CELL * 1.5f)

    const val DIE = 1.6f
    const val DIE_REST_Y = TOP + DIE / 2
}
