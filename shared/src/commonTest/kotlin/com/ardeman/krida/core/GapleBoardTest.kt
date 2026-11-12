package com.ardeman.krida.core

import com.ardeman.krida.core.games.GapleBoard
import com.ardeman.krida.core.games.GaplePlaced
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapleBoardTest {

    /** A random line of [n] tiles with the first tile at [origin]; ~1 in 5 tiles is a double. */
    private fun line(n: Int, random: Random): List<GaplePlaced> =
        List(n) { if (random.nextInt(5) == 0) GaplePlaced(3, 3) else GaplePlaced(1, 2) }

    /** Unit squares each tile covers (a double across covers two squares side by side). */
    private fun squares(x: Float, z: Float) = (x * 2).toInt() to (z * 2).toInt()

    @Test
    fun emptyLineHasNoEnds() {
        val layout = GapleBoard.layout(emptyList(), 0)
        assertTrue(layout.tiles.isEmpty())
        assertNull(layout.left)
        assertNull(layout.right)
    }

    @Test
    fun firstTileSitsInTheMiddle() {
        val layout = GapleBoard.layout(listOf(GaplePlaced(6, 4)), 0)
        val tile = layout.tiles.single()
        assertEquals(0f, tile.centerX)
        assertEquals(0f, tile.centerZ)
        assertTrue(tile.alongX)
        assertEquals(-1f, layout.left!!.x)
        assertEquals(1f, layout.right!!.x)
        val double = GapleBoard.layout(listOf(GaplePlaced(6, 6)), 0).tiles.single()
        assertTrue(!double.alongX)
    }

    @Test
    fun doublesLieAcrossOnAStraight() {
        val layout = GapleBoard.layout(listOf(GaplePlaced(6, 4), GaplePlaced(4, 4)), 0)
        val double = layout.tiles[1]
        assertTrue(!double.alongX)
        assertEquals(1.5f, double.centerX)
        assertEquals(2f, layout.right!!.x)
    }

    @Test
    fun longLinesSnakeWithoutOverlapAndStayInside() {
        for (seed in 0 until 40) {
            val random = Random(seed)
            val n = 2 + random.nextInt(27)
            val origin = random.nextInt(n)
            val tiles = line(n, random)
            val layout = GapleBoard.layout(tiles, origin)
            val seen = HashSet<Pair<Int, Int>>()
            for (pose in layout.tiles) {
                for ((x, z) in listOf(pose.leftX to pose.leftZ, pose.rightX to pose.rightZ)) {
                    assertTrue(abs(x) + 0.5f <= GapleBoard.HALF_WIDTH, "seed $seed x=$x")
                    assertTrue(seen.add(squares(x, z)), "seed $seed overlap at $x,$z")
                }
                // The two halves of a tile are neighbours.
                assertEquals(1f, abs(pose.leftX - pose.rightX) + abs(pose.leftZ - pose.rightZ))
            }
            // Neighbouring tiles touch: the right half of tile i meets the left half of i + 1.
            for (i in 0 until n - 1) {
                val a = layout.tiles[i]
                val b = layout.tiles[i + 1]
                val gap = minOf(
                    abs(a.rightX - b.leftX) + abs(a.rightZ - b.leftZ),
                    // A double across touches with its middle edge.
                    abs(a.centerX - b.centerX) + abs(a.centerZ - b.centerZ) - 0.5f,
                )
                assertTrue(gap <= 1.5f, "seed $seed tiles $i,${i + 1} apart")
            }
        }
    }

    @Test
    fun armsTurnAwayFromEachOther() {
        val layout = GapleBoard.layout(List(25) { GaplePlaced(1, 2) }, 12)
        assertTrue(layout.tiles.take(12).any { it.centerZ > 0.5f }, "left arm turns toward the camera")
        assertTrue(layout.tiles.drop(13).any { it.centerZ < -0.5f }, "right arm turns away")
        assertTrue(layout.minZ < -1f && layout.maxZ > 1f)
    }
}
