package com.ardeman.krida.core.protocol

/**
 * Preset Werewolf discussion claims. Ids travel on the wire; each app shows a localized
 * line, optionally filling a target seat name. No free text.
 */
object WerewolfClaims {
    data class Item(
        val id: String,
        /** When true the sender must pick a living seat other than themselves. */
        val needsTarget: Boolean,
    )

    val ALL: List<Item> = listOf(
        Item("suspect", needsTarget = true),
        Item("trust", needsTarget = true),
        Item("saw_wolf", needsTarget = true),
        Item("i_seer", needsTarget = false),
        Item("i_doctor", needsTarget = false),
        Item("i_villager", needsTarget = false),
        Item("i_hunter", needsTarget = false),
        Item("pass", needsTarget = false),
    )

    fun valid(id: String): Boolean = ALL.any { it.id == id }

    fun needsTarget(id: String): Boolean = ALL.firstOrNull { it.id == id }?.needsTarget == true

    fun item(id: String): Item? = ALL.firstOrNull { it.id == id }

    /** Minimum gap between two claims from the same seat (host-enforced). */
    const val COOLDOWN_MS: Long = 3_000
}
