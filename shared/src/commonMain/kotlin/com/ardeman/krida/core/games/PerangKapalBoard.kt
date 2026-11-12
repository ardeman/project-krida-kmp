package com.ardeman.krida.core.games

import com.ardeman.krida.core.voxel.VoxelMesh

/**
 * Perang kapal grids in world units (y up, camera on +z). One 10×10 ocean; in battle the
 * radar grid sits beside it and the ocean shrinks ([MINI_SCALE]). Pure geometry so Android and iOS match.
 *
 * Cell 0 is bottom-left (nearest the camera, column A / row 1). Rows run toward −z.
 */
object PerangKapalBoard {
    const val CELL = 1.1f
    private const val HALF = PerangKapalGame.SIZE * CELL / 2
    private const val GAP = 0.06f
    private const val RIM = 0.45f
    private const val EDGE = HALF + RIM
    private const val SHIP_MARGIN = 0.12f
    private const val FRAME = 0.4f

    const val TOP = 0.45f
    /** Height of a ship above [TOP]; a peg on a ship is lifted by this to stand on its deck. */
    const val SHIP_H = 0.5f
    private const val PEG_H = 0.35f

    val cameraEye = floatArrayOf(0f, 22f, 16f)
    val cameraTarget = floatArrayOf(0f, 0f, 0f)
    const val CAMERA_VERTICAL_FOV_DEGREES = 40f

    /** Centre of the radar grid relative to the ocean (battle layout). */
    const val RADAR_OFFSET_X = PerangKapalGame.SIZE * CELL + 1.6f

    /**
     * In battle the radar is the board to play on; your own ocean is only looked at, so the
     * ocean and everything on it is drawn at this scale (around the world origin) and then
     * moved by [miniOffset] to sit left of the radar, its near edge in line with the radar's.
     * The camera looks at the radar, which fills the free space; the small ocean may sit under
     * the HUD's left column, below the player tiles.
     */
    const val MINI_SCALE = 0.32f
    private const val MINI_GAP = 0.6f
    val miniOffset = floatArrayOf(RADAR_OFFSET_X - EDGE - MINI_GAP - EDGE * MINI_SCALE, 0f, EDGE * (1 - MINI_SCALE))

    private val WOOD = floatArrayOf(0.64f, 0.42f, 0.24f)
    private val CREAM = floatArrayOf(0.99f, 0.95f, 0.85f)
    private val SEA = floatArrayOf(0.22f, 0.45f, 0.62f)
    private val SEA_DARK = floatArrayOf(0.14f, 0.32f, 0.48f)
    private val HULL = floatArrayOf(0.43f, 0.47f, 0.52f)
    private val DECK = floatArrayOf(0.63f, 0.66f, 0.69f)
    private val DECK_DARK = floatArrayOf(0.34f, 0.37f, 0.41f)
    private val STRUCT = floatArrayOf(0.76f, 0.78f, 0.8f)
    private val DARK = floatArrayOf(0.2f, 0.22f, 0.26f)
    private val SUB = floatArrayOf(0.25f, 0.3f, 0.36f)
    private val HIT = floatArrayOf(0.84f, 0.27f, 0.24f)
    private val MISS = floatArrayOf(0.92f, 0.90f, 0.86f)
    private val GOLD = floatArrayOf(0.95f, 0.72f, 0.29f)

    /** World centre of cell [index] on a grid whose origin is at [originX], 0, 0. */
    fun cellCenter(index: Int, originX: Float = 0f): FloatArray {
        val col = index % PerangKapalGame.SIZE
        val row = index / PerangKapalGame.SIZE
        val x = originX - HALF + col * CELL + CELL / 2
        val z = HALF - row * CELL - CELL / 2
        return floatArrayOf(x, TOP, z)
    }

    fun bounds(originX: Float = 0f): List<FloatArray> {
        val e = HALF + RIM
        return listOf(0f, TOP + SHIP_H).flatMap { y ->
            listOf(
                floatArrayOf(originX - e, y, -e),
                floatArrayOf(originX + e, y, -e),
                floatArrayOf(originX - e, y, e),
                floatArrayOf(originX + e, y, e),
            )
        }
    }

    /** Board frame + 10×10 sea tiles. */
    fun gridMesh(originX: Float = 0f): VoxelMesh {
        val mesh = VoxelMesh()
        val e = HALF + RIM
        mesh.box(
            floatArrayOf(originX - e, 0f, -e),
            floatArrayOf(originX + e, FRAME, e),
            WOOD,
        )
        for (row in 0 until PerangKapalGame.SIZE) for (col in 0 until PerangKapalGame.SIZE) {
            val c = cellCenter(row * PerangKapalGame.SIZE + col, originX)
            val half = CELL / 2 - GAP
            val rgb = if ((row + col) % 2 == 0) SEA else SEA_DARK
            mesh.box(
                floatArrayOf(c[0] - half, FRAME, c[2] - half),
                floatArrayOf(c[0] + half, TOP, c[2] + half),
                rgb,
            )
        }
        return mesh
    }

    /** Length of ship [kind] along its cells, a little short of the cells it covers. */
    fun shipLength(kind: Int): Float = PerangKapalGame.FLEET[kind] * CELL - 2 * SHIP_MARGIN

    /**
     * The boxes ship [kind] is built from, nine floats each: u0, u1 (along the ship, 0 at the
     * bow), v0, v1 (across, 0 on the centre line), y0, y1 (above the sea), then r, g, b.
     * Lowest tops first, so a top-down drawing (the placement tray) can paint them in order.
     */
    fun shipParts(kind: Int): FloatArray {
        val parts = ArrayList<FloatArray>()
        fun add(u0: Float, u1: Float, v0: Float, v1: Float, y0: Float, y1: Float, rgb: FloatArray) {
            parts.add(floatArrayOf(u0, u1, v0, v1, y0, y1, rgb[0], rgb[1], rgb[2]))
        }
        fun sym(u0: Float, u1: Float, half: Float, y0: Float, y1: Float, rgb: FloatArray) = add(u0, u1, -half, half, y0, y1, rgb)
        val l = shipLength(kind)
        fun hull(half: Float, h: Float, rgb: FloatArray) {
            sym(0f, 0.18f, half * 0.4f, 0f, h * 0.8f, rgb)
            sym(0.18f, 0.42f, half * 0.72f, 0f, h, rgb)
            sym(0.42f, l - 0.22f, half, 0f, h, rgb)
            sym(l - 0.22f, l, half * 0.75f, 0f, h, rgb)
        }
        when (kind) {
            0 -> { // Kapal induk: a flight deck with a centre line and an island on one side.
                hull(0.36f, 0.22f, HULL)
                sym(0.25f, l - 0.05f, 0.42f, 0.22f, 0.3f, DECK_DARK)
                for (i in 0 until 5) sym(0.6f + i * 0.9f, 1.05f + i * 0.9f, 0.03f, 0.3f, 0.315f, MISS)
                add(3f, 3.9f, 0.2f, 0.42f, 0.3f, 0.5f, STRUCT)
                add(3.25f, 3.55f, 0.26f, 0.36f, 0.5f, 0.62f, DARK)
            }
            1 -> { // Kapal perang: gun turrets fore and aft of a stepped bridge.
                hull(0.36f, 0.24f, HULL)
                sym(0.42f, l - 0.22f, 0.3f, 0.24f, 0.28f, DECK)
                sym(0.4f, 0.8f, 0.05f, 0.31f, 0.37f, DARK)
                sym(0.8f, 1.25f, 0.2f, 0.28f, 0.4f, STRUCT)
                sym(1.5f, 2.7f, 0.24f, 0.28f, 0.44f, STRUCT)
                sym(1.75f, 2.3f, 0.16f, 0.44f, 0.5f, DECK)
                sym(2.4f, 2.65f, 0.1f, 0.44f, 0.56f, DARK)
                sym(2.95f, 3.4f, 0.2f, 0.28f, 0.4f, STRUCT)
                sym(3.4f, 3.8f, 0.05f, 0.31f, 0.37f, DARK)
            }
            2 -> { // Kapal penjelajah: one gun, a bridge and a funnel.
                hull(0.32f, 0.22f, HULL)
                sym(0.42f, l - 0.22f, 0.26f, 0.22f, 0.26f, DECK)
                sym(0.3f, 0.6f, 0.045f, 0.29f, 0.34f, DARK)
                sym(0.6f, 0.95f, 0.17f, 0.26f, 0.36f, STRUCT)
                sym(1.1f, 1.8f, 0.2f, 0.26f, 0.44f, STRUCT)
                sym(1.25f, 1.6f, 0.13f, 0.44f, 0.5f, DECK)
                sym(1.95f, 2.2f, 0.09f, 0.26f, 0.5f, DARK)
                sym(2.4f, 2.7f, 0.14f, 0.26f, 0.34f, STRUCT)
            }
            3 -> { // Kapal selam: a low dark hull with a conning tower.
                sym(0f, 0.25f, 0.08f, 0f, 0.14f, SUB)
                sym(0.25f, 0.55f, 0.17f, 0f, 0.2f, SUB)
                sym(0.55f, l - 0.6f, 0.24f, 0f, 0.2f, SUB)
                sym(l - 0.6f, l - 0.25f, 0.17f, 0f, 0.2f, SUB)
                sym(l - 0.25f, l, 0.08f, 0f, 0.14f, SUB)
                sym(0.55f, l - 0.6f, 0.14f, 0.2f, 0.25f, DARK)
                sym(1.2f, 1.85f, 0.11f, 0.25f, 0.46f, SUB)
                sym(1.4f, 1.48f, 0.03f, 0.46f, 0.6f, DECK)
            }
            else -> { // Kapal perusak: small, one gun.
                hull(0.28f, 0.2f, HULL)
                sym(0.42f, l - 0.22f, 0.22f, 0.2f, 0.24f, DECK)
                sym(0.25f, 0.5f, 0.04f, 0.26f, 0.3f, DARK)
                sym(0.5f, 0.75f, 0.13f, 0.24f, 0.32f, STRUCT)
                sym(0.85f, 1.3f, 0.17f, 0.24f, 0.42f, STRUCT)
                sym(1.4f, 1.58f, 0.08f, 0.24f, 0.44f, DARK)
            }
        }
        return parts.sortedBy { it[5] }.flatMap { it.asList() }.toFloatArray()
    }

    /**
     * Voxel ship [kind] with its bow on ocean cell 0, running across (toward +x) or up the
     * grid (toward −z). Platforms move it to the ship's first cell.
     */
    fun shipMesh(kind: Int, horizontal: Boolean): VoxelMesh {
        val mesh = VoxelMesh()
        val c = cellCenter(0)
        val start = -CELL / 2 + SHIP_MARGIN
        val p = shipParts(kind)
        for (i in p.indices step 9) {
            val u0 = start + p[i]
            val u1 = start + p[i + 1]
            val rgb = floatArrayOf(p[i + 6], p[i + 7], p[i + 8])
            if (horizontal) {
                mesh.box(floatArrayOf(c[0] + u0, TOP + p[i + 4], c[2] + p[i + 2]), floatArrayOf(c[0] + u1, TOP + p[i + 5], c[2] + p[i + 3]), rgb)
            } else {
                mesh.box(floatArrayOf(c[0] + p[i + 2], TOP + p[i + 4], c[2] - u1), floatArrayOf(c[0] + p[i + 3], TOP + p[i + 5], c[2] - u0), rgb)
            }
        }
        return mesh
    }

    /** Battle: the corners of the small ocean, which must stay on screen. */
    fun miniBounds(): List<FloatArray> = bounds(0f).map {
        floatArrayOf(it[0] * MINI_SCALE + miniOffset[0], it[1] * MINI_SCALE, it[2] * MINI_SCALE + miniOffset[2])
    }

    fun pegMesh(cell: Int, hit: Boolean, originX: Float = 0f): VoxelMesh {
        val mesh = VoxelMesh()
        val c = cellCenter(cell, originX)
        val r = if (hit) 0.28f else 0.22f
        val h = if (hit) PEG_H else PEG_H * 0.6f
        mesh.box(
            floatArrayOf(c[0] - r, TOP, c[2] - r),
            floatArrayOf(c[0] + r, TOP + h, c[2] + r),
            if (hit) HIT else MISS,
        )
        return mesh
    }

    fun ringMesh(cell: Int, originX: Float = 0f): VoxelMesh {
        val mesh = VoxelMesh()
        val c = cellCenter(cell, originX)
        val half = CELL / 2 - GAP * 0.5f
        val t = 0.08f
        val y0 = TOP + 0.02f
        val y1 = y0 + 0.1f
        // Four sides of a square ring.
        mesh.box(floatArrayOf(c[0] - half, y0, c[2] - half), floatArrayOf(c[0] + half, y1, c[2] - half + t), GOLD)
        mesh.box(floatArrayOf(c[0] - half, y0, c[2] + half - t), floatArrayOf(c[0] + half, y1, c[2] + half), GOLD)
        mesh.box(floatArrayOf(c[0] - half, y0, c[2] - half), floatArrayOf(c[0] - half + t, y1, c[2] + half), GOLD)
        mesh.box(floatArrayOf(c[0] + half - t, y0, c[2] - half), floatArrayOf(c[0] + half, y1, c[2] + half), GOLD)
        return mesh
    }

    /** Cream label strip colour for platforms that draw A–J / 1–10 themselves. */
    fun cream(): FloatArray = CREAM.copyOf()
}
