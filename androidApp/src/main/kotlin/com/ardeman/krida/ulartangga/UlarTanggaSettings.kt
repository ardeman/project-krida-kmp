package com.ardeman.krida.ulartangga

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.games.UlarTanggaOptions
import com.ardeman.krida.core.games.UlarTanggaRules

/** Ular tangga choices stored on this device: player count, pawn colors and the rule options. */
object UlarTanggaSettings {
    private const val PREFS = "ular_tangga"
    private const val KEY_PLAYERS = "ularTangga.players"
    private const val KEY_BOUNCE = "ularTangga.bounceBack"
    private const val KEY_SIX_ENTER = "ularTangga.sixToEnter"
    private const val KEY_FIRST_ENDS = "ularTangga.firstFinishEnds"
    private const val KEY_SIX_AGAIN = "ularTangga.sixRollsAgain"
    private const val KEY_COLORS = "ularTangga.colors"

    private lateinit var app: Context

    /** Seats at the table, 2..4 (you plus computers when playing the computer). */
    var players by mutableIntStateOf(2)
        private set

    var options by mutableStateOf(UlarTanggaOptions())
        private set

    /** All four pawn colors in player order; player n takes the n-th. */
    var colors by mutableStateOf(List(UlarTanggaGame.COLORS) { it })
        private set

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs
        players = p.getInt(KEY_PLAYERS, 2).coerceIn(2, 4)
        options = UlarTanggaOptions(
            bounceBack = p.getBoolean(KEY_BOUNCE, false),
            sixToEnter = p.getBoolean(KEY_SIX_ENTER, false),
            firstFinishEnds = p.getBoolean(KEY_FIRST_ENDS, false),
            sixRollsAgain = p.getBoolean(KEY_SIX_AGAIN, true),
        )
        colors = UlarTanggaGame.seatColors(
            UlarTanggaGame.COLORS,
            p.getString(KEY_COLORS, null).orEmpty().mapNotNull { it.digitToIntOrNull() },
        )
    }

    /**
     * Gives [player] the next color no other player at the table has, swapping it with that
     * spare color; with four players there is none, so it swaps with whoever has the next color.
     */
    fun nextColor(player: Int) {
        val inUse = colors.take(players)
        val current = colors[player]
        val after = (1 until UlarTanggaGame.COLORS).map { (current + it) % UlarTanggaGame.COLORS }
        val next = after.firstOrNull { it !in inUse } ?: after.first()
        colors = colors.map { if (it == current) next else if (it == next) current else it }
        prefs.edit().putString(KEY_COLORS, colors.joinToString("")).apply()
    }

    fun choosePlayers(count: Int) {
        players = count.coerceIn(2, 4)
        prefs.edit().putInt(KEY_PLAYERS, players).apply()
    }

    fun chooseOptions(value: UlarTanggaOptions) {
        options = value
        prefs.edit()
            .putBoolean(KEY_BOUNCE, value.bounceBack)
            .putBoolean(KEY_SIX_ENTER, value.sixToEnter)
            .putBoolean(KEY_FIRST_ENDS, value.firstFinishEnds)
            .putBoolean(KEY_SIX_AGAIN, value.sixRollsAgain)
            .apply()
    }

    /** Rules for a new host session, with the chosen options. */
    val rules: UlarTanggaRules get() = UlarTanggaRules(options, colors.take(players))
}
