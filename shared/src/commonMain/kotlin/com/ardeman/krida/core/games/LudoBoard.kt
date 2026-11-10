package com.ardeman.krida.core.games

/**
 * Ludo board layout in world units (y up, camera on +z). A 15×15 cell cross: the outer ring
 * is the 52-square track, each colour has a 2×2 yard and a 6-step home stretch into the
 * centre. Pure geometry so Android and iOS place tokens the same way; the voxel mesh comes
 * with the UI work.
 *
 * Colour order matches [LudoGame.STARTS]: 0 red (near/-z start), 1 blue (+x), 2 green
 * (far/+z), 3 yellow (−x).
 */
object LudoBoard {
    const val GRID = 15
    const val CELL = 1.2f
    private const val HALF = GRID * CELL / 2

    /** Camera shared so both platforms frame the board the same way. */
    val cameraEye = floatArrayOf(0f, 28f, 18f)
    val cameraTarget = floatArrayOf(0f, 0f, 0f)
    const val CAMERA_VERTICAL_FOV_DEGREES = 40f

    const val TOP = 0.5f

    /** Pawn colours, sRGB: red, blue, green, yellow. */
    val PIECE_COLORS = arrayOf(
        floatArrayOf(0.90f, 0.27f, 0.24f),
        floatArrayOf(0.24f, 0.50f, 0.90f),
        floatArrayOf(0.26f, 0.70f, 0.34f),
        floatArrayOf(0.98f, 0.78f, 0.18f),
    )

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
}
