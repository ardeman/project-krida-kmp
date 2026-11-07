package com.ardeman.krida.core.games

import com.ardeman.krida.core.session.Bot
import kotlin.random.Random

/**
 * Congklak computer player. [Level.EASY] picks any legal hole; the others search ahead with
 * alpha-beta minimax over [CongklakGame.play] (extra turns are just plies where the same
 * seat moves again) and score positions by store difference. Ties break randomly, so the
 * bot does not play the same game twice.
 */
class CongklakBot(val level: Level) : Bot<CongklakState, CongklakMove> {

    enum class Level(internal val depth: Int) { EASY(0), NORMAL(2), HARD(5) }

    override fun chooseMove(state: CongklakState, seat: Int, random: Random): CongklakMove {
        val moves = legalHoles(state, seat)
        require(moves.isNotEmpty()) { "No legal move for seat $seat" }
        if (level.depth == 0) return CongklakMove(moves.random(random))

        var best = Int.MIN_VALUE
        val bestMoves = ArrayList<Int>()
        for (hole in moves) {
            val next = CongklakGame.play(state, seat, CongklakMove(hole)).state
            val value = search(next, seat, level.depth - 1, Int.MIN_VALUE, Int.MAX_VALUE)
            if (value > best) { best = value; bestMoves.clear() }
            if (value == best) bestMoves += hole
        }
        return CongklakMove(bestMoves.random(random))
    }

    /** Value of [state] for [me]; the seat to move maximizes if it is [me], else minimizes. */
    private fun search(state: CongklakState, me: Int, depth: Int, alpha: Int, beta: Int): Int {
        if (state.finished || depth == 0) return evaluate(state, me)
        var a = alpha
        var b = beta
        val maximizing = state.turn == me
        var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        for (hole in legalHoles(state, state.turn)) {
            val next = CongklakGame.play(state, state.turn, CongklakMove(hole)).state
            val value = search(next, me, depth - 1, a, b)
            if (maximizing) { best = maxOf(best, value); a = maxOf(a, value) }
            else { best = minOf(best, value); b = minOf(b, value) }
            if (a >= b) break
        }
        return best
    }

    private fun evaluate(state: CongklakState, me: Int): Int {
        val diff = CongklakGame.score(state, me) - CongklakGame.score(state, 1 - me)
        if (!state.finished) return diff
        // A finished game outweighs any open position; prefer bigger wins.
        return diff + when {
            diff > 0 -> WIN
            diff < 0 -> -WIN
            else -> 0
        }
    }

    private fun legalHoles(state: CongklakState, seat: Int) =
        (0 until CongklakGame.HOLES).filter { state.pits[CongklakGame.pit(seat, it)] > 0 }

    private companion object {
        const val WIN = 1_000
    }
}
