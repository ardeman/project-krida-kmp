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
            assertEquals(LudoBoard.TOP, yard[1])
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
    fun viewYawPutsEachColourYardTowardTheCameraRight() {
        assertEquals(0f, LudoBoard.viewYawDegrees(LudoBoard.VIEW_HOME_COLOR))
        assertTrue(LudoBoard.cameraEyeFor(LudoBoard.VIEW_HOME_COLOR).contentEquals(LudoBoard.cameraEye))
        // Red (0) needs a half turn from the default green bottom-right seat.
        assertEquals(-180f, LudoBoard.viewYawDegrees(0))
        val redEye = LudoBoard.cameraEyeFor(0)
        assertEquals(0f, redEye[0], absoluteTolerance = 0.01f)
        assertEquals(LudoBoard.cameraEye[1], redEye[1], absoluteTolerance = 0.01f)
        assertEquals(-LudoBoard.cameraEye[2], redEye[2], absoluteTolerance = 0.01f)
    }

    @Test
    fun starCellsMatchClassicBoard() {
        // Nine steps past each start, on the outer ring of each arm.
        assertEquals(listOf(1, 6), LudoBoard.trackCell(9).toList())
        assertEquals(listOf(6, 13), LudoBoard.trackCell(22).toList())
        assertEquals(listOf(13, 8), LudoBoard.trackCell(35).toList())
        assertEquals(listOf(8, 1), LudoBoard.trackCell(48).toList())
        assertTrue(LudoBoard.boardMesh().vertexCount > 0)
    }
}
