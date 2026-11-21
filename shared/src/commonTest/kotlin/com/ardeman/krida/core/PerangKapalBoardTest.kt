package com.ardeman.krida.core

import com.ardeman.krida.core.games.PerangKapalBoard
import com.ardeman.krida.core.games.PerangKapalGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerangKapalBoardTest {
    @Test
    fun cellZeroIsBottomLeft() {
        val c = PerangKapalBoard.cellCenter(0)
        assertTrue(c[0] < 0)
        assertTrue(c[2] > 0)
    }

    @Test
    fun gridMeshHasVertices() {
        val mesh = PerangKapalBoard.gridMesh()
        assertTrue(mesh.vertexCount > 100)
        val radar = PerangKapalBoard.gridMesh(PerangKapalBoard.RADAR_OFFSET_X)
        assertTrue(radar.vertexCount > 100)
    }

    @Test
    fun shipAndPegMeshes() {
        // Every ship stays inside the cells it covers and under the deck height pegs stand on
        // (thin masts may go higher).
        for (kind in 0 until PerangKapalGame.KINDS) {
            assertTrue(PerangKapalBoard.shipMesh(kind, horizontal = true).vertexCount > 0)
            assertTrue(PerangKapalBoard.shipMesh(kind, horizontal = false).vertexCount > 0)
            val parts = PerangKapalBoard.shipParts(kind)
            assertEquals(0, parts.size % 9)
            for (i in parts.indices step 9) {
                assertTrue(parts[i] >= 0f && parts[i + 1] <= PerangKapalBoard.shipLength(kind) + 1e-4f)
                assertTrue(parts[i + 2] >= -PerangKapalBoard.CELL / 2 && parts[i + 3] <= PerangKapalBoard.CELL / 2)
                assertTrue(parts[i] < parts[i + 1] && parts[i + 2] < parts[i + 3] && parts[i + 4] < parts[i + 5])
            }
        }
        // The small ocean sits left of the radar without touching it.
        val radarLeft = PerangKapalBoard.bounds(PerangKapalBoard.RADAR_OFFSET_X).minOf { it[0] }
        assertTrue(PerangKapalBoard.miniBounds().maxOf { it[0] } < radarLeft)
        assertTrue(PerangKapalBoard.pegMesh(0, hit = true).vertexCount > 0)
        assertTrue(PerangKapalBoard.pegMesh(1, hit = false).vertexCount > 0)
        assertTrue(PerangKapalBoard.ringMesh(5).vertexCount > 0)
        assertEquals(PerangKapalGame.SIZE * PerangKapalGame.SIZE, PerangKapalGame.CELLS)
    }

    @Test
    fun cellAtInvertsCellCenter() {
        val origin = PerangKapalBoard.RADAR_OFFSET_X
        for (cell in 0 until PerangKapalGame.CELLS) {
            val c = PerangKapalBoard.cellCenter(cell, origin)
            assertEquals(cell, PerangKapalBoard.cellAt(c[0], c[2], origin))
            // Near a corner, still inside the cell.
            val e = PerangKapalBoard.CELL * 0.45f
            assertEquals(cell, PerangKapalBoard.cellAt(c[0] - e, c[2] + e, origin))
            assertEquals(cell, PerangKapalBoard.cellAt(c[0] + e, c[2] - e, origin))
        }
    }

    @Test
    fun cellAtSnapsJustOutsideAndIgnoresFarAway() {
        val c = PerangKapalBoard.cellCenter(0, 0f)
        val cell = PerangKapalBoard.CELL
        assertEquals(0, PerangKapalBoard.cellAt(c[0] - cell * 0.7f, c[2] + cell * 0.7f, 0f))
        assertNull(PerangKapalBoard.cellAt(c[0] - cell, c[2], 0f))
        assertNull(PerangKapalBoard.cellAt(0f, 0f, PerangKapalBoard.RADAR_OFFSET_X * 3))
    }
}
