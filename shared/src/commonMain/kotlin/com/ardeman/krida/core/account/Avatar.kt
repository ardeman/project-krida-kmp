package com.ardeman.krida.core.account

/**
 * Drawn avatars for signed-in players (online step 10d): a shape index with independent
 * background and symbol colours, or the Google profile picture. No image assets. The wire
 * form is `{shape}-{bg}-{fg}` (zero-based indices), or `g` for Google; see docs/protocol.md
 * and docs/mockups/profile.md.
 */
object Avatar {
    const val SHAPE_COUNT = 12
    const val COLOUR_COUNT = 14
    const val GOOGLE = "g"

    /** Hosts a client may load a Google picture from; anything else is treated as neutral. */
    const val GOOGLE_PICTURE_HOST = "lh3.googleusercontent.com"

    /**
     * Palette shared by background and symbol: the twelve shape colours, then dark brown and
     * white. ARGB ints so both platforms draw the same colours.
     */
    val COLOURS: IntArray = intArrayOf(
        0xFFC9783A.toInt(), // seed
        0xFFD9534F.toInt(), // die
        0xFF3B8FD9.toInt(), // kite
        0xFFE0A82E.toInt(), // star
        0xFF5B6BD6.toInt(), // moon
        0xFFE8833A.toInt(), // sun
        0xFFD65BA6.toInt(), // flower
        0xFF6DAA3C.toInt(), // leaf
        0xFFC8475B.toInt(), // heart
        0xFF9A5BD0.toInt(), // crown
        0xFF2E9E8F.toInt(), // drop
        0xFF5F7480.toInt(), // hill
        0xFF2B1D14.toInt(), // dark
        0xFFFFFFFF.toInt(), // white
    )

    enum class Shape {
        SEED, DIE, KITE, STAR, MOON, SUN, FLOWER, LEAF, HEART, CROWN, DROP, HILL;

        companion object {
            fun of(index: Int): Shape = entries[index]
        }
    }

    /** A valid mix: shape 0..11, colours 0..13, background ≠ symbol. */
    data class Mix(val shape: Int, val background: Int, val symbol: Int) {
        init {
            require(shape in 0 until SHAPE_COUNT) { "shape $shape" }
            require(background in 0 until COLOUR_COUNT) { "background $background" }
            require(symbol in 0 until COLOUR_COUNT) { "symbol $symbol" }
            require(background != symbol) { "background equals symbol" }
        }

        fun encode(): String = "$shape-$background-$symbol"

        val shapeKind: Shape get() = Shape.of(shape)
        val backgroundArgb: Int get() = COLOURS[background]
        val symbolArgb: Int get() = COLOURS[symbol]
    }

    /** What to show for a player: a drawn mix, a Google picture URL, or the neutral figure. */
    sealed class Resolved {
        data class Drawn(val mix: Mix) : Resolved()
        data class Google(val url: String) : Resolved()
        data object Neutral : Resolved()
    }

    /** Parses a stored or wire mix string; null when it is not a valid mix. */
    fun parseMix(value: String?): Mix? {
        if (value.isNullOrEmpty()) return null
        val parts = value.split('-')
        if (parts.size != 3) return null
        val shape = parts[0].toIntOrNull() ?: return null
        val background = parts[1].toIntOrNull() ?: return null
        val symbol = parts[2].toIntOrNull() ?: return null
        return try {
            Mix(shape, background, symbol)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** True when [value] is a URL this app may load as a Google profile picture. */
    fun isGooglePictureUrl(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        return try {
            val host = hostOf(value) ?: return false
            host == GOOGLE_PICTURE_HOST && (value.startsWith("https://") || value.startsWith("http://"))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stable mix for an account that never picked one, derived from [accountId] so every
     * phone shows the same avatar for that player.
     */
    fun defaultMix(accountId: String): Mix {
        var h = 0
        for (c in accountId) h = h * 31 + c.code
        if (h < 0) h = -h
        val shape = h % SHAPE_COUNT
        val background = (h / SHAPE_COUNT) % COLOUR_COUNT
        var symbol = (h / (SHAPE_COUNT * COLOUR_COUNT)) % COLOUR_COUNT
        if (symbol == background) symbol = (symbol + 1) % COLOUR_COUNT
        return Mix(shape, background, symbol)
    }

    /**
     * Resolves a wire [avatar] (mix, Google URL, `g`, or empty) for display. [picture] is the
     * Google picture URL when the player chose `g` but the API still carries the stored form.
     * [accountId] picks the default mix when nothing was chosen; null yields [Resolved.Neutral].
     */
    fun resolve(avatar: String?, accountId: String? = null, picture: String? = null): Resolved {
        if (avatar == GOOGLE) {
            if (isGooglePictureUrl(picture)) return Resolved.Google(picture!!)
        } else if (isGooglePictureUrl(avatar)) {
            return Resolved.Google(avatar!!)
        } else {
            parseMix(avatar)?.let { return Resolved.Drawn(it) }
        }
        if (!accountId.isNullOrEmpty()) return Resolved.Drawn(defaultMix(accountId))
        return Resolved.Neutral
    }

    /** Display value for the account screen: mix, Google URL, or the default mix for [accountId]. */
    fun display(avatar: String?, accountId: String, picture: String? = null): Resolved =
        resolve(avatar, accountId, picture)

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/').substringBefore(':').lowercase()
    }
}
