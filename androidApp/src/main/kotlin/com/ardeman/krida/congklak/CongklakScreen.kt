package com.ardeman.krida.congklak

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ardeman.krida.R
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.CongklakMove
import com.ardeman.krida.core.games.CongklakSeeds
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.transport.LocalTransport
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PICKUP_MS = 260L
private const val SOW_MS = 170L
private const val SETTLE_MS = 320L

/** Pass-and-play congklak on one device: seat 0 on the near side, seat 1 across. */
@Composable
fun CongklakScreen(onExit: () -> Unit) {
    val names = listOf(stringResource(R.string.congklak_player_1), stringResource(R.string.congklak_player_2))
    var session by remember { mutableStateOf(newSession(names)) }
    var state by remember { mutableStateOf(session.state!!) }
    val seeds = remember { CongklakSeeds(state.pits) }
    val counts = remember { mutableStateListOf(*state.pits.toTypedArray()) }
    var animating by remember { mutableStateOf(false) }
    var pitsOnScreen by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var renderer by remember { mutableStateOf<BoardRenderer?>(null) }
    val scope = rememberCoroutineScope()

    fun push() {
        renderer?.setSeedTargets(seeds.targets())
        seeds.counts().forEachIndexed { i, n -> if (counts[i] != n) counts[i] = n }
    }

    fun play(hole: Int) {
        val seat = state.turn
        val move = CongklakMove(hole)
        if (animating || state.finished || CongklakGame.validate(state, seat, move) != null) return
        val sowing = CongklakGame.play(state, seat, move)
        if (session.submitLocal(seat, move) != null) return
        val next = session.state!!
        animating = true
        scope.launch {
            for (step in CongklakSeeds.steps(state.pits, CongklakGame.pit(seat, hole), sowing.path)) {
                seeds.apply(step); push(); delay(if (step.pickUp) PICKUP_MS else SOW_MS)
            }
            // Captures and the end-of-game sweep: move whatever differs to match the rules.
            if (seeds.counts() != next.pits) { delay(SETTLE_MS); seeds.settle(next.pits); push() }
            state = next
            animating = false
        }
    }

    fun restart() {
        session = newSession(names)
        state = session.state!!
        seeds.reset(state.pits)
        push()
    }

    BackHandler(onBack = onExit)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, renderer) {
        val r = renderer
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) r?.resume()
            if (e == Lifecycle.Event.ON_PAUSE) r?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).also { view ->
                    renderer = BoardRenderer(view).apply {
                        onPitsProjected = { pitsOnScreen = it }
                        setSeedTargets(seeds.targets())
                        resume()
                    }
                }
            },
            onRelease = { renderer?.destroy(); renderer = null },
        )

        // Taps go to the nearest hole on screen.
        Box(
            Modifier.fillMaxSize().pointerInput(pitsOnScreen) {
                detectTapGestures { tap ->
                    if (pitsOnScreen.size < CongklakGame.PITS) return@detectTapGestures
                    val reach = hypot(pitsOnScreen[1][0] - pitsOnScreen[0][0], pitsOnScreen[1][1] - pitsOnScreen[0][1]) * 0.6f
                    val seat = state.turn
                    val hole = (0 until CongklakGame.HOLES).minBy {
                        val p = pitsOnScreen[CongklakGame.pit(seat, it)]
                        hypot(p[0] - tap.x, p[1] - tap.y)
                    }
                    val p = pitsOnScreen[CongklakGame.pit(seat, hole)]
                    if (hypot(p[0] - tap.x, p[1] - tap.y) <= reach) play(hole)
                }
            },
        )

        // Seed counts beside each pit; the mover's holes are highlighted.
        pitsOnScreen.forEachIndexed { pit, xy ->
            val owner = CongklakGame.ownerOf(pit)
            val store = CongklakGame.isStore(pit)
            val active = !state.finished && !store && owner == state.turn
            val dy = when {
                store -> 0.dp
                owner == 0 -> 34.dp
                else -> (-34).dp
            }
            val dx = when {
                !store -> 0.dp
                owner == 0 -> 48.dp
                else -> (-48).dp
            }
            CountBadge(
                count = counts[pit],
                active = active,
                store = store,
                modifier = Modifier.centeredAt(xy[0].toInt(), xy[1].toInt()).offset(dx, dy),
            )
        }

        Row(
            Modifier.align(Alignment.TopStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onExit) { Text(stringResource(R.string.congklak_back)) }
            Text(stringResource(R.string.congklak_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val winner = CongklakGame.winner(state)
            val line = when (winner) {
                null -> stringResource(R.string.congklak_turn, names[state.turn])
                -1 -> stringResource(R.string.congklak_draw)
                else -> stringResource(R.string.congklak_wins, names[winner])
            }
            Text(line, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.congklak_score, names[0], CongklakGame.score(state, 0), names[1], CongklakGame.score(state, 1)),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (winner != null) Button(onClick = ::restart) { Text(stringResource(R.string.congklak_play_again)) }
        }
    }
}

@Composable
private fun CountBadge(count: Int, active: Boolean, store: Boolean, modifier: Modifier) {
    val bg = when {
        active -> Color(0xFF2E7D32)
        store -> Color(0xFF5D4037)
        else -> Color(0x99000000)
    }
    Surface(modifier, shape = RoundedCornerShape(50), color = bg, contentColor = Color.White) {
        Text(
            count.toString(),
            Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
            style = if (store) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

/** Places the element so its center sits at pixel ([x], [y]) of the parent. */
private fun Modifier.centeredAt(x: Int, y: Int) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { p.place(IntOffset(x - p.width / 2, y - p.height / 2)) }
}.let { this.then(it) }

private fun newSession(names: List<String>) = HostSession(CongklakGame, LocalTransport(), roomCode = "LOCAL").apply {
    names.forEach { addLocalPlayer(it, Platform.ANDROID) }
    start()
}
