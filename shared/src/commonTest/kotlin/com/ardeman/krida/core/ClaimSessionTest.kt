package com.ardeman.krida.core

import com.ardeman.krida.core.games.WerewolfBot
import com.ardeman.krida.core.games.WerewolfGame
import com.ardeman.krida.core.games.WerewolfMove
import com.ardeman.krida.core.games.WerewolfPhase
import com.ardeman.krida.core.games.WerewolfRole
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

    @Test
    fun botClaimFansOutAndLocalSeatCannotUseSendBotClaim() {
        val guest = startWithGuest()
        var guestSeen: Triple<Int, String, Int?>? = null
        guest.onClaim = { seat, id, target -> guestSeen = Triple(seat, id, target) }

        assertFalse(host.sendBotClaim(0, "pass")) // host local seat is not a bot
        assertTrue(host.sendBotClaim(2, "pass"))
        assertEquals(Triple(2, "pass", null), guestSeen)

        guestSeen = null
        assertFalse(host.sendBotClaim(2, "i_villager")) // cooldown
        now += WerewolfClaims.COOLDOWN_MS
        assertTrue(host.sendBotClaim(2, "suspect", target = 0))
        assertEquals(Triple(2, "suspect", 0), guestSeen)
    }

    @Test
    fun botChooseClaimIsLegalOrNull() {
        val roles = listOf(
            WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR,
            WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
        )
        val state = WerewolfState(
            players = 5,
            roles = roles,
            alive = List(5) { true },
            phase = WerewolfPhase.DISCUSS,
            votes = List(5) { null },
            nightTarget = List(5) { -1 },
            nightDone = List(5) { true },
            revealed = List(5) { null },
        )
        val bot = WerewolfBot()
        repeat(40) { i ->
            val pick = bot.chooseClaim(state, seat = i % 5, random = Random(i))
            if (pick != null) {
                assertTrue(WerewolfClaims.valid(pick.first), pick.first)
                if (WerewolfClaims.needsTarget(pick.first)) {
                    val t = pick.second
                    assertTrue(t != null && t in 0 until 5 && t != i % 5)
                } else {
                    assertNull(pick.second)
                }
            }
        }
    }
}
