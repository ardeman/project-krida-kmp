package com.ardeman.krida.core

import com.ardeman.krida.core.games.UlarTanggaBot
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.games.UlarTanggaMove
import com.ardeman.krida.core.games.UlarTanggaOptions
import com.ardeman.krida.core.games.UlarTanggaRules
import com.ardeman.krida.core.games.UlarTanggaState
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A 4-player ular tangga room: an Android host and three guests over the loopback network. */
class UlarTanggaSessionTest {
    private var now = 0L
    private val net = LoopbackNetwork()
    private val options = UlarTanggaOptions(bounceBack = true, sixRollsAgain = false)
    private val host = HostSession(UlarTanggaRules(options), net.host, "KRD4", Random(7), { now })

    private fun guest(id: String, platform: Platform) =
        GuestSession(UlarTanggaGame, net.guest(id), id, platform, null).also { net.connect(id) }

    private fun fullRoom(): List<GuestSession<UlarTanggaState, UlarTanggaMove>> {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        return listOf(guest("ayu", Platform.IOS), guest("budi", Platform.ANDROID), guest("citra", Platform.IOS))
    }

    /** Moves for whoever is to move: the host player locally, guests by intent, bots by step. */
    private fun playOne(guests: List<GuestSession<UlarTanggaState, UlarTanggaMove>>) {
        if (host.stepBot()) return
        val turn = host.state!!.turn
        if (turn == 0) {
            assertNull(host.submitLocal(0, UlarTanggaMove()))
        } else {
            val g = guests.single { it.seat == turn }
            assertTrue(g.isMyTurn)
            g.sendMove(UlarTanggaMove())
        }
    }

    @Test
    fun fourPlayersThrowTheDiceOffAndFinish() {
        val guests = fullRoom()
        assertEquals(listOf(1, 2, 3), guests.map { it.seat })

        // A fifth device is turned away.
        var reason: String? = null
        val late = GuestSession(UlarTanggaGame, net.guest("dewi"), "dewi", Platform.IOS, null)
        late.onRejected = { reason = it }
        net.connect("dewi")
        assertEquals(RejectReason.FULL, reason)

        host.start()
        guests.forEach {
            assertTrue(it.state!!.opening)
            assertEquals(options, it.state!!.options)
            assertEquals(4, it.state!!.positions.size)
        }

        // Each guest can find every dice-off throw in consecutive snapshots.
        var seen = 0
        var before = guests[2].state!!
        guests[2].onState = { after ->
            if (before.opening && UlarTanggaGame.diceOffThrowBetween(before, after) != null) seen++
            before = after
        }
        while (host.state!!.opening) playOne(guests)
        assertTrue(seen >= 4)
        assertEquals(4, guests[0].state!!.order.toSet().size)

        var moves = 0
        while (!host.state!!.finished) { playOne(guests); moves++; check(moves < 5000) }
        guests.forEach {
            assertTrue(it.finished)
            assertEquals(host.state, it.state)
            assertEquals(4, UlarTanggaGame.ranking(it.state!!).toSet().size)
        }

        host.restart()
        guests.forEach { assertTrue(it.state!!.opening); assertEquals(options, it.state!!.options) }
    }

    @Test
    fun droppedGuestIsPlayedByTheComputerAfterTheGrace() {
        val guests = fullRoom()
        host.start()
        net.drop("budi")
        assertFalse(host.players[2].connected)
        var shown: Boolean? = null
        guests[0].onPlayers = { shown = it[2].connected }
        assertFalse(guests[0].players[2].connected)
        assertEquals(emptyList(), host.expiredSeats())
        assertFalse(host.replaceWithBot(1, UlarTanggaBot))   // still connected

        now += Krida.RECONNECT_GRACE_MS + 1
        assertEquals(listOf(2), host.expiredSeats())
        assertTrue(host.replaceWithBot(2, UlarTanggaBot))
        assertEquals(true, shown)
        assertEquals(emptyList(), host.expiredSeats())
        guests[0].players[2].let {
            assertTrue(it.bot)
            assertTrue(it.connected)
            assertEquals("budi", it.name)
        }

        // The others play on; the computer throws for seat 2 when its turn comes.
        val live = guests.filter { it.seat != 2 }
        var moves = 0
        while (!host.state!!.finished) { playOne(live); moves++; check(moves < 5000) }
        assertTrue(live.all { it.finished })
    }
}
