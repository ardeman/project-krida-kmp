package com.ardeman.krida.ludo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.LudoOptions
import com.ardeman.krida.core.games.LudoRules
import com.ardeman.krida.core.session.LocalSeat
import com.ardeman.krida.core.session.LocalSeats

/** Ludo choices on this device: seats for a game on this phone and the rule options. */
object LudoSettings {
    private const val PREFS = "ludo"
    private const val KEY_SEATS = "ludo.seats"
    const val SEATS = 4
    private const val KEY_SIX_ENTER = "ludo.sixToEnter"
    private const val KEY_SIX_AGAIN = "ludo.sixRollsAgain"
    private const val KEY_CAPTURE_AGAIN = "ludo.captureRollsAgain"
    private const val KEY_FIRST_ENDS = "ludo.firstFinishEnds"
    private const val KEY_BLOCK_PASS = "ludo.blocksStopPassing"

    private lateinit var app: Context

    var seats by mutableStateOf(defaultSeats(2))
        private set

    var options by mutableStateOf(LudoOptions())
        private set

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs
        seats = LocalSeats.decode(p.getString(KEY_SEATS, null), SEATS, defaultSeats(2))
        options = LudoOptions(
            sixToEnter = p.getBoolean(KEY_SIX_ENTER, true),
            sixRollsAgain = p.getBoolean(KEY_SIX_AGAIN, true),
            captureRollsAgain = p.getBoolean(KEY_CAPTURE_AGAIN, true),
            firstFinishEnds = p.getBoolean(KEY_FIRST_ENDS, false),
            blocksStopPassing = p.getBoolean(KEY_BLOCK_PASS, false),
        )
    }

    fun tapSeat(index: Int) {
        seats = LocalSeats.next(seats, index, allowEmpty = true)
        prefs.edit().putString(KEY_SEATS, LocalSeats.encode(seats)).apply()
    }

    private fun defaultSeats(people: Int) = List(SEATS) { if (it < people) LocalSeat.PERSON else LocalSeat.EMPTY }

    fun chooseOptions(value: LudoOptions) {
        options = value
        prefs.edit()
            .putBoolean(KEY_SIX_ENTER, value.sixToEnter)
            .putBoolean(KEY_SIX_AGAIN, value.sixRollsAgain)
            .putBoolean(KEY_CAPTURE_AGAIN, value.captureRollsAgain)
            .putBoolean(KEY_FIRST_ENDS, value.firstFinishEnds)
            .putBoolean(KEY_BLOCK_PASS, value.blocksStopPassing)
            .apply()
    }

    val rules: LudoRules get() = LudoRules(options)
}
