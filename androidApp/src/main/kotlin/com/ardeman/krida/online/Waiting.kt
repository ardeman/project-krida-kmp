package com.ardeman.krida.online

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.session.HostSession

/** "Waiting for Ayu… 15": a dropped player and the seconds left before the computer (or, for the host, the end). */
data class Waiting(val name: String, val seconds: Int, val host: Boolean = false)

/** Runs [tick] on the main thread once a second until [stop]. */
class SecondTicker(private val tick: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val run = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 1_000L)
        }
    }

    fun start() {
        main.removeCallbacks(run)
        main.post(run)
    }

    fun stop() = main.removeCallbacks(run)
}

/** The host's countdown: the dropped player whose seat goes to the computer soonest. */
fun HostSession<*, *>.waiting(): Waiting? =
    players.mapNotNull { p ->
        val ms = handOffInMs(p.seat)
        if (ms <= 0) null else Waiting(p.name, seconds(ms))
    }.minByOrNull { it.seconds }

/**
 * A guest's countdown, kept from the player lists and the host link, as guests don't see
 * the host's timers: seconds since each drop was first seen, 15 s each.
 */
class GuestWaiting {
    private val awaySince = HashMap<Int, Long>()
    private var hostAwaySince = 0L

    fun players(list: List<PlayerInfo>) {
        hostAwaySince = 0L
        val away = list.filter { !it.connected && !it.bot }.map { it.seat }.toSet()
        awaySince.keys.retainAll(away)
        val now = SystemClock.elapsedRealtime()
        away.forEach { awaySince.getOrPut(it) { now } }
    }

    fun hostLost() {
        if (hostAwaySince == 0L) hostAwaySince = SystemClock.elapsedRealtime()
    }

    /** The countdown to show; a host past its wait shows 0 ("host left"). */
    fun current(list: List<PlayerInfo>): Waiting? {
        val now = SystemClock.elapsedRealtime()
        if (hostAwaySince != 0L) {
            val name = list.firstOrNull { it.seat == 0 }?.name ?: ""
            val left = Krida.HANDOFF_WAIT_MS - (now - hostAwaySince)
            return Waiting(name, if (left <= 0) 0 else seconds(left), host = true)
        }
        return awaySince.mapNotNull { (seat, since) ->
            val left = Krida.HANDOFF_WAIT_MS - (now - since)
            val name = list.firstOrNull { it.seat == seat }?.name
            if (left <= 0 || name == null) null else Waiting(name, seconds(left))
        }.minByOrNull { it.seconds }
    }
}

private fun seconds(ms: Long) = ((ms + 999) / 1000).toInt()
