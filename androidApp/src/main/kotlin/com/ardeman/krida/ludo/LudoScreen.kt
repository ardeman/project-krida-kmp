package com.ardeman.krida.ludo

import com.ardeman.krida.account.avatarOf
import com.ardeman.krida.account.SeatAvatar
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ardeman.krida.R
import com.ardeman.krida.ads.Ads
import com.ardeman.krida.congklak.VoxelPreview
import androidx.activity.compose.LocalActivity
import com.ardeman.krida.core.games.LudoBoard
import com.ardeman.krida.core.games.LudoGame
import com.ardeman.krida.core.games.LudoOptions
import com.ardeman.krida.core.games.LudoState
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.CountdownNotice
import com.ardeman.krida.ui.TurnTimer
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

private const val ROLL_WAIT_MS = 4000L
private const val HOP_MS = 200L
private const val BOT_PAUSE_MS = 700L
private const val ROOM = "room"

@Composable
fun LudoScreen(table: LudoTable, onExit: () -> Unit) {
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

/** Where the menu preview's one pawn on the track stands (steps from its start). */
private const val PREVIEW_OUT = 2

@Composable
fun LudoPreview(modifier: Modifier = Modifier, onReady: () -> Unit = {}) =
    VoxelPreview("ludo", null, modifier, onReady) { view ->
        LudoRenderer(view).apply {
            setSeats(listOf(0, 1, 2, 3))
            for (color in 0 until 4) for (pawn in 0 until 4) {
                place(color, pawn, LudoBoard.tokenAnchor(color, pawn, LudoGame.YARD))
            }
            // One pawn already out on the track.
            place(0, 0, LudoBoard.tokenAnchor(0, 0, PREVIEW_OUT))
            // Zoomed in on one corner: the first colour's yard and the start of its track.
            frame(
                (0 until 4).map { LudoBoard.tokenAnchor(0, it, LudoGame.YARD) } +
                    (0..5).map { LudoBoard.tokenAnchor(0, 0, it) },
            )
        }
    }

@Composable
private fun Board(table: LudoTable, onExit: () -> Unit, onFirstFrame: () -> Unit, loading: () -> Boolean) {
    val playerOrigins = remember { mutableMapOf<Int, FloatArray>() }
    var state by remember { mutableStateOf(table.latest) }
    var animating by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<LudoRenderer?>(null) }
    val pendingToss = remember { arrayOfNulls<LudoRenderer.Toss>(1) }
    val queue = remember { ArrayDeque<LudoUpdate>() }
    val lifetime = rememberGameLifetime(table) {
        table.onUpdate = null
        queue.clear()
        renderer?.showPickRings(emptyList())
        renderer?.onDieBounce = null
        renderer?.holdDie(false)
        table.leave()
        Sounds.stopGameAudio()
    }
    val scope = lifetime.scope

    // Same-colour tokens on one square stack into a tower; colours sharing a square sit apart.
    fun spot(s: LudoState, seat: Int, pawn: Int) = LudoBoard.tokenSpot(s, seat, pawn)

    fun placeAll(s: LudoState) {
        val r = renderer ?: return
        // Your colour (or seat 0 when passing the phone) sits bottom-left.
        val viewSeat = table.mySeat ?: 0
        r.setViewColor(s.color(viewSeat.coerceIn(0, s.tokens.lastIndex)))
        r.setSeats(List(s.tokens.size) { s.color(it) })
        for (seat in s.tokens.indices) for (pawn in 0 until LudoGame.PAWNS) {
            r.place(seat, pawn, spot(s, seat, pawn))
        }
        r.recallDie(if (table.isLocal(s.turn)) null else playerOrigins[s.turn])
    }

    fun idle() {
        scope.launch {
            while (loading()) delay(100)
            delay(BOT_PAUSE_MS)
            if (!animating && queue.isEmpty()) table.stepBot()
        }
    }

    suspend fun animate(before: LudoState, after: LudoState) {
        val r = renderer ?: return
        val seat = after.lastSeat ?: return
        val roll = after.lastRoll ?: return
        val toss = pendingToss[0] ?: LudoRenderer.Toss.Short
        pendingToss[0] = null
        val origin = if (table.isLocal(seat)) null else playerOrigins[seat]
        // A pick after a multi-choice roll keeps lastRoll; only throw when this update is the roll
        // itself (or an auto-move that applied the roll in one step). Re-throwing on pick looked
        // like the die rolling the same face again.
        val throwNow = before.roll == null
        r.showPickRings(emptyList())
        if (throwNow) {
            val flight = r.throwDie(roll, toss, origin, seat)
            // Small buffer so the last throw frame can mark Landed before we hop (and recall).
            delay((flight * 1000).toLong() + 200L)
            Sounds.sayDie(roll)
        }

        val pawn = after.lastPawn
        if (pawn != null && after.lastFrom != null && after.lastTo != null) {
            val color = after.color(seat)
            val fromPos = after.lastFrom!!
            // Hop square by square; the last hop lands on top of a tower or beside another colour.
            val path = LudoGame.walk(fromPos, after.lastTo!!).map {
                LudoBoard.tokenAnchor(color, pawn, it)
            }.dropLast(1) + listOf(spot(after, seat, pawn))
            delay(180L)
            r.move(seat, pawn, path, (path.size * HOP_MS) / 1000f, hop = 0.35f)
            // Tokens the mover leaves or joins settle (a tower shrinks, a colour moves aside).
            val captured = after.lastCaptures.map { it.seat to it.pawn }.toSet()
            for (s in after.tokens.indices) for (p in 0 until LudoGame.PAWNS) {
                if ((s == seat && p == pawn) || (s to p) in captured) continue
                val to = spot(after, s, p)
                if (!to.contentEquals(spot(before, s, p))) r.move(s, p, listOf(to), 0.25f, hop = 0.15f)
            }
            // The same tok on each square as ular tangga.
            repeat(path.size) {
                Sounds.play(Sounds.Effect.Step)
                delay(HOP_MS)
            }
            if (after.lastTo == LudoGame.FINISH) Sounds.say(Sounds.Line.Home)
            delay(80L)
            for (cap in after.lastCaptures) {
                val to = spot(after, cap.seat, cap.pawn)
                r.move(cap.seat, cap.pawn, listOf(to), 0.35f, hop = 0.5f)
            }
            if (after.lastCaptures.isNotEmpty()) {
                Sounds.play(Sounds.Effect.Capture)
                Sounds.say(Sounds.Line.Captured)
                delay(400L)
            }
            // Let "Kena!" / "Masuk rumah!" finish before the turn line cuts in.
            if (after.lastCaptures.isNotEmpty() || after.lastTo == LudoGame.FINISH) delay(500L)
        }
        placeAll(after)
        r.recallDie(if (table.isLocal(after.turn)) null else playerOrigins[after.turn])
        delay(180L)
        delay(280L)
    }

    // The first turn of a game (or a restart) is announced once, as soon as the board shows.
    var startSaid by remember { mutableStateOf(false) }
    fun announceStart(s: LudoState) {
        if (s.lastSeat != null || s.finished) { startSaid = false; return }
        if (startSaid) return
        startSaid = true
        announceTurn(table, s.turn)
    }

    fun drain() {
        if (animating) return
        val next = queue.removeFirstOrNull() ?: run { idle(); return }
        animating = true
        scope.launch {
            try {
                if (next.fresh) animate(state, next.state) else placeAll(next.state)
                state = next.state
                if (next.fresh) announce(table, next.state) else announceStart(next.state)
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
        if (renderer == null) return@LaunchedEffect
        placeAll(state)
        drain()
        while (loading()) delay(100)
        if (!animating && queue.isEmpty()) announceStart(state)
    }

    val canRoll = !state.finished && state.roll == null && table.isLocal(state.turn) && !animating && queue.isEmpty()
    val legal = state.roll?.let { LudoGame.legalPawns(state, state.turn, it) }.orEmpty()
    val canPick = !state.finished && state.roll != null && table.isLocal(state.turn) && !animating && queue.isEmpty()
    var holdingDie by remember { mutableStateOf(false) }
    var rollSeconds by remember { mutableIntStateOf(0) }
    val rollTotal = (Krida.ROLL_TURN_MS / 1000L).toInt()
    val holdingNow by rememberUpdatedState(holdingDie)
    val canRollNow by rememberUpdatedState(canRoll)

    // If a prior recall raced the throw frame, snap the die home when it is time to roll again.
    LaunchedEffect(canRoll, renderer, state.turn) {
        if (canRoll) renderer?.recallDie(null)
    }

    // Gold rings on the board under legal tokens.
    LaunchedEffect(canPick, legal, renderer, animating, state.turn, state.roll, state.tokens) {
        val r = renderer ?: return@LaunchedEffect
        if (animating) return@LaunchedEffect
        if (canPick && legal.isNotEmpty()) {
            r.showPickRings(legal.map { spot(state, state.turn, it) })
        } else {
            r.showPickRings(emptyList())
        }
    }

    // Seats on this phone whose timer ran out: the phone plays their turns after a short pause
    // (Krida.AUTO_PLAY_MS) instead of a countdown, until that player rolls/picks or taps autopilot.
    val autoSeats = remember { mutableStateListOf<Int>() }
    var autoResume by remember { mutableIntStateOf(0) }

    fun resumeManual() {
        val local = autoSeats.filter { table.isLocal(it) }
        if (local.isEmpty()) return
        autoSeats.removeAll(local.toSet())
        autoResume += 1
    }

    fun roll(toss: LudoRenderer.Toss, byPlayer: Boolean = true) {
        if (!canRoll) return
        if (byPlayer) autoSeats.remove(state.turn)
        holdingDie = false
        rollSeconds = 0
        pendingToss[0] = toss
        // Keep pressed (and spinning for a long toss) until the throw animates, same as ular tangga.
        renderer?.holdDie(true, shake = toss == LudoRenderer.Toss.Long)
        if (!table.roll(state.turn)) {
            pendingToss[0] = null
            renderer?.holdDie(false)
        } else scope.launch {
            delay(ROLL_WAIT_MS)
            if (pendingToss[0] != null) {
                pendingToss[0] = null
                renderer?.holdDie(false)
            }
        }
    }

    // Countdown to throw: pauses while the die is held, then rolls a short toss.
    LaunchedEffect(canRoll, state.turn, state.lastRoll, state.lastSeat, autoResume) {
        if (!canRoll) {
            rollSeconds = 0
            return@LaunchedEffect
        }
        if (state.turn in autoSeats) {
            rollSeconds = 0
            delay(Krida.AUTO_PLAY_MS)
            if (canRollNow && !holdingNow && state.turn in autoSeats) roll(LudoRenderer.Toss.Short, byPlayer = false)
            return@LaunchedEffect
        }
        rollSeconds = rollTotal
        while (rollSeconds > 0) {
            delay(1000L)
            if (!canRollNow) return@LaunchedEffect
            if (holdingNow) continue
            val next = rollSeconds - 1
            rollSeconds = next
            if (next == 0 && canRollNow && !holdingNow) {
                if (state.turn !in autoSeats) autoSeats.add(state.turn)
                roll(LudoRenderer.Toss.Short, byPlayer = false)
            }
        }
    }

    // Pick timer: while a player on this device picks a token after a roll, a countdown runs
    // in the same spot; at 0 the phone moves the easy computer's pick for them
    // (LudoGame.timeoutMove). Animating or waiting on the host stops it.
    var pickSeconds by remember { mutableIntStateOf(0) }
    val pickTotal = (Krida.PLAY_TURN_MS / 1000L).toInt()
    val picking = canPick && legal.isNotEmpty()
    val pickingNow by rememberUpdatedState(picking)
    LaunchedEffect(picking, state, autoResume) {
        if (!picking) {
            pickSeconds = 0
            return@LaunchedEffect
        }
        if (state.turn in autoSeats) {
            pickSeconds = 0
            delay(Krida.AUTO_PLAY_MS)
        } else {
            Sounds.say(Sounds.Line.Pick)
            pickSeconds = pickTotal
            while (pickSeconds > 0) {
                delay(1000L)
                if (!pickingNow || !lifetime.active) return@LaunchedEffect
                pickSeconds -= 1
            }
            if (state.turn !in autoSeats) autoSeats.add(state.turn)
        }
        if (!pickingNow || !lifetime.active || state.turn !in autoSeats) return@LaunchedEffect
        LudoGame.timeoutMove(state, state.turn, Random.Default)?.pawn?.let { table.movePawn(state.turn, it) }
    }

    fun exit() {
        lifetime.stop()
        onExit()
    }

    BackHandler(onBack = ::exit)
    val activity = LocalActivity.current
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
    SideEffect { renderer?.dieReady = canRoll }

    // Origin is the TextureView's top-left in the window (same space Filament's viewport uses).
    var viewOrigin by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    var dieSpot by remember { mutableStateOf<FloatArray?>(null) }

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
                        renderer = LudoRenderer(view).apply {
                            this.onFirstFrame = onFirstFrame
                            onDieBounce = { if (lifetime.active) Sounds.play(Sounds.Effect.Die) }
                            resume()
                        }
                        view.tag = renderer
                    }
                },
                onRelease = { view ->
                    val own = view.tag as? LudoRenderer
                    own?.destroy()
                    if (renderer === own) renderer = null
                },
            )
        }

        // Tap a legal token on the board (no side buttons). Under the HUD so chrome still works.
        if (canPick) {
            val reach = with(LocalDensity.current) { 72.dp.toPx() }
            val pickLegal = legal
            val pickSeat = state.turn
            Box(
                Modifier.fillMaxSize().pointerInput(pickLegal, pickSeat, state.roll, renderer) {
                    detectTapGestures { tap ->
                        val r = renderer ?: return@detectTapGestures
                        val hit = pickLegal.map { pawn -> pawn to r.tokenOnScreen(pickSeat, pawn) }
                            .minByOrNull { (_, xy) -> hypot(xy[0] - tap.x, xy[1] - tap.y) }
                            ?: return@detectTapGestures
                        if (hypot(hit.second[0] - tap.x, hit.second[1] - tap.y) <= reach) {
                            autoSeats.remove(pickSeat)
                            table.movePawn(pickSeat, hit.first)
                        }
                    }
                },
            )
        }

        // Tell the renderer where the HUD sits, so the board takes the largest free space around it.
        LaunchedEffect(renderer, dieSpot) { renderer?.dieSpot = dieSpot }
        val hud = remember { mutableStateMapOf<String, FloatArray>() }
        fun Modifier.hud(key: String) = onGloballyPositioned {
            // Window space, same as TextureView.getLocationInWindow, so Filament pixels match.
            val p = it.positionInWindow()
            hud[key] = floatArrayOf(
                p.x - viewOrigin[0], p.y - viewOrigin[1],
                p.x - viewOrigin[0] + it.size.width, p.y - viewOrigin[1] + it.size.height,
            )
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
            Modifier.fillMaxSize().windowInsetsPadding(gameInsets).padding(horizontal = 24.dp, vertical = 14.dp).hud(ROOM),
        ) {
            Column(Modifier.width(136.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back_to_menu), ::exit, Modifier.hud("back"))
                Spacer(Modifier.height(4.dp))
                // Two tiles a row; each local seat keeps its timer / autopilot under the tile
                // (same slot as congklak's pill), so the die column does not jump.
                Column(Modifier.hud("players"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tokens.indices.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { seat ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    PlayerTile(
                                        table, state, seat,
                                        on = seat == state.turn && !state.finished,
                                        modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                                            val p = it.positionInWindow()
                                            playerOrigins[seat] = floatArrayOf(
                                                p.x - viewOrigin[0] + it.size.width / 2f,
                                                p.y - viewOrigin[1] + it.size.height / 2f,
                                            )
                                        },
                                    )
                                    Box(
                                        Modifier.padding(top = 6.dp).size(36.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (table.isLocal(seat)) {
                                            val seatAuto = seat in autoSeats
                                            TurnTimer(
                                                when {
                                                    seatAuto -> 0
                                                    seat != state.turn -> 0
                                                    picking -> pickSeconds
                                                    canRoll -> rollSeconds
                                                    else -> 0
                                                },
                                                if (picking && seat == state.turn) pickTotal else rollTotal,
                                                auto = seatAuto,
                                                onResume = ::resumeManual,
                                                size = 36.dp,
                                            )
                                        }
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.width(148.dp).fillMaxHeight(), horizontalAlignment = Alignment.End) {
                Row(Modifier.hud("buttons"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoundToggles()
                    CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), { showRules = true })
                }
                // Reactions sit right under the rules button.
                if (table.canEmote) Spacer(Modifier.height(8.dp))
                val youLabel = stringResource(R.string.bot_you)
                EmoteHud(
                    canEmote = table.canEmote,
                    emotes = table.emotes,
                    playerName = { seat -> if (seat == table.mySeat) youLabel else table.name(seat) },
                    onSend = table::sendEmote,
                    modifier = Modifier.hud("emote"),
                )
                Spacer(Modifier.weight(1f))
                val dieLabel = stringResource(R.string.ular_roll)
                Box(
                    Modifier.size(104.dp).align(Alignment.CenterHorizontally).hud("die")
                        .onGloballyPositioned {
                            val p = it.positionInWindow()
                            dieSpot = floatArrayOf(
                                p.x - viewOrigin[0] + it.size.width / 2f,
                                p.y - viewOrigin[1] + it.size.height / 2f,
                                it.size.width * 0.45f,
                            )
                        }
                        .semantics {
                            contentDescription = dieLabel
                            onClick(dieLabel) { roll(LudoRenderer.Toss.Short); true }
                        }
                        .pointerInput(renderer, canRoll) {
                            if (!canRoll) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                holdingDie = true
                                autoSeats.remove(state.turn)
                                renderer?.holdDie(true)
                                var long = false
                                var released = false
                                val hold = scope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    long = true
                                    renderer?.holdDie(true, shake = true)
                                    Sounds.play(Sounds.Effect.Tap)
                                }
                                try {
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.isConsumed || !canRoll) break
                                        // Offset from the press, same as ular tangga.
                                        val offset = change.position - down.position
                                        renderer?.moveHeldDie(offset.x, offset.y)
                                        change.consume()
                                        if (!change.pressed) { released = true; break }
                                    }
                                } finally {
                                    hold.cancel()
                                    holdingDie = false
                                    if (!released) renderer?.holdDie(false)
                                }
                                if (released) roll(if (long) LudoRenderer.Toss.Long else LudoRenderer.Toss.Short)
                            }
                        },
                )
                Spacer(Modifier.height(8.dp))
                StatusLine(table, state, canPick, Modifier.hud("status"))
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

        if (state.finished) ResultCard(
            table, state,
            onExit = { activity?.let { Ads.afterGame(it, ::exit) } ?: exit() },
            onRestart = { activity?.let { Ads.afterGame(it, table::restart) } ?: table.restart() },
        )
        if (showRules) RulesSheet(state.options) { showRules = false }
    }
}

/** Voice for whose turn it is; pass-and-play gets the chime only. */
private fun announceTurn(table: LudoTable, seat: Int) {
    val me = table.mySeat
    if (me != null) Sounds.say(if (seat == me) Sounds.Line.YourTurn else Sounds.Line.TheirTurn) else Sounds.play(Sounds.Effect.Turn)
}

/** After a move: the result when the game ends, "roll again" for an extra roll, else the next turn. */
private fun announce(table: LudoTable, after: LudoState) {
    val me = table.mySeat
    when {
        after.finished -> {
            val won = me == null || after.winner == me
            Sounds.play(if (won) Sounds.Effect.Win else Sounds.Effect.Lose)
            if (me != null) Sounds.say(if (won) Sounds.Line.Win else Sounds.Line.Lose)
        }
        // A roll with a choice: the pick prompt says "tap a token".
        after.roll != null -> Unit
        // Same seat again (a 6, a capture or a token home): roll again.
        after.turn == after.lastSeat -> if (table.isLocal(after.turn)) Sounds.say(Sounds.Line.RollAgain)
        else -> announceTurn(table, after.turn)
    }
}

@Composable
private fun StatusLine(table: LudoTable, state: LudoState, canPick: Boolean, modifier: Modifier = Modifier) {
    val text = when {
        state.finished -> ""
        state.burnedSix && state.lastSeat == state.turn -> stringResource(R.string.ludo_burned_six)
        canPick -> stringResource(R.string.ludo_pick_token)
        table.isLocal(state.turn) && state.roll == null -> stringResource(R.string.ular_your_turn)
        state.lastRoll == 6 && state.lastSeat == state.turn && state.roll == null && !state.finished ->
            stringResource(R.string.ular_six_again)
        state.lastCaptures.isNotEmpty() && state.lastSeat == state.turn ->
            stringResource(R.string.ludo_capture_again)
        else -> stringResource(R.string.ular_turn_of, if (table.mySeat == state.turn) stringResource(R.string.bot_you) else table.name(state.turn))
    }
    if (text.isNotEmpty()) {
        Text(text, style = Theme.font(13, FontWeight.Bold, Theme.ink), textAlign = TextAlign.End, modifier = modifier.fillMaxWidth())
    }
}

@Composable
private fun PlayerTile(table: LudoTable, state: LudoState, seat: Int, on: Boolean, modifier: Modifier = Modifier) {
    val color = pawnColor(state.color(seat))
    val name = if (seat == table.mySeat) stringResource(R.string.bot_you) else table.name(seat)
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (on) Theme.chip else Theme.surface)
            .border(if (on) 2.dp else 1.5.dp, if (on) color else Theme.line, RoundedCornerShape(12.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SeatAvatar(table.players.avatarOf(seat), color, 22.dp)
        Text(name, style = Theme.font(11, FontWeight.Bold, Theme.ink), maxLines = 1)
    }
}

@Composable
private fun ResultCard(table: LudoTable, state: LudoState, onExit: () -> Unit, onRestart: () -> Unit) {
    val ranking = LudoGame.ranking(state)
    val you = table.mySeat
    val title = when {
        you != null && ranking.firstOrNull() == you -> stringResource(R.string.ludo_you_win)
        ranking.isNotEmpty() -> stringResource(R.string.ludo_wins, table.name(ranking[0]))
        else -> stringResource(R.string.ludo_title)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(20.dp)).background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = Theme.font(22, FontWeight.Black, Theme.ink))
            Text(stringResource(R.string.ludo_all_home), style = Theme.font(14, FontWeight.Bold, Theme.muted))
            ranking.forEachIndexed { i, seat ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SeatAvatar(table.players.avatarOf(seat), pawnColor(state.color(seat)), 18.dp)
                    Text("${i + 1}. ${if (seat == you) stringResource(R.string.bot_you) else table.name(seat)}", style = Theme.font(16, FontWeight.Bold, Theme.ink))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(stringResource(R.string.congklak_to_menu), onExit, Modifier.weight(1f), tint = Theme.muted)
                if (table.canRestart) {
                    PrimaryButton(stringResource(R.string.congklak_play_again), onRestart, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RulesSheet(options: LudoOptions, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth()
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
                listOf(
                    if (options.sixToEnter) R.string.ludo_rule_six_enter else R.string.ludo_rule_any_enter,
                    R.string.ludo_rule_path,
                    R.string.ludo_rule_exact,
                    R.string.ludo_rule_capture,
                    R.string.ludo_rule_block,
                    if (options.blocksStopPassing) R.string.ludo_rule_block_pass else null,
                    R.string.ludo_rule_three_sixes,
                    if (options.sixRollsAgain) R.string.ludo_rule_six_again else null,
                    if (options.captureRollsAgain) R.string.ludo_rule_capture_again else null,
                    if (options.firstFinishEnds) R.string.ludo_rule_first_ends else R.string.ludo_rule_last_one,
                ).filterNotNull().forEach {
                    Text(stringResource(it), style = Theme.font(16, FontWeight.SemiBold, Theme.ink))
                }
            }
        }
    }
}
