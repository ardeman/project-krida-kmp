package com.ardeman.krida.core.session

import kotlin.random.Random

/**
 * A computer player. Runs only on the host, inside [HostSession]; guests see it as an
 * ordinary player flagged `bot`. Pure like [GameRules]: no I/O, no clocks, randomness only
 * through [random].
 */
interface Bot<S, M> {
    /** A move for [seat] that [GameRules.validate] accepts. Only called on [seat]'s turn. */
    fun chooseMove(state: S, seat: Int, random: Random): M
}
