package com.ardeman.krida.core.session

import com.ardeman.krida.core.protocol.Krida
import kotlin.random.Random

object RoomCode {
    /** No 0/O, 1/I/L: easy to read aloud and type. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Swift-friendly overload. */
    fun generate(): String = generate(Random.Default)

    fun generate(random: Random): String = code(Krida.ROOM_CODE_LENGTH, random)

    /** A 6-char code for an online room. Swift-friendly overload. */
    fun generateOnline(): String = generateOnline(Random.Default)

    fun generateOnline(random: Random): String = code(Krida.ONLINE_ROOM_CODE_LENGTH, random)

    /** A nearby (BLE / LAN) code. */
    fun isValid(code: String): Boolean = valid(code, Krida.ROOM_CODE_LENGTH)

    fun isValidOnline(code: String): Boolean = valid(code, Krida.ONLINE_ROOM_CODE_LENGTH)

    /** Share link for an online room; the QR shows the same link. */
    fun shareUrl(code: String): String = Krida.SHARE_URL_PREFIX + code

    /**
     * Parses an online room from a share link ("https://krida.ardeman.com/r/ABC234", with or
     * without scheme, query or trailing slash), "krida://join?room=ABC234", or a typed code.
     * Returns null for anything else, including nearby codes.
     */
    fun parseOnline(text: String): String? {
        val t = text.trim()
        val host = SHARE_HOST_PATH
        val at = t.indexOf(host, ignoreCase = true)
        val code = when {
            at >= 0 -> t.substring(at + host.length).takeWhile { it != '/' && it != '?' && it != '#' }
            t.startsWith(Krida.JOIN_URL_PREFIX) -> t.removePrefix(Krida.JOIN_URL_PREFIX)
            else -> t
        }.trim().uppercase()
        return code.takeIf(::isValidOnline)
    }

    private val SHARE_HOST_PATH = Krida.SHARE_URL_PREFIX.removePrefix("https://")

    private fun code(length: Int, random: Random): String =
        buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private fun valid(code: String, length: Int): Boolean =
        code.length == length && code.all { it in ALPHABET }

    fun joinUrl(code: String): String = Krida.JOIN_URL_PREFIX + code

    fun advertisedName(code: String): String = Krida.BLE_NAME_PREFIX + code

    /** Parses a nearby room: "Krida-ABCD" (BLE name), "krida://join?room=ABCD" (QR) or a typed code. */
    fun parse(text: String): String? {
        val code = when {
            text.startsWith(Krida.BLE_NAME_PREFIX) -> text.removePrefix(Krida.BLE_NAME_PREFIX)
            text.startsWith(Krida.JOIN_URL_PREFIX) -> text.removePrefix(Krida.JOIN_URL_PREFIX)
            else -> text
        }.trim().uppercase()
        return code.takeIf(::isValid)
    }
}
