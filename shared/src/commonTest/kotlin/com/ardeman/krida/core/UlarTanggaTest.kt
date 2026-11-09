package com.ardeman.krida.core

import com.ardeman.krida.core.games.UlarTanggaBot
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.games.UlarTanggaMove
import com.ardeman.krida.core.games.UlarTanggaOptions
import com.ardeman.krida.core.games.UlarTanggaRules
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

    private fun at(
        vararg positions: Int,
        turn: Int = 0,
        finishOrder: List<Int> = emptyList(),
        options: UlarTanggaOptions = UlarTanggaOptions(),
    ) = UlarTanggaState(positions.toList(), turn, finishOrder = finishOrder, options = options)

    @Test
    fun startsOffTheBoardWaitingForSeatZerosThrow() {
        val s = game.initialState(4, Random(1))
        assertEquals(listOf(0, 0, 0, 0), s.positions)
        assertTrue(s.opening)
        assertEquals(0, s.turn)
        assertEquals(listOf(listOf(0, 0, 0, 0)), s.openingRolls)
        assertNull(s.lastRoll)
    }

    private fun throwAll(s0: UlarTanggaState, vararg rolls: Int): UlarTanggaState =
        rolls.fold(s0) { s, roll -> game.diceOffThrow(s, s.turn, roll) }

    @Test
    fun diceOffSetsTheWholePlayOrder() {
        val s = throwAll(game.initialState(4, Random(1)), 2, 5, 3, 6)
        assertFalse(s.opening)
        assertEquals(listOf(3, 1, 2, 0), s.order)
        assertEquals(3, s.turn)
        assertEquals(3, s.firstTurn)
        // Turns follow the order, not the seats.
        assertEquals(1, game.play(s, 3, 2).state.turn)
        assertEquals(2, game.play(game.play(s, 3, 2).state, 1, 2).state.turn)
    }

    @Test
    fun tiedSeatsThrowAgainBetweenThemselves() {
        val start = game.initialState(4, Random(1))
        // Seats 0 and 2 tie on 4, seats 1 and 3 on 2.
        var s = throwAll(start, 4, 2, 4, 2)
        assertTrue(s.opening)
        assertEquals(2, s.openingRolls.size)
        assertEquals(0, s.turn)
        s = throwAll(s, 3)
        assertEquals(1, s.turn)
        s = throwAll(s, 5)
        assertEquals(2, s.turn)
        // Seat 2 ties seat 0 again; seat 3 beats seat 1.
        s = throwAll(s, 3, 6)
        assertTrue(s.opening)
        assertEquals(listOf(3, 5, 3, 6), s.openingRolls[1])
        assertEquals(listOf(0, 0, 0, 0), s.openingRolls[2])
        assertEquals(0, s.turn)
        s = throwAll(s, 1, 6)
        assertFalse(s.opening)
        assertEquals(listOf(2, 0, 3, 1), s.order)
    }

    @Test
    fun diceOffThrowBetweenFindsTheThrow() {
        val a = game.initialState(2, Random(1))
        val b = game.diceOffThrow(a, 0, 4)
        assertEquals(0 to 4, game.diceOffThrowBetween(a, b))
        val c = game.diceOffThrow(b, 1, 4)
        assertEquals(1 to 4, game.diceOffThrowBetween(b, c))
        assertTrue(c.opening)
        assertNull(game.diceOffThrowBetween(game.play(at(0, 0), 0, 2).state, at(0, 0)))
    }

    @Test
    fun hostRunsTheDiceOffThroughMoves() {
        val net = LoopbackNetwork()
        val host = HostSession(UlarTanggaRules(UlarTanggaOptions()), net.host, roomCode = "ULAR", random = Random(5))
        repeat(3) { host.addBot("Komputer ${it + 1}", Platform.ANDROID, UlarTanggaBot) }
        host.start()
        while (host.state!!.opening) assertTrue(host.stepBot())
        val s = host.state!!
        assertEquals(setOf(0, 1, 2), s.order.toSet())
        assertEquals(s.order[0], s.turn)
        assertNull(s.lastRoll)
    }

    @Test
    fun stateWithoutOpeningRollsStillDecodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString(UlarTanggaState.serializer(), "{\"positions\":[0,0],\"turn\":1,\"firstTurn\":1}")
        assertEquals(emptyList(), s.openingRolls)
        assertEquals(1, s.turn)
        assertEquals(emptyList(), s.colors)
        assertEquals(listOf(0, 1), listOf(s.color(0), s.color(1)))
    }

    @Test
    fun seatColorsKeepChoicesAndFillTheRest() {
        assertEquals(listOf(0, 1, 2), UlarTanggaGame.seatColors(3, emptyList()))
        assertEquals(listOf(3, 1), UlarTanggaGame.seatColors(2, listOf(3, 1, 0, 2)))
        // A repeat or out-of-range color is skipped; the seats left take free colors in order.
        assertEquals(listOf(2, 0, 1, 3), UlarTanggaGame.seatColors(4, listOf(2, 2, 9)))
    }

    @Test
    fun chosenColorsReachTheStateAndSurviveARestart() {
        val net = LoopbackNetwork()
        val host = HostSession(UlarTanggaRules(UlarTanggaOptions()), net.host, roomCode = "ULAR", random = Random(3))
        repeat(2) { host.addBot("Komputer ${it + 1}", Platform.ANDROID, UlarTanggaBot) }
        assertTrue(host.setColor(0, 3))
        assertTrue(host.setColor(1, 0))
        host.start()
        assertEquals(listOf(3, 0), host.state!!.colors)
        assertEquals(3, host.state!!.color(0))
        while (host.stepBot()) Unit
        host.restart()
        assertEquals(listOf(3, 0), host.state!!.colors)
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
    fun sixRollsAgainUnlessSwitchedOff() {
        val p = game.play(at(0, 0), 0, 6)
        assertTrue(p.extraTurn)
        assertEquals(0, p.state.turn)

        val off = game.play(at(0, 0, options = UlarTanggaOptions(sixRollsAgain = false)), 0, 6)
        assertFalse(off.extraTurn)
        assertEquals(1, off.state.turn)
    }

    @Test
    fun overshootStaysPut() {
        val p = game.play(at(98, 0), 0, 5)
        assertTrue(p.steps.isEmpty())
        assertNull(p.jumpTo)
        assertEquals(98, p.state.positions[0])
        assertEquals(1, p.state.turn)
    }

    @Test
    fun overshootBouncesBackAsAnOption() {
        val p = game.play(at(98, 0, options = UlarTanggaOptions(bounceBack = true)), 0, 5)
        assertEquals(listOf(99, 100, 99, 98, 97), p.steps)
        // 97 is a snake head.
        assertEquals(61, p.state.positions[0])
    }

    @Test
    fun sixToEnterAsAnOption() {
        val o = UlarTanggaOptions(sixToEnter = true)
        val miss = game.play(at(0, 0, options = o), 0, 5)
        assertTrue(miss.steps.isEmpty())
        assertEquals(0, miss.state.positions[0])
        assertEquals(1, miss.state.turn)

        val enter = game.play(at(0, 0, options = o), 0, 6)
        assertEquals(listOf(1), enter.steps)
        assertEquals(1, enter.state.positions[0])
        assertEquals(0, enter.state.turn)
    }

    @Test
    fun playGoesOnUntilOnePlayerIsLeft() {
        // Seat 1 finishes first; seats 0 and 2 play on, skipping seat 1.
        val first = game.play(at(10, 94, 20, turn = 1), 1, 6)
        assertFalse(first.extraTurn)
        assertFalse(first.state.finished)
        assertEquals(listOf(1), first.state.finishOrder)
        assertEquals(2, first.state.turn)

        val skip = game.play(first.state.copy(turn = 0), 0, 1)
        assertEquals(2, skip.state.turn)

        // Seat 2 finishes second: only seat 0 is left, so the game ends.
        val last = game.play(skip.state.copy(positions = listOf(11, 100, 96), turn = 2), 2, 5)
        assertEquals(96, last.state.positions[2]) // overshoot: stays
        val done = game.play(last.state.copy(positions = listOf(11, 100, 96), turn = 2), 2, 4)
        assertTrue(done.state.finished)
        assertEquals(listOf(1, 2), done.state.finishOrder)
        assertEquals(1, done.state.winner)
        assertEquals(listOf(1, 2, 0), game.ranking(done.state))
        assertEquals("finished", game.validate(done.state, 0, UlarTanggaMove()))
    }

    @Test
    fun firstFinishEndsAsAnOption() {
        val o = UlarTanggaOptions(firstFinishEnds = true)
        val p = game.play(at(10, 94, 20, turn = 1, options = o), 1, 6)
        assertTrue(p.state.finished)
        assertEquals(1, p.state.winner)
        assertEquals(listOf(1, 2, 0), game.ranking(p.state))
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
        val rules = UlarTanggaRules(UlarTanggaOptions(sixToEnter = true))
        val host = HostSession(rules, net.host, roomCode = "ULAR", random = Random(7))
        repeat(4) { host.addBot("Komputer ${it + 1}", Platform.ANDROID, UlarTanggaBot) }
        host.start()
        assertTrue(host.state!!.options.sixToEnter)
        var moves = 0
        while (host.stepBot()) moves++
        val s = assertNotNull(host.state)
        assertTrue(s.finished)
        assertEquals(3, s.finishOrder.size)
        assertTrue(s.finishOrder.all { s.positions[it] == 100 })
        assertTrue(moves > 0)
    }
}
