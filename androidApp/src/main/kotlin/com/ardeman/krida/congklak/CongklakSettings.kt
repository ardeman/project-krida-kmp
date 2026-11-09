package com.ardeman.krida.congklak

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.CongklakRules
import com.ardeman.krida.core.games.CongklakStarter

/** Congklak options stored on this device. Same key and default as iOS. */
object CongklakSettings {
    private const val PREFS = "congklak"
    private const val KEY_TEMBAK_AFTER_STORE = "congklak.tembakAfterStore"
    private const val KEY_STARTER = "congklak.starter"

    private lateinit var app: Context

    /**
     * The tembak rule for every mode started here (and rooms this device hosts): "Lewat
     * lumbung" (the Indonesian rule, default: only after this turn has passed your own
     * store) or "Bebas".
     */
    var tembakAfterStore by mutableStateOf(true)
        private set

    /** Who moves first in every mode started here: me, the opponent, or random (default). */
    var starter by mutableStateOf(CongklakStarter.RANDOM)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        tembakAfterStore = prefs.getBoolean(KEY_TEMBAK_AFTER_STORE, true)
        // Stored as "me" / "opponent" / "random", the same values as iOS.
        starter = CongklakStarter.entries.firstOrNull { it.name.lowercase() == prefs.getString(KEY_STARTER, null) }
            ?: CongklakStarter.RANDOM
    }

    fun chooseTembakAfterStore(value: Boolean) {
        tembakAfterStore = value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEMBAK_AFTER_STORE, value).apply()
    }

    fun chooseStarter(value: CongklakStarter) {
        starter = value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STARTER, value.name.lowercase()).apply()
    }

    /** Rules for a new host session, with the chosen tembak rule and starter. */
    val rules: CongklakRules get() = CongklakRules(tembakAfterStore, starter)
}
