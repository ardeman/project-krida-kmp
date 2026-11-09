package com.ardeman.krida.playonline

import android.os.Handler
import android.os.Looper
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.Message
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Connects to the matchmaker WebSocket and waits for a match assignment.
 * The matchmaker will respond with a Message.Matched containing:
 * - role: "host" or "guest"
 * - code: (only for host) the 6-char room code to connect to
 */
class QuickMatchClient(
    private val game: String,
    private val playerCount: Int,
) {
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var generation = 0
    private var closed = false
    
    fun start(
        onMatched: (role: String, code: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (closed) return
        val gen = ++generation
        
        // Connect to /v1/match/<GAME>/<PLAYER_COUNT>
        val url = "${Krida.RELAY_URL}/v1/match/$game/$playerCount"
        val request = Request.Builder().url(url).build()
        
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (gen == generation && !closed) {
                        // Connected, waiting for matched message
                    }
                }
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                main.post {
                    if (gen == generation && !closed) {
                        try {
                            val text = bytes.utf8()
                            val message = Json.decodeFromString<Message>(text)
                            if (message is Message.Matched) {
                                closed = true
                                val code = message.code ?: ""
                                onMatched(message.role, code)
                            }
                        } catch (e: Exception) {
                            onError("Failed to parse match: ${e.message}")
                        }
                    }
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    if (gen == generation && !closed) {
                        onError("Connection failed: ${t.message}")
                    }
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        })
    }
    
    fun close() {
        if (closed) return
        closed = true
        socket?.close(1000, null)
        socket = null
        main.removeCallbacksAndMessages(null)
    }
    
    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
