package com.ardeman.krida.core

import com.ardeman.krida.core.games.PerangKapalBot
import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.PerangKapalMove
import com.ardeman.krida.core.games.PerangKapalShip
import com.ardeman.krida.core.games.StandardRules
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

class PerangKapalTest {
    private val game = PerangKapalGame

    private fun horizontal(kind: Int, bow: Int) =
        PerangKapalShip(kind, game.cellsFor(bow, PerangKapalGame.FLEET[kind], true)!!)

    @Test
    fun startsPlacingWithEmptyFleets() {
        val s = game.initialState(2, Random(1))
        assertTrue(s.placing)
        assertEquals(listOf(emptyList(), emptyList()), s.fleets)
        assertEquals(listOf(false, false), s.ready)
        assertEquals(2, game.actingSeats(s).size)
    }

    @Test
    fun placesAndRejectsOverlap() {
        var s = game.initialState(2, Random(0))
        assertNull(game.validate(s, 0, PerangKapalMove(kind = 4, cell = 0, horizontal = true)))
        s = game.apply(s, 0, PerangKapalMove(kind = 4, cell = 0, horizontal = true), Random.Default)
        assertEquals(listOf(0, 1), s.fleets[0].single().cells)
        assertEquals("overlap", game.validate(s, 0, PerangKapalMove(kind = 3, cell = 1, horizontal = true)))
    }

    @Test
    fun bothReadyStartsBattle() {
        var s = game.initialState(2, Random(2))
        val first = s.turn
        val f0 = game.randomFleet(Random(10))
        val f1 = game.randomFleet(Random(11))
        s = game.apply(s, 0, PerangKapalMove(fleet = f0), Random.Default)
        s = game.apply(s, 0, PerangKapalMove(ready = true), Random.Default)
        assertTrue(s.placing)
        s = game.apply(s, 1, PerangKapalMove(fleet = f1), Random.Default)
        s = game.apply(s, 1, PerangKapalMove(ready = true), Random.Default)
        assertFalse(s.placing)
        assertEquals(first, s.turn)
        assertEquals(listOf(first), game.actingSeats(s))
    }

    @Test
    fun fireHitMissAndSink() {
        val destroyer = horizontal(4, 0) // cells 0,1
        val fleet0 = listOf(
            PerangKapalShip(0, listOf(90, 91, 92, 93, 94)),
            PerangKapalShip(1, listOf(80, 81, 82, 83)),
            PerangKapalShip(2, listOf(70, 71, 72)),
            PerangKapalShip(3, listOf(60, 61, 62)),
            destroyer,
        )
        val fleet1 = game.randomFleet(Random(3))
        assertTrue(game.legalFleet(fleet0))
        var s = game.initialState(2, Random(0)).copy(turn = 0)
        s = game.apply(s, 0, PerangKapalMove(fleet = fleet0), Random.Default)
        s = game.apply(s, 0, PerangKapalMove(ready = true), Random.Default)
        s = game.apply(s, 1, PerangKapalMove(fleet = fleet1), Random.Default)
        s = game.apply(s, 1, PerangKapalMove(ready = true), Random.Default)
        assertFalse(s.placing)

        // Seat 0 fires miss on empty water of seat 1 — pick a cell not in fleet1
        val missCell = (0 until PerangKapalGame.CELLS).first { it !in game.occupied(fleet1) }
        s = game.apply(s, 0, PerangKapalMove(fire = missCell), Random.Default)
        assertEquals(false, s.lastHit)
        assertEquals(1, s.turn)

        // Seat 1 sinks the destroyer
        s = game.apply(s, 1, PerangKapalMove(fire = 0), Random.Default)
        assertEquals(true, s.lastHit)
        assertNull(s.lastSunk)
        s = game.apply(s, 0, PerangKapalMove(fire = (0 until PerangKapalGame.CELLS).first { it != missCell && it !in game.occupied(fleet1) }), Random.Default)
        s = game.apply(s, 1, PerangKapalMove(fire = 1), Random.Default)
        assertEquals(true, s.lastHit)
        assertEquals(4, s.lastSunk)
    }

    @Test
    fun viewHidesOpponentFleetUntilSunk() {
        val fleet0 = game.randomFleet(Random(20))
        val fleet1 = game.randomFleet(Random(21))
        var s = game.initialState(2, Random(0)).copy(turn = 0)
        s = game.apply(s, 0, PerangKapalMove(fleet = fleet0), Random.Default)
        s = game.apply(s, 1, PerangKapalMove(fleet = fleet1), Random.Default)
        val mid = game.viewFor(s, 0)
        assertEquals(fleet0, mid.fleets[0])
        assertTrue(mid.fleets[1].isEmpty())

        s = game.apply(s, 0, PerangKapalMove(ready = true), Random.Default)
        s = game.apply(s, 1, PerangKapalMove(ready = true), Random.Default)
        val ship = fleet1.first()
        for (cell in ship.cells) {
            while (s.turn != 0) {
                val open = game.openCells(s, 1).first { it !in game.occupied(fleet0) }
                s = game.apply(s, 1, PerangKapalMove(fire = open), Random.Default)
            }
            s = game.apply(s, 0, PerangKapalMove(fire = cell), Random.Default)
        }
        val view = game.viewFor(s, 0)
        assertTrue(view.fleets[1].any { it.kind == ship.kind })
        assertTrue(view.fleets[1].size < fleet1.size || fleet1.size == 1)
    }

    @Test
    fun bowForCentresTheShipAndStaysOnBoard() {
        // Carrier (5) across, tapped on row 2: centred, then clamped at both edges.
        assertEquals(23, game.bowFor(25, 0, horizontal = true))
        assertEquals(20, game.bowFor(20, 0, horizontal = true))
        assertEquals(25, game.bowFor(29, 0, horizontal = true))
        // Up the grid from the last row: pushed back so all five rows fit.
        assertEquals(53, game.bowFor(93, 0, horizontal = false))
        for (kind in 0 until game.KINDS) for (cell in 0 until game.CELLS) for (h in listOf(true, false)) {
            assertTrue(game.cellsFor(game.bowFor(cell, kind, h), game.FLEET[kind], h) != null)
        }
    }

    @Test
    fun turnedBowKeepsTheMiddleCell() {
        val ship = PerangKapalShip(1, game.cellsFor(44, 4, true)!!)
        assertTrue(ship.horizontal)
        assertEquals(44, ship.bow)
        val bow = game.turnedBow(ship)
        val turned = PerangKapalShip(1, game.cellsFor(bow, 4, false)!!)
        assertTrue(!turned.horizontal)
        assertTrue(46 in turned.cells)
    }

    @Test
    fun viewHidesOpponentPlacementCell() {
        var s = game.initialState(2, Random(0))
        s = game.apply(s, 1, PerangKapalMove(kind = 4, cell = 37, horizontal = true), Random.Default)
        assertNull(game.viewFor(s, 0).lastCell)
        assertEquals(37, game.viewFor(s, 1).lastCell)
    }

    @Test
    fun viewMoveHidesOpponentPlacement() {
        val place = PerangKapalMove(kind = 4, cell = 0, horizontal = true)
        assertNull(game.viewMove(place, actor = 1, viewer = 0))
        assertEquals(place, game.viewMove(place, actor = 0, viewer = 0))
        val fire = PerangKapalMove(fire = 3)
        assertEquals(fire, game.viewMove(fire, actor = 1, viewer = 0))
    }

    @Test
    fun botPlacesAndFinishesAShortGame() {
        val net = LoopbackNetwork()
        val host = HostSession(StandardRules.perangKapal, net.host, "ABCD")
        host.addLocalPlayer("You", Platform.ANDROID)
        host.addBot("Bot", Platform.ANDROID, PerangKapalBot(PerangKapalBot.Level.HARD))
        host.start()
        // Human places and readies
        val fleet = game.randomFleet(Random(42))
        assertNull(host.submitLocal(0, PerangKapalMove(fleet = fleet)))
        assertNull(host.submitLocal(0, PerangKapalMove(ready = true)))
        // Bot places + ready (two stepBot calls), then battle
        var guard = 0
        while (!host.state!!.finished && guard++ < 500) {
            if (host.botToMove() != null) assertTrue(host.stepBot())
            else {
                val seat = 0
                val st = host.state!!
                if (seat !in game.actingSeats(st)) break
                val cell = game.openCells(st, seat).first()
                assertNull(host.submitLocal(seat, PerangKapalMove(fire = cell)))
            }
        }
        assertTrue(host.state!!.finished)
        assertNotNull(host.state!!.winner)
    }

    @Test
    fun randomFleetIsAlwaysLegal() {
        repeat(30) { i ->
            val fleet = game.randomFleet(Random(i))
            assertTrue(game.legalFleet(fleet), "seed $i")
        }
    }
    @Test
    fun timeoutFleetKeepsPlacedShipsAndFillsTheRest() {
        var s = game.initialState(2, Random(3))
        s = game.apply(s, 0, PerangKapalMove(kind = 0, cell = 0, horizontal = true), Random.Default)
        s = game.apply(s, 0, PerangKapalMove(kind = 4, cell = 90, horizontal = true), Random.Default)
        repeat(20) { i ->
            val fleet = assertNotNull(game.timeoutFleet(s, 0, Random(i)))
            assertTrue(game.legalFleet(fleet), "seed $i")
            assertEquals(s.fleets[0].first { it.kind == 0 }, fleet.first { it.kind == 0 })
            assertEquals(s.fleets[0].first { it.kind == 4 }, fleet.first { it.kind == 4 })
            val placed = game.apply(s, 0, PerangKapalMove(fleet = fleet), Random.Default)
            assertNull(game.validate(placed, 0, PerangKapalMove(ready = true)))
        }
        assertTrue(game.legalFleet(assertNotNull(game.timeoutFleet(s, 1, Random(1)))))
    }

    @Test
    fun timeoutFleetIsNullOnceReadyOrInBattle() {
        var s = game.initialState(2, Random(4))
        s = game.apply(s, 0, PerangKapalMove(fleet = game.randomFleet(Random(5))), Random.Default)
        s = game.apply(s, 0, PerangKapalMove(ready = true), Random.Default)
        assertNull(game.timeoutFleet(s, 0, Random(1)))
        s = game.apply(s, 1, PerangKapalMove(fleet = game.randomFleet(Random(6))), Random.Default)
        s = game.apply(s, 1, PerangKapalMove(ready = true), Random.Default)
        assertNull(game.timeoutFleet(s, 1, Random(1)))
    }
    @Test
    fun timeoutMoveFiresALegalShotOnlyOnYourTurn() {
        var s = game.initialState(2, Random(7))
        assertNull(game.timeoutMove(s, 0, Random(1)))
        for (seat in 0..1) {
            s = game.apply(s, seat, PerangKapalMove(fleet = game.randomFleet(Random(seat + 20))), Random.Default)
            s = game.apply(s, seat, PerangKapalMove(ready = true), Random.Default)
        }
        assertNull(game.timeoutMove(s, 1 - s.turn, Random(1)))
        repeat(40) {
            val seat = s.turn
            val move = assertNotNull(game.timeoutMove(game.viewFor(s, seat), seat, Random(it)))
            assertNull(game.validate(s, seat, move))
            s = game.apply(s, seat, move, Random.Default)
        }
    }
    @Test
    fun crowdedShipTurnsOnAnotherCell() {
        // Packed like a player's fleet: the carrier on the bottom row, ships stacked above it.
        val fleet = listOf(
            horizontal(0, 94), horizontal(3, 85), horizontal(1, 74), horizontal(2, 64),
            PerangKapalShip(4, game.cellsFor(45, 2, false)!!),
        )
        assertTrue(game.legalFleet(fleet))
        // The submarine is boxed in by full rows above and below: no turn exists.
        assertNull(game.turnedBow(fleet, fleet[1], 86))
        for (ship in fleet.filter { it.kind != 3 }) {
            for (tapped in ship.cells) {
                val bow = assertNotNull(game.turnedBow(fleet, ship, tapped), "kind ${ship.kind}")
                val cells = assertNotNull(game.cellsFor(bow, PerangKapalGame.FLEET[ship.kind], !ship.horizontal))
                assertTrue(cells.any { it in ship.cells }, "kind ${ship.kind} keeps a cell")
                val others = fleet.filter { it.kind != ship.kind }.flatMap { it.cells }
                assertTrue(cells.none { it in others }, "kind ${ship.kind} overlaps")
            }
        }
        // Free water: same as turning around the middle.
        val alone = horizontal(1, 44)
        assertEquals(game.turnedBow(alone), game.turnedBow(listOf(alone), alone, 44))
    }

    @Test
    fun boxedInShipCannotTurn() {
        val fleet = listOf(horizontal(4, 0), horizontal(0, 10))
        assertNull(game.turnedBow(fleet, fleet[0], 0))
    }
}
