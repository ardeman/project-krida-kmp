package com.ardeman.krida.ulartangga

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.UlarTanggaOptions
import com.ardeman.krida.core.games.UlarTanggaRules
import com.ardeman.krida.core.session.LocalSeat
import com.ardeman.krida.core.session.LocalSeats

/** Ular tangga choices stored on this device: the seats for a game on this phone and the rule options. */
object UlarTanggaSettings {
    private const val PREFS = "ular_tangga"
    private const val KEY_PLAYERS = "ularTangga.players"
    private const val KEY_SEATS = "ularTangga.seats"
    const val SEATS = 4
    private const val KEY_BOUNCE = "ularTangga.bounceBack"
    private const val KEY_SIX_ENTER = "ularTangga.sixToEnter"
    private const val KEY_FIRST_ENDS = "ularTangga.firstFinishEnds"
    private const val KEY_SIX_AGAIN = "ularTangga.sixRollsAgain"

    private lateinit var app: Context

    /** The four seat tiles of a game on this phone: person, computer or empty. */
    var seats by mutableStateOf(defaultSeats(2))
        private set

    var options by mutableStateOf(UlarTanggaOptions())
        private set

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs
        // Before the seat tiles the card stored a player count; it becomes that many people.
        seats = LocalSeats.decode(p.getString(KEY_SEATS, null), SEATS, defaultSeats(p.getInt(KEY_PLAYERS, 2).coerceIn(2, SEATS)))
        options = UlarTanggaOptions(
            bounceBack = p.getBoolean(KEY_BOUNCE, false),
            sixToEnter = p.getBoolean(KEY_SIX_ENTER, false),
            firstFinishEnds = p.getBoolean(KEY_FIRST_ENDS, false),
            sixRollsAgain = p.getBoolean(KEY_SIX_AGAIN, true),
        )
    }

    /** A tap on seat tile [index]: person → computer → empty → person. */
    fun tapSeat(index: Int) {
        seats = LocalSeats.next(seats, index, allowEmpty = true)
        prefs.edit().putString(KEY_SEATS, LocalSeats.encode(seats)).apply()
    }

    private fun defaultSeats(people: Int) = List(SEATS) { if (it < people) LocalSeat.PERSON else LocalSeat.EMPTY }

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
