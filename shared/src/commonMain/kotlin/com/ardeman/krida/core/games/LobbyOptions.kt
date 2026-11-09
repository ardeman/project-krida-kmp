package com.ardeman.krida.core.games

import com.ardeman.krida.core.protocol.Codec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Reads the rule options a host sent in `lobby` (`GuestSession.lobbyOptions`). Each returns
 * null when the host sent none (an older host) or something unreadable, so the lobby can
 * leave the rules out until the game's first state carries them.
 */
object LobbyOptions {
    fun congklak(options: JsonElement?): CongklakOptions? = decode(CongklakOptions.serializer(), options)

    fun ularTangga(options: JsonElement?): UlarTanggaOptions? = decode(UlarTanggaOptions.serializer(), options)

    private fun <T> decode(serializer: KSerializer<T>, options: JsonElement?): T? =
        options?.let { runCatching { Codec.json.decodeFromJsonElement(serializer, it) }.getOrNull() }
}
