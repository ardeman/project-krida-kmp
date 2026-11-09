package com.ardeman.krida.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Owns board work until exit. Every game uses this scope for animations and delayed turns. */
class GameLifetime(parent: CoroutineScope, private val cleanup: () -> Unit) {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    var active = true
        private set

    fun stop() {
        if (!active) return
        active = false
        scope.cancel()
        cleanup()
    }
}

@Composable
fun rememberGameLifetime(key: Any, cleanup: () -> Unit): GameLifetime {
    val parent = rememberCoroutineScope()
    val lifetime = remember(key) { GameLifetime(parent, cleanup) }
    DisposableEffect(lifetime) { onDispose { lifetime.stop() } }
    return lifetime
}
