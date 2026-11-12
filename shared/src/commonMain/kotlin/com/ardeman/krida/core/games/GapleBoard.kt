package com.ardeman.krida.core.games

import kotlin.math.abs

/** Where one tile of the line lies, in square units (one half of a tile is 1 × 1). */
data class GapleTilePose(
    /** Index in [GapleState.line]. */
    val index: Int,
    /** Centre of the half that carries [GaplePlaced.left]. */
    val leftX: Float,
    val leftZ: Float,
    /** Centre of the half that carries [GaplePlaced.right]. */
    val rightX: Float,
    val rightZ: Float,
) {
    val centerX: Float get() = (leftX + rightX) / 2
    val centerZ: Float get() = (leftZ + rightZ) / 2

    /** Long side along x (otherwise along z). */
    val alongX: Boolean get() = leftZ == rightZ
}

/** An open end: the middle of the free edge and the direction the line grows from it. */
data class GapleEndPose(val x: Float, val z: Float, val dirX: Int, val dirZ: Int)

data class GapleLayout(
    val tiles: List<GapleTilePose>,
    /** Open ends; null on an empty line. */
    val left: GapleEndPose?,
    val right: GapleEndPose?,
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
)

/**
 * The line of play on the felt, as pure geometry so Android and iOS lay it out the same.
 * Units are tile halves (a tile is 2 × 1), x to the right, z toward the camera.
 *
 * The round's first tile sits in the middle; the right arm grows right and the left arm left.
 * When an arm would come within one square of [HALF_WIDTH], it turns with two corner tiles
 * (always laid lengthwise, doubles too) and runs back the other way one row further out: the
 * right arm snakes away from the camera (−z), the left arm toward it (+z). Elsewhere doubles
 * lie across the line. Rows are 2 squares apart, so arms never touch.
 */
object GapleBoard {
    /** Half the usable felt width, in squares. */
    const val HALF_WIDTH = 8f

    fun layout(line: List<GaplePlaced>, origin: Int, halfWidth: Float = HALF_WIDTH): GapleLayout {
        if (line.isEmpty()) return GapleLayout(emptyList(), null, null, 0f, 0f, 0f, 0f)
        val poses = arrayOfNulls<GapleTilePose>(line.size)
        val first = line[origin]
        poses[origin] = if (first.isDouble) {
            GapleTilePose(origin, 0f, -0.5f, 0f, 0.5f)
        } else {
            GapleTilePose(origin, -0.5f, 0f, 0.5f, 0f)
        }
        val reach = if (first.isDouble) 0.5f else 1f
        // Right arm: inner half is `left`.
        val right = Arm(reach, 0f, 1, 0, turnZ = -1, halfWidth)
        for (i in origin + 1 until line.size) {
            val (inner, outer) = right.place(line[i].isDouble)
            poses[i] = GapleTilePose(i, inner[0], inner[1], outer[0], outer[1])
        }
        // Left arm: inner half is `right`.
        val left = Arm(-reach, 0f, -1, 0, turnZ = 1, halfWidth)
        for (i in origin - 1 downTo 0) {
            val (inner, outer) = left.place(line[i].isDouble)
            poses[i] = GapleTilePose(i, outer[0], outer[1], inner[0], inner[1])
        }
        val tiles = poses.map { it!! }
        val xs = tiles.flatMap { listOf(it.leftX, it.rightX) }
        val zs = tiles.flatMap { listOf(it.leftZ, it.rightZ) }
        return GapleLayout(
            tiles = tiles,
            left = left.end(),
            right = right.end(),
            minX = xs.min() - 0.5f,
            maxX = xs.max() + 0.5f,
            minZ = zs.min() - 0.5f,
            maxZ = zs.max() + 0.5f,
        )
    }

    /** One arm's open end ([x], [z]) and direction, with the corner it is in, if any. */
    private class Arm(
        var x: Float,
        var z: Float,
        var dx: Int,
        var dz: Int,
        val turnZ: Int,
        val halfWidth: Float,
    ) {
        /** Corner tiles still to lay: 2 = turn toward [turnZ], 1 = turn back along x. */
        private var corner = 0

        fun end() = GapleEndPose(x, z, dx, dz)

        /** Lays the next tile; returns the centres of its inner and outer halves. */
        fun place(double: Boolean): Pair<FloatArray, FloatArray> {
            if (corner == 0 && dz == 0 && abs(x + 3 * dx) > halfWidth) corner = 2
            if (corner > 0) {
                // L-shaped: the inner half carries on, the outer half steps to the new direction.
                val (nx, nz) = if (corner == 2) 0 to turnZ else -dxBeforeTurn to 0
                if (corner == 2) dxBeforeTurn = dx
                val ix = x + dx * 0.5f
                val iz = z + dz * 0.5f
                val inner = floatArrayOf(ix, iz)
                val outer = floatArrayOf(ix + nx, iz + nz)
                x = ix + nx * 1.5f
                z = iz + nz * 1.5f
                dx = nx
                dz = nz
                corner--
                return inner to outer
            }
            if (double) {
                // Across the line: both halves level with the end, one either side.
                val cx = x + dx * 0.5f
                val cz = z + dz * 0.5f
                val px = dz.toFloat()
                val pz = dx.toFloat()
                x += dx
                z += dz
                return floatArrayOf(cx - px * 0.5f, cz - pz * 0.5f) to floatArrayOf(cx + px * 0.5f, cz + pz * 0.5f)
            }
            val inner = floatArrayOf(x + dx * 0.5f, z + dz * 0.5f)
            val outer = floatArrayOf(x + dx * 1.5f, z + dz * 1.5f)
            x += dx * 2
            z += dz * 2
            return inner to outer
        }

        private var dxBeforeTurn = 0
    }
}
