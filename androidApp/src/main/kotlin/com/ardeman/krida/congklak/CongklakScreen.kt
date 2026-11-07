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
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PICKUP_MS = 260L
private const val SOW_MS = 170L
private const val SETTLE_MS = 320L
private const val BOT_PAUSE_MS = 600L

/**
 * Congklak on a voxel board, driven by a [CongklakTable]. The board is turned so this
 * device's seat sits on the near side (seat 0 for pass-and-play). Every snapshot from the
 * table is animated in order: the move it carries is replayed from the shown state, then
 * whatever still differs (captures, the end sweep, a restart) settles into place.
 */
@Composable
fun CongklakScreen(table: CongklakTable, onExit: () -> Unit) {
    val viewSeat = table.mySeat ?: 0
    var state by remember { mutableStateOf(table.latest!!) }
    val seeds = remember { CongklakSeeds(state.pits) }
    val counts = remember { mutableStateListOf(*state.pits.toTypedArray()) }
    var animating by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var pitsOnScreen by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var renderer by remember { mutableStateOf<BoardRenderer?>(null) }
    val queue = remember { ArrayDeque<CongklakUpdate>() }
    val scope = rememberCoroutineScope()

    fun push() {
        renderer?.setSeedTargets(seeds.targets())
        seeds.counts().forEachIndexed { i, n -> if (counts[i] != n) counts[i] = n }
    }

    // Once the board is still, give a computer player its turn after a short pause.
    fun idle() {
        scope.launch {
            delay(BOT_PAUSE_MS)
            if (!animating && queue.isEmpty()) table.stepBot()
        }
    }

    fun drain() {
        if (animating) return
        val update = queue.removeFirstOrNull() ?: return idle()
        animating = true
        scope.launch {
            val seat = update.seat
            val move = update.move
            var sowed = false
            if (seat != null && move != null && CongklakGame.validate(state, seat, move) == null) {
                val sowing = CongklakGame.play(state, seat, move)
                for (step in CongklakSeeds.steps(state.pits, CongklakGame.pit(seat, move.hole), sowing.path)) {
                    seeds.apply(step); push(); delay(if (step.pickUp) PICKUP_MS else SOW_MS)
                }
                sowed = true
            }
            if (seeds.counts() != update.state.pits) {
                if (sowed) delay(SETTLE_MS)
                seeds.settle(update.state.pits); push()
            }
            state = update.state
            animating = false
            drain()
        }
    }

    DisposableEffect(table) {
        table.onUpdate = { update -> waiting = false; queue.addLast(update); drain() }
        drain()
        onDispose { table.onUpdate = null }
    }

    fun canPlay() = !animating && !waiting && queue.isEmpty() && !state.finished &&
        (table.mySeat == null || table.mySeat == state.turn)

    fun play(hole: Int) {
        val seat = state.turn
        if (!canPlay() || CongklakGame.validate(state, seat, CongklakMove(hole)) != null) return
        // Pass-and-play answers synchronously; a guest waits for the host's snapshot.
        if (table.play(seat, hole) && table is GuestTable) waiting = true
    }

    fun exit() {
        table.leave()
        onExit()
    }

    LaunchedEffect(table.notice) { if (table.notice != null) waiting = false }

    BackHandler(onBack = ::exit)

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
                        this.viewSeat = viewSeat
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
                    if (pitsOnScreen.size < CongklakGame.PITS || !canPlay()) return@detectTapGestures
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
            val near = owner == viewSeat
            val dy = when {
                store -> 0.dp
                near -> 34.dp
                else -> (-34).dp
            }
            val dx = when {
                !store -> 0.dp
                near -> 48.dp
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
            TextButton(onClick = ::exit) { Text(stringResource(R.string.congklak_back)) }
            Text(stringResource(R.string.congklak_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val names = table.players.map { it.name }
            fun name(seat: Int) = names.getOrElse(seat) { "${seat + 1}" }
            val winner = CongklakGame.winner(state)
            val line = when {
                winner == -1 -> stringResource(R.string.congklak_draw)
                winner != null && winner == table.mySeat -> stringResource(R.string.congklak_you_win)
                winner != null -> stringResource(R.string.congklak_wins, name(winner))
                state.turn == table.mySeat -> stringResource(R.string.congklak_your_turn)
                else -> stringResource(R.string.congklak_turn, name(state.turn))
            }
            Text(line, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.congklak_score, name(0), CongklakGame.score(state, 0), name(1), CongklakGame.score(state, 1)),
                style = MaterialTheme.typography.bodyLarge,
            )
            table.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (winner != null && !animating) {
                if (table.canRestart) {
                    Button(onClick = table::restart) { Text(stringResource(R.string.congklak_play_again)) }
                } else {
                    Text(stringResource(R.string.congklak_wait_host), style = MaterialTheme.typography.bodyMedium)
                }
            }
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
