package com.ardeman.krida.perangkapal

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.ardeman.krida.core.games.PerangKapalBoard
import com.ardeman.krida.core.games.PerangKapalMove
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
    /** Latest snapshot from the table: this seat's view, or everything when passing the phone. */
    var state by remember { mutableStateOf(table.latest) }
    var renderer by remember { mutableStateOf<PerangKapalRenderer?>(null) }
    /** Two people share this phone. */
    val passing = table.mySeat == null
    /** The seat whose side of the game is on screen. */
    var viewer by remember { mutableIntStateOf(table.mySeat ?: 0) }
    /** Passing the phone: the seat to hand it to; the cover hides the board until they continue. */
    var cover by remember { mutableStateOf<Int?>(null) }
    /** Ship kind the next tap on an empty ocean cell places; null once all are placed. */
    var selectedKind by remember { mutableStateOf<Int?>(0) }
    /** A ship dragged from the tray that is not over the ocean yet, under the finger. */
    var ghost by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    /** Ocean cell the dragged ship was last put on. */
    val dragCell = remember { arrayOfNulls<Int>(1) }
    var showRules by remember { mutableStateOf(false) }
    val scopeActive = rememberUpdatedState(lifetime.active)

    DisposableEffect(table) {
        table.onUpdate = { update ->
            if (scopeActive.value) state = update.state
        }
        onDispose { table.onUpdate = null }
    }

    LaunchedEffect(renderer, state, viewer) {
        renderer?.applyState(PerangKapalGame.viewFor(state, viewer), viewer)
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

    // Passing the phone: when the other person has to act the cover goes up; after a shot
    // its result shows first.
    LaunchedEffect(state, viewer) {
        if (!passing || state.finished) {
            cover = null
            return@LaunchedEffect
        }
        val wanted = if (state.placing) state.ready.indexOfFirst { !it }.takeIf { it >= 0 } ?: viewer else state.turn
        if (wanted == viewer) return@LaunchedEffect
        if (cover == null && !state.placing && state.lastHit != null) delay(1500)
        if (lifetime.active) cover = wanted
    }

    fun fleet() = state.fleets.getOrElse(viewer) { emptyList() }
    fun canPlace() = lifetime.active && cover == null && state.placing && !state.finished &&
        table.isLocal(viewer) && !state.ready.getOrElse(viewer) { true }
    fun canFire() = lifetime.active && cover == null && !state.placing && !state.finished &&
        viewer == state.turn && table.isLocal(viewer)
    val canPlace = canPlace()
    val canFire = canFire()
    val canReady = canPlace && PerangKapalGame.fleetComplete(fleet())

    /** A placed ship keeps its direction when moved; a new one starts across. */
    fun horizontal(kind: Int) = fleet().firstOrNull { it.kind == kind }?.horizontal ?: true

    /** On to the first ship that is not on the ocean yet. */
    fun selectNext(extra: Int? = null) {
        val placed = fleet().map { it.kind } + listOfNotNull(extra)
        selectedKind = (0 until PerangKapalGame.KINDS).firstOrNull { it !in placed }
    }

    /**
     * Sends the placement if the rules allow it here (checked first, so a guest's drag over
     * another ship is not a refused move).
     */
    fun place(kind: Int, bow: Int, across: Boolean): Boolean {
        val move = PerangKapalMove(kind = kind, cell = bow, horizontal = across)
        if (PerangKapalGame.validate(table.latest, viewer, move) != null) return false
        return table.place(viewer, kind, bow, across)
    }

    /** The dragged ship follows the finger cell by cell, wherever it fits. */
    fun dragTo(kind: Int, at: Offset) {
        if (!canPlace()) return
        val cell = renderer?.pickCell(at.x, at.y, onRadar = false) ?: return
        if (cell == dragCell[0]) return
        dragCell[0] = cell
        val across = horizontal(kind)
        place(kind, PerangKapalGame.bowFor(cell, kind, across), across)
    }

    fun endDrag() {
        ghost = null
        if (dragCell[0] == null) return
        dragCell[0] = null
        Sounds.play(Sounds.Effect.Tap)
        selectNext()
    }

    fun tap(at: Offset) {
        val r = renderer ?: return
        if (canFire()) {
            val cell = r.pickCell(at.x, at.y, onRadar = true) ?: return
            if (table.fire(viewer, cell)) Sounds.play(Sounds.Effect.Tap)
        } else if (canPlace()) {
            val cell = r.pickCell(at.x, at.y, onRadar = false) ?: return
            val ship = fleet().firstOrNull { cell in it.cells }
            val kind = selectedKind
            if (ship != null) {
                // A tap on a placed ship turns it around its middle.
                if (place(ship.kind, PerangKapalGame.turnedBow(ship), !ship.horizontal)) Sounds.play(Sounds.Effect.Tap)
            } else if (kind != null) {
                val across = horizontal(kind)
                if (place(kind, PerangKapalGame.bowFor(cell, kind, across), across)) {
                    Sounds.play(Sounds.Effect.Tap)
                    selectNext(kind)
                }
            }
        }
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

    var viewOrigin by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    val slop = with(LocalDensity.current) { 10.dp.toPx() }

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

        // A tap fires or places; a drag that starts on a placed ship moves it.
        if (canPlace || canFire) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        val grabbed = if (canPlace()) {
                            renderer?.pickCell(start.x, start.y, onRadar = false)
                                ?.let { cell -> fleet().firstOrNull { cell in it.cells }?.kind }
                        } else null
                        var dragging = false
                        var lifted = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                lifted = true
                                break
                            }
                            if (grabbed == null) continue
                            if (!dragging && (change.position - start).getDistance() >= slop) dragging = true
                            if (dragging) {
                                change.consume()
                                dragTo(grabbed, change.position)
                            }
                        }
                        if (dragging) endDrag() else if (lifted) tap(start)
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
            Column(Modifier.width(136.dp).fillMaxHeight().hud("left"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back_to_menu), ::exit)
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (seat in 0..1) {
                        val on = if (state.placing) !state.ready[seat] && !state.finished
                        else seat == state.turn && !state.finished
                        PlayerTile(table, seat, on)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.width(168.dp).fillMaxHeight().hud("right"), horizontalAlignment = Alignment.End) {
                SoundToggles()
                Spacer(Modifier.height(8.dp))
                CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), {
                    showRules = true
                })
                Spacer(Modifier.height(10.dp))
                val youLabel = stringResource(R.string.bot_you)
                EmoteHud(
                    canEmote = table.canEmote,
                    emote = table.emote,
                    playerName = { seat -> if (seat == table.mySeat) youLabel else table.name(seat) },
                    onSend = table::sendEmote,
                )
                Spacer(Modifier.weight(1f))
                if (canPlace) {
                    ShipTray(
                        placed = fleet().map { it.kind }.toSet(),
                        selected = selectedKind,
                        // Finger that went down on a tray ship, in the board's coordinates.
                        onTouch = { kind, at, moved ->
                            if (canPlace() && (dragCell[0] != null || ghost != null || moved >= slop)) {
                                selectedKind = kind
                                val over = renderer?.pickCell(at.x, at.y, onRadar = false) != null
                                ghost = if (over) null else kind to at
                                dragTo(kind, at)
                            }
                        },
                        onRelease = { kind ->
                            if (dragCell[0] != null || ghost != null) endDrag()
                            else if (canPlace()) {
                                Sounds.play(Sounds.Effect.Tap)
                                selectedKind = kind
                            }
                        },
                        origin = { Offset(viewOrigin[0], viewOrigin[1]) },
                        canReady = canReady,
                        onReady = {
                            if (table.ready(viewer)) Sounds.play(Sounds.Effect.Tap)
                        },
                    )
                }
                if (!state.placing) {
                    FleetStatus(R.string.perang_kapal_enemy_fleet, state.lost(1 - viewer))
                    Spacer(Modifier.height(8.dp))
                    FleetStatus(R.string.perang_kapal_your_fleet, state.lost(viewer))
                }
                StatusLine(table, state, canPlace, Modifier.padding(top = 8.dp))
            }
        }

        ghost?.let { (kind, at) ->
            val w = with(LocalDensity.current) { (PerangKapalBoard.shipLength(kind) * TRAY_UNIT).dp.toPx() }
            val h = with(LocalDensity.current) { (PerangKapalBoard.CELL * TRAY_UNIT).dp.toPx() }
            ShipPicture(kind, Modifier.offset { IntOffset((at.x - w / 2).toInt(), (at.y - h / 2).toInt()) })
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

        cover?.let { next ->
            PassCover(table.name(next), if (next == 0) SEAT_0 else SEAT_1) {
                if (lifetime.active) {
                    Sounds.play(Sounds.Effect.Tap)
                    viewer = next
                    cover = null
                    val placed = state.fleets.getOrElse(next) { emptyList() }.map { it.kind }
                    selectedKind = (0 until PerangKapalGame.KINDS).firstOrNull { it !in placed }
                }
            }
        }
        if (state.finished) ResultCard(
            table, state,
            onExit = { activity?.let { Ads.afterGame(it, ::exit) } ?: exit() },
            onRestart = {
                selectedKind = 0
                activity?.let { Ads.afterGame(it, table::restart) } ?: table.restart()
            },
        )
        if (showRules) RulesSheet { showRules = false }
    }
}

private val SEAT_0 = Color(0xFFD6453D)
private val SEAT_1 = Color(0xFF2F6FD1)

/** Dp per board unit in the tray: the longest ship fills the column. */
private val TRAY_UNIT = 152f / PerangKapalBoard.shipLength(0)

/** True when a bot is among the seats that may act now. */
private fun PerangKapalTable.botToMove(): Boolean {
    val seats = PerangKapalGame.actingSeats(latest)
    return seats.any { it in botSeats }
}

/**
 * A ship seen from above, drawn from the boxes of its voxel model (`shipParts` in shared/),
 * bow to the left.
 */
@Composable
private fun ShipPicture(kind: Int, modifier: Modifier = Modifier, unitDp: Float = TRAY_UNIT) {
    val parts = remember(kind) { PerangKapalBoard.shipParts(kind) }
    Canvas(
        modifier.size((PerangKapalBoard.shipLength(kind) * unitDp).dp, (PerangKapalBoard.CELL * unitDp).dp),
    ) {
        val unit = unitDp.dp.toPx()
        for (i in parts.indices step 9) {
            drawRect(
                Color(parts[i + 6], parts[i + 7], parts[i + 8]),
                topLeft = Offset(parts[i] * unit, size.height / 2 + parts[i + 2] * unit),
                size = Size((parts[i + 1] - parts[i]) * unit, (parts[i + 3] - parts[i + 2]) * unit),
            )
        }
    }
}

/** The five ships themselves: tap one then a cell, or drag it onto the ocean. Then Ready. */
@Composable
private fun ShipTray(
    placed: Set<Int>,
    selected: Int?,
    onTouch: (kind: Int, at: Offset, moved: Float) -> Unit,
    onRelease: (kind: Int) -> Unit,
    origin: () -> Offset,
    canReady: Boolean,
    onReady: () -> Unit,
) {
    val touch = rememberUpdatedState(onTouch)
    val release = rememberUpdatedState(onRelease)
    val boardOrigin = rememberUpdatedState(origin)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
        Text(stringResource(R.string.perang_kapal_place_fleet), style = Theme.font(13, FontWeight.Bold, Theme.ink), textAlign = TextAlign.End)
        for (kind in 0 until PerangKapalGame.KINDS) {
            val on = kind == selected
            val name = stringResource(shipName(kind))
            var inWindow by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (on) Theme.chip else Theme.surface)
                    .border(1.5.dp, if (on) Theme.accent else Theme.line, RoundedCornerShape(10.dp))
                    .onGloballyPositioned { inWindow = it.positionInWindow() }
                    .semantics {
                        contentDescription = name
                        role = Role.Button
                        this.selected = on
                    }
                    .pointerInput(kind) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var lifted = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    lifted = true
                                    break
                                }
                                change.consume()
                                val at = inWindow + change.position - boardOrigin.value()
                                touch.value(kind, at, (change.position - down.position).getDistance())
                            }
                            release.value(kind)
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                ShipPicture(kind, Modifier.alpha(if (kind in placed) 0.35f else 1f))
            }
        }
        PrimaryButton(stringResource(R.string.perang_kapal_ready), onReady, Modifier.fillMaxWidth().padding(top = 2.dp), height = 38.dp, enabled = canReady)
    }
}

/** Ship kinds of [seat]'s fleet that the other side has sunk. */
private fun PerangKapalState.lost(seat: Int): Set<Int> =
    shots.getOrElse(1 - seat) { emptyList() }.mapNotNull { it.sunk }.toSet()

/** How the battle stands for one side: its five ships, the sunk ones faded and struck out. */
@Composable
private fun FleetStatus(title: Int, sunk: Set<Int>) {
    val left = "${PerangKapalGame.KINDS - sunk.size}/${PerangKapalGame.KINDS}"
    val label = stringResource(title)
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label $left" },
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = Theme.font(12, FontWeight.Bold, Theme.ink), maxLines = 1)
            Text(left, style = Theme.font(12, FontWeight.Bold, Theme.muted), maxLines = 1)
        }
        for (row in listOf(listOf(0, 1), listOf(2, 3, 4))) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (kind in row) Box(contentAlignment = Alignment.Center) {
                    ShipPicture(kind, Modifier.alpha(if (kind in sunk) 0.3f else 1f), unitDp = 13f)
                    if (kind in sunk) {
                        Box(Modifier.matchParentSize().padding(vertical = 7.dp).height(2.dp).background(Theme.error, CircleShape))
                    }
                }
            }
        }
    }
}

/** Two people on one phone: hides the board while it changes hands. */
@Composable
private fun PassCover(name: String, color: Color, onContinue: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Theme.backdropBrush).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(20.dp)).background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color))
            Text(
                stringResource(R.string.perang_kapal_pass, name),
                style = Theme.font(22, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center,
            )
            PrimaryButton(stringResource(R.string.perang_kapal_continue), onContinue, Modifier.fillMaxWidth(), height = 48.dp)
        }
    }
}

@Composable
private fun StatusLine(table: PerangKapalTable, state: PerangKapalState, canPlace: Boolean, modifier: Modifier = Modifier) {
    val you = stringResource(R.string.bot_you)
    val text = when {
        state.finished -> ""
        state.placing -> stringResource(if (canPlace) R.string.perang_kapal_place_hint else R.string.perang_kapal_waiting_ready)
        state.lastHit == true && state.lastSunk != null ->
            stringResource(R.string.perang_kapal_sunk, stringResource(shipName(state.lastSunk!!)))
        state.lastHit == true -> stringResource(R.string.perang_kapal_hit)
        state.lastHit == false -> stringResource(R.string.perang_kapal_miss)
        table.mySeat == state.turn -> stringResource(R.string.perang_kapal_your_turn)
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
    val color = if (seat == 0) SEAT_0 else SEAT_1
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
