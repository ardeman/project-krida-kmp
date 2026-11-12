package com.ardeman.krida.core.session

import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Pure rules for one game. No I/O, no rendering, no clocks: the same code runs on an
 * Android host and an iOS host, so both always agree on what is legal.
 *
 * Randomness (dice) only comes through [random], which the host owns.
 */
interface GameRules<S, M> {
    /** Stable id, e.g. "congklak", "ludo". Sent in Welcome. */
    val id: String
    val minPlayers: Int
    val maxPlayers: Int
    val stateSerializer: KSerializer<S>
    val moveSerializer: KSerializer<M>

    fun initialState(players: Int, random: Random): S

    /** Seat whose turn it is (also the `turn` field in a `state` message). */
    fun currentTurn(state: S): Int

    /**
     * Seats that may submit a move now. Defaults to only [currentTurn]. Games with a
     * simultaneous phase (e.g. perang kapal placement) return every seat that still acts.
     */
    fun actingSeats(state: S): List<Int> = listOf(currentTurn(state))

    /** null when legal, otherwise a short reason code. */
    fun validate(state: S, seat: Int, move: M): String?

    /** Only called after [validate] returned null. */
    fun apply(state: S, seat: Int, move: M, random: Random): S

    fun isFinished(state: S): Boolean
}

/**
 * Games where each seat must not see the full board (fog of war). [HostSession] sends
 * [viewFor] to each remote peer and keeps the true state on the host. The host UI should
 * also call [viewFor] for the local seat before drawing.
 */
interface PrivateStateRules<S, M> {
    fun viewFor(state: S, seat: Int): S

    /**
     * Move to put in `last` for [viewer], or null to omit it (e.g. an opponent's ship
     * placement would leak coordinates).
     */
    fun viewMove(move: M, actor: Int, viewer: Int): M?
}

/**
 * Games where each player picks a pawn colour in the lobby. [HostSession] gives every new seat
 * the first free colour, lets players change it while the room waits, and starts each game
 * through [initialState] with the colours by seat.
 */
interface SeatColorRules<S> {
    /** Colours are `0 until colorCount`; no two seats share one. */
    val colorCount: Int

    fun initialState(players: Int, colors: List<Int>, random: Random): S
}

/**
 * Games whose host picks rule options before the game. [HostSession] sends [lobbyOptions] in
 * every `lobby` message, so guests can show the rules while they wait; they read them with
 * `LobbyOptions`.
 */
interface LobbyOptionRules {
    val lobbyOptions: JsonElement
}
