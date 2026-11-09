package com.ardeman.krida.nearby

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.session.SavedRoom

/**
 * The running game this device left as a guest ([SavedRoom]), so the menu can offer to go
 * back and joining its code again rejoins the same seat. Same key and format as iOS.
 */
object LastRoom {
    private const val PREFS = "rooms"
    private const val KEY = "rooms.last"

    private lateinit var app: Context

    /** The saved seat while it is fresh; null when there is none. */
    var current by mutableStateOf<SavedRoom?>(null)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        current = SavedRoom.decode(prefs().getString(KEY, null))?.takeIf { it.isFresh(System.currentTimeMillis()) }
    }

    fun save(room: SavedRoom) {
        current = room
        prefs().edit().putString(KEY, room.encode()).apply()
    }

    /** The saved token for [code], to send when joining it. */
    fun tokenFor(code: String, online: Boolean): String? =
        current?.takeIf { it.code == code && it.online == online && it.isFresh(System.currentTimeMillis()) }?.token

    /** Forgets the seat in room [code] (any room when null). */
    fun clear(code: String? = null) {
        if (current == null || (code != null && current?.code != code)) return
        current = null
        prefs().edit().remove(KEY).apply()
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
