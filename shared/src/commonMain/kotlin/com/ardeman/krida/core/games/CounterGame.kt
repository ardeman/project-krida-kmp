package com.ardeman.krida.core.games

import com.ardeman.krida.core.session.GameRules
import kotlin.random.Random
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Demo game for testing connectivity between real devices (and for unit tests): players take turns adding 1..3; reaching 10 ends the game.
 */
@Serializable
data class CounterState(val total: Int, val turn: Int, val players: Int)

@Serializable
data class CounterMove(val add: Int)

object CounterGame : GameRules<CounterState, CounterMove> {
    override val id = "counter"
    override val minPlayers = 2
    override val maxPlayers = 4
    override val stateSerializer: KSerializer<CounterState> = CounterState.serializer()
    override val moveSerializer: KSerializer<CounterMove> = CounterMove.serializer()

    override fun initialState(players: Int, random: Random) = CounterState(0, 0, players)
    override fun currentTurn(state: CounterState) = state.turn
    override fun validate(state: CounterState, seat: Int, move: CounterMove) =
        if (move.add in 1..3) null else "add_1_to_3"
    override fun apply(state: CounterState, seat: Int, move: CounterMove, random: Random) =
        state.copy(total = state.total + move.add, turn = (state.turn + 1) % state.players)
    override fun isFinished(state: CounterState) = state.total >= 10
}
