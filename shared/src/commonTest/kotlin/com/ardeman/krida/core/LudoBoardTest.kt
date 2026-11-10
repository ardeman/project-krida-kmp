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
        assertEquals(listOf(6, 13), LudoBoard.trackCell(LudoGame.STARTS[0]).toList())
        assertEquals(listOf(1, 6), LudoBoard.trackCell(LudoGame.STARTS[1]).toList())
        assertEquals(listOf(8, 1), LudoBoard.trackCell(LudoGame.STARTS[2]).toList())
        assertEquals(listOf(13, 8), LudoBoard.trackCell(LudoGame.STARTS[3]).toList())
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
}
