package com.ardeman.krida.werewolf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.games.WerewolfBot
import com.ardeman.krida.core.games.WerewolfGame
import com.ardeman.krida.core.games.WerewolfMove
import com.ardeman.krida.core.games.WerewolfState
import com.ardeman.krida.core.session.GameRules

/**
 * Werewolf lobby choices on this device: table size (5–8) and computer difficulty.
 * This phone vs computers is supported; rooms stay Wi‑Fi / Online (no BLE).
 */
object WerewolfSettings {
    private const val PREFS = "werewolf"
    private const val KEY_SEATS = "werewolf.seats"
    private const val KEY_LEVEL = "werewolf.level"
    const val MIN_SEATS = 5
    const val MAX_SEATS = 8

    private lateinit var app: Context

    var targetSeats by mutableStateOf(MAX_SEATS)
        private set

    var botLevel by mutableStateOf(WerewolfBot.Level.NORMAL)
        private set

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs
        targetSeats = p.getInt(KEY_SEATS, MAX_SEATS).coerceIn(MIN_SEATS, MAX_SEATS)
        botLevel = WerewolfBot.Level.entries.getOrElse(p.getInt(KEY_LEVEL, WerewolfBot.Level.NORMAL.ordinal)) {
            WerewolfBot.Level.NORMAL
        }
    }

    fun chooseSeats(seats: Int) {
        targetSeats = seats.coerceIn(MIN_SEATS, MAX_SEATS)
        prefs.edit().putInt(KEY_SEATS, targetSeats).apply()
    }

    fun chooseLevel(level: WerewolfBot.Level) {
        botLevel = level
        prefs.edit().putInt(KEY_LEVEL, level.ordinal).apply()
    }

    val rules: GameRules<WerewolfState, WerewolfMove> get() = WerewolfGame

    fun bot(): WerewolfBot = WerewolfBot(botLevel)
}