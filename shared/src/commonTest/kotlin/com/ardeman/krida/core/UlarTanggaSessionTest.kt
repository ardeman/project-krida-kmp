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
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.session.SeatColorRules
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
    fun selectedRoomCapacityLimitsHumansAndComputers() {
        for (total in 2..3) {
            val network = LoopbackNetwork()
            val limited = HostSession(UlarTanggaGame, network.host, maxSeats = total)
            limited.addLocalPlayer("Host", Platform.ANDROID)
            repeat(total - 1) { limited.addBot("Computer", Platform.ANDROID, UlarTanggaBot) }
            assertNull(limited.addBot("Extra", Platform.ANDROID, UlarTanggaBot))
            assertNull(limited.addLocalPlayer("Extra", Platform.ANDROID))
            var reason: String? = null
            GuestSession(UlarTanggaGame, network.guest("late"), "Late", Platform.IOS, null).also {
                it.onRejected = { rejection -> reason = rejection }
            }
            network.connect("late")
            assertEquals(RejectReason.FULL, reason)
            limited.start()
            assertEquals(total, limited.state!!.positions.size)
        }
    }

    @Test
    fun guestsLearnSelectedLobbyCapacity() {
        for (total in 2..4) {
            val network = LoopbackNetwork()
            val limited = HostSession(UlarTanggaGame, network.host, maxSeats = total)
            limited.addLocalPlayer("Host", Platform.ANDROID)
            val guest = GuestSession(UlarTanggaGame, network.guest("guest"), "Guest", Platform.IOS, null)
            network.connect("guest")
            assertEquals(total, guest.lobbyCapacity)
            limited.start()
            assertEquals(2, guest.state!!.positions.size)
        }
    }

    @Test
    fun selectedRoomCapacityStaysWithinGameLimits() {
        for ((requested, expected) in listOf(0 to 2, 8 to 4)) {
            val network = LoopbackNetwork()
            val limited = HostSession(UlarTanggaGame, network.host, maxSeats = requested)
            repeat(expected) { assertEquals(it, limited.addLocalPlayer("Player", Platform.ANDROID)) }
            assertNull(limited.addLocalPlayer("Extra", Platform.ANDROID))
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
    fun droppedGuestIsPlayedByTheComputerAfterTheWait() {
        val guests = fullRoom()
        host.start()
        net.drop("budi")
        assertFalse(host.players[2].connected)
        var shown: Boolean? = null
        guests[0].onPlayers = { shown = it[2].connected }
        assertFalse(guests[0].players[2].connected)
        assertEquals(emptyList(), host.expiredSeats())
        assertFalse(host.replaceWithBot(1, UlarTanggaBot))   // still connected
        assertEquals(Krida.HANDOFF_WAIT_MS, host.handOffInMs(2))
        assertEquals(-1, host.handOffInMs(1))

        now += Krida.HANDOFF_WAIT_MS - 1
        assertEquals(1, host.handOffInMs(2))
        assertEquals(emptyList(), host.expiredSeats())
        now += 1
        assertEquals(listOf(2), host.expiredSeats())
        assertTrue(host.replaceWithBot(2, UlarTanggaBot))
        assertEquals(true, shown)
        assertEquals(emptyList(), host.expiredSeats())
        assertFalse(host.replaceWithBot(2, UlarTanggaBot))   // already played by the bot
        assertEquals(-1, host.handOffInMs(2))
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

    @Test
    fun returningGuestTakesTheSeatBackFromTheComputer() {
        val guests = fullRoom()
        host.start()
        val token = checkNotNull(guests[1].rejoinToken)
        net.drop("budi")
        now += Krida.HANDOFF_WAIT_MS
        assertTrue(host.replaceWithBot(2, UlarTanggaBot))
        assertTrue(host.players[2].bot)

        // The bot plays the seat while budi is away.
        val live = guests.filter { it.seat != 2 }
        var botMoved = false
        while (!botMoved) {
            botMoved = host.botToMove() == 2
            playOne(live)
        }

        now += 20_000   // 35 s after the drop: still within the reconnect grace
        val back = GuestSession(UlarTanggaGame, net.guest("budi-2"), "budi", Platform.ANDROID, token)
            .also { net.connect("budi-2") }
        assertEquals(2, back.seat)
        host.players[2].let {
            assertFalse(it.bot)
            assertTrue(it.connected)
        }
        assertFalse(guests[0].players[2].bot)

        // A late disconnect for the old connection changes nothing.
        host.onPeerDisconnected("budi")
        assertTrue(host.players[2].connected)
    }

    @Test
    fun playerTakesTheSeatBackLongAfterTheHandOff() {
        val guests = fullRoom()
        host.start()
        val token = checkNotNull(guests[1].rejoinToken)
        net.drop("budi")
        now += Krida.HANDOFF_WAIT_MS
        assertTrue(host.replaceWithBot(2, UlarTanggaBot))

        now += 10 * Krida.RECONNECT_GRACE_MS
        val late = GuestSession(UlarTanggaGame, net.guest("budi-2"), "budi", Platform.ANDROID, token)
        net.connect("budi-2")
        assertEquals(2, late.seat)
        assertFalse(host.players[2].bot)
        assertTrue(host.players[2].connected)
    }

    @Test
    fun guestRulesExposePawnColourChoices() {
        // Both native guest lobbies discover the picker through this interface.
        val rules: GameRules<UlarTanggaState, UlarTanggaMove> = UlarTanggaGame
        val colors = rules as SeatColorRules<*>
        assertEquals(4, colors.colorCount)
        assertEquals(listOf(3, 1), (colors.initialState(2, listOf(3, 1), Random(7)) as UlarTanggaState).colors)
    }

    @Test
    fun playersPickPawnColoursInTheLobby() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val ayu = guest("ayu", Platform.IOS)
        val budi = guest("budi", Platform.ANDROID)
        // New seats take the first free colour.
        assertEquals(listOf(0, 1, 2), host.players.map { it.color })
        assertEquals(1, ayu.color)

        // A free colour is granted and every guest sees it.
        ayu.pickColor(3)
        assertEquals(3, ayu.color)
        assertEquals(3, budi.players[1].color)

        // A held colour is refused; the guest keeps its own.
        var lobbies = 0
        budi.onLobby = { lobbies++ }
        budi.pickColor(3)
        budi.pickColor(9)
        assertEquals(2, budi.color)
        assertEquals(2, lobbies)

        // The host player changes colour too; colours stay distinct.
        assertFalse(host.setColor(0, 2))
        assertTrue(host.setColor(0, 1))
        assertFalse(host.setColor(1, 0))   // a guest's seat is the guest's to pick

        // A computer takes the first free colour, and leaves it when removed.
        val bot = host.addBot("Komputer", Platform.ANDROID, UlarTanggaBot)!!
        assertEquals(0, host.players[bot].color)
        assertTrue(host.removeBot(bot))
        assertFalse(host.removeBot(1))     // not a computer
        assertEquals(3, host.players.size)

        host.start()
        assertEquals(listOf(1, 3, 2), ayu.state!!.colors)
        ayu.pickColor(0)                    // too late
        assertEquals(listOf(1, 3, 2), host.state!!.colors)
    }

    @Test
    fun hostAndOneComputerCanStart() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        assertFalse(host.canStart())
        host.addBot("Komputer", Platform.ANDROID, UlarTanggaBot)
        assertTrue(host.canStart())
        host.start()
        var moves = 0
        while (!host.state!!.finished) {
            if (!host.stepBot()) assertNull(host.submitLocal(0, UlarTanggaMove()))
            check(++moves < 5000)
        }
    }
}
