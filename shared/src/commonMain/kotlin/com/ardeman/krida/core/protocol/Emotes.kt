package com.ardeman.krida.core.protocol

/**
 * Fixed bilingual reaction set for nearby and online play. Ids travel on the wire; each app
 * shows [glyph] plus a localized label. No free text, so no moderation.
 */
object Emotes {
    data class Item(val id: String, val glyph: String)

    val ALL: List<Item> = listOf(
        Item("nice", "👍"),
        Item("lets_go", "💪"),
        Item("awesome", "🎉"),
        Item("oops", "😅"),
        Item("hmm", "🤔"),
        Item("ready", "✊"),
    )

    /** Hosts drop unknown ids so older or crafted clients cannot invent free text. */
    fun valid(id: String): Boolean = ALL.any { it.id == id }

    fun glyph(id: String): String? = ALL.firstOrNull { it.id == id }?.glyph

    /** Minimum gap between two emotes from the same seat (host-enforced). */
    const val COOLDOWN_MS: Long = 2_000
}
