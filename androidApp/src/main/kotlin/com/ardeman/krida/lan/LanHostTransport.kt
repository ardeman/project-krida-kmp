package com.ardeman.krida.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.core.transport.Transport
import com.ardeman.krida.core.transport.TransportListener
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * LAN host: TCP [ServerSocket] + NSD advertise (`Krida.BONJOUR_SERVICE_TYPE`, name
 * `RoomCode.advertisedName`). Peer id = a short counter string. Every listener callback
 * runs on the main thread.
 */
class LanHostTransport(
    context: Context,
    private val roomCode: String,
) : Transport {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val nextPeer = AtomicInteger(1)

    private var sink: TransportListener? = null
    private var server: ServerSocket? = null
    private var closed = false
    private val guests = ConcurrentHashMap<String, Guest>()

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
            Log.w(TAG, "NSD register failed: $error")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
    }

    override fun setListener(listener: TransportListener?) { sink = listener }

    fun start() {
        if (closed) return
        io.execute {
            try {
                val sock = ServerSocket(0)
                if (closed) {
                    sock.close()
                    return@execute
                }
                server = sock
                val info = NsdServiceInfo().apply {
                    serviceName = RoomCode.advertisedName(roomCode)
                    serviceType = SERVICE_TYPE
                    port = sock.localPort
                }
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
                while (!closed) {
                    val client = try {
                        sock.accept()
                    } catch (_: IOException) {
                        break
                    }
                    if (closed) {
                        client.close()
                        break
                    }
                    attach(client)
                }
            } catch (e: IOException) {
                Log.w(TAG, "host listen failed", e)
            }
        }
    }

    private fun attach(socket: Socket) {
        val id = nextPeer.getAndIncrement().toString()
        val guest = Guest(id, socket)
        guests[id] = guest
        post { sink?.onPeerConnected(id) }
        io.execute {
            try {
                while (!closed) {
                    val data = LanFraming.read(socket.getInputStream()) ?: break
                    post { sink?.onMessage(id, data) }
                }
            } catch (_: IOException) {
            } finally {
                drop(id)
            }
        }
    }

    override fun send(peer: String?, data: ByteArray) {
        val targets = if (peer == null) guests.keys.toList() else listOfNotNull(peer.takeIf { guests.containsKey(it) })
        for (id in targets) {
            val guest = guests[id] ?: continue
            io.execute {
                try {
                    synchronized(guest.lock) { LanFraming.write(guest.socket.getOutputStream(), data) }
                } catch (_: IOException) {
                    drop(id)
                }
            }
        }
    }

    override fun disconnect(peer: String) {
        drop(peer)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            nsd.unregisterService(registration)
        } catch (_: Exception) {
        }
        guests.keys.toList().forEach { drop(it) }
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        io.shutdownNow()
    }

    private fun drop(id: String) {
        val guest = guests.remove(id) ?: return
        try {
            guest.socket.close()
        } catch (_: IOException) {
        }
        post { sink?.onPeerDisconnected(id) }
    }

    private fun post(block: () -> Unit) = main.post { if (!closed) block() }

    private class Guest(val id: String, val socket: Socket) {
        val lock = Any()
    }

    companion object {
        private const val TAG = "LanHost"
        /** Android NSD wants the trailing dot. */
        private const val SERVICE_TYPE = "_krida._tcp."
    }
}
