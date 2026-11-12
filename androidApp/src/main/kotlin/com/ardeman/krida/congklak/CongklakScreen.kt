package com.ardeman.krida.congklak

import com.ardeman.krida.account.avatarOf
import com.ardeman.krida.account.AvatarBadge
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import com.ardeman.krida.ui.LocalStageTurned
import com.ardeman.krida.ui.gameInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ardeman.krida.R
import com.ardeman.krida.ads.Ads
import com.ardeman.krida.core.games.CongklakBoard
import com.ardeman.krida.core.games.CongklakBot
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.CongklakMove
import com.ardeman.krida.core.games.CongklakSeeds
import com.ardeman.krida.core.games.CongklakState
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.CountdownRing
import com.ardeman.krida.ui.CountdownNotice
import com.ardeman.krida.ui.EmoteHud
import com.ardeman.krida.ui.SoundToggles
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.LoadingScreen
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.OpponentPill
import com.ardeman.krida.ui.PillSeat
import com.ardeman.krida.ui.PillTopBar
import com.ardeman.krida.ui.PlayerPill
import kotlin.math.hypot
import kotlin.random.Random
import com.ardeman.krida.ui.rememberGameLifetime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PICKUP_MS = 260L
private const val SOW_MS = 170L
private const val SETTLE_MS = 320L
private const val BOT_PAUSE_MS = 600L

private val rimShadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 2f)

/**
 * Congklak on a voxel board, driven by a [CongklakTable]. The board is turned so this
 * device's seat sits on the near side (seat 0 for pass-and-play). Every snapshot from the
 * table is animated in order: the move it carries is replayed from the shown state, then
 * whatever still differs (captures, the end sweep, a restart) settles into place.
 */
@Composable
fun CongklakScreen(table: CongklakTable, onExit: () -> Unit) {
    // Building the Filament engine and meshes blocks the main thread for a moment: put the
    // loading screen up first, build the board under it, and fade it out once the board's
    // first frame is on screen (or after a few seconds, if that never comes).
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
        // The view shows its frame a little after Filament renders it.
        delay(if (rendered) 120L else 4000L)
        loading = false
    }
    Box(Modifier.fillMaxSize()) {
        if (mounted) Board(table, onExit, onFirstFrame = { rendered = true })
        AnimatedVisibility(loading, enter = EnterTransition.None, exit = fadeOut(tween(250))) { LoadingScreen() }
    }
}

@Composable
private fun Board(table: CongklakTable, onExit: () -> Unit, onFirstFrame: () -> Unit) {
    val viewSeat = table.mySeat ?: 0
    var state by remember { mutableStateOf(table.latest!!) }
    val seeds = remember { CongklakSeeds(state.pits) }
    val counts = remember { mutableStateListOf(*state.pits.toTypedArray()) }
    var inHand by remember { mutableIntStateOf(0) }
    var animating by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<Int?>(null) } // hole suggested for the current turn
    val activity = LocalActivity.current
    var pitsOnScreen by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    val turned = LocalStageTurned.current
    var renderer by remember { mutableStateOf<BoardRenderer?>(null) }
    val queue = remember { ArrayDeque<CongklakUpdate>() }
    val lifetime = rememberGameLifetime(table) {
        table.onUpdate = null
        queue.clear()
        table.leave()
        Sounds.stopGameAudio()
    }
    val scope = lifetime.scope

    fun push() {
        renderer?.setSeedTargets(seeds.targets())
        seeds.counts().forEachIndexed { i, n -> if (counts[i] != n) counts[i] = n }
        inHand = seeds.handCount()
    }

    // Once the board is still, give a computer player its turn after a short pause.
    fun idle() {
        scope.launch {
            delay(BOT_PAUSE_MS)
            if (!animating && queue.isEmpty()) table.stepBot()
        }
    }

    fun drain() {
        if (!lifetime.active || animating) return
        val update = queue.removeFirstOrNull() ?: return idle()
        animating = true
        scope.launch {
            try {
                val seat = update.seat
                val move = update.move
                var sowed = false
                var shot = false
                if (seat != null && move != null && CongklakGame.validate(state, seat, move) == null) {
                    val sowing = CongklakGame.play(state, seat, move)
                    val steps = CongklakSeeds.steps(state.pits, CongklakGame.pit(seat, move.hole), sowing.path)
                    // One soft frame for the whole sow — zooming every pit feels dizzy at low FPS.
                    renderer?.watch(steps.map { it.pit }.distinct().map { CongklakBoard.pitAnchor(it) })
                    for (step in steps) {
                        seeds.apply(step); push()
                        if (!step.pickUp) Sounds.play(if (CongklakGame.isStore(step.pit)) Sounds.Effect.Store else Sounds.Effect.Sow)
                        delay(if (step.pickUp) PICKUP_MS else SOW_MS)
                    }
                    sowed = true
                    shot = sowing.capturedFrom != null
                }
                if (seeds.counts() != update.state.pits) {
                    if (sowed) delay(SETTLE_MS)
                    if (shot) {
                        Sounds.play(Sounds.Effect.Capture)
                        Sounds.say(Sounds.Line.Shoot)
                    }
                    seeds.settle(update.state.pits); push()
                }
                if (sowed) {
                    announce(state, update.state, table.mySeat)
                    renderer?.watch(null)
                    delay(280L)
                }
                state = update.state
            } finally {
                // Always ease back out after a sow, even if the animation was cancelled.
                renderer?.watch(null)
                animating = false
                drain()
            }
        }
    }

    DisposableEffect(table) {
        table.onUpdate = { update -> waiting = false; queue.addLast(update); drain() }
        drain()
        onDispose { lifetime.stop() }
    }

    fun canPlay() = lifetime.active && !animating && !waiting && queue.isEmpty() && !state.finished &&
        (table.mySeat == null || table.mySeat == state.turn)

    // Seats on this phone whose timer ran out: the phone picks for them after a short pause
    // (Krida.AUTO_PLAY_MS) instead of a countdown, until that player picks a pit themselves.
    val autoSeats = remember { mutableStateListOf<Int>() }

    fun play(hole: Int, byPlayer: Boolean = true) {
        val seat = state.turn
        if (!canPlay() || CongklakGame.validate(state, seat, CongklakMove(hole)) != null) return
        if (byPlayer) autoSeats.remove(seat)
        // Pass-and-play answers synchronously; a guest waits for the host's snapshot.
        if (table.play(seat, hole) && table is GuestTable) waiting = true
    }

    fun exit() {
        lifetime.stop()
        onExit()
    }

    // Turn timer: while a player on this device picks a pit, a countdown runs; at 0 the
    // phone plays the easy computer's pick for them (CongklakGame.timeoutMove). Sowing and
    // waiting on the host stop it; the next still board starts a new one.
    val turnTotal = (Krida.PLAY_TURN_MS / 1000L).toInt()
    var turnSeconds by remember { mutableIntStateOf(0) }
    val localTurn = canPlay() && Seats(table, viewSeat).isLocal(state.turn)
    val localTurnNow by rememberUpdatedState(localTurn)
    LaunchedEffect(localTurn, state) {
        if (!localTurn) {
            turnSeconds = 0
            return@LaunchedEffect
        }
        if (state.turn in autoSeats) {
            turnSeconds = 0
            delay(Krida.AUTO_PLAY_MS)
        } else {
            turnSeconds = turnTotal
            while (turnSeconds > 0) {
                delay(1000L)
                if (!localTurnNow || !lifetime.active) return@LaunchedEffect
                turnSeconds -= 1
            }
            if (state.turn !in autoSeats) autoSeats.add(state.turn)
        }
        if (!localTurnNow || !lifetime.active || state.turn !in autoSeats) return@LaunchedEffect
        CongklakGame.timeoutMove(state, state.turn, Random.Default)?.let { play(it.hole, byPlayer = false) }
    }

    LaunchedEffect(table.notice) { if (table.notice != null) waiting = false }
    LaunchedEffect(state) { hint = null }

    // The hard bot's choice for the player to move, paid for with a rewarded ad.
    fun showHint() {
        val a = activity ?: return
        Ads.rewarded(a) {
            if (canPlay()) hint = CongklakBot(CongklakBot.Level.HARD).chooseMove(state, state.turn, Random.Default).hole
        }
    }

    // Gold rings on the holes the mover may start from, only while this device can play;
    // a hint narrows them to the suggested hole.
    val rings = if (!canPlay()) emptySet() else hint?.let { setOf(CongklakGame.pit(state.turn, it)) } ?: (0 until CongklakGame.HOLES)
        .filter { CongklakGame.validate(state, state.turn, CongklakMove(it)) == null }
        .map { CongklakGame.pit(state.turn, it) }
        .toSet()
    LaunchedEffect(rings, renderer) { renderer?.showRings(rings) }

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

    val seats = Seats(table, viewSeat)
    val finished = state.finished
    fun active(seat: Int) = !finished && state.turn == seat
    val statusWaiting = stringResource(R.string.congklak_status_waiting)
    val statusSowing = stringResource(R.string.congklak_status_sowing, inHand)
    val statusYours = stringResource(R.string.congklak_status_your_turn)
    val statusThinking = stringResource(R.string.congklak_status_thinking)
    val statusTheirs = stringResource(R.string.congklak_status_their_turn)
    fun status(seat: Int) = when {
        !active(seat) -> statusWaiting
        animating && inHand > 0 -> statusSowing
        seats.isLocal(seat) -> statusYours
        seat == seats.botSeat -> statusThinking
        else -> statusTheirs
    }

    Box(Modifier.fillMaxSize().background(Theme.backdropBrush)) {
        // Folding or unfolding can turn the stage: build the view again for the new kind.
        key(turned) { AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // A TextureView: it turns with the stage, and it is transparent, so the
                // backdrop gradient shows around the board.
                val view = TextureView(ctx)
                view.also {
                    renderer = BoardRenderer(view).apply {
                        this.viewSeat = viewSeat
                        onPitsProjected = { pitsOnScreen = it }
                        this.onFirstFrame = onFirstFrame
                        setSeedTargets(seeds.targets())
                        resume()
                    }
                    view.tag = renderer
                }
            },
            // The view for a newly turned stage is built before the old one is released:
            // destroy this view's own renderer, not whichever is current.
            onRelease = { view ->
                val own = view.tag as? BoardRenderer
                own?.destroy()
                if (renderer === own) renderer = null
            },
        ) }

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

        // Seed counts on the rim: below near holes, above far ones, under each store with its tag.
        pitsOnScreen.forEachIndexed { pit, xy ->
            val near = CongklakGame.ownerOf(pit) == viewSeat
            val store = CongklakGame.isStore(pit)
            val dy = if (store || near) 34.dp else (-34).dp
            Box(Modifier.centeredAt(xy[0].toInt(), xy[1].toInt(), dy)) {
                if (store) {
                    StoreLabel(counts[pit], seats.storeTag(if (near) seats.near else seats.far), near)
                } else {
                    Text(
                        counts[pit].toString(),
                        style = Theme.font(15, color = if (near) Color(0xFFFFF6E8) else Theme.tan).copy(shadow = rimShadow),
                    )
                }
            }
        }

        // HUD: back, opponent, help on top; direction hint and your pill at the bottom.
        Box(Modifier.fillMaxSize().windowInsetsPadding(gameInsets).padding(horizontal = 24.dp, vertical = 14.dp)) {
            Column(Modifier.fillMaxSize()) {
                PillTopBar(gap = 12.dp) {
                    CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back_to_menu), ::exit)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OpponentPill(
                            seat = seats.pill(seats.far),
                            on = active(seats.far),
                            detail = listOfNotNull(table.botLevel?.takeIf { seats.far == seats.botSeat }, status(seats.far)).joinToString(" · "),
                            modifier = Modifier,
                        )
                        // Pass-and-play: the far player's countdown sits by their pill.
                        if (turnSeconds > 0 && state.turn == seats.far) CountdownRing(turnSeconds, turnTotal, size = 40.dp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundToggles()
                        if (canPlay() && seats.isLocal(state.turn) && hint == null) {
                            CircleIconButton(R.drawable.ic_hint, stringResource(if (Ads.removed) R.string.hint_free else R.string.hint), ::showHint)
                        }
                        CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), { showRules = true })
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val away = table.waiting
                    val notice = table.notice?.let { stringResource(it) }
                    if (away != null) {
                        CountdownNotice(stringResource(R.string.online_waiting_for, away.name), away.seconds, (Krida.HANDOFF_WAIT_MS / 1000).toInt())
                    } else if (notice != null) {
                        Text(notice, style = Theme.font(14, FontWeight.Bold, Theme.error))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painterResource(R.drawable.ic_rotate_cw), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(15.dp))
                            Text(stringResource(R.string.congklak_direction), style = Theme.font(13, FontWeight.Bold, Theme.muted))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PlayerPill(seats.pill(seats.near), active(seats.near), status(seats.near))
                        if (turnSeconds > 0 && state.turn == seats.near) CountdownRing(turnSeconds, turnTotal, size = 40.dp)
                    }
                }
            }
            val youLabel = stringResource(R.string.bot_you)
            EmoteHud(
                canEmote = table.canEmote,
                emotes = table.emotes,
                playerName = { seat -> if (seat == table.mySeat) youLabel else seats.name(seat) },
                onSend = table::sendEmote,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 52.dp),
                opensUp = true,
            )
        }

        AnimatedVisibility(finished && !animating, enter = fadeIn(), exit = fadeOut()) {
            // Interstitials only where nobody waits on this device: local games, or leaving to the menu.
            val local = table.mySeat == null || table.botLevel != null
            GameOver(
                table, seats, state,
                onExit = { activity?.let { Ads.afterGame(it, ::exit) } ?: exit() },
                onRestart = { if (local && activity != null) Ads.afterGame(activity, table::restart) else table.restart() },
            )
        }
    }

    if (showRules) CongklakRulesDialog(state.tembakAfterStore) { showRules = false }
}

/** Who sits where, and how each seat is named on this device. */
private class Seats(private val table: CongklakTable, val near: Int) {
    val far = 1 - near
    val botSeat: Int? = if (table.botLevel == null) null else 1 - (table.mySeat ?: 0)

    fun name(seat: Int) = table.players.firstOrNull { it.seat == seat }?.name ?: "${seat + 1}"

    /** The seat's account avatar in multi-device games; null keeps the initial or bot icon. */
    fun avatar(seat: Int) = if (seat == botSeat) null else table.players.avatarOf(seat)

    /** "Kamu" for this device's own seat; the player's name otherwise. */
    @Composable
    fun displayName(seat: Int) = if (table.mySeat == seat) stringResource(R.string.bot_you) else name(seat)

    /** The seat can be played from this device (own seat, or anyone in pass-and-play). */
    fun isLocal(seat: Int) = seat != botSeat && (table.mySeat == null || table.mySeat == seat)

    /** How the seat shows in its pill. */
    @Composable
    fun pill(seat: Int) = PillSeat(displayName(seat), avatar(seat), seat == botSeat)

    /** Short label on a store: KAMU, KOM, or the player's name. */
    @Composable
    fun storeTag(seat: Int) =
        if (seat == botSeat) stringResource(R.string.congklak_store_bot) else displayName(seat).uppercase().take(8)
}

@Composable
private fun StoreLabel(count: Int, tag: String, near: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(count.toString(), style = Theme.font(22, FontWeight.Black, Color(0xFFFCEBD2)).copy(shadow = rimShadow))
        Text(
            tag,
            style = Theme.font(11, FontWeight.Black, if (near) Color.White else Theme.inkOnLight),
            modifier = Modifier.background(if (near) Theme.accent else Theme.tan, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** The result card: who won, both scores, and Ke menu / Main lagi. */
@Composable
private fun GameOver(table: CongklakTable, seats: Seats, state: CongklakState, onExit: () -> Unit, onRestart: () -> Unit) {
    val winner = CongklakGame.winner(state)
    Box(
        Modifier.fillMaxSize().background(Theme.ink.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(380.dp)
                .shadow(25.dp, RoundedCornerShape(28.dp), spotColor = Theme.ink)
                .background(Theme.surface, RoundedCornerShape(28.dp))
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 20.dp)
                .semantics { isTraversalGroup = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(if (winner == null || winner < 0) R.drawable.ic_equal else R.drawable.ic_trophy),
                contentDescription = null,
                tint = Color(0xFFC98A1B),
                modifier = Modifier.size(36.dp),
            )
            val title = when {
                winner == null -> ""
                winner < 0 -> stringResource(R.string.congklak_draw)
                winner == table.mySeat -> stringResource(R.string.congklak_you_win)
                else -> stringResource(R.string.congklak_wins, seats.name(winner))
            }
            Text(title, style = Theme.font(26, FontWeight.Black, Theme.ink))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScoreTile(seats, seats.near, CongklakGame.score(state, seats.near), winner == seats.near)
                ScoreTile(seats, seats.far, CongklakGame.score(state, seats.far), winner == seats.far)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun RowScope.ScoreTile(seats: Seats, seat: Int, score: Int, won: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.weight(1f)
            .background(if (won) Theme.highlight else Theme.surfaceSoft, shape)
            .then(if (won) Modifier.border(2.dp, Theme.accent, shape) else Modifier)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(seats.displayName(seat), style = Theme.font(14, color = Theme.muted), maxLines = 1)
        Text(score.toString(), style = Theme.font(30, FontWeight.Black, if (won) Theme.ink else Theme.muted))
    }
}

/**
 * After a move: the result when the game ends, otherwise whose turn it is when it changed.
 * Voice lines need a seat on this device; pass-and-play gets the chime and effects only.
 */
private fun announce(before: CongklakState, after: CongklakState, me: Int?) {
    if (after.finished) {
        val winner = CongklakGame.winner(after) ?: -1
        when {
            winner < 0 -> { Sounds.play(Sounds.Effect.Win); Sounds.say(Sounds.Line.Draw) }
            me == null || me == winner -> { Sounds.play(Sounds.Effect.Win); if (me != null) Sounds.say(Sounds.Line.Win) }
            else -> { Sounds.play(Sounds.Effect.Lose); Sounds.say(Sounds.Line.Lose) }
        }
    } else if (after.turn != before.turn) {
        if (me != null) Sounds.say(if (after.turn == me) Sounds.Line.YourTurn else Sounds.Line.TheirTurn) else Sounds.play(Sounds.Effect.Turn)
    }
}

/** "Cara main": the rule variant from shared/ CongklakGame, in short, with this game's tembak rule. */
@Composable
internal fun CongklakRulesDialog(tembakAfterStore: Boolean, onDismiss: () -> Unit) {
    val all = stringArrayResource(R.array.congklak_rules)
    val anytime = stringResource(R.string.congklak_rule_capture_anytime)
    // Items 4 and 5 are the "Lewat lumbung" tembak; "Bebas" has one simpler line instead.
    val rules = if (tembakAfterStore) all.toList() else all.take(4) + anytime + all.drop(6)
    // An overlay inside the stage, not a Dialog: a Dialog is its own window and would stay
    // upright when the stage is turned (see LandscapeStage).
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

/** Places the element so its center sits at pixel ([x], [y]) of the parent, moved down by [dy]. */
private fun Modifier.centeredAt(x: Int, y: Int, dy: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { p.place(IntOffset(x - p.width / 2, y + dy.roundToPx() - p.height / 2)) }
}
