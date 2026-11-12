package com.ardeman.krida.gaple

import com.ardeman.krida.account.avatarOf
import com.ardeman.krida.account.SeatAvatar
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ardeman.krida.R
import com.ardeman.krida.ads.Ads
import com.ardeman.krida.congklak.VoxelPreview
import com.ardeman.krida.core.games.GapleGame
import com.ardeman.krida.core.games.GaplePlaced
import com.ardeman.krida.core.games.GapleState
import com.ardeman.krida.core.games.GapleTile
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.CountdownNotice
import com.ardeman.krida.ui.CountdownRing
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
import com.ardeman.krida.ulartangga.pawnColor
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BOT_PAUSE_MS = 650L
private const val SETTLE_MS = 250L
private const val ROOM = "room"
private val IVORY = Color(0xFFFBF3E0)
private val PIP = Color(0xFF2A211E)
private val PIP_RED = Color(0xFFBF2A2E)

@Composable
fun GapleScreen(table: GapleTable, onExit: () -> Unit) {
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

/** The menu card: an upright [6|6] with two tiles laid against it, as the mockup shows. */
@Composable
fun GaplePreview(modifier: Modifier = Modifier, onReady: () -> Unit = {}) =
    VoxelPreview("gaple", null, modifier, onReady) { view ->
        GapleRenderer(view).apply {
            val line = listOf(GaplePlaced(4, 6), GaplePlaced(6, 6), GaplePlaced(6, 3))
            applyState(
                GapleState(
                    players = 2,
                    hands = listOf(emptyList(), emptyList()),
                    handCounts = listOf(0, 5),
                    line = line,
                    origin = 1,
                    scores = listOf(0, 0),
                    passedOn = listOf(emptyList(), emptyList()),
                ),
                viewer = 0,
                animate = false,
            )
            // Zoomed in on the three tiles, with a little felt around them.
            focus = listOf(floatArrayOf(-3.4f, 0f, -2f), floatArrayOf(3.4f, 0f, 2f))
        }
    }

@Composable
private fun Board(table: GapleTable, onExit: () -> Unit, onFirstFrame: () -> Unit, loading: () -> Boolean) {
    var state by remember { mutableStateOf(table.latest) }
    var animating by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<GapleRenderer?>(null) }
    /** A tile that fits both ends, waiting for the player to tap one. */
    var picking by remember { mutableStateOf<GapleTile?>(null) }
    val queue = remember { ArrayDeque<GapleUpdate>() }
    val me = table.mySeat
    val lifetime = rememberGameLifetime(table) {
        table.onUpdate = null
        queue.clear()
        renderer?.pulseEnds(false)
        table.leave()
        Sounds.stopGameAudio()
    }
    val scope = lifetime.scope

    fun idle() {
        scope.launch {
            while (loading()) delay(100)
            delay(BOT_PAUSE_MS)
            if (!animating && queue.isEmpty()) table.stepBot()
        }
    }

    fun drain() {
        if (animating) return
        val next = queue.removeFirstOrNull() ?: run { idle(); return }
        animating = true
        scope.launch {
            try {
                val r = renderer
                if (r != null) {
                    r.applyState(next.state, me, animate = next.fresh)
                    if (next.fresh) {
                        Sounds.play(Sounds.Effect.Step)
                        delay((GapleRenderer.FLIGHT_SECONDS * 1000).toLong() + SETTLE_MS)
                    }
                }
                val before = state
                state = next.state
                // Seats that couldn't play after this move passed: "Pas!".
                if (next.fresh && next.state.lastPasses.isNotEmpty()) Sounds.say(Sounds.Line.Pass)
                if (next.state.finished && !before.finished) {
                    val mine = next.state.team(me) == next.state.winner?.let(next.state::team)
                    Sounds.play(if (mine) Sounds.Effect.Win else Sounds.Effect.Lose)
                } else if (!next.state.roundOver && next.state.turn == me && before.turn != me) {
                    Sounds.play(Sounds.Effect.Turn)
                }
            } finally {
                animating = false
                drain()
            }
        }
    }

    DisposableEffect(table) {
        table.onUpdate = { update ->
            queue.addLast(update)
            drain()
        }
        onDispose { table.onUpdate = null }
    }
    LaunchedEffect(renderer) {
        val r = renderer ?: return@LaunchedEffect
        r.applyState(state, me, animate = false)
        drain()
    }

    val settled = !animating && queue.isEmpty()
    val myTurn = settled && !state.roundOver && !state.finished && state.turn == me
    val playable = if (myTurn) GapleGame.playable(state, me) else emptyList()
    LaunchedEffect(myTurn) { if (!myTurn) picking = null }
    LaunchedEffect(picking, renderer) { renderer?.pulseEnds(picking != null) }

    // Once your timer runs out, the phone plays your turns after a short pause
    // (Krida.AUTO_PLAY_MS) instead of a countdown, until you tap a tile yourself.
    var auto by remember { mutableStateOf(false) }

    fun play(tile: GapleTile) {
        if (!myTurn || tile !in playable) return
        auto = false
        val ends = GapleGame.endsFor(state, tile)
        if (state.line.isEmpty() || ends.size == 1) {
            picking = null
            table.play(tile, if (state.line.isEmpty()) null else ends.single())
        } else {
            // Fits both ends: the rings pulse until one is tapped; tapping the tile again cancels.
            picking = if (picking == tile) null else tile
        }
    }

    fun playEnd(end: Int) {
        val tile = picking ?: return
        picking = null
        table.play(tile, end)
    }

    // Turn timer: at 0 the phone plays what the easy computer would (GapleGame.timeoutMove).
    val turnTotal = (Krida.PLAY_TURN_MS / 1000L).toInt()
    var turnSeconds by remember { mutableIntStateOf(0) }
    val myTurnNow by rememberUpdatedState(myTurn)
    LaunchedEffect(myTurn, state.turn, state.line.size, state.round) {
        if (!myTurn) {
            turnSeconds = 0
            return@LaunchedEffect
        }
        if (auto) {
            turnSeconds = 0
            delay(Krida.AUTO_PLAY_MS)
        } else {
            turnSeconds = turnTotal
            while (turnSeconds > 0) {
                delay(1000L)
                if (!myTurnNow) return@LaunchedEffect
                turnSeconds -= 1
            }
            auto = true
        }
        if (!myTurnNow || !auto) return@LaunchedEffect
        picking = null
        GapleGame.timeoutMove(state, me, Random.Default)?.let { table.play(it.tile!!, it.end) }
    }

    // Round end: the host deals on by itself after the same countdown. Against computers an
    // interstitial (frequency-capped) may run first; [dealing] keeps the button and the
    // countdown from dealing (or showing an ad) twice.
    val roundCard = settled && state.roundOver && !state.finished
    var roundSeconds by remember { mutableIntStateOf(0) }
    val activity = LocalActivity.current
    var dealing by remember(state.round) { mutableStateOf(false) }
    fun deal() {
        if (dealing || !table.isHost) return
        dealing = true
        val a = activity
        if (table.isLocal && a != null) Ads.afterGame(a) { table.nextRound() } else table.nextRound()
    }
    LaunchedEffect(roundCard, state.round) {
        if (!roundCard) {
            roundSeconds = 0
            return@LaunchedEffect
        }
        roundSeconds = turnTotal
        while (roundSeconds > 0) {
            delay(1000L)
            roundSeconds -= 1
        }
        deal()
    }

    fun exit() {
        lifetime.stop()
        onExit()
    }

    BackHandler(onBack = ::exit)
    val turned = LocalStageTurned.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, renderer) {
        val r = renderer
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) r?.resume()
            if (e == Lifecycle.Event.ON_PAUSE) r?.pause()
            if (e == Lifecycle.Event.ON_STOP) Sounds.stopGameAudio()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    var viewOrigin by remember { mutableStateOf(floatArrayOf(0f, 0f)) }

    Box(Modifier.fillMaxSize().background(Theme.backdropBrush)) {
        key(turned) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).also { view ->
                        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            val loc = IntArray(2)
                            v.getLocationInWindow(loc)
                            viewOrigin = floatArrayOf(loc[0].toFloat(), loc[1].toFloat())
                        }
                        renderer = GapleRenderer(view).apply {
                            this.onFirstFrame = onFirstFrame
                            resume()
                        }
                        view.tag = renderer
                    }
                },
                onRelease = { view ->
                    val own = view.tag as? GapleRenderer
                    own?.destroy()
                    if (renderer === own) renderer = null
                },
            )
        }

        // Picking an end: tap near one of the two pulsing rings.
        if (picking != null) {
            val reach = with(LocalDensity.current) { 64.dp.toPx() }
            val leftLabel = stringResource(R.string.gaple_end_left)
            val rightLabel = stringResource(R.string.gaple_end_right)
            Box(
                Modifier.fillMaxSize()
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(leftLabel) { playEnd(GapleGame.LEFT); true },
                            CustomAccessibilityAction(rightLabel) { playEnd(GapleGame.RIGHT); true },
                        )
                    }
                    .pointerInput(renderer, picking) {
                        detectTapGestures { tap ->
                            val r = renderer ?: return@detectTapGestures
                            val hit = (0..1).mapNotNull { end -> r.endOnScreen(end)?.let { end to it } }
                                .minByOrNull { (_, xy) -> hypot(xy[0] - tap.x, xy[1] - tap.y) }
                                ?: return@detectTapGestures
                            if (hypot(hit.second[0] - tap.x, hit.second[1] - tap.y) <= reach) playEnd(hit.first)
                        }
                    },
            )
        }

        // The table takes the largest free space the HUD leaves.
        val hud = remember { mutableStateMapOf<String, FloatArray>() }
        fun Modifier.hud(key: String) = onGloballyPositioned {
            val p = it.positionInWindow()
            hud[key] = floatArrayOf(
                p.x - viewOrigin[0], p.y - viewOrigin[1],
                p.x - viewOrigin[0] + it.size.width, p.y - viewOrigin[1] + it.size.height,
            )
        }
        val margin = with(LocalDensity.current) { 8.dp.toPx() }
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

        Row(Modifier.fillMaxSize().windowInsetsPadding(gameInsets).padding(horizontal = 24.dp, vertical = 14.dp).hud(ROOM)) {
            Column(Modifier.width(150.dp).fillMaxHeight().hud("left"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back_to_menu), ::exit)
                Text(stringResource(R.string.gaple_title), style = Theme.font(18, FontWeight.Black, Theme.ink))
                Text(
                    stringResource(R.string.gaple_round, state.round, GapleGame.TARGET),
                    style = Theme.font(12, FontWeight.Bold, Theme.muted),
                )
                for (seat in 0 until state.players) {
                    PlayerTile(table, state, seat, on = seat == state.turn && !state.roundOver && !state.finished)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                EventPills(table, state, myTurn, picking != null, Modifier.heightIn(min = 32.dp).hud("pills"))
                Spacer(Modifier.weight(1f))
                Hand(
                    hand = state.hands.getOrNull(me).orEmpty(),
                    playable = playable.toSet(),
                    picking = picking,
                    onTap = ::play,
                    modifier = Modifier.hud("hand"),
                )
            }
            Column(Modifier.width(160.dp).fillMaxHeight().hud("right"), horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoundToggles()
                    CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), { showRules = true })
                }
                // Reactions sit right under the rules button.
                if (table.canEmote) Spacer(Modifier.height(8.dp))
                val youLabel = stringResource(R.string.bot_you)
                EmoteHud(
                    canEmote = table.canEmote,
                    emotes = table.emotes,
                    playerName = { seat -> if (seat == me) youLabel else table.name(seat) },
                    onSend = table::sendEmote,
                )
                Spacer(Modifier.weight(1f))
                if (myTurn && turnSeconds > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.gaple_your_turn), style = Theme.font(13, FontWeight.Bold, Theme.ink))
                        CountdownRing(turnSeconds, turnTotal, size = 40.dp)
                    }
                }
            }
        }

        table.waiting?.let { away ->
            CountdownNotice(
                stringResource(R.string.online_waiting_for, away.name),
                away.seconds,
                (Krida.HANDOFF_WAIT_MS / 1000).toInt(),
                Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            )
        } ?: table.notice?.let {
            Text(
                stringResource(it),
                style = Theme.font(14, FontWeight.Bold, Theme.error),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                    .background(Theme.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (roundCard) RoundCard(table, state, roundSeconds, turnTotal, onNext = ::deal)
        if (settled && state.finished) ResultCard(
            table, state,
            onExit = { activity?.let { Ads.afterGame(it, ::exit) } ?: exit() },
            onRestart = { activity?.let { Ads.afterGame(it, table::restart) } ?: table.restart() },
        )
        if (showRules) RulesSheet(state.partners) { showRules = false }
    }
}

/** "You", a player's name, or "Team A" with partners. */
@Composable
private fun seatName(table: GapleTable, seat: Int): String =
    if (seat == table.mySeat) stringResource(R.string.bot_you) else table.name(seat)

@Composable
private fun teamName(team: Int) = stringResource(R.string.gaple_team, if (team == 0) "A" else "B")

/** Pills at the top of the felt: whose turn it is and the last thing that happened. */
@Composable
private fun EventPills(table: GapleTable, state: GapleState, myTurn: Boolean, picking: Boolean, modifier: Modifier) {
    val turn = when {
        state.roundOver || state.finished -> null
        picking -> stringResource(R.string.gaple_pick_end)
        // "Your turn" itself sits by the countdown ring.
        myTurn -> state.mustPlay?.let { stringResource(R.string.gaple_must_open, it.a, it.b) }
        else -> stringResource(R.string.gaple_turn_of, seatName(table, state.turn))
    }
    val passes = state.lastPasses.map { stringResource(R.string.gaple_pass, seatName(table, it)) }
    val played = state.lastSeat?.let { seat ->
        state.lastTile?.let {
            if (seat == table.mySeat) stringResource(R.string.gaple_you_play, it.left, it.right)
            else stringResource(R.string.gaple_plays, table.name(seat), it.left, it.right)
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        (listOfNotNull(played) + passes).takeLast(2).forEach { Pill(it, Theme.muted) }
        turn?.let { Pill(it, Theme.ink) }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = Theme.font(12, FontWeight.Bold, color),
        maxLines = 1,
        modifier = Modifier.background(Theme.surface.copy(alpha = 0.9f), RoundedCornerShape(50))
            .border(1.dp, Theme.line, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PlayerTile(table: GapleTable, state: GapleState, seat: Int, on: Boolean) {
    val color = pawnColor(seat)
    val shape = RoundedCornerShape(12.dp)
    val score = state.scores.getOrElse(seat) { 0 }
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (on) Theme.chip else Theme.surface)
            .border(if (on) 2.dp else 1.dp, if (on) color else Theme.line, shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val avatar = table.players.avatarOf(seat)
        SeatAvatar(avatar, color, if (avatar != null) 22.dp else 14.dp)
        Column(Modifier.weight(1f)) {
            Text(seatName(table, seat), style = Theme.font(12, FontWeight.Bold, Theme.ink), maxLines = 1)
            Text(
                stringResource(R.string.gaple_tiles_left, state.handCounts.getOrElse(seat) { 0 }) +
                    if (state.partners) " · ${if (state.team(seat) == 0) "A" else "B"}" else "",
                style = Theme.font(11, FontWeight.SemiBold, Theme.muted),
                maxLines = 1,
            )
        }
        Text("$score", style = Theme.font(15, FontWeight.Black, Theme.ink))
    }
}

/** Your hand, upright. Tiles that fit are raised with a gold ring. */
@Composable
private fun Hand(
    hand: List<GapleTile>,
    playable: Set<GapleTile>,
    picking: GapleTile?,
    onTap: (GapleTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        hand.forEach { tile ->
            val fits = tile in playable
            val lift by animateDpAsState(if (fits) (-10).dp else 0.dp, label = "lift")
            val label = stringResource(R.string.gaple_tile, tile.a, tile.b)
            TileFace(
                tile,
                width = 34.dp,
                ring = when {
                    tile == picking -> Theme.accent
                    fits -> Theme.gold
                    else -> null
                },
                dim = playable.isNotEmpty() && !fits,
                modifier = Modifier.offset(y = lift)
                    .then(if (fits) Modifier.clickable(role = Role.Button) { onTap(tile) } else Modifier)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/** A domino face, upright: [GapleTile.a] on top. */
@Composable
private fun TileFace(tile: GapleTile, width: Dp, ring: Color?, dim: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(width, width * 2)
            .clip(shape)
            .background(IVORY.copy(alpha = if (dim) 0.7f else 1f))
            .border(if (ring != null) 2.5.dp else 1.dp, ring ?: Color(0xFFCBB89A), shape),
    ) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val half = size.height / 2
            drawRect(PIP.copy(alpha = 0.6f), Offset(size.width * 0.15f, half - 0.75f), Size(size.width * 0.7f, 1.5f))
            pips(tile.a, Offset(0f, 0f), Size(size.width, half), dim)
            pips(tile.b, Offset(0f, half), Size(size.width, half), dim)
        }
    }
}

private fun DrawScope.pips(value: Int, origin: Offset, area: Size, dim: Boolean) {
    val d = 0.28f
    val spots = when (value) {
        1 -> listOf(0f to 0f)
        2 -> listOf(-d to -d, d to d)
        3 -> listOf(-d to -d, 0f to 0f, d to d)
        4 -> listOf(-d to -d, d to -d, -d to d, d to d)
        5 -> listOf(-d to -d, d to -d, 0f to 0f, -d to d, d to d)
        6 -> listOf(-d to -d, -d to 0f, -d to d, d to -d, d to 0f, d to d)
        else -> emptyList()
    }
    val r = minOf(area.width, area.height) * 0.09f
    val color = (if (value == 1) PIP_RED else PIP).copy(alpha = if (dim) 0.55f else 1f)
    for ((x, y) in spots) {
        drawCircle(color, r, Offset(origin.x + area.width * (0.5f + x), origin.y + area.height * (0.5f + y)))
    }
}

@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(20.dp)).background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

/** Rows of the round table: per seat, or per team with partners. */
private class ScoreRow(val seats: List<Int>, val pips: Int, val points: Int, val total: Int)

private fun scoreRows(state: GapleState): List<ScoreRow> {
    val n = state.players
    val groups = if (state.partners) listOf(listOf(0, 2), listOf(1, 3)) else (0 until n).map { listOf(it) }
    return groups.map { g ->
        ScoreRow(
            seats = g,
            pips = g.sumOf { state.roundPips.getOrElse(it) { 0 } },
            points = state.roundPoints.getOrElse(g[0]) { 0 },
            total = state.scores.getOrElse(g[0]) { 0 },
        )
    }
}

@Composable
private fun ScoreTable(table: GapleTable, state: GapleState, rows: List<ScoreRow>) {
    @Composable
    fun Cell(text: String, w: Dp, bold: Boolean = false, color: Color = Theme.ink) =
        Text(text, style = Theme.font(13, if (bold) FontWeight.Black else FontWeight.Bold, color), textAlign = TextAlign.End, modifier = Modifier.width(w))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Cell(stringResource(R.string.gaple_col_pips), 56.dp, color = Theme.muted)
        Cell(stringResource(R.string.gaple_col_points), 56.dp, color = Theme.muted)
        Cell(stringResource(R.string.gaple_col_total), 56.dp, color = Theme.muted)
    }
    rows.forEach { row ->
        val won = state.roundWinner?.let { it in row.seats } == true
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(if (won) Theme.gold.copy(alpha = 0.28f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.seats.forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(pawnColor(it))) }
            val name = if (state.partners) teamName(state.team(row.seats[0])) + " · " + row.seats.map { seatName(table, it) }.joinToString(" + ")
            else seatName(table, row.seats[0])
            Text(name, style = Theme.font(13, FontWeight.Bold, Theme.ink), maxLines = 1, modifier = Modifier.weight(1f))
            Cell("${row.pips}", 50.dp)
            Cell(if (row.points > 0) "+${row.points}" else "0", 50.dp)
            Cell("${row.total}", 50.dp, bold = true)
        }
    }
}

@Composable
private fun RoundCard(table: GapleTable, state: GapleState, seconds: Int, total: Int, onNext: () -> Unit) {
    val winner = state.roundWinner
    val title = when {
        state.roundEnd == "gaple" -> stringResource(R.string.gaple_gaple)
        winner == table.mySeat -> stringResource(R.string.gaple_domino_you)
        winner != null -> stringResource(R.string.gaple_domino, table.name(winner))
        else -> ""
    }
    CardFrame {
        Text(title, style = Theme.font(20, FontWeight.Black, Theme.ink))
        ScoreTable(table, state, scoreRows(state))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CountdownRing(seconds, total, size = 40.dp)
            if (table.isHost) {
                PrimaryButton(stringResource(R.string.gaple_next_round), onNext, Modifier.width(200.dp))
            } else {
                Text(stringResource(R.string.gaple_waiting_host), style = Theme.font(14, FontWeight.Bold, Theme.muted))
            }
        }
    }
}

@Composable
private fun ResultCard(table: GapleTable, state: GapleState, onExit: () -> Unit, onRestart: () -> Unit) {
    val winner = state.winner
    val me = table.mySeat
    val title = when {
        winner == null -> stringResource(R.string.gaple_title)
        state.partners && state.team(winner) == state.team(me) -> stringResource(R.string.gaple_your_team_wins)
        state.partners -> stringResource(R.string.gaple_team_wins, if (state.team(winner) == 0) "A" else "B")
        winner == me -> stringResource(R.string.ludo_you_win)
        else -> stringResource(R.string.ludo_wins, table.name(winner))
    }
    CardFrame {
        Text(title, style = Theme.font(22, FontWeight.Black, Theme.ink))
        Text(stringResource(R.string.gaple_target), style = Theme.font(13, FontWeight.Bold, Theme.muted))
        ScoreTable(table, state, scoreRows(state).sortedByDescending { it.total })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.congklak_to_menu), onExit, Modifier.weight(1f), tint = Theme.muted)
            if (table.isHost) PrimaryButton(stringResource(R.string.congklak_play_again), onRestart, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RulesSheet(partners: Boolean, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.congklak_rules_title), style = Theme.font(20, FontWeight.Black, Theme.ink), modifier = Modifier.weight(1f))
                CircleIconButton(R.drawable.ic_close, stringResource(R.string.congklak_rules_close), onDismiss)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOfNotNull(
                    R.string.gaple_rule_tiles,
                    R.string.gaple_rule_open,
                    R.string.gaple_rule_play,
                    R.string.gaple_rule_pass,
                    R.string.gaple_rule_end,
                    R.string.gaple_rule_score,
                    if (partners) R.string.gaple_rule_partners else null,
                    R.string.gaple_rule_timer,
                ).forEach {
                    Text(stringResource(it), style = Theme.font(15, FontWeight.SemiBold, Theme.ink))
                }
            }
        }
    }
}
