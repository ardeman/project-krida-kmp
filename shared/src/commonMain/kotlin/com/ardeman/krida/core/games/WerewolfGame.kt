package com.ardeman.krida.core.games

import com.ardeman.krida.core.protocol.WerewolfClaims
import com.ardeman.krida.core.session.Bot
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.PrivateStateRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Dealt secret roles. Wire values are stable English ids. */
object WerewolfRole {
    const val WEREWOLF = "werewolf"
    const val VILLAGER = "villager"
    const val SEER = "seer"
    const val DOCTOR = "doctor"
    const val HUNTER = "hunter"
    const val WITCH = "witch"

    val ALL = listOf(WEREWOLF, VILLAGER, SEER, DOCTOR, HUNTER, WITCH)
}

/** Phase ids on [WerewolfState.phase]. */
object WerewolfPhase {
    const val NIGHT = "night"
    const val HUNTER = "hunter"
    const val SUCCESSOR = "successor"
    const val SHERIFF_ELECT = "sheriff_elect"
    const val DISCUSS = "discuss"
    const val VOTE = "vote"
    const val FINISHED = "finished"
}

@Serializable
data class WerewolfState(
    val players: Int,
    /** Dealt role per seat ([WerewolfRole]). Hidden in views except own + wolf teammates. */
    val roles: List<String>,
    val alive: List<Boolean>,
    val phase: String = WerewolfPhase.NIGHT,
    val day: Int = 1,
    /** Public Sheriff seat, or null if vacant. */
    val sheriff: Int? = null,
    /** Per-seat night pick: wolf / seer / doctor / witch-poison target. -1 = skipped / unset. */
    val nightTarget: List<Int> = emptyList(),
    /** Witch heal this night: null undecided, true heal, false skip. */
    val witchHeal: Boolean? = null,
    val witchHealLeft: Boolean = true,
    val witchPoisonLeft: Boolean = true,
    /** Seats that locked in their night action (or have none). */
    val nightDone: List<Boolean> = emptyList(),
    /** Seer-only: last peek target and role (stripped for others in [WerewolfGame.viewFor]). */
    val seerPeekTarget: Int? = null,
    val seerPeekRole: String? = null,
    /** Deaths announced at the last dawn (public). */
    val lastDeaths: List<Int> = emptyList(),
    /** Cause per [lastDeaths] entry: "wolf", "poison", "lynch", "hunter". */
    val lastDeathCauses: List<String> = emptyList(),
    /** Hunter who must shoot, if [phase] is hunter. */
    val hunterSeat: Int? = null,
    /** Dying sheriff who must name a successor. */
    val successorFrom: Int? = null,
    /** Per-seat vote target; -1 abstain; null not yet voted. */
    val votes: List<Int?> = emptyList(),
    val winnerSide: String? = null,
    /** Roles revealed when a seat dies (null while alive). */
    val revealed: List<String?> = emptyList(),
) {
    fun living(): List<Int> = alive.indices.filter { alive[it] }

    fun livingWolves(): List<Int> =
        living().filter { roles.getOrNull(it) == WerewolfRole.WEREWOLF }

    fun teamOf(seat: Int): String =
        if (roles.getOrNull(seat) == WerewolfRole.WEREWOLF) "wolves" else "town"
}

/**
 * Intent for the current phase. [target] is a living seat, or -1 to abstain / skip where
 * allowed. [witchHeal] is only for the witch on night. [advance] ends discussion (host or
 * timeout). [done] locks the witch after heal/poison choices.
 */
@Serializable
data class WerewolfMove(
    val target: Int? = null,
    val witchHeal: Boolean? = null,
    val done: Boolean = false,
    val advance: Boolean = false,
) {
    constructor() : this(null, null, false, false)
}

object WerewolfGame : GameRules<WerewolfState, WerewolfMove>, PrivateStateRules<WerewolfState, WerewolfMove> {
    override val id = "werewolf"
    override val minPlayers = 5
    override val maxPlayers = 8
    override val stateSerializer: KSerializer<WerewolfState> = WerewolfState.serializer()
    override val moveSerializer: KSerializer<WerewolfMove> = WerewolfMove.serializer()

    /** Role multiset for a lobby of [players] seats. */
    fun dealRoles(players: Int): List<String> {
        require(players in minPlayers..maxPlayers) { "werewolf is 5–8 players" }
        val pack = when (players) {
            5 -> listOf(
                WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR,
                WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
            )
            6 -> listOf(
                WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR,
                WerewolfRole.HUNTER, WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
            )
            7 -> listOf(
                WerewolfRole.WEREWOLF, WerewolfRole.WEREWOLF, WerewolfRole.SEER,
                WerewolfRole.DOCTOR, WerewolfRole.HUNTER,
                WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
            )
            else -> listOf(
                WerewolfRole.WEREWOLF, WerewolfRole.WEREWOLF, WerewolfRole.SEER,
                WerewolfRole.DOCTOR, WerewolfRole.HUNTER, WerewolfRole.WITCH,
                WerewolfRole.VILLAGER, WerewolfRole.VILLAGER,
            )
        }
        return pack
    }

    override fun initialState(players: Int, random: Random): WerewolfState {
        val roles = dealRoles(players).shuffled(random)
        return WerewolfState(
            players = players,
            roles = roles,
            alive = List(players) { true },
            phase = WerewolfPhase.NIGHT,
            day = 1,
            nightTarget = List(players) { -1 },
            nightDone = List(players) { false },
            votes = List(players) { null },
            revealed = List(players) { null },
        )
    }

    override fun currentTurn(state: WerewolfState): Int =
        actingSeats(state).firstOrNull() ?: 0

    override fun actingSeats(state: WerewolfState): List<Int> =
        when (state.phase) {
            WerewolfPhase.NIGHT -> nightActors(state).filter { !state.nightDone.getOrElse(it) { true } }
            WerewolfPhase.HUNTER -> listOfNotNull(state.hunterSeat)
            WerewolfPhase.SUCCESSOR -> listOfNotNull(state.successorFrom)
            WerewolfPhase.SHERIFF_ELECT, WerewolfPhase.VOTE ->
                state.living().filter { state.votes.getOrNull(it) == null }
            WerewolfPhase.DISCUSS -> listOf(0) // host advances after the discuss timer
            else -> emptyList()
        }

    override fun validate(state: WerewolfState, seat: Int, move: WerewolfMove): String? {
        if (state.phase == WerewolfPhase.FINISHED) return "finished"
        if (seat !in actingSeats(state) && !(state.phase == WerewolfPhase.DISCUSS && move.advance && seat == 0)) {
            return "not_your_turn"
        }
        return when (state.phase) {
            WerewolfPhase.NIGHT -> validateNight(state, seat, move)
            WerewolfPhase.HUNTER -> {
                val t = move.target ?: return "need_target"
                if (t !in state.living() || t == seat) "bad_target" else null
            }
            WerewolfPhase.SUCCESSOR -> {
                val t = move.target ?: return "need_target"
                if (t !in state.living()) "bad_target" else null
            }
            WerewolfPhase.SHERIFF_ELECT -> {
                val t = move.target ?: return "need_target"
                if (t !in state.living()) "bad_target" else null
            }
            WerewolfPhase.VOTE -> {
                if (move.target == -1) null
                else {
                    val t = move.target ?: return "need_target"
                    if (t !in state.living() || t == seat) "bad_target" else null
                }
            }
            WerewolfPhase.DISCUSS -> if (move.advance && seat == 0) null else "not_advance"
            else -> "bad_phase"
        }
    }

    override fun apply(state: WerewolfState, seat: Int, move: WerewolfMove, random: Random): WerewolfState =
        when (state.phase) {
            WerewolfPhase.NIGHT -> applyNight(state, seat, move, random)
            WerewolfPhase.HUNTER -> applyHunter(state, seat, move.target!!, random)
            WerewolfPhase.SUCCESSOR -> applySuccessor(state, move.target!!, random)
            WerewolfPhase.SHERIFF_ELECT -> applyElectVote(state, seat, move.target!!, random)
            WerewolfPhase.VOTE -> applyLynchVote(state, seat, move.target ?: -1, random)
            WerewolfPhase.DISCUSS -> state.copy(phase = WerewolfPhase.VOTE, votes = List(state.players) { null })
            else -> state
        }

    override fun isFinished(state: WerewolfState): Boolean =
        state.phase == WerewolfPhase.FINISHED || state.winnerSide != null

    override fun viewFor(state: WerewolfState, seat: Int): WerewolfState {
        val wolf = state.roles.getOrNull(seat) == WerewolfRole.WEREWOLF
        val roles = state.roles.mapIndexed { i, role ->
            when {
                i == seat -> role
                wolf && role == WerewolfRole.WEREWOLF -> role
                state.revealed.getOrNull(i) != null -> role
                else -> ""
            }
        }
        val seer = state.roles.getOrNull(seat) == WerewolfRole.SEER
        return state.copy(
            roles = roles,
            seerPeekTarget = if (seer) state.seerPeekTarget else null,
            seerPeekRole = if (seer) state.seerPeekRole else null,
            // Other seats' undecided night picks stay private except fellow wolves' wolf votes.
            nightTarget = state.nightTarget.mapIndexed { i, t ->
                when {
                    i == seat -> t
                    wolf && state.roles.getOrNull(i) == WerewolfRole.WEREWOLF -> t
                    else -> -1
                }
            },
            witchHeal = if (state.roles.getOrNull(seat) == WerewolfRole.WITCH) state.witchHeal else null,
        )
    }

    override fun viewMove(move: WerewolfMove, actor: Int, viewer: Int): WerewolfMove? =
        if (actor == viewer) move else WerewolfMove(advance = move.advance)

    /** Close night for anyone still pending (timer). */
    fun timeoutMove(state: WerewolfState, seat: Int, random: Random): WerewolfMove? {
        if (seat !in actingSeats(state)) return null
        return WerewolfBot(WerewolfBot.Level.EASY).chooseMove(state, seat, random)
    }

    fun nightActors(state: WerewolfState): List<Int> =
        state.living().filter { seat ->
            when (state.roles[seat]) {
                WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR -> true
                WerewolfRole.WITCH -> state.witchHealLeft || state.witchPoisonLeft
                else -> false
            }
        }

    private fun validateNight(state: WerewolfState, seat: Int, move: WerewolfMove): String? {
        val role = state.roles[seat]
        return when (role) {
            WerewolfRole.WEREWOLF -> {
                val t = move.target ?: return "need_target"
                if (t !in state.living() || t == seat) "bad_target" else null
            }
            WerewolfRole.SEER -> {
                val t = move.target ?: return "need_target"
                if (t !in state.living() || t == seat) "bad_target" else null
            }
            WerewolfRole.DOCTOR -> {
                val t = move.target ?: return "need_target"
                if (t !in state.living()) "bad_target" else null
            }
            WerewolfRole.WITCH -> {
                if (move.done) return null
                if (move.witchHeal != null) {
                    if (!state.witchHealLeft && move.witchHeal) return "no_heal"
                    return null
                }
                if (move.target != null) {
                    if (!state.witchPoisonLeft) return "no_poison"
                    if (move.target != -1 && move.target !in state.living()) return "bad_target"
                    return null
                }
                "need_action"
            }
            else -> "no_night_action"
        }
    }

    private fun applyNight(
        state: WerewolfState,
        seat: Int,
        move: WerewolfMove,
        random: Random,
    ): WerewolfState {
        var s = state
        val role = s.roles[seat]
        when (role) {
            WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR -> {
                val t = move.target!!
                val targets = s.nightTarget.toMutableList()
                targets[seat] = t
                val done = s.nightDone.toMutableList()
                done[seat] = true
                var peekTarget = s.seerPeekTarget
                var peekRole = s.seerPeekRole
                if (role == WerewolfRole.SEER && t in s.living()) {
                    peekTarget = t
                    peekRole = s.roles[t]
                }
                s = s.copy(
                    nightTarget = targets,
                    nightDone = done,
                    seerPeekTarget = peekTarget,
                    seerPeekRole = peekRole,
                )
            }
            WerewolfRole.WITCH -> {
                if (move.witchHeal != null) {
                    s = s.copy(witchHeal = move.witchHeal)
                }
                if (move.target != null) {
                    val targets = s.nightTarget.toMutableList()
                    targets[seat] = move.target
                    s = s.copy(nightTarget = targets)
                }
                if (move.done || (move.witchHeal != null && move.target != null) ||
                    (move.witchHeal != null && !s.witchPoisonLeft) ||
                    (move.target != null && !s.witchHealLeft)
                ) {
                    val done = s.nightDone.toMutableList()
                    done[seat] = true
                    s = s.copy(nightDone = done)
                }
            }
        }
        return if (nightActors(s).all { s.nightDone.getOrElse(it) { true } }) {
            resolveNight(s, random)
        } else s
    }

    private fun majorityTarget(wolfSeats: List<Int>, targets: List<Int>, random: Random): Int? {
        val counts = mutableMapOf<Int, Int>()
        for (seat in wolfSeats) {
            val t = targets.getOrElse(seat) { -1 }
            if (t >= 0) counts[t] = (counts[t] ?: 0) + 1
        }
        if (counts.isEmpty()) return null
        val best = counts.values.maxOrNull()!!
        val tied = counts.filter { it.value == best }.keys.toList()
        return tied[random.nextInt(tied.size)]
    }

    private fun resolveNight(state: WerewolfState, random: Random): WerewolfState {
        val wolves = state.livingWolves()
        val wolfTarget = majorityTarget(wolves, state.nightTarget, random)
        val doctor = state.living().firstOrNull { state.roles[it] == WerewolfRole.DOCTOR }
        val protect = doctor?.let { state.nightTarget.getOrElse(it) { -1 } }?.takeIf { it >= 0 }
        val witch = state.living().firstOrNull { state.roles[it] == WerewolfRole.WITCH }
        val heal = witch != null && state.witchHeal == true && state.witchHealLeft
        val poison = witch?.let { state.nightTarget.getOrElse(it) { -1 } }?.takeIf { it >= 0 && state.witchPoisonLeft }

        val deaths = mutableListOf<Int>()
        val causes = mutableListOf<String>()
        if (wolfTarget != null) {
            val saved = protect == wolfTarget || heal
            if (!saved) {
                deaths += wolfTarget
                causes += "wolf"
            }
        }
        if (poison != null && poison !in deaths) {
            deaths += poison
            causes += "poison"
        }

        var healLeft = state.witchHealLeft
        var poisonLeft = state.witchPoisonLeft
        if (heal) healLeft = false
        if (poison != null) poisonLeft = false

        var s = kill(state, deaths, causes).copy(
            witchHealLeft = healLeft,
            witchPoisonLeft = poisonLeft,
            witchHeal = null,
            nightTarget = List(state.players) { -1 },
            nightDone = List(state.players) { false },
            lastDeaths = deaths,
            lastDeathCauses = causes,
        )
        s = checkWin(s) ?: s
        if (s.winnerSide != null) return s.copy(phase = WerewolfPhase.FINISHED)

        val hunterDead = deaths.firstOrNull { state.roles[it] == WerewolfRole.HUNTER }
        if (hunterDead != null) {
            return s.copy(phase = WerewolfPhase.HUNTER, hunterSeat = hunterDead)
        }
        return afterDeaths(s, deaths, random)
    }

    private fun applyHunter(state: WerewolfState, hunter: Int, target: Int, random: Random): WerewolfState {
        var s = kill(state, listOf(target), listOf("hunter")).copy(
            phase = WerewolfPhase.DISCUSS, // may override below
            hunterSeat = null,
            lastDeaths = state.lastDeaths + target,
            lastDeathCauses = state.lastDeathCauses + "hunter",
        )
        s = checkWin(s) ?: s
        if (s.winnerSide != null) return s.copy(phase = WerewolfPhase.FINISHED)
        return afterDeaths(s, state.lastDeaths + target, random)
    }

    private fun afterDeaths(state: WerewolfState, deaths: List<Int>, random: Random): WerewolfState {
        val sheriffDied = state.sheriff != null && state.sheriff in deaths
        if (sheriffDied) {
            return state.copy(
                phase = WerewolfPhase.SUCCESSOR,
                successorFrom = state.sheriff,
                sheriff = null,
            )
        }
        if (state.sheriff == null) {
            return state.copy(
                phase = WerewolfPhase.SHERIFF_ELECT,
                votes = List(state.players) { null },
                successorFrom = null,
            )
        }
        return state.copy(
            phase = WerewolfPhase.DISCUSS,
            votes = List(state.players) { null },
            successorFrom = null,
        )
    }

    private fun applySuccessor(state: WerewolfState, target: Int, random: Random): WerewolfState {
        val s = state.copy(sheriff = target, successorFrom = null, phase = WerewolfPhase.DISCUSS)
        return s
    }

    private fun applyElectVote(state: WerewolfState, seat: Int, target: Int, random: Random): WerewolfState {
        val votes = state.votes.toMutableList()
        votes[seat] = target
        val s = state.copy(votes = votes)
        if (s.living().any { s.votes.getOrNull(it) == null }) return s
        val counts = mutableMapOf<Int, Int>()
        for (v in s.living()) {
            val t = s.votes[v] ?: continue
            counts[t] = (counts[t] ?: 0) + 1
        }
        val best = counts.values.maxOrNull() ?: return s.copy(phase = WerewolfPhase.DISCUSS, votes = List(s.players) { null })
        val tied = counts.filter { it.value == best }.keys.toList()
        val winner = tied[random.nextInt(tied.size)]
        return s.copy(
            sheriff = winner,
            phase = WerewolfPhase.DISCUSS,
            votes = List(s.players) { null },
        )
    }

    private fun applyLynchVote(state: WerewolfState, seat: Int, target: Int, random: Random): WerewolfState {
        val votes = state.votes.toMutableList()
        votes[seat] = target
        val s = state.copy(votes = votes)
        if (s.living().any { s.votes.getOrNull(it) == null }) return s

        val counts = mutableMapOf<Int, Int>()
        for (v in s.living()) {
            val t = s.votes[v] ?: continue
            if (t < 0) continue
            var weight = 1
            if (v == s.sheriff) weight = 2
            counts[t] = (counts[t] ?: 0) + weight
        }
        // Plurality with a unique winner; tie → no lynch. Sheriff ballots already count ×2.
        val unique = run {
            val top = counts.values.maxOrNull() ?: return@run null
            val tied = counts.filter { it.value == top }.keys
            if (tied.size == 1) tied.first() else null
        }

        var next = s.copy(votes = List(s.players) { null }, lastDeaths = emptyList(), lastDeathCauses = emptyList())
        if (unique != null) {
            next = kill(next, listOf(unique), listOf("lynch")).copy(
                lastDeaths = listOf(unique),
                lastDeathCauses = listOf("lynch"),
            )
            next = checkWin(next) ?: next
            if (next.winnerSide != null) return next.copy(phase = WerewolfPhase.FINISHED)
            if (next.roles.getOrNull(unique) == WerewolfRole.HUNTER && !next.alive[unique]) {
                return next.copy(phase = WerewolfPhase.HUNTER, hunterSeat = unique)
            }
            if (next.sheriff == unique) {
                return next.copy(phase = WerewolfPhase.SUCCESSOR, successorFrom = unique, sheriff = null)
            }
        }
        return next.copy(
            phase = WerewolfPhase.NIGHT,
            day = next.day + 1,
            nightTarget = List(next.players) { -1 },
            nightDone = List(next.players) { false },
            witchHeal = null,
        )
    }

    private fun kill(state: WerewolfState, seats: List<Int>, causes: List<String>): WerewolfState {
        if (seats.isEmpty()) return state
        val alive = state.alive.toMutableList()
        val revealed = state.revealed.toMutableList()
        for (i in seats.indices) {
            val seat = seats[i]
            if (seat in alive.indices && alive[seat]) {
                alive[seat] = false
                revealed[seat] = state.roles[seat]
            }
        }
        return state.copy(alive = alive, revealed = revealed)
    }

    /** Returns state with winnerSide set, or null if the game continues. */
    private fun checkWin(state: WerewolfState): WerewolfState? {
        val living = state.living()
        val wolves = living.count { state.roles[it] == WerewolfRole.WEREWOLF }
        val town = living.size - wolves
        return when {
            wolves == 0 -> state.copy(winnerSide = "town", phase = WerewolfPhase.FINISHED)
            wolves >= town -> state.copy(winnerSide = "wolves", phase = WerewolfPhase.FINISHED)
            else -> null
        }
    }
}

class WerewolfBot(val level: Level = Level.NORMAL) : Bot<WerewolfState, WerewolfMove> {
    enum class Level { EASY, NORMAL, HARD }

    constructor() : this(Level.NORMAL)

    override fun chooseMove(state: WerewolfState, seat: Int, random: Random): WerewolfMove {
        return when (state.phase) {
            WerewolfPhase.DISCUSS -> WerewolfMove(advance = true)
            WerewolfPhase.HUNTER, WerewolfPhase.SUCCESSOR -> {
                val opts = state.living().filter { it != seat }
                WerewolfMove(target = opts.random(random))
            }
            WerewolfPhase.SHERIFF_ELECT -> WerewolfMove(target = state.living().random(random))
            WerewolfPhase.VOTE -> vote(state, seat, random)
            WerewolfPhase.NIGHT -> night(state, seat, random)
            else -> WerewolfMove(target = -1)
        }
    }

    private fun night(state: WerewolfState, seat: Int, random: Random): WerewolfMove {
        val role = state.roles[seat]
        val living = state.living()
        when (role) {
            WerewolfRole.WEREWOLF -> {
                val prey = living.filter { state.roles[it] != WerewolfRole.WEREWOLF }
                val pool = if (level == Level.EASY) living.filter { it != seat } else prey.ifEmpty { living.filter { it != seat } }
                val target = if (level == Level.HARD && prey.isNotEmpty()) {
                    // Align: pick the majority of other wolves' picks if any, else random prey.
                    val others = state.livingWolves().filter { it != seat }
                    val voted = others.map { state.nightTarget[it] }.filter { it >= 0 }
                    voted.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                        ?: prey.random(random)
                } else pool.random(random)
                return WerewolfMove(target = target)
            }
            WerewolfRole.SEER -> {
                val opts = living.filter { it != seat }
                return WerewolfMove(target = opts.random(random))
            }
            WerewolfRole.DOCTOR -> {
                val target = when (level) {
                    Level.EASY -> living.random(random)
                    else -> if (random.nextBoolean()) seat else living.random(random)
                }
                return WerewolfMove(target = target)
            }
            WerewolfRole.WITCH -> {
                if (level == Level.EASY) return WerewolfMove(witchHeal = false, target = -1, done = true)
                val wolves = state.livingWolves()
                val wolfTarget = WerewolfGame.run {
                    // peek at majority without resolving
                    val counts = mutableMapOf<Int, Int>()
                    for (w in wolves) {
                        val t = state.nightTarget.getOrElse(w) { -1 }
                        if (t >= 0) counts[t] = (counts[t] ?: 0) + 1
                    }
                    counts.maxByOrNull { it.value }?.key
                }
                val heal = state.witchHealLeft && wolfTarget == seat
                val poison = if (level == Level.HARD && state.witchPoisonLeft && random.nextBoolean()) {
                    living.filter { state.roles[it] != WerewolfRole.WITCH }.randomOrNull(random) ?: -1
                } else -1
                return WerewolfMove(witchHeal = heal, target = poison, done = true)
            }
            else -> return WerewolfMove(done = true)
        }
    }

    private fun vote(state: WerewolfState, seat: Int, random: Random): WerewolfMove {
        val living = state.living().filter { it != seat }
        if (living.isEmpty()) return WerewolfMove(target = -1)
        if (level == Level.EASY) return WerewolfMove(target = living.random(random))
        val role = state.roles[seat]
        if (role == WerewolfRole.WEREWOLF) {
            val town = living.filter { state.roles[it] != WerewolfRole.WEREWOLF }
            val target = if (level == Level.HARD && town.isNotEmpty()) {
                val allies = state.livingWolves().filter { it != seat }
                val voted = allies.mapNotNull { state.votes[it] }.filter { it >= 0 }
                voted.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: town.random(random)
            } else town.randomOrNull(random) ?: living.random(random)
            return WerewolfMove(target = target)
        }
        // Town: prefer last night's wolf victim's... they are dead. Prefer someone not self.
        val suspect = state.lastDeaths.firstOrNull()?.let { /* no-op */ null }
        return WerewolfMove(target = living.random(random))
    }

    /**
     * Discussion claim for [seat], or null to stay quiet this beat. Host apps call this on a
     * timer during [WerewolfPhase.DISCUSS] (bots are not in [actingSeats] then).
     */
    fun chooseClaim(state: WerewolfState, seat: Int, random: Random): Pair<String, Int?>? {
        if (state.phase != WerewolfPhase.DISCUSS) return null
        if (!state.alive.getOrElse(seat) { false }) return null
        // Pace the feed: about half the ticks a bot stays quiet.
        if (random.nextFloat() > 0.55f) return null
        val others = state.living().filter { it != seat }
        if (others.isEmpty()) return null
        val role = state.roles.getOrElse(seat) { WerewolfRole.VILLAGER }
        val id = when (role) {
            WerewolfRole.WEREWOLF ->
                listOf("suspect", "trust", "saw_wolf", "i_villager", "pass").random(random)
            WerewolfRole.SEER ->
                listOf("suspect", "saw_wolf", "i_seer", "trust", "pass").random(random)
            WerewolfRole.DOCTOR ->
                listOf("trust", "i_doctor", "suspect", "i_villager", "pass").random(random)
            WerewolfRole.HUNTER ->
                listOf("suspect", "i_hunter", "trust", "pass").random(random)
            else ->
                listOf("suspect", "trust", "i_villager", "pass").random(random)
        }
        if (!WerewolfClaims.needsTarget(id)) return id to null
        val target = when {
            role == WerewolfRole.WEREWOLF && (id == "suspect" || id == "saw_wolf") ->
                others.filter { state.roles[it] != WerewolfRole.WEREWOLF }
                    .randomOrNull(random) ?: others.random(random)
            role == WerewolfRole.WEREWOLF && id == "trust" ->
                state.livingWolves().filter { it != seat }.randomOrNull(random)
                    ?: others.random(random)
            else -> others.random(random)
        }
        return id to target
    }
}
