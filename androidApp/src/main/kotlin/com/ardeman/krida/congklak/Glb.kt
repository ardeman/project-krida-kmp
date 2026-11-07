package com.ardeman.krida.congklak

import com.ardeman.krida.core.games.CongklakBoard
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.voxel.VoxelMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/**
 * Writes a binary glTF (.glb) with one unlit, vertex-colored material and one node per
 * named mesh instance. Small on purpose: just what gltfio needs to draw voxels.
 */
class GlbWriter {
    private class Node(val name: String, val mesh: Int, val translation: FloatArray?)

    private val meshes = ArrayList<VoxelMesh>()
    private val nodes = ArrayList<Node>()

    /** Returns the mesh index to use with [node]. */
    fun mesh(mesh: VoxelMesh): Int { meshes += mesh; return meshes.lastIndex }

    fun node(name: String, mesh: Int, translation: FloatArray? = null) { nodes += Node(name, mesh, translation) }

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
            // glTF vertex colors are linear RGBA; the shared mesh is sRGB RGB.
            val rgb = m.colorArray()
            val rgba = FloatArray(m.vertexCount * 4) { i -> if (i % 4 == 3) 1f else srgbToLinear(rgb[i / 4 * 3 + i % 4]) }
            val colView = view(floats(rgba), ARRAY_BUFFER)
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
        nodes.forEach { n ->
            val node = JSONObject().put("name", n.name).put("mesh", n.mesh)
            n.translation?.let { t -> node.put("translation", JSONArray(t.map { it.toDouble() })) }
            jsonNodes.put(node)
        }

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

    companion object {
        fun srgbToLinear(c: Float) = c.coerceIn(0f, 1f).toDouble().pow(2.2).toFloat()

        private const val FLOAT = 5126
        private const val UNSIGNED_INT = 5125
        private const val ARRAY_BUFFER = 34962
        private const val ELEMENT_ARRAY_BUFFER = 34963
    }
}

/**
 * The congklak board, its seeds and a gold ring per hole as one .glb; seed nodes are named by
 * [seedName], ring nodes by [ringName] (stores have none).
 */
object CongklakGlb {
    fun seedName(i: Int) = "seed$i"
    fun ringName(pit: Int) = "ring$pit"

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node("board", glb.mesh(CongklakBoard.boardMesh()))
        val ring = glb.mesh(CongklakBoard.ringMesh())
        for (pit in 0 until CongklakGame.PITS) {
            if (!CongklakGame.isStore(pit)) glb.node(ringName(pit), ring, CongklakBoard.ringOrigin(pit))
        }
        val seedMeshes = (0 until CongklakBoard.SEED_VARIANTS).map { glb.mesh(CongklakBoard.seedMesh(it)) }
        repeat(CongklakBoard.SEED_COUNT) { glb.node(seedName(it), seedMeshes[CongklakBoard.seedVariant(it)]) }
        return glb.build()
    }
}
