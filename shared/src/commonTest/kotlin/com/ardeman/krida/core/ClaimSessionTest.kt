package com.ardeman.krida.core

import com.ardeman.krida.core.games.WerewolfBot
import com.ardeman.krida.core.games.WerewolfGame
import com.ardeman.krida.core.games.WerewolfMove
import com.ardeman.krida.core.games.WerewolfState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.WerewolfClaims
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LoopbackNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Werewolf claim chips fan out through the host and respect target + cooldown rules. */
class ClaimSessionTest {
    private var now = 0L
    private val net = LoopbackNetwork()
    private val host = HostSession(WerewolfGame, net.host, "WOLF", Random(1), { now })

    private fun startWithGuest(): GuestSession<WerewolfState, WerewolfMove> {
        host.addLocalPlayer("Host", Platform.ANDROID)
        val guest = GuestSession(WerewolfGame, net.guest("ayu"), "ayu", Platform.IOS, null)
        net.connect("ayu")
        repeat(3) { host.addBot("Bot$it", Platform.ANDROID, WerewolfBot()) }
        host.start()
        return guest
    }

    @Test
    fun hostFansOutGuestClaimWithTarget() {
        val guest = startWithGuest()
        var hostSeen: Triple<Int, String, Int?>? = null
        var guestSeen: Triple<Int, String, Int?>? = null
        host.onClaim = { seat, id, target -> hostSeen = Triple(seat, id, target) }
        guest.onClaim = { seat, id, target -> guestSeen = Triple(seat, id, target) }

        guest.sendClaim("suspect", target = 0)
        assertEquals(Triple(1, "suspect", 0), hostSeen)
        assertEquals(Triple(1, "suspect", 0), guestSeen)

        hostSeen = null
        guestSeen = null
        guest.sendClaim("trust", target = 2)
        assertNull(hostSeen)
        assertNull(guestSeen)

        now += WerewolfClaims.COOLDOWN_MS
        guest.sendClaim("trust", target = 2)
        assertEquals(Triple(1, "trust", 2), hostSeen)
        assertEquals(Triple(1, "trust", 2), guestSeen)
    }

    @Test
    fun localHostClaimReachesGuest() {
        val guest = startWithGuest()
        var guestSeen: Triple<Int, String, Int?>? = null
        guest.onClaim = { seat, id, target -> guestSeen = Triple(seat, id, target) }

        assertTrue(host.sendClaim(0, "i_seer"))
        assertEquals(Triple(0, "i_seer", null), guestSeen)
    }

    @Test
    fun unknownMissingTargetAndSelfTargetAreDropped() {
        startWithGuest()
        assertFalse(host.sendClaim(0, "free_text"))
        assertFalse(host.sendClaim(0, "suspect", target = null))
        assertFalse(host.sendClaim(0, "suspect", target = 0))
        assertTrue(host.sendClaim(0, "pass"))
    }
}
