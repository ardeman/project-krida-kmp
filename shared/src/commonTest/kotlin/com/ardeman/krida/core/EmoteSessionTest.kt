package com.ardeman.krida.core

import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.CongklakMove
import com.ardeman.krida.core.games.CongklakState
import com.ardeman.krida.core.protocol.Emotes
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Preset reactions fan out through the host and respect the cooldown. */
class EmoteSessionTest {
    private var now = 0L
    private val net = LoopbackNetwork()
    private val host = HostSession(CongklakGame, net.host, "KRD4", Random(1), { now })

    @Test
    fun hostFansOutGuestEmoteAndEchoes() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val guest = GuestSession(CongklakGame, net.guest("ayu"), "ayu", Platform.IOS, null)
        net.connect("ayu")
        host.start()

        var hostSeen: Pair<Int, String>? = null
        var guestSeen: Pair<Int, String>? = null
        host.onEmote = { seat, id -> hostSeen = seat to id }
        guest.onEmote = { seat, id -> guestSeen = seat to id }

        guest.sendEmote("nice")
        assertEquals(1 to "nice", hostSeen)
        assertEquals(1 to "nice", guestSeen)

        // Cooldown blocks a second send from the same seat.
        hostSeen = null
        guestSeen = null
        guest.sendEmote("awesome")
        assertEquals(null, hostSeen)
        assertEquals(null, guestSeen)

        now += Emotes.COOLDOWN_MS
        guest.sendEmote("awesome")
        assertEquals(1 to "awesome", hostSeen)
        assertEquals(1 to "awesome", guestSeen)
    }

    @Test
    fun localHostEmoteReachesGuest() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val guest = GuestSession(CongklakGame, net.guest("ayu"), "ayu", Platform.IOS, null)
        net.connect("ayu")
        host.start()

        var guestSeen: Pair<Int, String>? = null
        guest.onEmote = { seat, id -> guestSeen = seat to id }

        assertTrue(host.sendEmote(0, "lets_go"))
        assertEquals(0 to "lets_go", guestSeen)
    }

    @Test
    fun unknownAndLobbyEmotesAreDropped() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val guest = GuestSession(CongklakGame, net.guest("ayu"), "ayu", Platform.IOS, null)
        net.connect("ayu")

        assertFalse(host.sendEmote(0, "nice")) // game not started
        host.start()
        assertFalse(host.sendEmote(0, "free_text"))
        guest.sendEmote("not-a-real-id")
        // Still seated; a valid one after cooldown works.
        now += Emotes.COOLDOWN_MS
        assertTrue(host.sendEmote(0, "hmm"))
    }

    @Test
    fun moveStillWorksAlongsideEmotes() {
        host.addLocalPlayer("Redo", Platform.ANDROID)
        val guest = GuestSession(CongklakGame, net.guest("ayu"), "ayu", Platform.IOS, null)
        net.connect("ayu")
        host.start()
        guest.sendEmote("ready")
        val state = host.state as CongklakState
        if (state.turn == 0) {
            assertEquals(null, host.submitLocal(0, CongklakMove(0)))
        } else {
            guest.sendMove(CongklakMove(0))
        }
        assertTrue(host.seq >= 1)
    }
}
