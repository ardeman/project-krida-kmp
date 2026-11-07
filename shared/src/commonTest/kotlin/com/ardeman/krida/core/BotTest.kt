package com.ardeman.krida.core

import com.ardeman.krida.core.games.CongklakBot
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.CongklakMove
import com.ardeman.krida.core.games.CongklakState
import com.ardeman.krida.core.games.CounterGame
import com.ardeman.krida.core.games.CounterMove
import com.ardeman.krida.core.games.CounterState
import com.ardeman.krida.core.protocol.InvalidReason
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotTest {
    private val addOne = object : Bot<CounterState, CounterMove> {
        override fun chooseMove(state: CounterState, seat: Int, random: Random) = CounterMove(1)
    }

    @Test
    fun botSeatsMoveOnlyWhenStepped() {
        val net = LoopbackNetwork()
        val host = HostSession(CounterGame, net.host, roomCode = "KRD4", random = Random(1))
        host.addLocalPlayer("Redo", Platform.ANDROID)
        host.addBot("Komputer", Platform.ANDROID, addOne)
        host.addBot("Komputer 2", Platform.ANDROID, addOne)
        host.start()

        assertNull(host.botToMove())
        assertFalse(host.stepBot())
        assertNull(host.submitLocal(0, CounterMove(3)))

        // A person can't move for a bot.
        assertEquals(InvalidReason.NOT_SEATED, host.submitLocal(1, CounterMove(1)))

        assertEquals(1, host.botToMove())
        assertEquals(3, host.state?.total) // nothing happens until the app steps
        assertTrue(host.stepBot())
        assertTrue(host.stepBot())
        assertFalse(host.stepBot())
        assertEquals(5, host.state?.total)
        assertEquals(0, host.state?.turn)
    }

    @Test
    fun guestsSeeBotsAsPlayersFlaggedBot() {
        val net = LoopbackNetwork()
        val host = HostSession(CounterGame, net.host, roomCode = "KRD4")
        host.addLocalPlayer("Redo", Platform.ANDROID)
        host.addBot("Komputer", Platform.ANDROID, addOne)
        val ayu = GuestSession(CounterGame, net.guest("ayu"), "ayu", Platform.IOS, null).also { net.connect("ayu") }
        assertEquals(listOf(false, true, false), ayu.players.map { it.bot })
        assertEquals(listOf(true, false, false), ayu.players.map { it.local })

        host.start()
        host.submitLocal(0, CounterMove(2))
        host.stepBot()
        assertEquals(3, ayu.state?.total)
        assertEquals(1, ayu.lastSeat)
        assertTrue(ayu.isMyTurn)
    }

    @Test
    fun congklakBotsAlwaysPlayLegalMovesToTheEnd() {
        for (level in CongklakBot.Level.entries) {
            val net = LoopbackNetwork()
            val host = HostSession(CongklakGame, net.host, roomCode = "KRD4", random = Random(7))
            host.addBot("A", Platform.IOS, CongklakBot(level))
            host.addBot("B", Platform.IOS, CongklakBot(level))
            host.start()
            var moves = 0
            while (host.stepBot()) moves++
            assertTrue(host.state!!.finished, "$level game did not finish")
            assertEquals(98, host.state!!.pits.sum())
            assertTrue(moves > 0)
        }
    }

    @Test
    fun congklakBotTakesTheExtraTurnThenTheCapture() {
        val bot = CongklakBot(CongklakBot.Level.NORMAL)
        // Seat 0: hole 6 has 1 seed (lands in own store, extra turn); hole 0 has 1 seed (lands
        // in empty hole 1, capturing the 9 seeds opposite). Best is both: store first, then capture.
        val pits = MutableList(CongklakGame.PITS) { 0 }
        pits[CongklakGame.pit(0, 0)] = 1
        pits[CongklakGame.pit(0, 6)] = 1
        pits[CongklakGame.opposite(CongklakGame.pit(0, 1))] = 9
        pits[CongklakGame.pit(1, 0)] = 1
        val state = CongklakState(pits, turn = 0)
        repeat(5) { assertEquals(CongklakMove(6), bot.chooseMove(state, 0, Random(it))) }
        val again = CongklakGame.play(state, 0, CongklakMove(6)).state
        assertEquals(0, again.turn)
        repeat(5) { assertEquals(CongklakMove(0), bot.chooseMove(again, 0, Random(it))) }
    }

    @Test
    fun strongerCongklakBotBeatsEasyMostOfTheTime() {
        var wins = 0
        val games = 20
        repeat(games) { g ->
            val random = Random(g)
            val strong = CongklakBot(CongklakBot.Level.NORMAL)
            val easy = CongklakBot(CongklakBot.Level.EASY)
            val strongSeat = g % 2
            var state = CongklakGame.initialState(2, random)
            while (!state.finished) {
                val bot = if (state.turn == strongSeat) strong else easy
                state = CongklakGame.play(state, state.turn, bot.chooseMove(state, state.turn, random)).state
            }
            if (CongklakGame.winner(state) == strongSeat) wins++
        }
        assertTrue(wins >= games * 3 / 4, "won $wins of $games")
    }
}
