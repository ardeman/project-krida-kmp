package com.ardeman.krida.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN guest: NSD browse for `RoomCode.advertisedName(code)`, resolve, TCP connect. Listener
 * callbacks run on the main thread. Peer id for the host is [TransportListener.HOST_PEER].
 */
class LanGuestTransport(
    context: Context,
    private val roomCode: String,
    /** Emulator e2e: skip NSD and TCP-connect here (adb forward → 10.0.2.2). */
    private val directHost: String? = null,
    private val directPort: Int = 0,
) : Transport {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val resolving = AtomicBoolean(false)

    private var sink: TransportListener? = null
    private var socket: Socket? = null
    private var closed = false
    private var browsing = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, error: Int) {
            Log.w(TAG, "NSD start failed: $error")
        }
        override fun onStopDiscoveryFailed(type: String, error: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            if (closed) return
            if (info.serviceName != RoomCode.advertisedName(roomCode)) return
            if (!resolving.compareAndSet(false, true)) return
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    Log.w(TAG, "NSD resolve failed: $errorCode")
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    stopBrowse()
                    connect(serviceInfo)
                }
            })
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
    }

    override fun setListener(listener: TransportListener?) { sink = listener }

    fun start() {
        if (closed || browsing) return
        if (directHost != null && directPort > 0) {
            connect(directHost, directPort)
            return
        }
        browsing = true
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    private fun connect(info: NsdServiceInfo) {
        val host = info.host ?: run {
            resolving.set(false)
            return
        }
        connect(host.hostAddress ?: host.hostName, info.port)
    }

    private fun connect(host: String, port: Int) {
        io.execute {
            if (closed) return@execute
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), CONNECT_MS)
                if (closed) {
                    sock.close()
                    return@execute
                }
                socket = sock
                post { sink?.onPeerConnected(TransportListener.HOST_PEER) }
                while (!closed) {
                    val data = LanFraming.read(sock.getInputStream()) ?: break
                    post { sink?.onMessage(TransportListener.HOST_PEER, data) }
                }
            } catch (e: IOException) {
                Log.w(TAG, "connect failed", e)
            } finally {
                lost()
            }
        }
    }

    override fun send(peer: String?, data: ByteArray) {
        val sock = socket ?: return
        io.execute {
            try {
                synchronized(this) { LanFraming.write(sock.getOutputStream(), data) }
            } catch (_: IOException) {
                lost()
            }
        }
    }

    override fun disconnect(peer: String) {}

    override fun close() {
        if (closed) return
        closed = true
        stopBrowse()
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        io.shutdownNow()
    }

    private fun stopBrowse() {
        if (!browsing) return
        browsing = false
        try {
            nsd.stopServiceDiscovery(discovery)
        } catch (_: Exception) {
        }
    }

    private fun lost() {
        val had = socket != null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        if (had) post { sink?.onPeerDisconnected(TransportListener.HOST_PEER) }
    }

    private fun post(block: () -> Unit) = main.post { if (!closed) block() }

    companion object {
        private const val TAG = "LanGuest"
        private const val SERVICE_TYPE = "_krida._tcp."
        private const val CONNECT_MS = 8_000
    }
}
