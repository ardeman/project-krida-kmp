package com.ardeman.krida.core

import com.ardeman.krida.core.games.GapleBoard
import com.ardeman.krida.core.games.GaplePlaced
import com.ardeman.krida.core.games.GapleScene
import com.ardeman.krida.core.games.GapleTile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GapleSceneTest {

    /** Where the tile mesh's local point (x, 0, 0) lands under a column-major [m]. */
    private fun apply(m: FloatArray, x: Float) = floatArrayOf(m[0] * x + m[12], m[2] * x + m[14])

    private fun close(a: Float, b: Float) = abs(a - b) < 1e-4f

    @Test
    fun tileHalvesLandOnTheirPoses() {
        // 6|6 first, then 6|4 on the right (shows 6 on its left), then 4|2 turned (tile 2|4).
        val line = listOf(GaplePlaced(6, 6), GaplePlaced(6, 4), GaplePlaced(4, 2))
        val layout = GapleBoard.layout(line, 0)
        val tiles = listOf(GapleTile(6, 6), GapleTile(4, 6), GapleTile(2, 4))
        for (i in line.indices) {
            val pose = layout.tiles[i]
            val m = GapleScene.tileMatrix(tiles[i], line[i], pose)
            val aHalf = apply(m, -0.5f)
            val bHalf = apply(m, 0.5f)
            // The half carrying tile.a sits where the line shows that value.
            val aX = if (line[i].left == tiles[i].a) pose.leftX else pose.rightX
            val aZ = if (line[i].left == tiles[i].a) pose.leftZ else pose.rightZ
            val bX = if (line[i].left == tiles[i].a) pose.rightX else pose.leftX
            val bZ = if (line[i].left == tiles[i].a) pose.rightZ else pose.leftZ
            assertTrue(close(aHalf[0], aX) && close(aHalf[1], aZ), "tile $i a half")
            assertTrue(close(bHalf[0], bX) && close(bHalf[1], bZ), "tile $i b half")
        }
    }

    @Test
    fun sidesGoClockwiseFromTheViewer() {
        assertEquals(listOf(0, 2), (0..1).map { GapleScene.side(it, 0, 2) })
        assertEquals(listOf(0, 1, 3), (0..2).map { GapleScene.side(it, 0, 3) })
        assertEquals(listOf(3, 0, 1, 2), (0..3).map { GapleScene.side(it, 1, 4) })
    }

    @Test
    fun lineStaysOnTheFelt() {
        val line = List(27) { GaplePlaced(1, 2) } + GaplePlaced(2, 2)
        for (origin in listOf(0, 13, 27)) {
            val l = GapleBoard.layout(line, origin)
            assertTrue(l.minX >= -GapleScene.FELT_X && l.maxX <= GapleScene.FELT_X, "x, origin $origin")
            assertTrue(l.minZ >= -GapleScene.FELT_Z && l.maxZ <= GapleScene.FELT_Z, "z, origin $origin")
        }
    }

    @Test
    fun nearHandBoundsHoldYourWholeHand() {
        val box = GapleScene.nearHandBounds()
        val maxX = box.maxOf { it[0] }
        val minZ = box.minOf { it[2] }
        val maxZ = box.maxOf { it[2] }
        val maxY = box.maxOf { it[1] }
        for (count in listOf(1, 7, 14)) for (i in 0 until count) {
            val m = GapleScene.handMatrix(0, i, count, GapleScene.HAND_LIFT_PICKING, GapleScene.HAND_SCALE)
            // Tile corners: local x ±1 runs along z, local z ±0.5 along x, scaled.
            for (lx in listOf(-1f, 1f)) for (lz in listOf(-0.5f, 0.5f)) {
                val x = m[0] * lx + m[8] * lz + m[12]
                val z = m[2] * lx + m[10] * lz + m[14]
                assertTrue(abs(x) <= maxX + 1e-4f, "x, $i of $count")
                assertTrue(z >= minZ - 1e-4f && z <= maxZ + 1e-4f, "z, $i of $count")
            }
            assertTrue(m[13] + GapleScene.TILE_H * GapleScene.HAND_SCALE <= maxY + 1e-4f, "y, $i of $count")
        }
    }
}
