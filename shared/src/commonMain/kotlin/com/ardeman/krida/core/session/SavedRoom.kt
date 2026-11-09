package com.ardeman.krida.core.session

/**
 * A seat this device holds in someone else's running game, kept on disk so the player can
 * go back after leaving the room or closing the app. Joining [code] again sends [token] in
 * Hello, and the host gives the same seat back (from the computer, if it stood in).
 * The apps store it as one string ([encode]); Android and iOS use the same format.
 */
data class SavedRoom(
    val code: String,
    /** Through the online relay rather than a nearby (Bluetooth) room. */
    val online: Boolean,
    /** The room's game id (`GameRules.id`). */
    val game: String,
    val token: String,
    /** When the host welcomed this device, epoch milliseconds. */
    val savedAt: Long,
) {
    /** Still worth offering at [now]: younger than [MAX_AGE_MS]. */
    fun isFresh(now: Long): Boolean = now - savedAt in 0..MAX_AGE_MS

    fun encode(): String = listOf(code, if (online) "1" else "0", game, token, savedAt.toString()).joinToString("|")

    companion object {
        /** Long after any game ends; the relay also drops a room 10 min after it goes quiet. */
        const val MAX_AGE_MS: Long = 60 * 60_000L

        fun decode(text: String?): SavedRoom? {
            val parts = text?.split("|") ?: return null
            if (parts.size != 5 || parts.any { it.isEmpty() }) return null
            val savedAt = parts[4].toLongOrNull() ?: return null
            return SavedRoom(parts[0], parts[1] == "1", parts[2], parts[3], savedAt)
        }
    }
}
