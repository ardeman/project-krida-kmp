package com.ardeman.krida.core

import com.ardeman.krida.core.games.WerewolfBot
import com.ardeman.krida.core.games.WerewolfGame
import com.ardeman.krida.core.games.WerewolfMove
import com.ardeman.krida.core.games.WerewolfPhase
import com.ardeman.krida.core.games.WerewolfRole
import com.ardeman.krida.core.games.WerewolfState
import com.ardeman.krida.core.games.StandardRules
import com.ardeman.krida.core.protocol.Krida
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

class WerewolfTest {
    private val game = WerewolfGame
    private val rng = Random(1)

    @Test
    fun dealRolesMatchTable() {
        assertEquals(5, game.dealRoles(5).size)
        assertEquals(1, game.dealRoles(5).count { it == WerewolfRole.WEREWOLF })
        assertEquals(0, game.dealRoles(5).count { it == WerewolfRole.HUNTER })
        assertEquals(1, game.dealRoles(6).count { it == WerewolfRole.HUNTER })
        assertEquals(2, game.dealRoles(7).count { it == WerewolfRole.WEREWOLF })
        assertEquals(1, game.dealRoles(8).count { it == WerewolfRole.WITCH })
        assertEquals(WerewolfGame, StandardRules.werewolf)
        assertEquals(8, Krida.MAX_PLAYERS)
        assertEquals(5, game.minPlayers)
        assertEquals(8, game.maxPlayers)
    }

    @Test
    fun viewHidesOtherRolesExceptWolves() {
        val roles = listOf(
            WerewolfRole.WEREWOLF, WerewolfRole.WEREWOLF, WerewolfRole.SEER,
            WerewolfRole.DOCTOR, WerewolfRole.VILLAGER,
        )
        val state = base(roles)
        val wolfView = game.viewFor(state, 0)
        assertEquals(WerewolfRole.WEREWOLF, wolfView.roles[0])
        assertEquals(WerewolfRole.WEREWOLF, wolfView.roles[1])
        assertEquals("", wolfView.roles[2])
        val seerView = game.viewFor(state, 2)
        assertEquals(WerewolfRole.SEER, seerView.roles[2])
        assertEquals("", seerView.roles[0])
    }

    @Test
    fun nightKillAndDoctorSave() {
        val roles = listOf(
            WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR,
            WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
        )
        var s = base(roles)
        s = step(s, 0, WerewolfMove(target = 3))
        s = step(s, 1, WerewolfMove(target = 0))
        s = step(s, 2, WerewolfMove(target = 3))
        assertTrue(s.alive[3], "doctor protects seat 3")
        assertTrue(s.phase == WerewolfPhase.SHERIFF_ELECT || s.phase == WerewolfPhase.DISCUSS)
    }

    @Test
    fun wolvesWinWhenEqual() {
        val roles = listOf(
            WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR,
            WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
        )
        var s = base(roles).copy(
            alive = listOf(true, true, false, false, false),
            phase = WerewolfPhase.VOTE,
            sheriff = 0,
            votes = List(5) { null },
        )
        // Force win check via a lynch that leaves 1 wolf and 1 town... actually start with 1 wolf 1 town
        s = s.copy(alive = listOf(true, true, false, false, false))
        // wolf majority: 1 wolf >= 1 town → already wolves win on check
        val checked = game.initialState(5, Random(0)).copy(
            roles = roles,
            alive = listOf(true, true, false, false, false),
            phase = WerewolfPhase.NIGHT,
        )
        // Apply a no-op night that resolves: only wolf and seer act
        var t = checked
        t = step(t, 0, WerewolfMove(target = 1))
        t = step(t, 1, WerewolfMove(target = 0))
        // doctor dead — not acting. After kill of seer, only wolf left → town 0, wolves win
        assertEquals("wolves", t.winnerSide)
    }

    @Test
    fun sheriffVoteCountsDouble() {
        val roles = listOf(
            WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR,
            WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
        )
        var s = base(roles).copy(
            phase = WerewolfPhase.VOTE,
            sheriff = 1,
            day = 1,
            votes = List(5) { null },
            nightDone = List(5) { true },
        )
        // Seats 0,2,3,4 alive; sheriff 1 votes for 0 (×2), others abstain or vote 3
        s = step(s, 0, WerewolfMove(target = -1))
        s = step(s, 1, WerewolfMove(target = 0))
        s = step(s, 2, WerewolfMove(target = -1))
        s = step(s, 3, WerewolfMove(target = -1))
        s = step(s, 4, WerewolfMove(target = -1))
        assertFalse(s.alive[0], "sheriff double vote lynches seat 0")
    }

    @Test
    fun botPicksLegalNightMove() {
        val s = game.initialState(6, Random(2))
        for (seat in game.actingSeats(s)) {
            val move = WerewolfBot(WerewolfBot.Level.EASY).chooseMove(s, seat, Random(3))
            assertNull(game.validate(s, seat, move), "seat $seat move $move")
        }
    }

    @Test
    fun hostSessionCapacityEight() {
        val net = LoopbackNetwork()
        val host = HostSession(game, net.host, "WOLF", Random(0))
        assertNotNull(host.addLocalPlayer("H", Platform.ANDROID))
        repeat(7) { assertNotNull(host.addBot("B$it", Platform.ANDROID, WerewolfBot())) }
        assertEquals(8, host.players.size)
        assertNull(host.addBot("extra", Platform.ANDROID, WerewolfBot()))
    }

    private fun base(roles: List<String>): WerewolfState =
        WerewolfState(
            players = roles.size,
            roles = roles,
            alive = List(roles.size) { true },
            phase = WerewolfPhase.NIGHT,
            nightTarget = List(roles.size) { -1 },
            nightDone = List(roles.size) { false },
            votes = List(roles.size) { null },
            revealed = List(roles.size) { null },
            witchHealLeft = roles.contains(WerewolfRole.WITCH),
            witchPoisonLeft = roles.contains(WerewolfRole.WITCH),
        )

    private fun step(state: WerewolfState, seat: Int, move: WerewolfMove): WerewolfState {
        assertNull(game.validate(state, seat, move), "invalid $move for $seat in ${state.phase}")
        return game.apply(state, seat, move, rng)
    }
}
