package com.ardeman.krida.ulartangga

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ardeman.krida.R
import com.ardeman.krida.ads.Ads
import com.ardeman.krida.core.games.UlarTanggaBoard
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.games.UlarTanggaOptions
import com.ardeman.krida.core.games.UlarTanggaState
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.CountdownNotice
import com.ardeman.krida.ui.EmoteHud
import com.ardeman.krida.ui.LoadingScreen
import com.ardeman.krida.ui.LocalStageTurned
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.SoundToggles
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.gameInsets
import com.ardeman.krida.ui.rememberGameLifetime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the die stays shaking for a roll the host hasn't answered yet. */
private const val ROLL_WAIT_MS = 4000L
private const val HOP_MS = 230L
private const val JUMP_UNITS_PER_SECOND = 9f
private const val SETTLE_MS = 180L
private const val BOT_PAUSE_MS = 700L

/** Pawn color [color] (0..3), for the HUD. */
fun pawnColor(color: Int): Color = UlarTanggaBoard.pieceColor(color).let { Color(it[0], it[1], it[2]) }

/** [seat]'s pawn color in [state], for the HUD. */
fun seatColor(state: UlarTanggaState, seat: Int): Color = pawnColor(state.color(seat))

/**
 * Ular tangga on a voxel board, driven by an [UlarTanggaTable]. Every snapshot is animated in
 * order: a roll is replayed from the shown state (die tumble, a hop per square, then the snake
 * or ladder), then the pawns settle; a new game starts with the dice-off for who goes first.
 */
@Composable
fun UlarTanggaScreen(table: UlarTanggaTable, onExit: () -> Unit) {
    // As congklak: loading screen first, build the board under it, fade out on its first frame.
    var mounted by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        withFrameNanos {}
        mounted = true
    }
    LaunchedEffect(mounted, rendered) {
        if (!mounted) return@LaunchedEffect
        delay(if (rendered) 120L else 4000L)
        loading = false
    }
    Box(Modifier.fillMaxSize()) {
        if (mounted) Board(table, onExit, onFirstFrame = { rendered = true }, loading = { loading })
        AnimatedVisibility(loading, enter = EnterTransition.None, exit = fadeOut(tween(250))) { LoadingScreen() }
    }
}

@Composable
private fun Board(table: UlarTanggaTable, onExit: () -> Unit, onFirstFrame: () -> Unit, loading: () -> Boolean) {
    val playerOrigins = remember { mutableMapOf<Int, FloatArray>() }
    var state by remember { mutableStateOf(table.latest) }
    var die by remember { mutableStateOf<Int?>(null) }
    /** How the local player threw the die, for the roll the table hasn't sent back yet. */
    val pendingToss = remember { arrayOfNulls<UlarTanggaRenderer.Toss>(1) }
    var animating by remember { mutableStateOf(false) }
    /** Just after the dice-off: everyone's throws stay on the tiles for a moment. */
    var offHold by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<UlarTanggaRenderer?>(null) }
    val turned = LocalStageTurned.current
    val activity = LocalActivity.current
    val queue = remember { ArrayDeque<UlarTanggaUpdate>() }
    val lifetime = rememberGameLifetime(table) {
        table.onUpdate = null
        queue.clear()
        renderer?.onDieBounce = null
        renderer?.holdDie(false)
        table.leave()
        Sounds.stopGameAudio()
    }
    val scope = lifetime.scope
    val players = state.positions.size

    fun recallDie(next: UlarTanggaState) {
        renderer?.recallDie(if (table.isLocal(next.turn)) null else playerOrigins[next.turn])
    }

    fun spot(s: UlarTanggaState, seat: Int): FloatArray {
        val square = s.positions[seat]
        return UlarTanggaBoard.pieceSpot(square, seat, s.positions.count { it == square } > 1)
    }

    fun placeAll(s: UlarTanggaState) {
        val r = renderer ?: return
        r.setSeats(List(s.positions.size) { s.color(it) })
        repeat(s.positions.size) { r.place(it, spot(s, it)) }
    }

    fun idle() {
        scope.launch {
            while (loading()) delay(100)
            delay(BOT_PAUSE_MS)
            if (!animating && queue.isEmpty()) table.stepBot()
        }
    }

    fun drain() {
        if (!lifetime.active || animating) return
        val update = queue.removeFirstOrNull() ?: return idle()
        animating = true
        scope.launch {
            val next = update.state
            val seat = next.lastSeat
            val roll = next.lastRoll
            val offThrow = if (update.fresh) UlarTanggaGame.diceOffThrowBetween(state, next) else null
            if (offThrow != null) {
                // A dice-off throw: the die lands on the throw, which shows on the seat's tile.
                val (thrower, value) = offThrow
                val toss = pendingToss[0]?.takeIf { table.isLocal(thrower) } ?: UlarTanggaRenderer.Toss.Short
                pendingToss[0] = null
                val origin = if (table.isLocal(thrower)) null else playerOrigins[thrower]
                val seconds = renderer?.throwDie(value, toss, origin, thrower) ?: 0f
                delay((seconds * 1000).toLong() + 150)
                die = value
                state = next
                delay(450)
                recallDie(next)
                if (!next.opening) {
                    // The order is set: show every throw a moment, then the tiles line up in order.
                    offHold = true
                    delay(1800)
                    offHold = false
                    announceTurn(table, next.turn)
                }
            } else if (update.fresh && seat != null && roll != null) {
                // Bots and remote players toss; the local player's tap or throw is replayed.
                val toss = pendingToss[0]?.takeIf { table.isLocal(seat) } ?: UlarTanggaRenderer.Toss.Short
                pendingToss[0] = null
                val origin = if (table.isLocal(seat)) null else playerOrigins[seat]
                val seconds = renderer?.throwDie(roll, toss, origin, seat) ?: 0f
                delay((seconds * 1000).toLong() + 200)
                die = roll
                val play = UlarTanggaGame.play(state, seat, roll)
                val r = renderer
                for (square in play.steps) {
                    r?.move(seat, listOf(UlarTanggaBoard.pieceSpot(square, seat, false)), HOP_MS / 1000f * 0.9f, hop = 0.7f)
                    Sounds.play(Sounds.Effect.Step)
                    delay(HOP_MS)
                }
                val landed = play.steps.lastOrNull()
                if (landed != null && play.jumpTo != null) {
                    delay(160)
                    val ladder = landed in UlarTanggaGame.LADDERS
                    val path = if (ladder) UlarTanggaBoard.ladderPath(landed) else UlarTanggaBoard.snakePath(landed)
                    val seconds = pathLength(path) / JUMP_UNITS_PER_SECOND + 0.2f
                    Sounds.play(if (ladder) Sounds.Effect.Store else Sounds.Effect.Capture)
                    r?.move(seat, path.drop(1), seconds)
                    delay((seconds * 1000).toLong() + 80)
                }
                repeat(next.positions.size) { renderer?.move(it, listOf(spot(next, it)), SETTLE_MS / 1000f) }
                delay(SETTLE_MS)
                recallDie(next)
                state = next
                announce(table, play.state, landed == UlarTanggaGame.LAST || play.jumpTo == UlarTanggaGame.LAST)
            } else {
                // A new game (or the screen's first state): pawns to the start; the dice-off
                // waits for the players' throws.
                die = null
                state = next
                placeAll(next)
                recallDie(next)
            }
            animating = false
            drain()
        }
    }

    // The first state runs through the queue too, so the pawns are placed.
    DisposableEffect(table) {
        table.onUpdate = { queue.addLast(it); drain() }
        queue.addLast(UlarTanggaUpdate(table.latest, fresh = false))
        onDispose { lifetime.stop() }
    }
    LaunchedEffect(renderer) {
        if (renderer == null) return@LaunchedEffect
        placeAll(state)
        drain()
    }

    fun canRoll() = lifetime.active && !animating && queue.isEmpty() && !state.finished && table.isLocal(state.turn)

    /** Throws the die for the local player whose turn it is; it shakes until the roll comes back. */
    fun roll(toss: UlarTanggaRenderer.Toss) {
        val r = renderer
        if (!canRoll() || pendingToss[0] != null) { r?.holdDie(false); return }
        pendingToss[0] = toss
        r?.holdDie(true, shake = toss == UlarTanggaRenderer.Toss.Long)
        if (!table.roll(state.turn)) {
            pendingToss[0] = null
            r?.holdDie(false)
            return
        }
        scope.launch {
            delay(ROLL_WAIT_MS)
            if (pendingToss[0] === toss && !animating) { pendingToss[0] = null; renderer?.holdDie(false) }
        }
    }

    fun exit() {
        lifetime.stop()
        onExit()
    }

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

    val highlighted = if (state.finished || queue.isNotEmpty()) null else state.turn
    val ready = canRoll() && !offHold && !loading()
    // During the dice-off (and a moment after) the tiles show throws: this round's for the
    // seats throwing, the last one, dimmed, for seats already placed.
    val off = state.opening || offHold
    val throwing = if (state.opening) UlarTanggaGame.diceOffThrowers(state.openingRolls.dropLast(1), players) else emptyList()
    fun offRoll(seat: Int): Int? = when {
        !off -> null
        seat in throwing -> state.openingRolls.last()[seat].takeIf { it > 0 }
        else -> state.openingRolls.lastOrNull { it[seat] > 0 }?.get(seat)
    }
    // Once the order is set, the tiles stand in play order.
    val tileOrder = if (off) (0 until players).toList() else state.playOrder
    SideEffect { renderer?.dieReady = ready }
    val rollLabel = stringResource(R.string.ular_roll)
    val dieLabel = die?.toString() ?: ""

    Box(Modifier.fillMaxSize().background(Theme.backdropBrush)) {
        key(turned) { AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // A TextureView: it turns with the stage, and it is transparent, so the
                // backdrop gradient shows around the board.
                val view = TextureView(ctx)
                view.also {
                    renderer = UlarTanggaRenderer(view).apply {
                        this.onFirstFrame = onFirstFrame
                        onDieBounce = { if (lifetime.active) Sounds.play(Sounds.Effect.Die) }
                        resume()
                    }
                    view.tag = renderer
                }
            },
            onRelease = { view ->
                val own = view.tag as? UlarTanggaRenderer
                own?.destroy()
                if (renderer === own) renderer = null
            },
        ) }

        // Tell the renderer where the HUD sits, so the board takes the largest free space around it.
        var rootX by remember { mutableStateOf(0f) }
        var rootY by remember { mutableStateOf(0f) }
        var dieSpot by remember { mutableStateOf<FloatArray?>(null) }
        LaunchedEffect(renderer, dieSpot) { renderer?.dieSpot = dieSpot }
        val hud = remember { mutableStateMapOf<String, FloatArray>() }
        fun Modifier.hud(key: String) = onGloballyPositioned {
            val p = it.positionInRoot()
            hud[key] = floatArrayOf(p.x - rootX, p.y - rootY, p.x - rootX + it.size.width, p.y - rootY + it.size.height)
        }
        val margin = with(LocalDensity.current) { 12.dp.toPx() }
        LaunchedEffect(renderer) {
            val r = renderer ?: return@LaunchedEffect
            snapshotFlow { hud.toMap() }.collect { boxes ->
                val room = boxes[ROOM] ?: return@collect
                val obstacles = boxes.filterKeys { it != ROOM }.toSortedMap().values.map {
                    floatArrayOf(it[0] - margin, it[1] - margin, it[2] + margin, it[3] + margin)
                }
                r.fitBoard(room, obstacles)
            }
        }
        Row(
            Modifier.fillMaxSize().onGloballyPositioned { rootX = it.positionInRoot().x; rootY = it.positionInRoot().y }
                .windowInsetsPadding(gameInsets).padding(horizontal = 24.dp, vertical = 14.dp).hud(ROOM),
        ) {
            // Left: back, then the players.
            Column(
                Modifier.width(136.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back_to_menu), ::exit, Modifier.hud("back"))
                Spacer(Modifier.height(4.dp))
                // Two tiles a row.
                Column(Modifier.hud("players"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tileOrder.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { seat -> PlayerTile(
                                table, state, seat, on = seat == highlighted,
                                roll = offRoll(seat), out = state.opening && seat !in throwing,
                                modifier = Modifier.weight(1f).onGloballyPositioned {
                                    val p = it.positionInRoot()
                                    playerOrigins[seat] = floatArrayOf(p.x - rootX + it.size.width / 2f, p.y - rootY + it.size.height / 2f)
                                },
                            ) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Right: sound and rules (three 44 dp buttons, so 148 dp wide), then the die and whose turn it is.
            Column(
                Modifier.width(148.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.End,
            ) {
                Row(Modifier.hud("buttons"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoundToggles()
                    CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), { showRules = true })
                }
                Spacer(Modifier.weight(1f))
                val youLabel = stringResource(R.string.bot_you)
                EmoteHud(
                    canEmote = table.canEmote,
                    emote = table.emote,
                    playerName = { seat -> if (seat == table.mySeat) youLabel else table.name(seat) },
                    onSend = table::sendEmote,
                    modifier = Modifier.hud("emote"),
                )
                if (table.canEmote) Spacer(Modifier.height(10.dp))
                // The die itself is 3D, drawn by the renderer over this spot; tap it to toss
                // it, hold it to shake it and let go to throw it.
                Box(
                    Modifier.size(104.dp).align(Alignment.CenterHorizontally).hud("die")
                        .onGloballyPositioned {
                            val p = it.positionInRoot()
                            dieSpot = floatArrayOf(p.x - rootX + it.size.width / 2f, p.y - rootY + it.size.height / 2f, it.size.width * 0.45f)
                        }
                        .semantics {
                            contentDescription = dieLabel
                            onClick(rollLabel) { roll(UlarTanggaRenderer.Toss.Short); true }
                        }
                        .pointerInput(renderer) {
                            val r = renderer ?: return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!canRoll() || pendingToss[0] != null) return@awaitEachGesture
                                down.consume()
                                r.holdDie(true)
                                var long = false
                                var released = false
                                val hold = scope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    long = true
                                    r.holdDie(true, shake = true)
                                    Sounds.play(Sounds.Effect.Tap)
                                }
                                try {
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.isConsumed || !canRoll()) break
                                        val offset = change.position - down.position
                                        r.moveHeldDie(offset.x, offset.y)
                                        change.consume()
                                        if (!change.pressed) { released = true; break }
                                    }
                                } finally {
                                    hold.cancel()
                                    if (!released) r.holdDie(false)
                                }
                                if (released) roll(if (long) UlarTanggaRenderer.Toss.Long else UlarTanggaRenderer.Toss.Short)
                            }
                        },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        offHold -> stringResource(R.string.ular_dice_off_done, displayName(table, state.turn))
                        else -> status(table, state, animating)
                    },
                    style = Theme.font(14, FontWeight.Bold, Theme.muted),
                    textAlign = TextAlign.Center,
                    // Two lines kept even for a short status, so the die and the board don't move when it changes.
                    minLines = 2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().hud("status"),
                )
                val away = table.waiting
                val notice = table.notice?.let { stringResource(it) }
                if (away != null) CountdownNotice(
                    stringResource(R.string.online_waiting_for, away.name), away.seconds, (Krida.HANDOFF_WAIT_MS / 1000).toInt(),
                    Modifier.fillMaxWidth().hud("notice"), fontSize = 13,
                ) else notice?.let { notice ->
                    Text(
                        notice,
                        style = Theme.font(13, FontWeight.Bold, Theme.error),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().hud("notice"),
                    )
                }
            }
        }

        AnimatedVisibility(state.finished && !animating, enter = fadeIn(), exit = fadeOut()) {
            GameOver(
                table, state,
                onExit = { activity?.let { Ads.afterGame(it, ::exit) } ?: exit() },
                onRestart = { activity?.let { Ads.afterGame(it, table::restart) } ?: table.restart() },
            )
        }
    }

    if (showRules) UlarTanggaRulesDialog(state.options) { showRules = false }
}

/** [hud] key of the space the board may use. */
private const val ROOM = "room"

private fun pathLength(path: List<FloatArray>): Float = path.zipWithNext().sumOf { (a, b) ->
    val dx = b[0] - a[0]; val dz = b[2] - a[2]
    kotlin.math.sqrt(dx * dx + dz * dz).toDouble()
}.toFloat()

/** What is happening, under the die. */
@Composable
private fun status(table: UlarTanggaTable, state: UlarTanggaState, busy: Boolean): String {
    val seat = state.turn
    return when {
        state.finished -> ""
        busy -> ""
        state.opening && seat in table.botSeats -> stringResource(R.string.congklak_status_thinking)
        state.opening && state.openingRolls.size > 1 -> stringResource(R.string.ular_dice_off_tie, displayName(table, seat))
        state.opening && table.mySeat == seat -> stringResource(R.string.ular_dice_off_you)
        state.opening -> stringResource(R.string.ular_dice_off_of, table.name(seat))
        state.lastSeat == seat && state.lastRoll == 6 && state.options.sixRollsAgain -> stringResource(R.string.ular_six_again)
        seat in table.botSeats -> stringResource(R.string.congklak_status_thinking)
        table.mySeat == seat -> stringResource(R.string.ular_your_turn)
        else -> stringResource(R.string.ular_turn_of, table.name(seat))
    }
}

/** A seat as a square tile: its square (or finishing place) inside a circle in its color, the name below. */
@Composable
private fun PlayerTile(
    table: UlarTanggaTable,
    state: UlarTanggaState,
    seat: Int,
    on: Boolean,
    roll: Int? = null,
    out: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val place = state.finishOrder.indexOf(seat)
    val square = state.positions[seat]
    val color = seatColor(state, seat)
    // Dark digits on a light pawn color (yellow), white on the rest.
    val digits = if (color.red * 0.3f + color.green * 0.59f + color.blue * 0.11f > 0.6f) Theme.inkOnLight else Color.White
    Column(
        modifier.aspectRatio(1f)
            .alpha(if (out) 0.4f else 1f)
            .then(if (on) Modifier.shadow(8.dp, shape, ambientColor = Theme.gold, spotColor = Theme.gold) else Modifier)
            .background(if (on) Theme.accent else Theme.surface, shape)
            .then(if (on) Modifier else Modifier.border(1.5.dp, Theme.line, shape))
            .padding(4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Box(
                Modifier.size(34.dp).background(Color.White, CircleShape).padding(2.dp).background(color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // During the dice-off the circle shows the seat's throw.
                val start = place < 0 && square == 0 && roll == null
                Text(
                    when {
                        roll != null -> roll.toString()
                        place >= 0 -> "#${place + 1}"
                        start -> stringResource(R.string.ular_start)
                        else -> square.toString()
                    },
                    style = Theme.font(if (start) 9 else 14, FontWeight.Black, digits).copy(lineHeight = 14.sp),
                    maxLines = 1,
                )
            }
            if (seat in table.botSeats) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(0.dp).size(16.dp).background(Theme.surface, CircleShape).border(1.dp, Theme.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_computer), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(10.dp))
                }
            }
        }
        Text(
            displayName(table, seat),
            style = Theme.font(11, FontWeight.Bold, if (on) Color.White else Theme.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun displayName(table: UlarTanggaTable, seat: Int) =
    if (table.mySeat == seat) stringResource(R.string.bot_you) else table.name(seat)

/** The result card: the ranking, and Menu / Main lagi. */
@Composable
private fun GameOver(table: UlarTanggaTable, state: UlarTanggaState, onExit: () -> Unit, onRestart: () -> Unit) {
    val ranking = UlarTanggaGame.ranking(state)
    val winner = ranking.first()
    Box(
        Modifier.fillMaxSize().background(Theme.ink.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(380.dp)
                .shadow(25.dp, RoundedCornerShape(28.dp), spotColor = Theme.ink)
                .background(Theme.surface, RoundedCornerShape(28.dp))
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)
                .semantics { isTraversalGroup = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, tint = Color(0xFFC98A1B), modifier = Modifier.size(34.dp))
            Text(
                if (winner == table.mySeat) stringResource(R.string.congklak_you_win) else stringResource(R.string.congklak_wins, table.name(winner)),
                style = Theme.font(24, FontWeight.Black, Theme.ink),
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ranking.forEachIndexed { i, seat ->
                    Row(
                        Modifier.fillMaxWidth().background(if (i == 0) Theme.highlight else Theme.surfaceSoft, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("${i + 1}", style = Theme.font(16, FontWeight.Black, Theme.muted), modifier = Modifier.width(18.dp))
                        Box(Modifier.size(14.dp).background(seatColor(state, seat), CircleShape))
                        Text(displayName(table, seat), style = Theme.font(16, color = Theme.ink), maxLines = 1, modifier = Modifier.weight(1f))
                        val square = state.positions[seat]
                        Text(if (square == 0) stringResource(R.string.ular_start) else square.toString(), style = Theme.font(15, FontWeight.Bold, Theme.muted))
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.congklak_to_menu), onExit, Modifier.weight(1f), height = 48.dp, tint = Theme.ink)
                if (table.canRestart) {
                    PrimaryButton(stringResource(R.string.congklak_play_again), onRestart, Modifier.weight(1f), height = 48.dp)
                } else {
                    Text(
                        stringResource(R.string.congklak_wait_host),
                        style = Theme.font(14, FontWeight.Bold, Theme.muted),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Voice for whose turn it is; pass-and-play gets the chime only. */
private fun announceTurn(table: UlarTanggaTable, seat: Int) {
    val me = table.mySeat
    if (me != null) Sounds.say(if (seat == me) Sounds.Line.YourTurn else Sounds.Line.TheirTurn) else Sounds.play(Sounds.Effect.Turn)
}

/** After a roll: the result when the game ends, a fanfare when someone reaches 100, else the next turn. */
private fun announce(table: UlarTanggaTable, after: UlarTanggaState, reached: Boolean) {
    val me = table.mySeat
    when {
        after.finished -> {
            val won = me == null || after.winner == me
            Sounds.play(if (won) Sounds.Effect.Win else Sounds.Effect.Lose)
            if (me != null) Sounds.say(if (won) Sounds.Line.Win else Sounds.Line.Lose)
        }
        reached -> Sounds.play(Sounds.Effect.Win)
        after.turn != after.lastSeat -> announceTurn(table, after.turn)
    }
}

/** "Cara main" with this game's options. */
@Composable
internal fun UlarTanggaRulesDialog(options: UlarTanggaOptions, onDismiss: () -> Unit) {
    val rules = listOfNotNull(
        stringResource(R.string.ular_rule_dice_off),
        stringResource(if (options.sixToEnter) R.string.ular_rule_six_to_enter else R.string.ular_rule_start),
        stringResource(R.string.ular_rule_ladders),
        stringResource(R.string.ular_rule_snakes),
        stringResource(if (options.bounceBack) R.string.ular_rule_bounce else R.string.ular_rule_exact),
        if (options.sixRollsAgain) stringResource(R.string.ular_rule_six_again) else null,
        stringResource(if (options.firstFinishEnds) R.string.ular_rule_first_ends else R.string.ular_rule_last_one),
    )
    // An overlay inside the stage, not a Dialog, so it turns with the stage.
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Theme.ink.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).background(Theme.surface, RoundedCornerShape(24.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 16.dp),
        ) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.congklak_rules_title), style = Theme.font(20, FontWeight.Black, Theme.ink), modifier = Modifier.weight(1f))
                CircleIconButton(R.drawable.ic_close, stringResource(R.string.congklak_rules_close), onDismiss)
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rules.forEach { rule ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(7.dp).background(Theme.accent, CircleShape))
                        Text(rule, style = Theme.font(16, FontWeight.SemiBold, Theme.ink))
                    }
                }
            }
        }
    }
}
