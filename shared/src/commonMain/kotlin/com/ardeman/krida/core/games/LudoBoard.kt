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
 * Layout matches a classic board (row 0 = toward the camera / bottom of the screen): red
 * bottom-left, blue top-left, yellow top-right, green bottom-right. Gameplay orbits the
 * camera with [cameraEyeFor] so each player's own colour sits bottom-left.
 */
object LudoBoard {
    const val GRID = 15
    const val CELL = 1.2f
    private const val HALF = GRID * CELL / 2
    private const val GAP = 0.08f
    private const val FRAME = 0.45f
    private const val RIM = 0.5f

    /** Colour whose yard sits bottom-left with no view yaw (red). */
    const val VIEW_HOME_COLOR = 0

    /**
     * Yaw steps (×90°) from the default red bottom-left seat, clockwise around the board:
     * red → green → yellow → blue. Not colour-index order.
     */
    private val VIEW_YAW_STEPS = intArrayOf(0, 3, 1, 2) // red, blue, green, yellow

    /** Camera shared so both platforms frame the board the same way. */
    val cameraEye = floatArrayOf(0f, 28f, 18f)
    val cameraTarget = floatArrayOf(0f, 0f, 0f)
    const val CAMERA_VERTICAL_FOV_DEGREES = 40f

    /** Yaw in degrees that puts [color]'s yard at the bottom-left of the screen. */
    fun viewYawDegrees(color: Int): Float {
        val c = ((color % LudoGame.COLORS) + LudoGame.COLORS) % LudoGame.COLORS
        return VIEW_YAW_STEPS[c] * 90f
    }

    /** [cameraEye] orbited around Y so [color]'s yard sits bottom-left on screen. */
    fun cameraEyeFor(color: Int): FloatArray = cameraEyeAtYaw(viewYawDegrees(color))

    fun cameraEyeAtYaw(yawDegrees: Float): FloatArray {
        val rad = yawDegrees * PI.toFloat() / 180f
        val x0 = cameraEye[0]
        val z0 = cameraEye[2]
        return floatArrayOf(
            x0 * cos(rad) + z0 * sin(rad),
            cameraEye[1],
            -x0 * sin(rad) + z0 * cos(rad),
        )
    }

    const val TOP = 0.5f
    private const val MARK = TOP + 0.02f
    /** Yard cream pad and slot tops sit above [TOP] so they read over the continuous yard colour. */
    private const val YARD_CREAM_TOP = TOP + 0.02f
    const val YARD_SLOT_TOP = TOP + 0.04f
    private const val PIECE_SCALE = 0.95f

    private val WOOD = floatArrayOf(0.64f, 0.42f, 0.24f)
    private val CREAM = floatArrayOf(0.99f, 0.95f, 0.85f)
    private val WHITE = floatArrayOf(1f, 1f, 1f)
    private val INK = floatArrayOf(0.18f, 0.12f, 0.08f)
    private val GOLD = floatArrayOf(0.95f, 0.72f, 0.29f)
    private const val RING_HEIGHT = 0.12f

    /** Pawn colours, sRGB: red, blue, green, yellow. */
    val PIECE_COLORS = arrayOf(
        floatArrayOf(0.90f, 0.27f, 0.24f),
        floatArrayOf(0.24f, 0.50f, 0.90f),
        floatArrayOf(0.26f, 0.70f, 0.34f),
        floatArrayOf(0.98f, 0.78f, 0.18f),
    )

    fun pieceColor(color: Int): FloatArray = PIECE_COLORS[color % PIECE_COLORS.size]
    /**
     * Absolute ring square `0..51` → board cell (column, row). Row 0 is toward the camera
     * (bottom of the screen), row 14 is far (top). Square 0 is the red start on the bottom
     * arm; then clockwise: blue, yellow, green. Matches [LudoGame.STARTS].
     */
    private val TRACK_CELLS: Array<IntArray> = run {
        val cells = ArrayList<IntArray>(52)
        fun add(c: Int, r: Int) { cells += intArrayOf(c, r) }
        // Bottom arm, up the left column from red's start.
        add(6, 1) // 0 red start
        for (r in 2..5) add(6, r) // 1..4
        // Left arm, left along the bottom row, then up the outer edge.
        add(5, 6); add(4, 6); add(3, 6); add(2, 6); add(1, 6) // 5..9
        add(0, 6); add(0, 7); add(0, 8) // 10..12
        add(1, 8) // 13 blue start (arrow right)
        for (c in 2..5) add(c, 8) // 14..17
        // Top arm, up the left column, across the far edge.
        for (r in 9..13) add(6, r) // 18..22
        add(6, 14); add(7, 14); add(8, 14) // 23..25
        add(8, 13) // 26 yellow start (arrow down)
        for (r in 12 downTo 9) add(8, r) // 27..30
        // Right arm, right along the top row, then down the outer edge.
        for (c in 9..13) add(c, 8) // 31..35
        add(14, 8); add(14, 7); add(14, 6) // 36..38
        add(13, 6) // 39 green start (arrow left)
        for (c in 12 downTo 9) add(c, 6) // 40..43
        // Bottom arm, down the right column, across the near edge back to red.
        for (r in 5 downTo 1) add(8, r) // 44..48
        add(8, 0); add(7, 0); add(6, 0) // 49..51
        check(cells.size == LudoGame.TRACK) { "track has ${cells.size} cells" }
        cells.toTypedArray()
    }

    /** Home-stretch cells for each colour, from entrance to the finish (6 cells). */
    private val HOME_CELLS = arrayOf(
        // Red: up from the bottom arm into the centre.
        arrayOf(intArrayOf(7, 1), intArrayOf(7, 2), intArrayOf(7, 3), intArrayOf(7, 4), intArrayOf(7, 5), intArrayOf(7, 6)),
        // Blue: right from the left arm into the centre.
        arrayOf(intArrayOf(1, 7), intArrayOf(2, 7), intArrayOf(3, 7), intArrayOf(4, 7), intArrayOf(5, 7), intArrayOf(6, 7)),
        // Green: left from the right arm into the centre.
        arrayOf(intArrayOf(13, 7), intArrayOf(12, 7), intArrayOf(11, 7), intArrayOf(10, 7), intArrayOf(9, 7), intArrayOf(8, 7)),
        // Yellow: down from the top arm into the centre.
        arrayOf(intArrayOf(7, 13), intArrayOf(7, 12), intArrayOf(7, 11), intArrayOf(7, 10), intArrayOf(7, 9), intArrayOf(7, 8)),
    )

    /** Yard slot cells for each colour's four tokens (2×2). */
    private val YARD_CELLS = arrayOf(
        arrayOf(intArrayOf(2, 2), intArrayOf(3, 2), intArrayOf(2, 3), intArrayOf(3, 3)), // red bottom-left
        arrayOf(intArrayOf(2, 11), intArrayOf(3, 11), intArrayOf(2, 12), intArrayOf(3, 12)), // blue top-left
        arrayOf(intArrayOf(11, 2), intArrayOf(12, 2), intArrayOf(11, 3), intArrayOf(12, 3)), // green bottom-right
        arrayOf(intArrayOf(11, 11), intArrayOf(12, 11), intArrayOf(11, 12), intArrayOf(12, 12)), // yellow top-right
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
        val y = if (pos == LudoGame.YARD) YARD_SLOT_TOP else TOP
        return floatArrayOf(xz[0], y, xz[1])
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

    /**
     * Gold outline of one board cell, centered on the origin and sitting on y = 0. Place at
     * a token's (x, [TOP], z) to mark a pawn the player may tap.
     */
    fun ringMesh(): VoxelMesh {
        val half = CELL / 2 - GAP / 2
        val t = CELL * 0.14f
        return VoxelMesh().apply {
            box(floatArrayOf(-half, 0f, -half), floatArrayOf(half, RING_HEIGHT, -half + t), GOLD)
            box(floatArrayOf(-half, 0f, half - t), floatArrayOf(half, RING_HEIGHT, half), GOLD)
            box(floatArrayOf(-half, 0f, -half + t), floatArrayOf(-half + t, RING_HEIGHT, half - t), GOLD)
            box(floatArrayOf(half - t, 0f, -half + t), floatArrayOf(half, RING_HEIGHT, half - t), GOLD)
        }
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
        // Yard slot pads leave a wider cream gap so the four starts don't look jammed.
        val slotH = CELL / 2 - GAP * 2.5f
        fun tile(column: Int, row: Int, rgb: FloatArray, half: Float = h, top: Float = TOP) {
            val c = cellCenter(column, row)
            mesh.box(
                floatArrayOf(c[0] - half, FRAME, c[1] - half),
                floatArrayOf(c[0] + half, top, c[1] + half),
                rgb,
            )
        }
        /** Continuous floor over [c0..c1] × [r0..r1] (no wood seams — non-play areas). */
        fun slab(
            c0: Int, c1: Int, r0: Int, r1: Int, rgb: FloatArray,
            inset: Float = GAP / 2, top: Float = TOP,
        ) {
            val x0 = c0 * CELL - HALF + inset
            val x1 = (c1 + 1) * CELL - HALF - inset
            val zFar = (GRID - 1 - r1) * CELL - HALF + inset
            val zNear = (GRID - r0) * CELL - HALF - inset
            mesh.box(floatArrayOf(x0, FRAME, zFar), floatArrayOf(x1, top, zNear), rgb)
        }
        // Coloured yard floors and cream pads are solid slabs (tokens only sit on the four slots).
        val yards = arrayOf(
            intArrayOf(0, 5, 0, 5), // red bottom-left
            intArrayOf(0, 5, 9, 14), // blue top-left
            intArrayOf(9, 14, 0, 5), // green bottom-right
            intArrayOf(9, 14, 9, 14), // yellow top-right
        )
        for (color in 0 until LudoGame.COLORS) {
            val c0 = yards[color][0]; val c1 = yards[color][1]
            val r0 = yards[color][2]; val r1 = yards[color][3]
            slab(c0, c1, r0, r1, pieceColor(color))
            // Inner cream square behind the four slot tiles.
            slab(c0 + 1, c1 - 1, r0 + 1, r1 - 1, CREAM, top = YARD_CREAM_TOP)
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
                tile(cell[0], cell[1], pieceColor(color), slotH, YARD_SLOT_TOP)
                // Raised disc so the yard slots read as classic circles.
                disc(mesh, cellCenter(cell[0], cell[1]), CELL * 0.24f, YARD_SLOT_TOP + 0.02f, pieceColor(color))
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
        val bl = floatArrayOf(mid[0] - half, y, mid[1] + half) // bottom-left corner
        val br = floatArrayOf(mid[0] + half, y, mid[1] + half) // bottom-right
        val tr = floatArrayOf(mid[0] + half, y, mid[1] - half) // top-right
        val tl = floatArrayOf(mid[0] - half, y, mid[1] - half) // top-left
        val c = floatArrayOf(mid[0], y, mid[1])
        // Each triangle as a degenerate quad (centre repeated) so VoxelMesh can draw it.
        fun tri(a: FloatArray, b: FloatArray, rgb: FloatArray) {
            mesh.quad(arrayOf(c, a, b, c), rgb)
        }
        tri(bl, br, pieceColor(0)) // red → bottom
        tri(tl, bl, pieceColor(1)) // blue → left
        tri(br, tr, pieceColor(2)) // green → right
        tri(tr, tl, pieceColor(3)) // yellow → top
        // Home-entrance arrows (point into the coloured finish lane) and starred safe squares.
        for (color in 0 until LudoGame.COLORS) {
            val gate = HOME_GATE_CELLS[color]
            val home = HOME_CELLS[color][0]
            arrow(
                mesh,
                cellCenter(gate[0], gate[1]),
                cellCenter(home[0], home[1]),
                pieceColor(color),
            )
        }
        for (square in LudoGame.STARS) {
            val cell = TRACK_CELLS[square]
            star(mesh, cellCenter(cell[0], cell[1]), CELL * 0.28f, INK)
        }
        return mesh
    }

    /**
     * Cream track cell just outside each home stretch, where the classic board draws an
     * arrow into that colour's finish lane: red bottom, blue left, green right, yellow top.
     */
    private val HOME_GATE_CELLS = arrayOf(
        intArrayOf(7, 0), // red → up into (7, 1)
        intArrayOf(0, 7), // blue → right into (1, 7)
        intArrayOf(14, 7), // green → left into (13, 7)
        intArrayOf(7, 14), // yellow → down into (7, 13)
    )

    /**
     * Flat five-point star on the tile top, like the classic safe-square mark. The tip
     * reaches farther than the two lower points, so the draw centre is nudged toward the
     * base to keep the mark visually centred in the cell.
     */
    private fun star(mesh: VoxelMesh, at: FloatArray, radius: Float, rgb: FloatArray) {
        val y = MARK
        // Tip is toward −z (angle −π/2); lower tips only reach ~0.81·radius toward +z.
        val cx = at[0]
        val cz = at[1] + radius * 0.095f
        val c = floatArrayOf(cx, y, cz)
        fun tip(i: Int, r: Float): FloatArray {
            val a = (-PI / 2 + i * PI / 5).toFloat()
            return floatArrayOf(cx + cos(a) * r, y, cz + sin(a) * r)
        }
        for (i in 0 until 5) {
            val outer = tip(i * 2, radius)
            val before = tip((i * 2 + 9) % 10, radius * 0.4f)
            val after = tip(i * 2 + 1, radius * 0.4f)
            mesh.quad(arrayOf(c, before, outer, after), rgb)
        }
    }

    /**
     * Block arrow centred on [from], pointing toward [toward] (into the home stretch).
     * Stem + triangular head, matching a classic Ludo board mark.
     */
    private fun arrow(mesh: VoxelMesh, from: FloatArray, toward: FloatArray, rgb: FloatArray) {
        val dx = toward[0] - from[0]
        val dz = toward[1] - from[1]
        val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.001f)
        val fx = dx / len
        val fz = dz / len
        val px = -fz
        val pz = fx
        val y = MARK
        val tip = floatArrayOf(from[0] + fx * CELL * 0.32f, y, from[1] + fz * CELL * 0.32f)
        val headBase = floatArrayOf(from[0] + fx * CELL * 0.02f, y, from[1] + fz * CELL * 0.02f)
        val headL = floatArrayOf(headBase[0] + px * CELL * 0.22f, y, headBase[2] + pz * CELL * 0.22f)
        val headR = floatArrayOf(headBase[0] - px * CELL * 0.22f, y, headBase[2] - pz * CELL * 0.22f)
        mesh.quad(arrayOf(tip, headL, headR, tip), rgb)
        val stemBack = floatArrayOf(from[0] - fx * CELL * 0.28f, y, from[1] - fz * CELL * 0.28f)
        val stemHalf = CELL * 0.08f
        val s0 = floatArrayOf(stemBack[0] + px * stemHalf, y, stemBack[2] + pz * stemHalf)
        val s1 = floatArrayOf(stemBack[0] - px * stemHalf, y, stemBack[2] - pz * stemHalf)
        val s2 = floatArrayOf(headBase[0] - px * stemHalf, y, headBase[2] - pz * stemHalf)
        val s3 = floatArrayOf(headBase[0] + px * stemHalf, y, headBase[2] + pz * stemHalf)
        mesh.quad(arrayOf(s0, s1, s2, s3), rgb)
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
