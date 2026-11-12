package com.ardeman.krida.core

import com.ardeman.krida.core.games.LudoBot
import com.ardeman.krida.core.games.LudoCapture
import com.ardeman.krida.core.games.LudoGame
import com.ardeman.krida.core.games.LudoMove
import com.ardeman.krida.core.games.LudoOptions
import com.ardeman.krida.core.games.LudoRules
import com.ardeman.krida.core.games.LudoState
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

class LudoTest {
    private val game = LudoGame

    private fun at(
        vararg seats: List<Int>,
        turn: Int = 0,
        roll: Int? = null,
        sixes: Int = 0,
        options: LudoOptions = LudoOptions(),
        finishOrder: List<Int> = emptyList(),
        colors: List<Int> = emptyList(),
        finished: Boolean = false,
    ) = LudoState(
        tokens = seats.toList(),
        turn = turn,
        roll = roll,
        sixes = sixes,
        options = options,
        finishOrder = finishOrder,
        colors = colors.ifEmpty { seats.indices.toList() },
        finished = finished,
    )

    @Test
    fun startsInTheYardWaitingToRoll() {
        val s = game.initialState(4, Random(1))
        assertEquals(List(4) { List(4) { LudoGame.YARD } }, s.tokens)
        assertNull(s.roll)
        assertEquals(0, s.turn)
        assertEquals(listOf(0, 1, 2, 3), s.colors)
    }

    @Test
    fun sixLeavesTheYardOntoTheStart() {
        val s = at(listOf(-1, -1, -1, -1), listOf(-1, -1, -1, -1))
        val rolled = game.roll(s, 0, 6)
        assertEquals(6, rolled.state.roll)
        assertEquals(listOf(0, 1, 2, 3), game.legalPawns(rolled.state, 0, 6))
        val play = game.movePawn(rolled.state, 0, 0, 6)
        assertEquals(0, play.to)
        assertEquals(0, play.state.tokens[0][0])
        assertNull(play.state.roll)
        assertEquals(0, play.state.turn)
        assertTrue(play.extraTurn)
    }

    @Test
    fun withoutSixCannotLeaveWhenSixToEnter() {
        val s = at(listOf(-1, -1, -1, -1), listOf(-1, -1, -1, -1))
        val play = game.roll(s, 0, 5)
        assertNull(play.pawn)
        assertEquals(1, play.state.turn)
        assertTrue(play.state.tokens[0].all { it == LudoGame.YARD })
    }

    @Test
    fun noSixToEnterLeavesAndWalks() {
        val s = at(
            listOf(-1, -1, -1, -1),
            listOf(-1, -1, -1, -1),
            options = LudoOptions(sixToEnter = false),
        )
        val rolled = game.roll(s, 0, 4)
        assertEquals(4, rolled.state.roll)
        val play = game.movePawn(rolled.state, 0, 0, 4)
        assertEquals(3, play.to)
        assertEquals(3, play.state.tokens[0][0])
    }

    @Test
    fun pendingRollWhenTwoTokensCanMove() {
        val s = at(listOf(0, 2, -1, -1), listOf(-1, -1, -1, -1))
        val play = game.roll(s, 0, 3)
        assertEquals(3, play.state.roll)
        assertNull(play.pawn)
        assertEquals(listOf(0, 1), game.legalPawns(play.state, 0, 3))
        assertEquals("must_move", game.validate(play.state, 0, LudoMove(null)))
        assertNull(game.validate(play.state, 0, LudoMove(1)))
        val moved = game.movePawn(play.state, 0, 1, 3)
        assertEquals(5, moved.to)
        assertNull(moved.state.roll)
        assertEquals(1, moved.state.turn)
    }

    @Test
    fun captureSendsOpponentHomeAndRollsAgain() {
        // Red at 4 rolling 1 lands on 5; blue relative 44 → absolute (13+44)%52 = 5.
        val s = at(
            listOf(4, -1, -1, -1),
            listOf(44, -1, -1, -1),
            colors = listOf(0, 1),
        )
        assertEquals(5, game.absolute(s, 1, 44))
        val hit = game.movePawn(s.copy(roll = 1), 0, 0, 1)
        assertEquals(listOf(LudoCapture(1, 0)), hit.captures)
        assertEquals(LudoGame.YARD, hit.state.tokens[1][0])
        assertEquals(0, hit.state.turn)
        assertTrue(hit.extraTurn)
    }

    @Test
    fun startSquareIsSafe() {
        val s = at(
            listOf(0, -1, -1, -1),
            listOf(38, -1, -1, -1),
            colors = listOf(0, 1),
        )
        val play = game.movePawn(s.copy(roll = 1), 1, 0, 1)
        assertTrue(play.captures.isEmpty())
        assertEquals(0, play.state.tokens[0][0])
        assertEquals(39, play.state.tokens[1][0])
        assertEquals(0, game.absolute(play.state, 1, 39))
    }

    @Test
    fun starSquareIsSafe() {
        // Red on star square 8 (relative 8); blue walks onto it from relative 46 → absolute 7, +1 → 8.
        assertTrue(LudoGame.isSafeAbsolute(8))
        assertEquals(listOf(8, 21, 34, 47), LudoGame.STARS.toList())
        val s = at(
            listOf(8, -1, -1, -1),
            listOf(46, -1, -1, -1),
            colors = listOf(0, 1),
        )
        assertEquals(7, game.absolute(s, 1, 46))
        val play = game.movePawn(s.copy(roll = 1), 1, 0, 1)
        assertTrue(play.captures.isEmpty())
        assertEquals(8, play.state.tokens[0][0])
        assertEquals(47, play.state.tokens[1][0])
        assertEquals(8, game.absolute(play.state, 1, 47))
    }

    @Test
    fun blockStopsLanding() {
        // Red at 8 + 6 → 14; blue relative 1 → absolute (13+1)%52 = 14.
        val open = at(
            listOf(8, -1, -1, -1),
            listOf(1, -1, -1, -1),
            colors = listOf(0, 1),
        )
        assertEquals(14, game.absolute(open, 0, 14))
        assertEquals(14, game.absolute(open, 1, 1))
        assertNotNull(game.destination(open, 0, 0, 6))

        val blocked = at(
            listOf(8, -1, -1, -1),
            listOf(1, 1, -1, -1),
            colors = listOf(0, 1),
        )
        assertNull(game.destination(blocked, 0, 0, 6))
    }

    @Test
    fun exactFinishRequired() {
        val s = at(listOf(LudoGame.FINISH - 2, -1, -1, -1), listOf(-1, -1, -1, -1))
        assertNull(game.destination(s, 0, 0, 3))
        assertEquals(LudoGame.FINISH, game.destination(s, 0, 0, 2))
    }

    @Test
    fun threeSixesBurnEvenWhenMovesExist() {
        val s = at(listOf(0, 1, 2, 3), listOf(-1, -1, -1, -1), sixes = 2)
        val play = game.roll(s, 0, 6)
        assertTrue(play.burnedSix)
        assertEquals(1, play.state.turn)
        assertEquals(listOf(0, 1, 2, 3), play.state.tokens[0])
    }

    @Test
    fun firstFinishEndsWhenOptionSet() {
        val almost = List(4) { LudoGame.FINISH }.toMutableList().also { it[0] = LudoGame.FINISH - 1 }
        val s = at(almost, List(4) { 0 }, options = LudoOptions(firstFinishEnds = true))
        val play = game.movePawn(s.copy(roll = 1), 0, 0, 1)
        assertTrue(play.state.finished)
        assertEquals(listOf(0), play.state.finishOrder)
    }

    @Test
    fun botRollsThenPicksAPawn() {
        val net = LoopbackNetwork()
        val host = HostSession(LudoRules(LudoOptions()), net.host, roomCode = "LUDO", random = Random(2))
        repeat(2) { host.addBot("Komputer ${it + 1}", Platform.ANDROID, LudoBot(LudoBot.Level.NORMAL)) }
        host.start()
        var steps = 0
        while (!host.state!!.finished && steps < 500) {
            assertTrue(host.stepBot())
            steps++
        }
        assertTrue(steps > 2)
        assertTrue(host.state!!.finished || host.state!!.tokens.any { seat -> seat.any { it >= 0 } })
    }

    @Test
    fun rankingPutsFinishersFirst() {
        val s = at(
            List(4) { LudoGame.FINISH },
            listOf(10, 10, 10, 10),
            listOf(1, 1, 1, 1),
            finishOrder = listOf(0),
            finished = true,
        )
        assertEquals(listOf(0, 1, 2), game.ranking(s))
    }

    @Test
    fun seatColorsSkipDuplicates() {
        assertEquals(listOf(2, 0, 1, 3), game.seatColors(4, listOf(2, 2, 9)))
    }

    @Test
    fun timeoutMovePicksLikeTheEasyBot() {
        val picking = at(listOf(-1, 5, 10, -1), listOf(-1, -1, -1, -1), roll = 6)
        repeat(20) { i ->
            val move = assertNotNull(game.timeoutMove(picking, 0, Random(i)))
            assertNull(game.validate(picking, 0, move))
            assertEquals(LudoBot(LudoBot.Level.EASY).chooseMove(picking, 0, Random(i)), move)
        }
        // Not this seat's turn, no roll yet, or the game is over.
        assertNull(game.timeoutMove(picking, 1, Random(0)))
        assertNull(game.timeoutMove(picking.copy(roll = null), 0, Random(0)))
        assertNull(game.timeoutMove(picking.copy(finished = true), 0, Random(0)))
        // Only one token can use the roll: that is the move.
        val one = at(listOf(-1, 5, -1, -1), listOf(-1, -1, -1, -1), roll = 3)
        assertEquals(LudoMove(1), game.timeoutMove(one, 0, Random(3)))
    }
}
