package com.ardeman.krida.core

import com.ardeman.krida.core.games.CounterGame
import com.ardeman.krida.core.games.CounterMove
import com.ardeman.krida.core.protocol.InvalidReason
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.session.GuestJoin
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.session.SavedRoom
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private var now = 0L
    private val net = LoopbackNetwork()
    private val host = HostSession(CounterGame, net.host, roomCode = "KRD4", clock = { now })

    private fun guest(id: String, platform: Platform = Platform.IOS, token: String? = null) =
        GuestSession(CounterGame, net.guest(id), id, platform, token).also { net.connect(id) }

    @Test
    fun androidHostAndIosGuestPlayAGame() {
        assertEquals(0, host.addLocalPlayer("Redo", Platform.ANDROID))
        val ayu = guest("ayu")
        assertEquals(1, ayu.seat)
        assertEquals("KRD4", ayu.roomCode)
        assertEquals(2, ayu.players.size)

        host.start()
        assertEquals(0, ayu.turn)
        assertFalse(ayu.isMyTurn)

        // Guest moving out of turn is refused.
        var invalid: String? = null
        ayu.onInvalid = { invalid = it }
        ayu.sendMove(CounterMove(2))
        assertEquals(InvalidReason.NOT_YOUR_TURN, invalid)

        assertNull(host.submitLocal(0, CounterMove(3)))
        assertTrue(ayu.isMyTurn)
        assertEquals(3, ayu.state?.total)

        // Rules reject an illegal move.
        ayu.sendMove(CounterMove(9))
        assertEquals("add_1_to_3", invalid)

        ayu.sendMove(CounterMove(3))
        assertEquals(6, host.state?.total)
        host.submitLocal(0, CounterMove(3))
        ayu.sendMove(CounterMove(1))
        assertTrue(ayu.finished)
        assertEquals(InvalidReason.FINISHED, host.submitLocal(0, CounterMove(1)))
    }

    @Test
    fun rejectsWrongVersionFullRoomAndLateJoiners() {
        repeat(Krida.MAX_PLAYERS - 1) { host.addLocalPlayer("P$it", Platform.ANDROID) }
        guest("fourth")
        var reason: String? = null
        val fifth = GuestSession(CounterGame, net.guest("fifth"), "fifth", Platform.IOS)
        fifth.onRejected = { reason = it }
        net.connect("fifth")
        assertEquals(RejectReason.FULL, reason)
        assertNull(fifth.seat)

        host.start()
        val late = GuestSession(CounterGame, net.guest("late"), "late", Platform.IOS)
        late.onRejected = { reason = it }
        net.connect("late")
        assertEquals(RejectReason.STARTED, reason)
    }

    @Test
    fun lobbyLeaveCompactsSeats() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        guest("a")
        val b = guest("b")
        assertEquals(2, b.seat)
        net.drop("a")
        assertEquals(1, b.seat)
        assertEquals(2, host.players.size)
    }

    @Test
    fun guestRejoinsKeepsSeat() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val first = guest("ayu-1")
        host.start()
        val token = assertNotNull(first.rejoinToken)

        net.drop("ayu-1")
        assertFalse(host.players[1].connected)

        now += 30_000
        val back = guest("ayu-2", token = token)
        assertEquals(1, back.seat)
        assertTrue(host.players[1].connected)
        assertEquals(1, back.stateSeq)

        // Long after a drop the token still works: the seat waits for its player.
        net.drop("ayu-2")
        now += 30 * 60_000L
        val late = guest("ayu-3", token = token)
        assertEquals(1, late.seat)
        assertTrue(host.players[1].connected)

        // Someone without the token can't take a seat in a running game.
        var reason: String? = null
        val stranger = GuestSession(CounterGame, net.guest("eko"), "eko", Platform.IOS, "0000000000000000")
        stranger.onRejected = { reason = it }
        net.connect("eko")
        assertEquals(RejectReason.STARTED, reason)
    }

    @Test
    fun guestJoinRejoinsWithSavedToken() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val first = guest("ayu-1")
        host.start()
        val saved = SavedRoom("ABC234", true, CounterGame.id, assertNotNull(first.rejoinToken), now)
        net.drop("ayu-1")

        // A fresh join by code (the game not known yet) sends the saved token in Hello.
        val back = SavedRoom.decode(saved.encode())!!
        val join = GuestJoin(net.guest("ayu-2"), "ayu", Platform.IOS, back.token)
        var game: String? = null
        join.onGame = { g, _ -> game = g }
        net.connect("ayu-2")
        assertEquals(CounterGame.id, game)
        assertEquals(saved.token, join.rejoinToken)
        val session = GuestSession(CounterGame, join)
        session.replay()
        assertEquals(1, session.seat)
        assertEquals(1, session.stateSeq)
        assertEquals(2, host.players.size)
    }

    @Test
    fun savedRoomRoundTripsAndExpires() {
        val room = SavedRoom("ABC234", false, "congklak", "0123456789abcdef", 1_000L)
        assertEquals(room, SavedRoom.decode(room.encode()))
        assertNull(SavedRoom.decode(null))
        assertNull(SavedRoom.decode("ABC234|1|congklak"))
        assertTrue(room.isFresh(1_000L + SavedRoom.MAX_AGE_MS))
        assertFalse(room.isFresh(1_001L + SavedRoom.MAX_AGE_MS))
    }

    @Test
    fun stateCarriesLastMoveForAnimation() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val first = guest("ayu-1")
        host.start()
        assertNull(first.lastMove)
        assertNull(host.lastMove)

        host.submitLocal(0, CounterMove(2))
        assertEquals(0, first.lastSeat)
        assertEquals(CounterMove(2), first.lastMove)

        first.sendMove(CounterMove(1))
        assertEquals(1, host.lastSeat)
        assertEquals(CounterMove(1), host.lastMove)
        assertEquals(CounterMove(1), first.lastMove)

        // A rejoining guest gets a snapshot, not an animation of a move it never saw.
        val token = assertNotNull(first.rejoinToken)
        net.drop("ayu-1")
        val back = guest("ayu-2", token = token)
        assertEquals(3, back.stateSeq)
        assertNull(back.lastMove)
        assertNull(back.lastSeat)
    }

    @Test
    fun restartAfterFinishReachesGuests() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val ayu = guest("ayu")
        host.start()
        host.submitLocal(0, CounterMove(3))
        ayu.sendMove(CounterMove(3))
        host.submitLocal(0, CounterMove(3))
        ayu.sendMove(CounterMove(1))
        assertTrue(ayu.finished)

        host.restart()
        assertFalse(ayu.finished)
        assertEquals(0, ayu.state?.total)
        assertEquals(6, ayu.stateSeq)
        assertNull(ayu.lastMove)
    }
}
