package com.ardeman.krida.core.games

import com.ardeman.krida.core.voxel.VoxelMesh

/**
 * Perang kapal grids in world units (y up, camera on +z). One 10×10 ocean; battle HUD places
 * a second copy beside it for radar. Pure geometry so Android and iOS match.
 *
 * Cell 0 is bottom-left (nearest the camera, column A / row 1). Rows run toward −z.
 */
object PerangKapalBoard {
    const val CELL = 1.1f
    private const val HALF = PerangKapalGame.SIZE * CELL / 2
    private const val GAP = 0.06f
    private const val RIM = 0.45f
    private const val FRAME = 0.4f

    const val TOP = 0.45f
    private const val SHIP_H = 0.55f
    private const val PEG_H = 0.35f

    val cameraEye = floatArrayOf(0f, 22f, 16f)
    val cameraTarget = floatArrayOf(0f, 0f, 0f)
    const val CAMERA_VERTICAL_FOV_DEGREES = 40f

    /** Centre of the radar grid relative to the ocean (battle layout). */
    const val RADAR_OFFSET_X = PerangKapalGame.SIZE * CELL + 1.6f

    private val WOOD = floatArrayOf(0.64f, 0.42f, 0.24f)
    private val CREAM = floatArrayOf(0.99f, 0.95f, 0.85f)
    private val SEA = floatArrayOf(0.22f, 0.45f, 0.62f)
    private val SEA_DARK = floatArrayOf(0.14f, 0.32f, 0.48f)
    private val SHIP = floatArrayOf(0.56f, 0.33f, 0.16f)
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

    /** Voxel bar covering [ship]'s cells on the grid at [originX]. */
    fun shipMesh(ship: PerangKapalShip, originX: Float = 0f): VoxelMesh {
        val mesh = VoxelMesh()
        for (cell in ship.cells) {
            val c = cellCenter(cell, originX)
            val half = CELL / 2 - GAP - 0.08f
            mesh.box(
                floatArrayOf(c[0] - half, TOP, c[2] - half),
                floatArrayOf(c[0] + half, TOP + SHIP_H, c[2] + half),
                SHIP,
            )
        }
        return mesh
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
