package com.ardeman.krida.core.voxel

/**
 * Triangle mesh with per-vertex sRGB colors, built quad by quad. Lighting is baked into the
 * colors (tops brightest, sides darker), so platforms draw it unlit: Filament from a glTF,
 * RealityKit from a palette texture.
 */
class VoxelMesh {
    private val positions = ArrayList<Float>()
    private val colors = ArrayList<Float>()
    private val indices = ArrayList<Int>()

    val vertexCount: Int get() = positions.size / 3

    /** Adds a quad (corners in order around the face) with an sRGB [rgb] color. */
    fun quad(corners: Array<FloatArray>, rgb: FloatArray) {
        val base = vertexCount
        for (c in corners) {
            positions += c[0]; positions += c[1]; positions += c[2]
            colors += rgb[0]; colors += rgb[1]; colors += rgb[2]
        }
        indices += base; indices += base + 1; indices += base + 2
        indices += base; indices += base + 2; indices += base + 3
    }

    /** Axis-aligned box from [min] to [max], each face shaded by [shade] (top, x sides, z sides). */
    fun box(min: FloatArray, max: FloatArray, rgb: FloatArray, shade: FloatArray = DEFAULT_SHADE, bottom: Boolean = false) {
        val x0 = min[0]; val y0 = min[1]; val z0 = min[2]
        val x1 = max[0]; val y1 = max[1]; val z1 = max[2]
        fun s(f: Float) = floatArrayOf(rgb[0] * f, rgb[1] * f, rgb[2] * f)
        quad(arrayOf(p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0)), s(shade[0]))
        quad(arrayOf(p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1)), s(shade[1]))
        quad(arrayOf(p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0), p(x0, y0, z0)), s(shade[1]))
        quad(arrayOf(p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1)), s(shade[2]))
        quad(arrayOf(p(x1, y0, z0), p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0)), s(shade[2]))
        if (bottom) quad(arrayOf(p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1)), s(shade[2]))
    }

    /** x, y, z per vertex. */
    fun positionArray(): FloatArray = positions.toFloatArray()

    /** r, g, b (sRGB, 0..1) per vertex. */
    fun colorArray(): FloatArray = colors.toFloatArray()

    /** Triangles, counter-clockwise seen from the outside where it matters. */
    fun indexArray(): IntArray = indices.toIntArray()

    companion object {
        val DEFAULT_SHADE = floatArrayOf(1f, 0.78f, 0.88f)

        fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    }
}
