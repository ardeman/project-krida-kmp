package com.ardeman.krida.core

import com.ardeman.krida.core.games.GapleBot
import com.ardeman.krida.core.games.GapleGame
import com.ardeman.krida.core.games.GapleMove
import com.ardeman.krida.core.games.GapleOptions
import com.ardeman.krida.core.games.GaplePlaced
import com.ardeman.krida.core.games.GapleRules
import com.ardeman.krida.core.games.GapleState
import com.ardeman.krida.core.games.GapleTile
import com.ardeman.krida.core.games.LobbyOptions
import com.ardeman.krida.core.games.StandardRules
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapleTest {
    private val game = GapleGame

    private fun t(a: Int, b: Int) = GapleTile(minOf(a, b), maxOf(a, b))

    /** A mid-round state with chosen hands and line, seat [turn] to move. */
    private fun state(
        hands: List<List<GapleTile>>,
        line: List<GaplePlaced>,
        turn: Int = 0,
        partners: Boolean = false,
        scores: List<Int> = List(hands.size) { 0 },
    ) = GapleState(
        players = hands.size,
        hands = hands,
        handCounts = hands.map { it.size },
        line = line,
        turn = turn,
        scores = scores,
        partners = partners,
        passedOn = List(hands.size) { emptyList() },
    )

    @Test
    fun setHas28Tiles() {
        assertEquals(28, GapleGame.SET.size)
        assertEquals(28, GapleGame.SET.toSet().size)
    }

    @Test
    fun dealsSevenEachAndSetsTheRestAside() {
        for (n in 2..4) {
            val s = game.initialState(n, Random(n))
            assertTrue(s.hands.all { it.size == 7 })
            assertEquals(28 - 7 * n, s.aside.size)
            assertEquals(28, (s.hands.flatten() + s.aside).toSet().size)
        }
    }

    @Test
    fun balakEnamOpensWithFourPlayers() {
        repeat(10) { seed ->
            val s = game.initialState(4, Random(seed))
            assertEquals(t(6, 6), s.mustPlay)
            assertTrue(t(6, 6) in s.hands[s.turn])
            val other = s.hands[s.turn].first { it != t(6, 6) }
            assertEquals("must_play", game.validate(s, s.turn, GapleMove(other)))
            assertNull(game.validate(s, s.turn, GapleMove(t(6, 6))))
        }
    }

    @Test
    fun highestDoubleOpensWhenSixSixIsAside() {
        val seed = (0 until 500).first { t(6, 6) in game.initialState(2, Random(it)).aside }
        val s = game.initialState(2, Random(seed))
        val top = s.hands.flatten().filter { it.isDouble }.maxBy { it.a }
        assertEquals(top, s.mustPlay)
        assertTrue(top in s.hands[s.turn])
    }

    @Test
    fun playsOnTheMatchingEndAndTurnsTheTile() {
        val s = state(
            hands = listOf(listOf(t(4, 2), t(5, 5)), listOf(t(1, 1), t(0, 0))),
            line = listOf(GaplePlaced(2, 6), GaplePlaced(6, 3)),
        )
        assertEquals(listOf(GapleGame.LEFT), game.endsFor(s, t(4, 2)))
        assertNull(game.validate(s, 0, GapleMove(t(4, 2))))
        assertEquals("no_fit", game.validate(s, 0, GapleMove(t(5, 5))))
        val next = game.apply(s, 0, GapleMove(t(4, 2)), Random(0))
        assertEquals(GaplePlaced(4, 2), next.line.first())
        assertEquals(1, next.origin)
        assertEquals(4, next.leftEnd)
    }

    @Test
    fun tileFittingBothEndsNeedsAnEnd() {
        val s = state(
            hands = listOf(listOf(t(2, 3), t(0, 0)), listOf(t(1, 1), t(5, 5))),
            line = listOf(GaplePlaced(2, 6), GaplePlaced(6, 3)),
        )
        assertEquals("pick_end", game.validate(s, 0, GapleMove(t(2, 3))))
        val right = game.apply(s, 0, GapleMove(t(2, 3), GapleGame.RIGHT), Random(0))
        assertEquals(GaplePlaced(3, 2), right.line.last())
        assertEquals(2, right.rightEnd)
    }

    @Test
    fun seatsWithNoFitPassAutomatically() {
        // Seat 0 plays [6|1]; seat 1 holds nothing with 1 or 6, seat 2 does.
        val s = state(
            hands = listOf(listOf(t(6, 1), t(0, 0)), listOf(t(2, 3), t(4, 4)), listOf(t(1, 5), t(3, 3))),
            line = listOf(GaplePlaced(6, 6)),
        )
        val next = game.apply(s, 0, GapleMove(t(6, 1), GapleGame.RIGHT), Random(0))
        assertEquals(2, next.turn)
        assertEquals(listOf(1), next.lastPasses)
        assertEquals(listOf(6, 1), next.passedOn[1])
        assertEquals("not_your_turn", game.validate(next, 1, GapleMove(t(2, 3))))
    }

    @Test
    fun emptyHandIsDominoAndScoresOtherHands() {
        val s = state(
            hands = listOf(listOf(t(6, 2)), listOf(t(5, 4), t(1, 0)), listOf(t(3, 3))),
            line = listOf(GaplePlaced(6, 6)),
        )
        val end = game.apply(s, 0, GapleMove(t(6, 2), GapleGame.RIGHT), Random(0))
        assertTrue(end.roundOver)
        assertEquals("domino", end.roundEnd)
        assertEquals(0, end.roundWinner)
        assertEquals(listOf(16, 0, 0), end.roundPoints)
        assertEquals(listOf(16, 0, 0), end.scores)
        assertEquals(0, game.currentTurn(end))
    }

    @Test
    fun blockedLineIsGapleAndLowestPipsWin() {
        // After [6|0] the ends are 0 and 6 — nobody holds a 0 or a 6.
        val s = state(
            hands = listOf(listOf(t(6, 0), t(5, 5)), listOf(t(2, 1)), listOf(t(4, 3))),
            line = listOf(GaplePlaced(6, 6)),
        )
        val end = game.apply(s, 0, GapleMove(t(6, 0), GapleGame.RIGHT), Random(0))
        assertEquals("gaple", end.roundEnd)
        assertEquals(1, end.roundWinner)
        assertEquals(listOf(10, 3, 7), end.roundPips)
        assertEquals(listOf(0, 17, 0), end.roundPoints)
    }

    @Test
    fun gapleTieGoesToTheFirstSeatAfterTheLastPlacer() {
        // Seat 1 places last; seats 0 and 2 tie on 3 pips, seat 2 comes first after seat 1.
        val s = state(
            hands = listOf(listOf(t(2, 1)), listOf(t(6, 0), t(5, 5)), listOf(t(1, 2))),
            line = listOf(GaplePlaced(6, 6)),
            turn = 1,
        )
        val end = game.apply(s, 1, GapleMove(t(6, 0), GapleGame.RIGHT), Random(0))
        assertEquals("gaple", end.roundEnd)
        assertEquals(2, end.roundWinner)
    }

    @Test
    fun partnersShareTheScoreAndSkipTheirPartnersPips() {
        val s = state(
            hands = listOf(listOf(t(6, 2)), listOf(t(5, 4)), listOf(t(3, 3)), listOf(t(1, 0))),
            line = listOf(GaplePlaced(6, 6)),
            partners = true,
        )
        val end = game.apply(s, 0, GapleMove(t(6, 2), GapleGame.RIGHT), Random(0))
        assertEquals(listOf(10, 0, 10, 0), end.roundPoints)
        assertEquals(listOf(10, 0, 10, 0), end.scores)
        assertEquals(listOf(10, 0), game.totals(end))
    }

    @Test
    fun partnersOnlyWithFourSeats() {
        assertTrue(game.initialState(4, GapleOptions(partners = true), Random(1)).partners)
        assertFalse(game.initialState(3, GapleOptions(partners = true), Random(1)).partners)
    }

    @Test
    fun reachingTargetEndsTheMatch() {
        val s = state(
            hands = listOf(listOf(t(6, 2)), listOf(t(5, 4))),
            line = listOf(GaplePlaced(6, 6)),
            scores = listOf(95, 99),
        )
        val end = game.apply(s, 0, GapleMove(t(6, 2), GapleGame.RIGHT), Random(0))
        assertTrue(end.finished)
        assertEquals(0, end.winner)
        assertEquals(listOf(104, 99), end.scores)
    }

    @Test
    fun nextRoundIsHostOnlyAndTheWinnerLeads() {
        val s = state(
            hands = listOf(listOf(t(5, 5)), listOf(t(6, 2)), listOf(t(3, 1))),
            line = listOf(GaplePlaced(6, 6)),
            turn = 1,
        )
        val end = game.apply(s, 1, GapleMove(t(6, 2), GapleGame.RIGHT), Random(0))
        assertEquals("round_over", game.validate(end, 1, GapleMove(t(5, 5))))
        assertEquals("not_host", game.validate(end, 1, GapleMove(next = true)))
        assertNull(game.validate(end, 0, GapleMove(next = true)))
        val next = game.apply(end, 0, GapleMove(next = true), Random(3))
        assertEquals(2, next.round)
        assertEquals(1, next.turn)
        assertNull(next.mustPlay)
        assertTrue(next.line.isEmpty())
        assertEquals(end.scores, next.scores)
        assertTrue(next.hands.all { it.size == 7 })
    }

    @Test
    fun viewHidesOtherHandsUntilTheRoundEnds() {
        val s = game.initialState(3, Random(5))
        val view = game.viewFor(s, 1)
        assertEquals(s.hands[1], view.hands[1])
        assertTrue(view.hands[0].isEmpty() && view.hands[2].isEmpty())
        assertTrue(view.aside.isEmpty())
        assertEquals(listOf(7, 7, 7), view.handCounts)
        val over = s.copy(roundOver = true)
        assertEquals(s.hands, game.viewFor(over, 1).hands)
        assertTrue(game.viewFor(over, 1).aside.isEmpty())
    }

    @Test
    fun normalBotPlaysTheHeaviestTile() {
        val s = state(
            hands = listOf(listOf(t(6, 1), t(6, 5), t(0, 0)), listOf(t(2, 2))),
            line = listOf(GaplePlaced(6, 6)),
        )
        val move = GapleBot(GapleBot.Level.NORMAL).chooseMove(s, 0, Random(0))
        assertEquals(t(6, 5), move.tile)
    }

    @Test
    fun hardBotBlocksAnEndTheNextSeatPassedOn() {
        // Either tile fits; [6|3] leaves a 3, which seat 1 has passed on.
        val s = state(
            hands = listOf(listOf(t(6, 3), t(6, 4)), listOf(t(1, 1))),
            line = listOf(GaplePlaced(6, 6)),
        ).let { it.copy(passedOn = listOf(emptyList(), listOf(3))) }
        val move = GapleBot(GapleBot.Level.HARD).chooseMove(s, 0, Random(0))
        assertEquals(t(6, 3), move.tile)
    }

    @Test
    fun botsFinishAMatchForEverySeatCount() {
        for (n in 2..4) for (level in GapleBot.Level.entries) {
            val bot = GapleBot(level)
            val random = Random(n * 10 + level.ordinal)
            var s = game.initialState(n, GapleOptions(partners = n == 4), random)
            var guard = 0
            while (!s.finished && guard++ < 5_000) {
                val seat = game.currentTurn(s)
                val move = bot.chooseMove(s, seat, random)
                assertNull(game.validate(s, seat, move), "n=$n $level")
                s = game.apply(s, seat, move, random)
                // Tiles are never lost or duplicated within a round.
                if (!s.roundOver) assertEquals(28, (s.hands.flatten() + s.aside).size + s.line.size)
            }
            assertTrue(s.finished, "n=$n $level")
            assertTrue(s.scores.max() >= GapleGame.TARGET)
        }
    }

    @Test
    fun guestsReceiveOnlyTheirOwnHand() {
        val net = LoopbackNetwork()
        val host = HostSession(GapleRules(GapleOptions(partners = true)), net.host, "GAPL", Random(9), { 0L })
        host.addLocalPlayer("Host", Platform.ANDROID)
        val guest = GuestSession(GapleGame, net.guest("ayu"), "ayu", Platform.IOS, null).also { net.connect("ayu") }
        host.addBot("Bot 3", Platform.ANDROID, GapleBot())
        host.addBot("Bot 4", Platform.ANDROID, GapleBot())
        assertEquals(GapleOptions(partners = true), LobbyOptions.gaple(guest.lobbyOptions))
        host.start()
        val seen = assertNotNull(guest.state)
        val truth = host.state!!
        assertEquals(truth.hands[1], seen.hands[1])
        assertTrue(seen.hands.filterIndexed { i, _ -> i != 1 }.all { it.isEmpty() })
        assertTrue(seen.aside.isEmpty())
        assertTrue(seen.partners)
    }

    @Test
    fun timeoutPlaysTheHeaviestFittingTileAndDealsAtRoundEnd() {
        val s = state(
            hands = listOf(listOf(t(2, 3), t(6, 1), t(5, 5)), listOf(t(2, 2))),
            line = listOf(GaplePlaced(2, 6), GaplePlaced(6, 3)),
        )
        val move = assertNotNull(game.timeoutMove(s, 0))
        assertEquals(t(2, 3), move.tile)
        assertNull(game.validate(s, 0, move))
        assertNull(game.timeoutMove(s, 1))
        val opening = game.initialState(4, Random(2))
        assertEquals(opening.mustPlay, game.timeoutMove(opening, opening.turn)?.tile)
        val over = s.copy(roundOver = true)
        assertEquals(GapleMove(next = true), game.timeoutMove(over, 0))
        assertNull(game.timeoutMove(over, 1))
    }

    @Test
    fun standardRulesHaveNoPartners() {
        assertFalse(StandardRules.gaple.options.partners)
    }
}
