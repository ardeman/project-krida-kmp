package com.ardeman.krida.core

import com.ardeman.krida.core.games.LudoBoard
import com.ardeman.krida.core.games.LudoGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LudoBoardTest {
    @Test
    fun trackHasFiftyTwoUniqueCellsMatchingColourStarts() {
        val cells = (0 until LudoGame.TRACK).map { LudoBoard.trackCell(it).toList() }
        assertEquals(LudoGame.TRACK, cells.toSet().size)
        // Classic layout: red bottom, blue left, green right, yellow top.
        assertEquals(listOf(6, 1), LudoBoard.trackCell(LudoGame.STARTS[0]).toList())
        assertEquals(listOf(1, 8), LudoBoard.trackCell(LudoGame.STARTS[1]).toList())
        assertEquals(listOf(13, 6), LudoBoard.trackCell(LudoGame.STARTS[2]).toList())
        assertEquals(listOf(8, 13), LudoBoard.trackCell(LudoGame.STARTS[3]).toList())
        assertEquals(intArrayOf(0, 13, 39, 26).toList(), LudoGame.STARTS.toList())
    }

    @Test
    fun tokenAnchorsSitOnTheBoardTop() {
        for (color in 0 until LudoGame.COLORS) {
            val yard = LudoBoard.tokenAnchor(color, 0, LudoGame.YARD)
            assertEquals(LudoBoard.YARD_SLOT_TOP, yard[1])
            val start = LudoBoard.tokenAnchor(color, 0, 0)
            assertEquals(LudoBoard.TOP, start[1])
            val home = LudoBoard.tokenAnchor(color, 0, LudoGame.FINISH)
            assertEquals(LudoBoard.TOP, home[1])
        }
    }

    @Test
    fun relativeStartMatchesAbsoluteTrackAnchor() {
        for (color in 0 until LudoGame.COLORS) {
            val fromToken = LudoBoard.tokenAnchor(color, 0, 0)
            val fromTrack = LudoBoard.trackAnchor(LudoGame.STARTS[color])
            assertTrue(fromToken.contentEquals(fromTrack))
        }
    }

    @Test
    fun viewYawPutsEachColourYardAtBottomLeft() {
        assertEquals(0, LudoBoard.VIEW_HOME_COLOR)
        assertEquals(0f, LudoBoard.viewYawDegrees(0)) // red
        assertEquals(270f, LudoBoard.viewYawDegrees(1)) // blue
        assertEquals(90f, LudoBoard.viewYawDegrees(2)) // green
        assertEquals(180f, LudoBoard.viewYawDegrees(3)) // yellow
        assertTrue(LudoBoard.cameraEyeFor(0).contentEquals(LudoBoard.cameraEye))
        val blueEye = LudoBoard.cameraEyeFor(1)
        assertEquals(-LudoBoard.cameraEye[2], blueEye[0], absoluteTolerance = 0.01f)
        assertEquals(LudoBoard.cameraEye[1], blueEye[1], absoluteTolerance = 0.01f)
        assertEquals(0f, blueEye[2], absoluteTolerance = 0.01f)
    }

    @Test
    fun starCellsMatchClassicBoard() {
        // Eight steps past each start, on the outer ring of each arm.
        assertEquals(listOf(2, 6), LudoBoard.trackCell(8).toList())
        assertEquals(listOf(6, 12), LudoBoard.trackCell(21).toList())
        assertEquals(listOf(12, 8), LudoBoard.trackCell(34).toList())
        assertEquals(listOf(8, 2), LudoBoard.trackCell(47).toList())
        assertTrue(LudoBoard.boardMesh().vertexCount > 0)
    }

    @Test
    fun pickRingMeshIsBuilt() {
        assertTrue(LudoBoard.ringMesh().vertexCount > 0)
    }

    @Test
    fun homeGateCellsSitOnTheOuterArmEnds() {
        // Cream cells just outside each finish lane (classic arrow marks).
        assertEquals(listOf(7, 0), LudoBoard.trackCell(50).toList()) // red gate
        assertEquals(listOf(0, 7), LudoBoard.trackCell(11).toList()) // blue gate
        assertEquals(listOf(14, 7), LudoBoard.trackCell(37).toList()) // green gate
        assertEquals(listOf(7, 14), LudoBoard.trackCell(24).toList()) // yellow gate
        assertTrue(LudoBoard.boardMesh().vertexCount > 0)
    }
}
