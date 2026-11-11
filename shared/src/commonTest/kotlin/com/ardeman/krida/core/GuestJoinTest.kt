package com.ardeman.krida.core

import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.games.UlarTanggaMove
import com.ardeman.krida.core.games.UlarTanggaOptions
import com.ardeman.krida.core.games.UlarTanggaRules
import com.ardeman.krida.core.games.UlarTanggaState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.RejectReason
import com.ardeman.krida.core.session.GuestJoin
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Joining by room code, learning the game from Welcome, then playing it. */
class GuestJoinTest {
    private val net = LoopbackNetwork()

    @Test
    fun joinFindsTheGameAndHandsOverToASession() {
        val host = HostSession(UlarTanggaRules(UlarTanggaOptions()), net.host, "KRD4", Random(3), { 0L })
        host.addLocalPlayer("Redo", Platform.ANDROID)

        val join = GuestJoin(net.guest("ayu"), "Ayu", Platform.IOS)
        var session: GuestSession<UlarTanggaState, UlarTanggaMove>? = null
        var lobby = 0
        var connected = false
        join.onConnected = { connected = true }
        join.onGame = { game, token ->
            assertEquals(UlarTanggaGame.id, game)
            assertEquals(join.rejoinToken, token)
            session = GuestSession(UlarTanggaGame, join).also { s ->
                s.onLobby = { lobby = it.size }
                s.replay()
            }
        }
        net.connect("ayu")

        assertEquals(true, connected)
        val s = assertNotNull(session)
        // The Lobby that followed Welcome was replayed into the session.
        assertEquals(1, s.seat)
        assertEquals(2, lobby)
        assertEquals(listOf("Redo", "Ayu"), host.players.map { it.name })

        host.start()
        assertEquals(true, s.state?.opening)
        while (host.state!!.opening) {
            val turn = host.state!!.turn
            if (turn == 0) host.submitLocal(0, UlarTanggaMove()) else s.sendMove(UlarTanggaMove())
        }
        assertEquals(host.state, s.state)
    }

    @Test
    fun joinReportsARefusal() {
        val host = HostSession(CongklakGame, net.host, "KRD4", Random(3), { 0L })
        host.addLocalPlayer("Redo", Platform.ANDROID)
        host.addLocalPlayer("Ayu", Platform.ANDROID)

        val join = GuestJoin(net.guest("budi"), "Budi", Platform.IOS)
        var reason: String? = null
        join.onRejected = { reason = it }
        net.connect("budi")
        assertEquals(RejectReason.FULL, reason)
        assertEquals(null, join.game)
    }
}
