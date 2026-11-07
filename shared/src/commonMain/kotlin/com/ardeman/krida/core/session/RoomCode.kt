package com.ardeman.krida.core.session

import com.ardeman.krida.core.protocol.Krida
import kotlin.random.Random

object RoomCode {
    /** No 0/O, 1/I/L: easy to read aloud and type. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Swift-friendly overload. */
    fun generate(): String = generate(Random.Default)

    fun generate(random: Random): String =
        buildString { repeat(Krida.ROOM_CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun isValid(code: String): Boolean =
        code.length == Krida.ROOM_CODE_LENGTH && code.all { it in ALPHABET }

    fun joinUrl(code: String): String = Krida.JOIN_URL_PREFIX + code

    fun advertisedName(code: String): String = Krida.BLE_NAME_PREFIX + code

    /** Parses "Krida-ABCD" (BLE name) or "krida://join?room=ABCD" (QR). */
    fun parse(text: String): String? {
        val code = when {
            text.startsWith(Krida.BLE_NAME_PREFIX) -> text.removePrefix(Krida.BLE_NAME_PREFIX)
            text.startsWith(Krida.JOIN_URL_PREFIX) -> text.removePrefix(Krida.JOIN_URL_PREFIX)
            else -> text
        }.trim().uppercase()
        return code.takeIf(::isValid)
    }
}
