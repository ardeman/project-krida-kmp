package com.ardeman.krida.congklak

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/** Triangle mesh with per-vertex colors, built quad by quad. */
class VoxelMesh {
    private val positions = ArrayList<Float>()
    private val colors = ArrayList<Float>()
    private val indices = ArrayList<Int>()

    val vertexCount get() = positions.size / 3

    /** Adds a quad (corners in order around the face) with an sRGB [rgb] color. */
    fun quad(corners: Array<FloatArray>, rgb: FloatArray) {
        val base = vertexCount
        for (c in corners) {
            positions += c[0]; positions += c[1]; positions += c[2]
            // glTF vertex colors are linear.
            colors += srgbToLinear(rgb[0]); colors += srgbToLinear(rgb[1]); colors += srgbToLinear(rgb[2]); colors += 1f
        }
        indices += base; indices += base + 1; indices += base + 2
        indices += base; indices += base + 2; indices += base + 3
    }

    /** Axis-aligned box from [min] to [max], each face shaded by [shade] (top, x sides, z sides). */
    fun box(min: FloatArray, max: FloatArray, rgb: FloatArray, shade: FloatArray = DEFAULT_SHADE, bottom: Boolean = false) {
        val (x0, y0, z0) = min
        val (x1, y1, z1) = max
        fun s(f: Float) = floatArrayOf(rgb[0] * f, rgb[1] * f, rgb[2] * f)
        quad(arrayOf(f(x0, y1, z0), f(x0, y1, z1), f(x1, y1, z1), f(x1, y1, z0)), s(shade[0]))
        quad(arrayOf(f(x1, y0, z0), f(x1, y1, z0), f(x1, y1, z1), f(x1, y0, z1)), s(shade[1]))
        quad(arrayOf(f(x0, y0, z1), f(x0, y1, z1), f(x0, y1, z0), f(x0, y0, z0)), s(shade[1]))
        quad(arrayOf(f(x0, y0, z1), f(x1, y0, z1), f(x1, y1, z1), f(x0, y1, z1)), s(shade[2]))
        quad(arrayOf(f(x1, y0, z0), f(x0, y0, z0), f(x0, y1, z0), f(x1, y1, z0)), s(shade[2]))
        if (bottom) quad(arrayOf(f(x0, y0, z0), f(x1, y0, z0), f(x1, y0, z1), f(x0, y0, z1)), s(shade[2]))
    }

    internal fun positionArray() = positions.toFloatArray()
    internal fun colorArray() = colors.toFloatArray()
    internal fun indexArray() = indices.toIntArray()

    companion object {
        /** Baked light: tops brightest, sides darker, so unlit voxels still read as 3D. */
        val DEFAULT_SHADE = floatArrayOf(1f, 0.78f, 0.88f)

        fun f(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        fun srgbToLinear(c: Float) = c.coerceIn(0f, 1f).toDouble().pow(2.2).toFloat()
    }
}

/**
 * Writes a binary glTF (.glb) with one unlit, vertex-colored material and one node per
 * named mesh instance. Small on purpose: just what gltfio needs to draw voxels.
 */
class GlbWriter {
    private class Node(val name: String, val mesh: Int)

    private val meshes = ArrayList<VoxelMesh>()
    private val nodes = ArrayList<Node>()

    /** Returns the mesh index to use with [node]. */
    fun mesh(mesh: VoxelMesh): Int { meshes += mesh; return meshes.lastIndex }

    fun node(name: String, mesh: Int) { nodes += Node(name, mesh) }

    fun build(): ByteBuffer {
        val bin = java.io.ByteArrayOutputStream()
        val views = JSONArray()
        val accessors = JSONArray()
        val jsonMeshes = JSONArray()

        fun view(bytes: ByteArray, target: Int): Int {
            views.put(JSONObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", bytes.size).put("target", target))
            bin.write(bytes)
            return views.length() - 1
        }

        for (m in meshes) {
            val pos = m.positionArray()
            val min = FloatArray(3) { Float.MAX_VALUE }
            val max = FloatArray(3) { -Float.MAX_VALUE }
            for (i in pos.indices) { min[i % 3] = minOf(min[i % 3], pos[i]); max[i % 3] = maxOf(max[i % 3], pos[i]) }

            val posView = view(floats(pos), ARRAY_BUFFER)
            accessors.put(
                JSONObject().put("bufferView", posView).put("componentType", FLOAT).put("count", m.vertexCount)
                    .put("type", "VEC3").put("min", JSONArray(min.map { it.toDouble() })).put("max", JSONArray(max.map { it.toDouble() })),
            )
            val colView = view(floats(m.colorArray()), ARRAY_BUFFER)
            accessors.put(JSONObject().put("bufferView", colView).put("componentType", FLOAT).put("count", m.vertexCount).put("type", "VEC4"))
            val idx = m.indexArray()
            val idxView = view(ints(idx), ELEMENT_ARRAY_BUFFER)
            accessors.put(JSONObject().put("bufferView", idxView).put("componentType", UNSIGNED_INT).put("count", idx.size).put("type", "SCALAR"))

            val a = accessors.length()
            val primitive = JSONObject()
                .put("attributes", JSONObject().put("POSITION", a - 3).put("COLOR_0", a - 2))
                .put("indices", a - 1)
                .put("material", 0)
            jsonMeshes.put(JSONObject().put("primitives", JSONArray().put(primitive)))
        }

        val jsonNodes = JSONArray()
        nodes.forEach { jsonNodes.put(JSONObject().put("name", it.name).put("mesh", it.mesh)) }

        val material = JSONObject()
            .put("pbrMetallicRoughness", JSONObject().put("baseColorFactor", JSONArray(listOf(1, 1, 1, 1))).put("metallicFactor", 0).put("roughnessFactor", 1))
            .put("doubleSided", true)
            .put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))

        val gltf = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "krida"))
            .put("extensionsUsed", JSONArray().put("KHR_materials_unlit"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray(nodes.indices.toList()))))
            .put("nodes", jsonNodes)
            .put("meshes", jsonMeshes)
            .put("materials", JSONArray().put(material))
            .put("accessors", accessors)
            .put("bufferViews", views)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))

        val json = pad(gltf.toString().toByteArray(Charsets.UTF_8), ' '.code.toByte())
        val data = pad(bin.toByteArray(), 0)
        val total = 12 + 8 + json.size + 8 + data.size
        return ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(total)
            putInt(json.size); putInt(0x4E4F534A); put(json)
            putInt(data.size); putInt(0x004E4942); put(data)
            flip()
        }
    }

    private fun floats(a: FloatArray): ByteArray =
        ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(a) }.array()

    private fun ints(a: IntArray): ByteArray =
        ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(a) }.array()

    private fun pad(bytes: ByteArray, fill: Byte): ByteArray {
        val size = (bytes.size + 3) / 4 * 4
        return if (size == bytes.size) bytes else bytes.copyOf(size).also { it.fill(fill, bytes.size, size) }
    }

    private companion object {
        const val FLOAT = 5126
        const val UNSIGNED_INT = 5125
        const val ARRAY_BUFFER = 34962
        const val ELEMENT_ARRAY_BUFFER = 34963
    }
}
