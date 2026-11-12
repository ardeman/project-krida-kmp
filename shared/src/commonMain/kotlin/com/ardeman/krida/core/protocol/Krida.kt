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
    /**
     * Host room code (UTF-8, readable). Guests use this when the advertisement has the
     * service UUID but the local name was dropped (common on iOS: name + 128-bit UUID
     * exceeds the 31-byte ADV limit).
     */
    const val BLE_ROOM_UUID: String = "6b72a1d0-4b1e-4c8a-9b8e-6b7269646104"
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
    /** Share link for a friend code: "https://krida.ardeman.com/f/K7M2XQ4D". */
    const val FRIEND_URL_PREFIX: String = "https://krida.ardeman.com/f/"

    /** Friend codes use the room-code alphabet. */
    const val FRIEND_CODE_LENGTH: Int = 8

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
    /**
     * How long a local player has to play a tile (gaple), pick a pit (congklak) or pick a token
     * after a roll (ludo) before the phone plays [com.ardeman.krida.core.games.GapleGame.timeoutMove],
     * [com.ardeman.krida.core.games.CongklakGame.timeoutMove] or
     * [com.ardeman.krida.core.games.LudoGame.timeoutMove] for them, and how long the gaple round-end
     * card waits before the host deals the next round.
     */
    const val PLAY_TURN_MS: Long = 15_000L
    /**
     * Once a player on this phone lets a turn timer run out, the phone plays their later turns
     * (rolls, picks, moves) after this short pause instead of a full countdown, until that
     * player acts again themselves.
     */
    const val AUTO_PLAY_MS: Long = 800L
}
