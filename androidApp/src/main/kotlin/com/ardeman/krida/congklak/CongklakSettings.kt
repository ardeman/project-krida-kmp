package com.ardeman.krida.congklak

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.CongklakRules

/** Congklak options stored on this device. Same key and default as iOS. */
object CongklakSettings {
    private const val PREFS = "congklak"
    private const val KEY_TEMBAK_AFTER_STORE = "congklak.tembakAfterStore"

    private lateinit var app: Context

    /**
     * The tembak rule for every mode started here (and rooms this device hosts): "Lewat
     * lumbung" (the Indonesian rule, default: only after this turn has passed your own
     * store) or "Bebas".
     */
    var tembakAfterStore by mutableStateOf(true)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        tembakAfterStore = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TEMBAK_AFTER_STORE, true)
    }

    fun chooseTembakAfterStore(value: Boolean) {
        tembakAfterStore = value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEMBAK_AFTER_STORE, value).apply()
    }

    /** Rules for a new host session, with the chosen tembak rule. */
    val rules: CongklakRules get() = CongklakRules(tembakAfterStore)
}
