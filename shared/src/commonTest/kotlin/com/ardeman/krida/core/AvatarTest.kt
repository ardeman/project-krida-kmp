package com.ardeman.krida.core

import com.ardeman.krida.core.account.Avatar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarTest {
    @Test
    fun mixRoundTrip() {
        val mix = Avatar.Mix(2, 12, 3)
        assertEquals("2-12-3", mix.encode())
        assertEquals(mix, Avatar.parseMix(mix.encode()))
        assertEquals(Avatar.Shape.KITE, mix.shapeKind)
        assertEquals(Avatar.COLOURS[12], mix.backgroundArgb)
        assertEquals(Avatar.COLOURS[3], mix.symbolArgb)
    }

    @Test
    fun parseRejectsBad() {
        assertNull(Avatar.parseMix(null))
        assertNull(Avatar.parseMix(""))
        assertNull(Avatar.parseMix("g"))
        assertNull(Avatar.parseMix("2-12"))
        assertNull(Avatar.parseMix("2-12-12")) // same colours
        assertNull(Avatar.parseMix("12-0-1")) // shape out of range
        assertNull(Avatar.parseMix("0-14-1")) // colour out of range
        assertNull(Avatar.parseMix("a-b-c"))
    }

    @Test
    fun googlePictureHost() {
        assertTrue(Avatar.isGooglePictureUrl("https://lh3.googleusercontent.com/a/abc"))
        assertFalse(Avatar.isGooglePictureUrl("https://evil.example/lh3.googleusercontent.com/a"))
        assertFalse(Avatar.isGooglePictureUrl("https://lh3.googleusercontent.com.evil/a"))
        assertFalse(Avatar.isGooglePictureUrl("https://other.com/photo.jpg"))
        assertFalse(Avatar.isGooglePictureUrl(null))
    }

    @Test
    fun defaultMixIsStableAndValid() {
        val a = Avatar.defaultMix("user-abc")
        val b = Avatar.defaultMix("user-abc")
        assertEquals(a, b)
        assertNotEquals(a.background, a.symbol)
        assertNotEquals(a, Avatar.defaultMix("user-xyz"))
    }

    @Test
    fun resolve() {
        assertIs<Avatar.Resolved.Drawn>(Avatar.resolve("2-12-3"))
        assertIs<Avatar.Resolved.Google>(
            Avatar.resolve("https://lh3.googleusercontent.com/a/x"),
        )
        assertIs<Avatar.Resolved.Google>(
            Avatar.resolve(Avatar.GOOGLE, picture = "https://lh3.googleusercontent.com/a/x"),
        )
        assertIs<Avatar.Resolved.Drawn>(Avatar.resolve(null, accountId = "id1"))
        assertIs<Avatar.Resolved.Neutral>(Avatar.resolve(null, accountId = null))
    }
}
