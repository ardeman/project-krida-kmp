package com.ardeman.krida.ulartangga

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.UlarTanggaOptions
import com.ardeman.krida.core.games.UlarTanggaRules

/** Ular tangga choices stored on this device: player count and the rule options. */
object UlarTanggaSettings {
    private const val PREFS = "ular_tangga"
    private const val KEY_PLAYERS = "ularTangga.players"
    private const val KEY_BOUNCE = "ularTangga.bounceBack"
    private const val KEY_SIX_ENTER = "ularTangga.sixToEnter"
    private const val KEY_FIRST_ENDS = "ularTangga.firstFinishEnds"
    private const val KEY_SIX_AGAIN = "ularTangga.sixRollsAgain"

    private lateinit var app: Context

    /** Seats at the table, 2..4 (you plus computers when playing the computer). */
    var players by mutableIntStateOf(2)
        private set

    var options by mutableStateOf(UlarTanggaOptions())
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
    val rules: UlarTanggaRules get() = UlarTanggaRules(options)
}
