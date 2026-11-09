package com.ardeman.krida.playonline

import android.os.Handler
import android.os.Looper
import com.ardeman.krida.core.protocol.Krida
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/** Group-search events are separate from the game-session protocol. Callbacks run on main. */
class QuickMatchClient(private val game: String, private val playerCount: Int) {
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var closed = false

    fun start(onEvent: (JsonObject) -> Unit, onError: () -> Unit) {
        if (closed || socket != null) return
        socket = httpClient.newWebSocket(
            Request.Builder().url("${Krida.RELAY_URL}/v1/match/$game/$playerCount?flow=group").build(),
            object : WebSocketListener() {
                private fun receive(text: String) {
                    main.post {
                        if (!closed) {
                            try { onEvent(Json.parseToJsonElement(text) as JsonObject) }
                            catch (_: Exception) { close(); onError() }
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = receive(text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.utf8())
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    main.post { if (!closed) { close(); onError() } }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    main.post { if (!closed) { close(); onError() } }
                }
            }
        )
    }

    fun acceptBots(revision: Int) { socket?.send("{\"type\":\"accept_bots\",\"revision\":$revision}") }
    fun keepSearching() { socket?.send("{\"type\":\"keep_searching\"}") }
    fun hostReady() { socket?.send("{\"type\":\"host_ready\"}") }

    fun close() {
        if (closed) return
        closed = true
        socket?.close(1000, null)
        socket = null
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        private val httpClient by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }
    }
}
