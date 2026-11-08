package com.ardeman.krida.core

import com.ardeman.krida.core.games.UlarTanggaBot
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.games.UlarTanggaMove
import com.ardeman.krida.core.games.UlarTanggaState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UlarTanggaTest {
    private val game = UlarTanggaGame

    private fun at(vararg positions: Int, turn: Int = 0) =
        UlarTanggaState(positions = positions.toList(), turn = turn)

    @Test
    fun startsOffTheBoard() {
        val s = game.initialState(4, Random(0))
        assertEquals(listOf(0, 0, 0, 0), s.positions)
        assertEquals(0, s.turn)
        assertNull(s.lastRoll)
    }

    @Test
    fun plainMovePassesTheTurn() {
        val p = game.play(at(0, 0), 0, 2)
        assertEquals(listOf(1, 2), p.steps)
        assertNull(p.jumpTo)
        assertEquals(listOf(2, 0), p.state.positions)
        assertEquals(1, p.state.turn)
        assertEquals(2, p.state.lastRoll)
        assertEquals(0, p.state.lastSeat)
    }

    @Test
    fun ladderClimbsAndSnakeSlides() {
        val up = game.play(at(1, 0), 0, 3)
        assertEquals(4, up.steps.last())
        assertEquals(25, up.jumpTo)
        assertEquals(25, up.state.positions[0])

        val down = game.play(at(0, 24, turn = 1), 1, 3)
        assertEquals(5, down.state.positions[1])
    }

    @Test
    fun sixRollsAgain() {
        val p = game.play(at(0, 0), 0, 6)
        assertTrue(p.extraTurn)
        assertEquals(0, p.state.turn)
    }

    @Test
    fun overshootBouncesBack() {
        val p = game.play(at(98, 0), 0, 5)
        assertEquals(listOf(99, 100, 99, 98, 97), p.steps)
        // 97 is a snake head.
        assertEquals(61, p.state.positions[0])
        assertFalse(p.state.finished)
    }

    @Test
    fun exactHundredWins() {
        val p = game.play(at(10, 94, turn = 1), 1, 6)
        assertTrue(p.state.finished)
        assertEquals(1, p.state.winner)
        assertFalse(p.extraTurn)
        assertEquals("finished", game.validate(p.state, 1, UlarTanggaMove()))
    }

    @Test
    fun boardIsBoustrophedon() {
        assertEquals(0 to 0, game.column(1) to game.row(1))
        assertEquals(9 to 0, game.column(10) to game.row(10))
        assertEquals(9 to 1, game.column(11) to game.row(11))
        assertEquals(0 to 9, game.column(100) to game.row(100))
    }

    @Test
    fun snakesAndLaddersNeverChain() {
        val ends = game.LADDERS.values + game.SNAKES.values
        val starts = game.LADDERS.keys + game.SNAKES.keys
        assertTrue(ends.none { it in starts })
        assertTrue(game.LADDERS.all { (a, b) -> b > a })
        assertTrue(game.SNAKES.all { (a, b) -> b < a && a < UlarTanggaGame.LAST })
    }

    @Test
    fun botsPlayAFullFourPlayerGame() {
        val net = LoopbackNetwork()
        val host = HostSession(game, net.host, roomCode = "ULAR", random = Random(7))
        repeat(4) { host.addBot("Komputer ${it + 1}", Platform.ANDROID, UlarTanggaBot) }
        host.start()
        var moves = 0
        while (host.stepBot()) moves++
        val s = assertNotNull(host.state)
        assertTrue(s.finished)
        assertEquals(100, s.positions[assertNotNull(s.winner)])
        assertTrue(moves > 0)
    }
}
