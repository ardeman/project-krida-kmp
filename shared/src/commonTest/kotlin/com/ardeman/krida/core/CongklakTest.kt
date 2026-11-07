package com.ardeman.krida.core

import com.ardeman.krida.core.games.CongklakBoard
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.CongklakSeeds
import com.ardeman.krida.core.games.CongklakGame.PITS
import com.ardeman.krida.core.games.CongklakMove
import com.ardeman.krida.core.games.CongklakState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class CongklakTest {
    private val g = CongklakGame

    /** Board with the given non-zero pits; everything else empty. */
    private fun board(turn: Int, vararg seeds: Pair<Int, Int>) =
        CongklakState(List(PITS) { i -> seeds.firstOrNull { it.first == i }?.second ?: 0 }, turn)

    @Test
    fun initialBoard() {
        val s = g.initialState(2, Random(1))
        assertEquals(98, s.pits.sum())
        assertEquals(0, g.score(s, 0))
        assertEquals(0, g.score(s, 1))
        assertEquals(0, g.currentTurn(s))
    }

    @Test
    fun lastSeedInOwnStoreGivesExtraTurn() {
        val p = g.play(g.initialState(2, Random(1)), 0, CongklakMove(0))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), p.path)
        assertTrue(p.extraTurn)
        assertEquals(0, p.state.turn)
        assertEquals(1, g.score(p.state, 0))
    }

    @Test
    fun emptyOwnHoleCapturesFacingHole() {
        val s = board(0, 0 to 1, 13 to 5, 10 to 2)
        val p = g.play(s, 0, CongklakMove(0))
        assertEquals(13, p.capturedFrom)
        assertEquals(6, g.score(p.state, 0))
        assertEquals(0, p.state.pits[1])
        assertEquals(0, p.state.pits[13])
        assertEquals(1, p.state.turn)
    }

    @Test
    fun emptyOwnHoleWithEmptyFacingHoleKeepsSeed() {
        val s = board(0, 0 to 1, 10 to 2)
        val p = g.play(s, 0, CongklakMove(0))
        assertNull(p.capturedFrom)
        assertEquals(1, p.state.pits[1])
        assertEquals(1, p.state.turn)
    }

    @Test
    fun relaySowingSkipsOpponentStore() {
        // pit 5 -> 6 (now 3) -> pick up -> 7, 8, 9: empty on opponent side, turn ends.
        val s = board(0, 5 to 1, 6 to 2, 11 to 1)
        val p = g.play(s, 0, CongklakMove(5))
        assertEquals(listOf(6, 7, 8, 9), p.path)
        assertEquals(1, p.state.turn)

        // Seat 1 sowing from pit 14 skips seat 0's store (7)... and passes its own store 15.
        val t = board(1, 14 to 3, 2 to 1)
        assertEquals(listOf(15, 0, 1), g.play(t, 1, CongklakMove(6)).path)
        val u = board(1, 14 to 9, 2 to 1)
        assertFalse(7 in g.play(u, 1, CongklakMove(6)).path)
    }

    @Test
    fun validation() {
        val s = board(0, 0 to 1, 10 to 2)
        assertEquals("bad_hole", g.validate(s, 0, CongklakMove(7)))
        assertEquals("bad_hole", g.validate(s, 0, CongklakMove(-1)))
        assertEquals("empty_hole", g.validate(s, 0, CongklakMove(3)))
        assertNull(g.validate(s, 0, CongklakMove(0)))
    }

    @Test
    fun gameEndsWhenMoverHasNoSeedsAndOpponentSweeps() {
        // Seat 0 drops its last seed in its store: extra turn, but nothing left to move.
        val s = board(0, 6 to 1, 7 to 40, 10 to 3, 12 to 4, 15 to 50)
        val end = g.play(s, 0, CongklakMove(6)).state
        assertTrue(g.isFinished(end))
        assertEquals(41, g.score(end, 0))
        assertEquals(57, g.score(end, 1))
        assertEquals(1, g.winner(end))
    }

    @Test
    fun randomGamesConserveSeedsAndFinish() {
        val json = Json
        repeat(500) { game ->
            val rnd = Random(game)
            var s = g.initialState(2, rnd)
            var moves = 0
            while (!g.isFinished(s)) {
                val seat = g.currentTurn(s)
                val legal = (0 until 7).map(::CongklakMove).filter { g.validate(s, seat, it) == null }
                assertTrue(legal.isNotEmpty(), "game $game: no legal move but not finished")
                s = g.apply(s, seat, legal.random(rnd), rnd)
                assertEquals(98, s.pits.sum(), "game $game move $moves")
                assertTrue(s.pits.all { it >= 0 })
                assertTrue(++moves < 2_000, "game $game did not finish")
            }
            assertEquals(98, g.score(s, 0) + g.score(s, 1))
            val round = json.decodeFromString(g.stateSerializer, json.encodeToString(g.stateSerializer, s))
            assertEquals(s, round)
        }
    }

    @Test
    fun seedAnimationEndsOnTheRulesBoard() {
        repeat(100) { game ->
            val rnd = Random(1000 + game)
            var s = g.initialState(2, rnd)
            val seeds = CongklakSeeds(s.pits)
            while (!g.isFinished(s)) {
                val seat = g.currentTurn(s)
                val move = (0 until 7).map(::CongklakMove).filter { g.validate(s, seat, it) == null }.random(rnd)
                val play = g.play(s, seat, move)
                val steps = CongklakSeeds.steps(s.pits, g.pit(seat, move.hole), play.path)
                assertEquals(play.path.size, steps.count { !it.pickUp })
                steps.forEach(seeds::apply)
                seeds.settle(play.state.pits)
                assertEquals(play.state.pits, seeds.counts(), "game $game")
                s = play.state
            }
            // Every seed has a place on the board (no seed left at the origin).
            val t = seeds.targets()
            assertTrue((0 until CongklakBoard.SEED_COUNT).all { t[it * 3 + 1] > 0f })
        }
    }

    @Test
    fun boardMeshIsWellFormed() {
        val m = CongklakBoard.boardMesh()
        val idx = m.indexArray()
        assertTrue(m.vertexCount in 1..65_535, "${m.vertexCount} vertices")
        assertTrue(idx.all { it in 0 until m.vertexCount })
        assertEquals(m.vertexCount * 3, m.colorArray().size)
    }
}
