package com.ardeman.krida.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class Platform {
    @SerialName("android") ANDROID,
    @SerialName("ios") IOS,
}

@Serializable
data class PlayerInfo(
    val seat: Int,
    val name: String,
    val platform: Platform,
    /** false while a remote player is temporarily disconnected. */
    val connected: Boolean = true,
    /** true for players sitting at the host device (host player, pass-and-play). */
    val local: Boolean = false,
    /** true for computer players; they run on the host. Optional: older guests ignore it. */
    val bot: Boolean = false,
    /**
     * Pawn colour, in games where players pick one (see `SeatColorRules`); null otherwise.
     * Optional: older guests ignore it.
     */
    val color: Int? = null,
)

/** A move as applied by the host: who made it and the game-specific move JSON. */
@Serializable
data class LastMove(val seat: Int, val move: JsonElement)

/** Every message on every transport. See docs/protocol.md. */
@Serializable
sealed class Message {

    /** guest -> host: first message after connecting. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val name: String,
        val platform: Platform,
        val rejoinToken: String? = null,
    ) : Message()

    /** host -> guest: you got a seat. Keep [rejoinToken] to reconnect. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val seat: Int,
        val roomCode: String,
        val game: String,
        val rejoinToken: String,
    ) : Message()

    /** host -> guest: cannot join. [reason] is one of [RejectReason]. */
    @Serializable
    @SerialName("reject")
    data class Reject(val reason: String) : Message()

    /**
     * host -> each guest: player list before the game starts. [you] is the receiver's seat.
     * [options] is the game's rule options the host chose (game-specific JSON, see
     * `LobbyOptionRules`), so guests can read the rules before the first state. Optional.
     */
    @Serializable
    @SerialName("lobby")
    data class Lobby(
        val players: List<PlayerInfo>,
        val you: Int,
        val capacity: Int? = null,
        val options: JsonElement? = null,
    ) : Message()

    /**
     * guest -> host, before the game starts: "I want this pawn colour". The host takes it if
     * no one else holds it and answers with a new [Lobby] either way. Older hosts ignore it.
     */
    @Serializable
    @SerialName("color")
    data class PickColor(val color: Int) : Message()

    /** guest -> host: "I want to make this move". [move] is game-specific JSON. */
    @Serializable
    @SerialName("intent")
    data class Intent(val seq: Int, val move: JsonElement) : Message()

    /** host -> all: full snapshot. [seq] increases by one for every applied move. */
    @Serializable
    @SerialName("state")
    data class State(
        val seq: Int,
        val turn: Int,
        val finished: Boolean,
        val state: JsonElement,
        val players: List<PlayerInfo>,
        /** The move that produced this snapshot (absent for the first one), for animations. */
        val last: LastMove? = null,
    ) : Message()

    /** host -> guest: the intent with [seq] was refused. */
    @Serializable
    @SerialName("invalid")
    data class Invalid(val seq: Int, val reason: String) : Message()

    /** Relay (matchmaker) -> player: "A match has been found". Role is "host" or "guest";
     *  only host includes [code]. Optional: new in quick match feature.
     */
    @Serializable
    @SerialName("matched")
    data class Matched(
        val role: String,
        val code: String? = null,
    ) : Message()

    @Serializable
    @SerialName("ping")
    data class Ping(val t: Long) : Message()

    @Serializable
    @SerialName("pong")
    data class Pong(val t: Long) : Message()

    /**
     * Preset reaction during a running game. Guest → host sends only [id]; the host fills
     * [seat] and fans the same message out to every guest (including the sender). Older
     * peers drop the unknown type. Ids are from [Emotes].
     */
    @Serializable
    @SerialName("emote")
    data class Emote(val id: String, val seat: Int? = null) : Message()

    /** either side: leaving on purpose. */
    @Serializable
    @SerialName("bye")
    data object Bye : Message()
}

object RejectReason {
    const val VERSION = "version"
    const val FULL = "full"
    const val STARTED = "started"
}

object InvalidReason {
    const val NOT_STARTED = "not_started"
    const val FINISHED = "finished"
    const val NOT_YOUR_TURN = "not_your_turn"
    const val BAD_MOVE = "bad_move"
    const val NOT_SEATED = "not_seated"
}
