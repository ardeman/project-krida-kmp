package com.ardeman.krida.account

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Friend invites ("Invites" in docs/protocol.md). Receiving: the inbox socket, open while
 * the app is signed in and shows a menu; [current] is the invite to show. Sending: the host
 * of an online room invites a friend, and [sent] remembers it for the lobby's picker. Same
 * behavior as iOS (`Account/Invites.swift`).
 */
object Invites {
    /** [until] is on the `SystemClock.elapsedRealtime` clock. */
    data class Invite(
        val id: String,
        val from: String,
        val fromAvatar: String? = null,
        val code: String,
        val game: String,
        val seats: Int,
        val players: Int,
        val until: Long,
    )

    /** An invite this phone sent from room [code]; [id] is what the friend's join carries. */
    data class Sent(val id: String, val code: String, val until: Long)

    private const val RETRY_MS = 5_000L
    /** The relay's invite lifetime, for an answer that does not say. */
    private const val INVITE_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }
    private var socket: WebSocket? = null
    private var listening = false
    private val retry = Runnable { connect() }

    private var received by mutableStateOf<List<Invite>>(emptyList())

    /** The invite to show over the menus: the oldest one still open. */
    val current: Invite? get() = received.firstOrNull()

    /** Invites sent from this phone, by friend id. */
    var sent by mutableStateOf<Map<String, Sent>>(emptyMap())
        private set

    /** The last invite could not be sent; cleared by the next one. */
    var sendFailed by mutableStateOf(false)

    /** Opens the inbox and keeps it open (retrying) until [stop]. */
    fun listen() {
        if (listening) return
        listening = true
        connect()
    }

    /** Closes the inbox. Invites still open on the relay come again with the next [listen]. */
    fun stop() {
        listening = false
        main.removeCallbacks(retry)
        socket?.close(1000, null)
        socket = null
        received = emptyList()
    }

    /** Not now, or Join: the invite goes away on every device of this account. */
    fun dismiss(id: String) {
        expire(id)
        socket?.send(buildJsonObject { put("type", "dismiss"); put("id", id) }.toString())
    }

    /** The invite's two minutes are over. */
    fun expire(id: String) {
        received = received.filter { it.id != id }
    }

    /** Invites [friendId] to the online room [code] this phone hosts with [hostKey]. */
    fun send(friendId: String, code: String, hostKey: String) {
        val token = Account.sessionToken ?: return
        sendFailed = false
        val payload = buildJsonObject { put("friendId", friendId); put("code", code); put("key", hostKey) }.toString()
        val request = Request.Builder().url("${Account.API}/v1/invites").header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(Account.JSON_TYPE)).build()
        Account.send(request) { status, body ->
            val id = body?.get("id")?.jsonPrimitive?.content
            val ttl = try { body?.get("ttlMs")?.jsonPrimitive?.long } catch (e: Exception) { null } ?: INVITE_MS
            when {
                status == 200 && id != null -> sent = sent + (friendId to Sent(id, code, SystemClock.elapsedRealtime() + ttl))
                // Sent before (say, before this screen was rebuilt): show it as waiting.
                body?.get("error")?.jsonPrimitive?.content == "already_invited" ->
                    sent = sent + (friendId to Sent("", code, SystemClock.elapsedRealtime() + INVITE_MS))
                else -> sendFailed = true
            }
        }
    }

    private fun connect() {
        main.removeCallbacks(retry)
        val token = Account.sessionToken
        if (!listening || socket != null || token == null) return
        val request = Request.Builder().url("${Account.API.replaceFirst("http", "ws")}/v1/inbox").header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (socket === webSocket) message(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                lost(webSocket, again = true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 401: the session ended; Account signs out on its own check.
                lost(webSocket, again = response?.code != 401)
            }
        })
    }

    private fun lost(webSocket: WebSocket, again: Boolean) = main.post {
        if (socket !== webSocket) return@post
        socket = null
        received = emptyList()
        if (listening && again) main.postDelayed(retry, RETRY_MS)
    }

    private fun message(text: String) {
        try {
            val o = Json.parseToJsonElement(text).jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            when (o.getValue("type").jsonPrimitive.content) {
                "invite" -> {
                    val from = o.getValue("from").jsonObject
                    val invite = Invite(
                        id = id,
                        from = from.getValue("name").jsonPrimitive.content,
                        fromAvatar = from["avatar"]?.jsonPrimitive?.content,
                        code = o.getValue("code").jsonPrimitive.content,
                        game = o.getValue("game").jsonPrimitive.content,
                        seats = o.getValue("seats").jsonPrimitive.int,
                        players = o.getValue("players").jsonPrimitive.int,
                        until = SystemClock.elapsedRealtime() + o.getValue("ttlMs").jsonPrimitive.long,
                    )
                    received = received.filter { it.id != id } + invite
                }
                "invite_end" -> expire(id)
            }
        } catch (e: Exception) {
            // A message this build can't read is skipped.
        }
    }
}
