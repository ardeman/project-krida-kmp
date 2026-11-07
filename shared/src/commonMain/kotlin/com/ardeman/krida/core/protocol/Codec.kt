package com.ardeman.krida.core.protocol

import kotlinx.serialization.json.Json

/** Message <-> bytes. JSON for v1: easy to debug, small enough for board games. */
object Codec {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true // newer peers may add optional fields
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(message: Message): ByteArray =
        json.encodeToString(Message.serializer(), message).encodeToByteArray()

    /** Returns null for garbage instead of throwing; transports should just drop it. */
    fun decode(bytes: ByteArray): Message? =
        try {
            json.decodeFromString(Message.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            null
        }
}
