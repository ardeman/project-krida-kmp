package com.ardeman.krida.online

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * One WebSocket to the online relay (`docs/protocol.md`, "Online relay"), reopened after a
 * drop for up to [Krida.RECONNECT_GRACE_MS]. Relay frames are `[kind, peer, payload…]`.
 * OkHttp calls back on its own threads; everything is hopped to the main thread before it
 * touches state or the listener.
 */
abstract class OnlineTransport(protected val code: String) : Transport {
    protected val main = Handler(Looper.getMainLooper())
    protected var sink: TransportListener? = null

    /** The relay closed this room for good with one of its close codes (4001, 4004, …). */
    var onClosed: ((Int) -> Unit)? = null

    /** The host may release quick-match guests only after its room socket is open. */
    var onReady: (() -> Unit)? = null

    private var socket: WebSocket? = null
    private var closed = false
    private var downSince = 0L
    private var attempt = 0
    /** Bumped on every connect, so callbacks from an old socket are ignored. */
    private var generation = 0

    protected open fun onSocketOpen() {}
    protected abstract fun query(): String
    protected abstract fun onFrame(kind: Int, peer: Int, payload: ByteArray)
    /** The socket dropped (it may come back). */
    protected abstract fun onSocketLost()

    override fun setListener(listener: TransportListener?) { sink = listener }

    fun start() = connect()

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        socket?.close(NORMAL, null)
        socket = null
    }

    protected fun sendFrame(kind: Int, peer: Int, payload: ByteArray = EMPTY) {
        val frame = ByteArray(2 + payload.size)
        frame[0] = kind.toByte()
        frame[1] = peer.toByte()
        payload.copyInto(frame, 2)
        // While the socket is down messages are dropped; the host resends the state on rejoin.
        socket?.send(frame.toByteString())
    }

    private fun connect() {
        if (closed) return
        val gen = ++generation
        val request = Request.Builder().url("${Krida.RELAY_URL}/v1/rooms/$code?${query()}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (gen == generation && !closed) { attempt = 0; downSince = 0; onSocketOpen(); onReady?.invoke() } }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                main.post {
                    if (gen == generation && !closed) {
                        onFrame(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, data.copyOfRange(2, data.size))
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                main.post { if (gen == generation) dropped(code) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (gen == generation) dropped(null) }
            }
        })
    }

    /** [closeCode] is null for a network failure. */
    private fun dropped(closeCode: Int?) {
        if (closed) return
        socket = null
        generation++
        onSocketLost()
        if (closeCode != null && closeCode in FATAL) {
            closed = true
            onClosed?.invoke(closeCode)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (downSince == 0L) downSince = now
        if (now - downSince > Krida.RECONNECT_GRACE_MS) {
            closed = true
            onClosed?.invoke(GAVE_UP)
            return
        }
        // 0.5 s, 1 s, 2 s, then every 4 s.
        val wait = minOf(500L shl attempt.coerceAtMost(3), 4_000L)
        attempt++
        val gen = generation
        main.postDelayed({ if (gen == generation) connect() }, wait)
    }

    companion object {
        const val DATA = 1
        const val CONNECTED = 2
        const val DISCONNECTED = 3
        const val KICK = 4

        const val ROOM_TAKEN = 4001
        const val NO_ROOM = 4004
        const val KICKED = 4008
        const val FULL = 4009
        const val HOST_GONE = 4010
        const val REPLACED = 4011
        /** Not a relay code: the socket stayed down past the reconnect grace. */
        const val GAVE_UP = -1

        private const val NORMAL = 1000
        private val FATAL = setOf(4000, ROOM_TAKEN, NO_ROOM, KICKED, FULL, HOST_GONE, REPLACED, 4013)
        private val EMPTY = ByteArray(0)

        private val client: OkHttpClient by lazy {
            // Keeps NATs and mobile networks from dropping an idle game.
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }

        private val random = SecureRandom()
        private const val KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

        /** The host's secret for its room: 24 random chars, as the relay asks for 16–64. */
        fun newHostKey(): String = buildString { repeat(24) { append(KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)]) } }
    }
}

/**
 * Hosting an online room. The relay gives each guest a number; the session's peer id is its
 * decimal string. When this phone's own socket drops, every guest is reported gone, and the
 * relay reports them back once the socket is up again.
 */
class OnlineHostTransport(
    code: String,
    private val key: String = newHostKey(),
    private val game: String? = null,
    private val seats: Int = 4,
    private val publicRoom: Boolean = false,
) : OnlineTransport(code) {
    private var lobbyBots = 0
    private var started = false

    fun updateLobby(bots: Int, started: Boolean = this.started) {
        if (lobbyBots == bots && this.started == started) return
        lobbyBots = bots
        this.started = started
        sendLobbyStatus()
    }

    private fun sendLobbyStatus() {
        if (game != null) sendFrame(5, 0, "{\"bots\":$lobbyBots,\"started\":$started}".encodeToByteArray())
    }

    override fun onSocketOpen() = sendLobbyStatus()

    private val guests = LinkedHashSet<Int>()

    override fun query() = "role=host&key=$key" + (game?.let { "&game=$it&seats=$seats&public=${if (publicRoom) 1 else 0}" } ?: "")

    override fun send(peer: String?, data: ByteArray) {
        val id = if (peer == null) 0 else peer.toIntOrNull()?.takeIf { it in guests } ?: return
        sendFrame(DATA, id, data)
    }

    override fun disconnect(peer: String) {
        val id = peer.toIntOrNull() ?: return
        sendFrame(KICK, id)
    }

    override fun onFrame(kind: Int, peer: Int, payload: ByteArray) {
        when (kind) {
            DATA -> if (peer in guests) sink?.onMessage(peer.toString(), payload)
            CONNECTED -> if (guests.add(peer)) sink?.onPeerConnected(peer.toString())
            DISCONNECTED -> if (guests.remove(peer)) sink?.onPeerDisconnected(peer.toString())
        }
    }

    override fun onSocketLost() {
        val gone = guests.toList()
        guests.clear()
        gone.forEach { sink?.onPeerDisconnected(it.toString()) }
    }
}

/** Joining an online room by code. The host is peer [TransportListener.HOST_PEER]. */
class OnlineGuestTransport(code: String, private var reservation: String? = null) : OnlineTransport(code) {
    private var hostHere = false

    override fun query() = "role=guest" + (reservation?.let { "&reservation=$it" } ?: "")

    override fun onSocketOpen() { reservation = null }

    override fun send(peer: String?, data: ByteArray) = sendFrame(DATA, 0, data)

    override fun disconnect(peer: String) = Unit

    override fun onFrame(kind: Int, peer: Int, payload: ByteArray) {
        when (kind) {
            DATA -> sink?.onMessage(TransportListener.HOST_PEER, payload)
            CONNECTED -> if (!hostHere) {
                hostHere = true
                sink?.onPeerConnected(TransportListener.HOST_PEER)
            }
            DISCONNECTED -> onSocketLost()
        }
    }

    override fun onSocketLost() {
        if (!hostHere) return
        hostHere = false
        sink?.onPeerDisconnected(TransportListener.HOST_PEER)
    }
}
