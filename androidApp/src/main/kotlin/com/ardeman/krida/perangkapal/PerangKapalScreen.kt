package com.ardeman.krida.perangkapal

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.PerangKapalState
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.CountdownNotice
import com.ardeman.krida.ui.EmoteHud
import com.ardeman.krida.ui.LocalStageTurned
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.SoundToggles
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.gameInsets
import com.ardeman.krida.ui.rememberGameLifetime
import kotlinx.coroutines.delay

@Composable
fun PerangKapalScreen(table: PerangKapalTable, onExit: () -> Unit) {
    var ready by remember { mutableStateOf(false) }
    var minDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        minDone = true
    }
    val loading = !ready || !minDone
    Box(Modifier.fillMaxSize()) {
        Board(table, onExit, onFirstFrame = { ready = true }, loading = { loading })
        if (loading) {
            Box(Modifier.fillMaxSize().background(Theme.backdropBrush), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.perang_kapal_title), style = Theme.font(22, FontWeight.Black, Theme.ink))
            }
        }
    }
}

@Composable
fun PerangKapalPreview(modifier: Modifier = Modifier, onReady: () -> Unit = {}) =
    VoxelPreview("perang_kapal", null, modifier, onReady) { view ->
        PerangKapalRenderer(view).apply {
            applyState(
                PerangKapalState(
                    placing = true,
                    fleets = listOf(
                        PerangKapalGame.randomFleet(kotlin.random.Random(1)),
                        emptyList(),
                    ),
                    ready = listOf(false, false),
                ),
                mySeat = 0,
            )
            resume()
        }
    }

@Composable
private fun Board(table: PerangKapalTable, onExit: () -> Unit, onFirstFrame: () -> Unit, loading: () -> Boolean) {
    val lifetime = rememberGameLifetime(table) {
        table.leave()
        Sounds.stopGameAudio()
    }
    var state by remember { mutableStateOf(table.latest) }
    var renderer by remember { mutableStateOf<PerangKapalRenderer?>(null) }
    var selectedKind by remember { mutableIntStateOf(4) }
    var horizontal by remember { mutableStateOf(true) }
    var showRules by remember { mutableStateOf(false) }
    val mySeat = table.mySeat ?: 0
    val scopeActive = rememberUpdatedState(lifetime.active)

    DisposableEffect(table) {
        table.onUpdate = { update ->
            if (scopeActive.value) state = update.state
        }
        onDispose { table.onUpdate = null }
    }

    LaunchedEffect(renderer, state, mySeat) {
        renderer?.applyState(state, mySeat)
    }

    // Bot steps after each update while a bot may act.
    LaunchedEffect(state, table.players, lifetime.active) {
        if (state.finished || !lifetime.active) return@LaunchedEffect
        delay(320)
        var guard = 0
        while (lifetime.active && table.botToMove() && guard++ < 40) {
            table.stepBot()
            delay(260)
        }
    }

    val canPlace = state.placing && table.isLocal(mySeat) && !state.ready[mySeat]
    val canReady = canPlace && PerangKapalGame.fleetComplete(state.fleets[mySeat])
    val canFire = !state.placing && !state.finished && table.isLocal(state.turn) &&
        mySeat == state.turn

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

    var viewOrigin by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    val reach = with(LocalDensity.current) { 36.dp.toPx() }

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
                        renderer = PerangKapalRenderer(view).apply {
                            this.onFirstFrame = onFirstFrame
                            resume()
                        }
                        view.tag = renderer
                    }
                },
                onRelease = { view ->
                    val own = view.tag as? PerangKapalRenderer
                    own?.destroy()
                    if (renderer === own) renderer = null
                },
            )
        }

        if (canPlace || canFire) {
            Box(
                Modifier.fillMaxSize().pointerInput(canPlace, canFire, selectedKind, horizontal, state) {
                    detectTapGestures { tap ->
                        val r = renderer ?: return@detectTapGestures
                        if (canFire) {
                            val cell = r.pickCell(tap.x, tap.y, onRadar = true, reach = reach) ?: return@detectTapGestures
                            if (table.fire(mySeat, cell)) Sounds.play(Sounds.Effect.Tap)
                        } else if (canPlace) {
                            val cell = r.pickCell(tap.x, tap.y, onRadar = false, reach = reach) ?: return@detectTapGestures
                            if (table.place(mySeat, selectedKind, cell, horizontal)) Sounds.play(Sounds.Effect.Tap)
                        }
                    }
                },
            )
        }

        val hud = remember { mutableStateMapOf<String, FloatArray>() }
        fun Modifier.hud(key: String) = onGloballyPositioned {
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
                val room = boxes["room"] ?: return@collect
                val obstacles = boxes.filterKeys { it != "room" }.toSortedMap().values.map {
                    floatArrayOf(it[0] - margin, it[1] - margin, it[2] + margin, it[3] + margin)
                }
                r.fitBoard(room, obstacles)
            }
        }

        Row(
            Modifier.fillMaxSize().windowInsetsPadding(gameInsets).padding(horizontal = 24.dp, vertical = 14.dp).hud("room"),
        ) {
            Column(Modifier.width(136.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back_to_menu), ::exit, Modifier.hud("back"))
                Spacer(Modifier.height(4.dp))
                Column(Modifier.hud("players"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (seat in 0..1) {
                        val on = if (state.placing) !state.ready[seat] && !state.finished
                        else seat == state.turn && !state.finished
                        PlayerTile(table, seat, on)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.width(168.dp).fillMaxHeight(), horizontalAlignment = Alignment.End) {
                SoundToggles(Modifier.hud("sound"))
                Spacer(Modifier.height(8.dp))
                CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), {
                    showRules = true
                }, Modifier.hud("help"))
                Spacer(Modifier.height(10.dp))
                val youLabel = stringResource(R.string.bot_you)
                EmoteHud(
                    canEmote = table.canEmote,
                    emote = table.emote,
                    playerName = { seat -> if (seat == table.mySeat) youLabel else table.name(seat) },
                    onSend = table::sendEmote,
                )
                Spacer(Modifier.weight(1f))
                if (state.placing && table.isLocal(mySeat) && !state.ready.getOrElse(mySeat) { true }) {
                    ShipTray(
                        fleetKinds = state.fleets[mySeat].map { it.kind }.toSet(),
                        selected = selectedKind,
                        horizontal = horizontal,
                        onSelect = { selectedKind = it },
                        onRotate = { horizontal = !horizontal },
                        canReady = canReady,
                        onReady = {
                            if (table.ready(mySeat)) Sounds.play(Sounds.Effect.Tap)
                        },
                        modifier = Modifier.hud("tray"),
                    )
                }
                StatusLine(table, state, Modifier.hud("status").padding(top = 8.dp))
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
        if (showRules) RulesSheet { showRules = false }
    }
}

/** True when a bot is among the seats that may act now. */
private fun PerangKapalTable.botToMove(): Boolean {
    val seats = PerangKapalGame.actingSeats(latest)
    return seats.any { it in botSeats }
}

@Composable
private fun ShipTray(
    fleetKinds: Set<Int>,
    selected: Int,
    horizontal: Boolean,
    onSelect: (Int) -> Unit,
    onRotate: () -> Unit,
    canReady: Boolean,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = listOf(
        R.string.perang_kapal_ship_carrier,
        R.string.perang_kapal_ship_battleship,
        R.string.perang_kapal_ship_cruiser,
        R.string.perang_kapal_ship_sub,
        R.string.perang_kapal_ship_destroyer,
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        Text(stringResource(R.string.perang_kapal_place_fleet), style = Theme.font(13, FontWeight.Bold, Theme.ink), textAlign = TextAlign.End)
        names.forEachIndexed { kind, label ->
            val on = kind == selected
            val placed = kind in fleetKinds
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (on) Theme.chip else Theme.surface)
                    .border(1.5.dp, if (on) Theme.accent else Theme.line, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onSelect(kind) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(label), style = Theme.font(12, FontWeight.Bold, Theme.ink))
                Text(
                    if (placed) "✓" else "${PerangKapalGame.FLEET[kind]}",
                    style = Theme.font(12, FontWeight.Bold, Theme.muted),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                stringResource(if (horizontal) R.string.perang_kapal_rotate else R.string.perang_kapal_rotate),
                onRotate,
                Modifier.weight(1f),
                tint = Theme.muted,
            )
            PrimaryButton(stringResource(R.string.perang_kapal_ready), onReady, Modifier.weight(1f), enabled = canReady)
        }
    }
}

@Composable
private fun StatusLine(table: PerangKapalTable, state: PerangKapalState, modifier: Modifier = Modifier) {
    val you = stringResource(R.string.bot_you)
    val text = when {
        state.finished -> ""
        state.placing -> {
            val seat = table.mySeat ?: 0
            when {
                state.ready.getOrElse(seat) { false } -> stringResource(R.string.perang_kapal_waiting_ready)
                else -> stringResource(R.string.perang_kapal_place_hint)
            }
        }
        state.lastHit == true && state.lastSunk != null ->
            stringResource(R.string.perang_kapal_sunk, stringResource(shipName(state.lastSunk!!)))
        state.lastHit == true -> stringResource(R.string.perang_kapal_hit)
        state.lastHit == false -> stringResource(R.string.perang_kapal_miss)
        table.isLocal(state.turn) -> stringResource(R.string.perang_kapal_your_turn)
        else -> stringResource(R.string.ular_turn_of, if (table.mySeat == state.turn) you else table.name(state.turn))
    }
    if (text.isNotEmpty()) {
        Text(text, style = Theme.font(13, FontWeight.Bold, Theme.ink), textAlign = TextAlign.End, modifier = modifier.fillMaxWidth())
    }
}

private fun shipName(kind: Int) = when (kind) {
    0 -> R.string.perang_kapal_ship_carrier
    1 -> R.string.perang_kapal_ship_battleship
    2 -> R.string.perang_kapal_ship_cruiser
    3 -> R.string.perang_kapal_ship_sub
    else -> R.string.perang_kapal_ship_destroyer
}

@Composable
private fun PlayerTile(table: PerangKapalTable, seat: Int, on: Boolean) {
    val name = if (seat == table.mySeat) stringResource(R.string.bot_you) else table.name(seat)
    val color = if (seat == 0) Color(0xFFD6453D) else Color(0xFF2F6FD1)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (on) Theme.chip else Theme.surface)
            .border(if (on) 2.dp else 1.5.dp, if (on) color else Theme.line, RoundedCornerShape(12.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(color))
        Text(name, style = Theme.font(11, FontWeight.Bold, Theme.ink), maxLines = 1)
    }
}

@Composable
private fun ResultCard(table: PerangKapalTable, state: PerangKapalState, onExit: () -> Unit, onRestart: () -> Unit) {
    val you = table.mySeat
    val winner = state.winner
    val title = when {
        you != null && winner == you -> stringResource(R.string.perang_kapal_you_win)
        winner != null -> stringResource(R.string.perang_kapal_wins, table.name(winner))
        else -> stringResource(R.string.perang_kapal_title)
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
            Text(stringResource(R.string.perang_kapal_fleet_sunk), style = Theme.font(14, FontWeight.Bold, Theme.muted))
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
private fun RulesSheet(onDismiss: () -> Unit) {
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
                    R.string.perang_kapal_rule_fleet,
                    R.string.perang_kapal_rule_place,
                    R.string.perang_kapal_rule_fire,
                    R.string.perang_kapal_rule_win,
                ).forEach {
                    Text(stringResource(it), style = Theme.font(16, FontWeight.SemiBold, Theme.ink))
                }
            }
        }
    }
}
