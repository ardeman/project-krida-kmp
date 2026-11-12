package com.ardeman.krida.core

import com.ardeman.krida.core.games.PerangKapalBoard
import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.PerangKapalShip
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val ship = PerangKapalShip(4, listOf(0, 1))
        assertTrue(PerangKapalBoard.shipMesh(ship).vertexCount > 0)
        assertTrue(PerangKapalBoard.pegMesh(0, hit = true).vertexCount > 0)
        assertTrue(PerangKapalBoard.pegMesh(1, hit = false).vertexCount > 0)
        assertTrue(PerangKapalBoard.ringMesh(5).vertexCount > 0)
        assertEquals(PerangKapalGame.SIZE * PerangKapalGame.SIZE, PerangKapalGame.CELLS)
    }
}
