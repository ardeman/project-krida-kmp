package com.ardeman.krida.gaple

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.GapleOptions
import com.ardeman.krida.core.games.GapleRules
import com.ardeman.krida.core.session.LocalSeat
import com.ardeman.krida.core.session.LocalSeats

/**
 * Gaple choices on this device. On this phone the first seat is you and the other three are a
 * computer or empty (at least one computer); two people on one phone is out of scope, since
 * hands are hidden. Partners only apply with four seats.
 */
object GapleSettings {
    private const val PREFS = "gaple"
    private const val KEY_SEATS = "gaple.seats"
    private const val KEY_PARTNERS = "gaple.partners"
    private const val KEY_DRAW = "gaple.draw"
    const val SEATS = 4

    private val DEFAULT = listOf(LocalSeat.PERSON, LocalSeat.COMPUTER, LocalSeat.COMPUTER, LocalSeat.COMPUTER)

    private lateinit var app: Context

    var seats by mutableStateOf(DEFAULT)
        private set

    var options by mutableStateOf(GapleOptions())
        private set

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs
        seats = sanitize(LocalSeats.decode(p.getString(KEY_SEATS, null), SEATS, DEFAULT))
        options = GapleOptions(partners = p.getBoolean(KEY_PARTNERS, false), draw = p.getBoolean(KEY_DRAW, false))
    }

    /** Seats 1–3 switch between a computer and empty; one computer always stays. */
    fun tapSeat(index: Int) {
        if (index !in 1 until SEATS) return
        val next = seats.toMutableList()
        next[index] = if (next[index] == LocalSeat.COMPUTER) LocalSeat.EMPTY else LocalSeat.COMPUTER
        if (next.count { it == LocalSeat.COMPUTER } == 0) return
        seats = next
        prefs.edit().putString(KEY_SEATS, LocalSeats.encode(seats)).apply()
    }

    fun choosePartners(on: Boolean) {
        options = options.copy(partners = on)
        prefs.edit().putBoolean(KEY_PARTNERS, on).apply()
    }

    fun chooseDraw(on: Boolean) {
        options = options.copy(draw = on)
        prefs.edit().putBoolean(KEY_DRAW, on).apply()
    }

    /** Players at the table on this phone (you plus the computers). */
    val players: Int get() = seats.count { it != LocalSeat.EMPTY }

    val rules: GapleRules get() = GapleRules(options)

    private fun sanitize(list: List<LocalSeat>): List<LocalSeat> {
        val fixed = list.mapIndexed { i, s -> if (i == 0) LocalSeat.PERSON else if (s == LocalSeat.PERSON) LocalSeat.COMPUTER else s }
        return if (fixed.none { it == LocalSeat.COMPUTER }) DEFAULT else fixed
    }
}
