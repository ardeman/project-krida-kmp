package com.ardeman.krida.core.games

import com.ardeman.krida.core.voxel.VoxelMesh

/**
 * Voxel meshes and table geometry for gaple, shared so Android and iOS draw the same table.
 * World units are [GapleBoard] squares (a tile is 2 × 1), y up, z toward the camera; the
 * felt's top is at y = 0 and the round's first tile sits on its centre.
 */
object GapleScene {
    /** Half the felt, in squares: the line turns before [GapleBoard.HALF_WIDTH]. */
    const val FELT_X = 10f
    const val FELT_Z = 7f
    /** Wooden rim around the felt; face-down hands lie on it. */
    const val RIM = 2f
    private const val TABLE_H = 0.8f
    /** A tile's thickness. */
    const val TILE_H = 0.32f
    private const val TILE_INSET = 0.04f
    private const val PIP = 0.17f
    private const val PIP_H = 0.03f
    /** Spacing of face-down tiles in a hand on the rim. */
    const val HAND_STEP = 1.2f

    val cameraEye = floatArrayOf(0f, 26f, 11f)
    val cameraTarget = floatArrayOf(0f, 0f, 0.6f)
    const val CAMERA_VERTICAL_FOV_DEGREES = 40f

    private val WOOD = floatArrayOf(0.55f, 0.33f, 0.2f)
    private val FELT = floatArrayOf(0.17f, 0.45f, 0.32f)
    private val FELT_DARK = floatArrayOf(0.14f, 0.39f, 0.28f)
    private val IVORY = floatArrayOf(0.98f, 0.95f, 0.88f)
    private val PIP_DARK = floatArrayOf(0.16f, 0.13f, 0.12f)
    private val PIP_RED = floatArrayOf(0.75f, 0.16f, 0.18f)
    private val BACK = floatArrayOf(0.48f, 0.18f, 0.23f)
    private val BACK_EDGE = floatArrayOf(0.36f, 0.12f, 0.17f)
    private val GOLD = floatArrayOf(0.95f, 0.72f, 0.29f)
    private val TOP_ONLY = floatArrayOf(1f, 0.82f, 0.9f)

    /** Corners of the table with the rim, low and high, for fitting the camera. */
    fun bounds(): List<FloatArray> {
        val x = FELT_X + RIM
        val z = FELT_Z + RIM
        return listOf(0f, TILE_H).flatMap { y ->
            listOf(
                floatArrayOf(-x, y, -z), floatArrayOf(x, y, -z),
                floatArrayOf(-x, y, z), floatArrayOf(x, y, z),
            )
        }
    }

    /** Wooden table with a felt top, two tones in big squares so the tilt reads. */
    fun tableMesh(): VoxelMesh {
        val mesh = VoxelMesh()
        mesh.box(
            floatArrayOf(-FELT_X - RIM, -TABLE_H, -FELT_Z - RIM),
            floatArrayOf(FELT_X + RIM, 0f, FELT_Z + RIM),
            WOOD,
        )
        val step = 2f
        var x = -FELT_X
        var i = 0
        while (x < FELT_X - 0.01f) {
            var z = -FELT_Z
            var j = 0
            while (z < FELT_Z - 0.01f) {
                val rgb = if ((i + j) % 2 == 0) FELT else FELT_DARK
                mesh.box(
                    floatArrayOf(x, -0.02f, z),
                    floatArrayOf(minOf(x + step, FELT_X), 0.005f, minOf(z + step, FELT_Z)),
                    rgb, TOP_ONLY,
                )
                z += step
                j++
            }
            x += step
            i++
        }
        return mesh
    }

    /**
     * Tile [a]|[b] face up, lying along x: the [a] half at x = −0.5, the [b] half at +0.5,
     * centred on the origin, bottom at y = 0. [GapleScene.tileMatrix] turns and moves it.
     */
    fun tileMesh(a: Int, b: Int): VoxelMesh {
        val mesh = VoxelMesh()
        mesh.box(
            floatArrayOf(-1f + TILE_INSET, 0f, -0.5f + TILE_INSET),
            floatArrayOf(1f - TILE_INSET, TILE_H, 0.5f - TILE_INSET),
            IVORY,
        )
        // Divider across the middle.
        mesh.box(
            floatArrayOf(-0.025f, TILE_H, -0.36f),
            floatArrayOf(0.025f, TILE_H + PIP_H, 0.36f),
            PIP_DARK, TOP_ONLY,
        )
        pips(mesh, a, -0.5f)
        pips(mesh, b, 0.5f)
        return mesh
    }

    /** A face-down tile (the back), the same size and orientation as [tileMesh]. */
    fun backMesh(): VoxelMesh {
        val mesh = VoxelMesh()
        mesh.box(
            floatArrayOf(-1f + TILE_INSET, 0f, -0.5f + TILE_INSET),
            floatArrayOf(1f - TILE_INSET, TILE_H, 0.5f - TILE_INSET),
            BACK_EDGE,
        )
        mesh.box(
            floatArrayOf(-0.8f, TILE_H, -0.3f),
            floatArrayOf(0.8f, TILE_H + PIP_H, 0.3f),
            BACK, TOP_ONLY,
        )
        return mesh
    }

    /** A dashed gold square one square wide, around the origin, for an open end. */
    fun ringMesh(): VoxelMesh {
        val mesh = VoxelMesh()
        val r = 0.62f
        val t = 0.09f
        val dash = 0.32f
        val y0 = 0.01f
        val y1 = 0.06f
        // Three dashes per side: two at the corners, one in the middle.
        for (c in listOf(-r + dash / 2, 0f, r - dash / 2)) {
            mesh.box(floatArrayOf(c - dash / 2, y0, -r - t / 2), floatArrayOf(c + dash / 2, y1, -r + t / 2), GOLD, TOP_ONLY)
            mesh.box(floatArrayOf(c - dash / 2, y0, r - t / 2), floatArrayOf(c + dash / 2, y1, r + t / 2), GOLD, TOP_ONLY)
            mesh.box(floatArrayOf(-r - t / 2, y0, c - dash / 2), floatArrayOf(-r + t / 2, y1, c + dash / 2), GOLD, TOP_ONLY)
            mesh.box(floatArrayOf(r - t / 2, y0, c - dash / 2), floatArrayOf(r + t / 2, y1, c + dash / 2), GOLD, TOP_ONLY)
        }
        return mesh
    }

    /** Where an open end's ring sits: the square the next tile half would cover. */
    fun ringCenter(end: GapleEndPose): FloatArray =
        floatArrayOf(end.x + end.dirX * 0.5f, 0f, end.z + end.dirZ * 0.5f)

    /**
     * Column-major 4 × 4 matrix that lays a [tileMesh] of [tile] at [pose] for [placed] (the
     * line's tile, turned so its left value meets the left end), [lift] above the felt.
     */
    fun tileMatrix(tile: GapleTile, placed: GaplePlaced, pose: GapleTilePose, lift: Float = 0f): FloatArray {
        // The mesh carries tile.a at −x. Point −x at the half that shows tile.a.
        val aOnLeft = placed.left == tile.a
        val fromX = if (aOnLeft) pose.leftX else pose.rightX
        val fromZ = if (aOnLeft) pose.leftZ else pose.rightZ
        val toX = if (aOnLeft) pose.rightX else pose.leftX
        val toZ = if (aOnLeft) pose.rightZ else pose.leftZ
        return matrix(toX - fromX, toZ - fromZ, pose.centerX, lift, pose.centerZ)
    }

    /**
     * Seat [seat]'s side of the table as [viewer] sees it, clockwise from the bottom: 0 bottom
     * (the viewer), 1 left, 2 top, 3 right. Two players face each other; with three the others
     * sit left and right.
     */
    fun side(seat: Int, viewer: Int, players: Int): Int {
        val offset = ((seat - viewer) % players + players) % players
        return when (players) {
            2 -> if (offset == 0) 0 else 2
            3 -> listOf(0, 1, 3)[offset]
            else -> offset
        }
    }

    /**
     * Column-major matrix for face-down tile [index] of [count] in the hand on [side] (see
     * [side]); the tiles point at the table's centre and lie in a row along the rim.
     */
    fun handMatrix(side: Int, index: Int, count: Int): FloatArray {
        // Hands that grew by drawing close up so they stay on the rim.
        val span = if (side == 1 || side == 3) 2 * FELT_Z - 2f else 2 * FELT_X - 4f
        val step = if (count > 1) minOf(HAND_STEP, span / (count - 1)) else HAND_STEP
        val offset = (index - (count - 1) / 2f) * step
        val edge = RIM / 2
        return when (side) {
            1 -> matrix(1f, 0f, -FELT_X - edge, 0f, offset)
            3 -> matrix(1f, 0f, FELT_X + edge, 0f, offset)
            0 -> matrix(0f, 1f, offset, 0f, FELT_Z + edge)
            else -> matrix(0f, 1f, offset, 0f, -FELT_Z - edge)
        }
    }

    /** Where a played tile flies in from: the middle of [side]'s hand. */
    fun handCenter(side: Int): FloatArray {
        val edge = RIM / 2
        return when (side) {
            1 -> floatArrayOf(-FELT_X - edge, 0f, 0f)
            3 -> floatArrayOf(FELT_X + edge, 0f, 0f)
            0 -> floatArrayOf(0f, 0f, FELT_Z + edge)
            else -> floatArrayOf(0f, 0f, -FELT_Z - edge)
        }
    }

    /** Turns local +x toward ([dx], [dz]) (axis-aligned) and moves the origin to x, y, z. */
    private fun matrix(dx: Float, dz: Float, x: Float, y: Float, z: Float): FloatArray {
        val ux = if (dx > 0.01f) 1f else if (dx < -0.01f) -1f else 0f
        val uz = if (dz > 0.01f) 1f else if (dz < -0.01f) -1f else 0f
        return floatArrayOf(
            ux, 0f, uz, 0f,
            0f, 1f, 0f, 0f,
            -uz, 0f, ux, 0f,
            x, y, z, 1f,
        )
    }

    /** Pips of [value] on the half centred at x = [cx]; a lone pip is red, like many sets. */
    private fun pips(mesh: VoxelMesh, value: Int, cx: Float) {
        val rgb = if (value == 1) PIP_RED else PIP_DARK
        val d = 0.24f
        val spots = when (value) {
            1 -> listOf(0f to 0f)
            2 -> listOf(-d to -d, d to d)
            3 -> listOf(-d to -d, 0f to 0f, d to d)
            4 -> listOf(-d to -d, d to -d, -d to d, d to d)
            5 -> listOf(-d to -d, d to -d, 0f to 0f, -d to d, d to d)
            6 -> listOf(-d to -d, 0f to -d, d to -d, -d to d, 0f to d, d to d)
            else -> emptyList()
        }
        for ((px, pz) in spots) {
            mesh.box(
                floatArrayOf(cx + px - PIP / 2, TILE_H, pz - PIP / 2),
                floatArrayOf(cx + px + PIP / 2, TILE_H + PIP_H, pz + PIP / 2),
                rgb, TOP_ONLY,
            )
        }
    }
}
