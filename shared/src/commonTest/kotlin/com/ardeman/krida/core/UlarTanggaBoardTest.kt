package com.ardeman.krida.core

import com.ardeman.krida.core.games.UlarTanggaBoard
import com.ardeman.krida.core.games.UlarTanggaGame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UlarTanggaBoardTest {
    @Test
    fun squareOneIsNearLeftAndHundredFarLeft() {
        val one = UlarTanggaBoard.squareAnchor(1)
        val ten = UlarTanggaBoard.squareAnchor(10)
        val hundred = UlarTanggaBoard.squareAnchor(100)
        assertTrue(one[0] < ten[0])
        assertEquals(one[2], ten[2])
        assertTrue(hundred[2] < one[2], "100 is farther from the camera (+z) than 1")
        assertEquals(one[0], hundred[0])
    }

    @Test
    fun sharedSquaresSpreadPieces() {
        val spots = (0 until 4).map { UlarTanggaBoard.pieceSpot(37, it, shared = true).toList() }
        assertEquals(4, spots.toSet().size)
        assertEquals(UlarTanggaBoard.squareAnchor(37).toList(), UlarTanggaBoard.pieceSpot(37, 2, shared = false).toList())
        // The start pad always spreads them, and sits left of square 1.
        val pad = UlarTanggaBoard.pieceSpot(0, 0, shared = false)
        assertNotEquals(UlarTanggaBoard.pieceSpot(0, 1, shared = false).toList(), pad.toList())
        assertTrue(pad[0] < UlarTanggaBoard.squareAnchor(1)[0] - 2f)
    }

    @Test
    fun pathsRunFromStartToEnd() {
        UlarTanggaGame.SNAKES.forEach { (head, tail) ->
            val path = UlarTanggaBoard.snakePath(head)
            assertClose(UlarTanggaBoard.squareAnchor(head), path.first())
            assertClose(UlarTanggaBoard.squareAnchor(tail), path.last())
        }
        UlarTanggaGame.LADDERS.forEach { (foot, top) ->
            val path = UlarTanggaBoard.ladderPath(foot)
            assertClose(UlarTanggaBoard.squareAnchor(foot), path.first())
            assertClose(UlarTanggaBoard.squareAnchor(top), path.last())
        }
    }

    @Test
    fun meshesAreBuilt() {
        assertTrue(UlarTanggaBoard.boardMesh().vertexCount > 1000)
        repeat(UlarTanggaBoard.PIECES) { assertTrue(UlarTanggaBoard.pieceMesh(it).vertexCount > 0) }
    }

    private fun assertClose(expected: FloatArray, actual: FloatArray) {
        for (i in 0..2) assertTrue(abs(expected[i] - actual[i]) < 1e-3f, "${expected.toList()} vs ${actual.toList()}")
    }
}
