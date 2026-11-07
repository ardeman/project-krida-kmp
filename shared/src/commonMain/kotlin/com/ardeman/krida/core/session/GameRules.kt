package com.ardeman.krida.core.session

import kotlin.random.Random
import kotlinx.serialization.KSerializer

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

    /** Seat whose turn it is. */
    fun currentTurn(state: S): Int

    /** null when legal, otherwise a short reason code. */
    fun validate(state: S, seat: Int, move: M): String?

    /** Only called after [validate] returned null. */
    fun apply(state: S, seat: Int, move: M, random: Random): S

    fun isFinished(state: S): Boolean
}
