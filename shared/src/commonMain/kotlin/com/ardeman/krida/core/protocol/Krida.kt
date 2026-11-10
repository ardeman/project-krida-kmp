package com.ardeman.krida.core.protocol

/** Protocol constants shared by Android, iOS and docs/protocol.md. Change them together. */
object Krida {
    const val PROTOCOL_VERSION: Int = 1
    const val MAX_PLAYERS: Int = 4

    // BLE GATT layout. "6b726964" is "krid" in hex.
    const val BLE_SERVICE_UUID: String = "6b72a1d0-4b1e-4c8a-9b8e-6b7269646101"
    /** Guest -> host. Guests WRITE frames here. */
    const val BLE_TO_HOST_UUID: String = "6b72a1d0-4b1e-4c8a-9b8e-6b7269646102"
    /** Host -> guest. Host NOTIFIES frames here. */
    const val BLE_TO_GUEST_UUID: String = "6b72a1d0-4b1e-4c8a-9b8e-6b7269646103"
    /** Standard Client Characteristic Configuration Descriptor (enables notify). */
    const val BLE_CCCD_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"
    /** Advertised local name is "Krida-<ROOM>". */
    const val BLE_NAME_PREFIX: String = "Krida-"
    /** MTU we ask for. The platform may grant less; iOS typically ~185. */
    const val BLE_PREFERRED_MTU: Int = 512

    const val BONJOUR_SERVICE_TYPE: String = "_krida._tcp"
    const val JOIN_URL_PREFIX: String = "krida://join?room="

    /** Nearby rooms (BLE / LAN). */
    const val ROOM_CODE_LENGTH: Int = 4
    /** Online rooms: longer, so strangers can't guess a code on the public relay. */
    const val ONLINE_ROOM_CODE_LENGTH: Int = 6
    /** Share link and QR for online rooms: "https://krida.ardeman.com/r/ABC234". */
    const val SHARE_URL_PREFIX: String = "https://krida.ardeman.com/r/"

    /** The online relay (`relay/`); rooms are at `$RELAY_URL/v1/rooms/<CODE>`. */
    const val RELAY_URL: String = "wss://krida-relay.ardeman.com"

    /** How long a transport keeps reopening a dropped link (online socket) before giving up. */
    const val RECONNECT_GRACE_MS: Long = 60_000L
    /** How long a game waits for a dropped player before the computer plays the seat. */
    const val HANDOFF_WAIT_MS: Long = 15_000L
    /**
     * How long a local player has to throw the die before the phone rolls for them.
     * Holding the die pauses the countdown.
     */
    const val ROLL_TURN_MS: Long = 15_000L
}
