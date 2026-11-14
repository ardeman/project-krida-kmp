package com.ardeman.krida.perangkapal

import com.ardeman.krida.account.AvatarBadge
import com.ardeman.krida.account.avatarOf
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ardeman.krida.R
import com.ardeman.krida.ads.Ads
import com.ardeman.krida.congklak.VoxelPreview
import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.PerangKapalShip
import com.ardeman.krida.core.games.PerangKapalState
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.CountdownNotice
import com.ardeman.krida.ui.CountdownRing
import com.ardeman.krida.ui.TurnTimer
import com.ardeman.krida.ui.EmoteHud
import com.ardeman.krida.ui.LocalStageTurned
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.SoundToggles
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.gameInsets
import com.ardeman.krida.ui.rememberGameLifetime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

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

/**
 * The menu preview's fleet (bow cell, across), one ship per kind, same as iOS: the carrier,
 * battleship and a cruiser sit near the centre, where the preview zooms in.
 */
private val PREVIEW_FLEET = listOf(33 to true, 54 to true, 42 to false, 7 to false, 90 to true)

@Composable
fun PerangKapalPreview(modifier: Modifier = Modifier, onReady: () -> Unit = {}) =
    VoxelPreview("perang_kapal", null, modifier, onReady) { view ->
        PerangKapalRenderer(view).apply {
            val fleet = PREVIEW_FLEET.mapIndexedNotNull { kind, (bow, across) ->
                PerangKapalGame.cellsFor(bow, PerangKapalGame.FLEET[kind], across)?.let { PerangKapalShip(kind, it) }
            }
            applyState(
                PerangKapalState(placing = true, fleets = listOf(fleet, emptyList()), ready = listOf(false, false)),
                mySeat = 0,
            )
            // Zoomed in on the middle of the grid: rows 3–6, columns 2–7.
            val x = 3 * PerangKapalBoard.CELL
            val z = 2 * PerangKapalBoard.CELL
            focus = listOf(PerangKapalBoard.TOP, PerangKapalBoard.TOP + PerangKapalBoard.SHIP_H).flatMap { y ->
                listOf(floatArrayOf(-x, y, -z), floatArrayOf(x, y, -z), floatArrayOf(-x, y, z), floatArrayOf(x, y, z))
            }
            resume()
        }
    }

@Composable
private fun Board(table: PerangKapalTable, onExit: () -> Unit, onFirstFrame: () -> Unit, loading: () -> Boolean) {
    val lifetime = rememberGameLifetime(table) {
        table.leave()
        Sounds.stopGameAudio()
    }
    val scope = rememberCoroutineScope()
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
    /** One announce at a time so a fast bot shot does not stack miss/turn lines. */
    val announceJob = remember { arrayOfNulls<Job>(1) }

    DisposableEffect(table) {
        table.onUpdate = { update ->
            if (scopeActive.value) {
                val before = state
                state = update.state
                if (update.fresh) {
                    announceJob[0]?.cancel()
                    announceJob[0] = scope.launch {
                        announce(before, update.state, table.mySeat, table.botSeats)
                    }
                }
            }
        }
        onDispose {
            table.onUpdate = null
            announceJob[0]?.cancel()
            announceJob[0] = null
        }
    }

    LaunchedEffect(renderer, state, viewer) {
        renderer?.applyState(PerangKapalGame.viewFor(state, viewer), viewer)
    }

    // Bot steps after each update while a bot may act. Battle waits for shot VO to finish;
    // placement stays snappy.
    LaunchedEffect(state, table.players, lifetime.active) {
        if (state.finished || !lifetime.active) return@LaunchedEffect
        val battle = !state.placing
        delay(if (battle) 1_600L else 320L)
        var guard = 0
        while (lifetime.active && table.botToMove() && guard++ < 40) {
            table.stepBot()
            delay(if (battle) 1_400L else 260L)
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
    // Placement: tell the local player to drag ships onto their ocean.
    LaunchedEffect(canPlace) {
        if (canPlace) Sounds.say(Sounds.Line.PlaceShips)
    }

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

    // Seats on this phone whose timer ran out: the phone fires for them after a short pause
    // (Krida.AUTO_PLAY_MS) instead of a countdown, until that player fires themselves or taps
    // the autopilot button in the timer slot.
    val autoSeats = remember { mutableStateListOf<Int>() }
    // Bumped when the player taps autopilot to leave it, so the fire timer restarts a
    // normal countdown without keying the effect on autoSeats (which would cancel the shot
    // that runs right after a timeout).
    var autoResume by remember { mutableIntStateOf(0) }

    fun resumeManual() {
        if (viewer !in autoSeats) return
        autoSeats.remove(viewer)
        autoResume += 1
    }

    fun tap(at: Offset) {
        val r = renderer ?: return
        if (canFire()) {
            val cell = r.pickCell(at.x, at.y, onRadar = true) ?: return
            if (table.fire(viewer, cell)) {
                Sounds.play(Sounds.Effect.Tap)
                autoSeats.remove(viewer)
            }
        } else if (canPlace()) {
            val cell = r.pickCell(at.x, at.y, onRadar = false) ?: return
            val ship = fleet().firstOrNull { cell in it.cells }
            val kind = selectedKind
            if (ship != null) {
                // A tap on a placed ship turns it: around its middle, or on another of its
                // cells when the middle is crowded.
                val bow = PerangKapalGame.turnedBow(fleet(), ship, cell)
                if (bow != null && place(ship.kind, bow, !ship.horizontal)) Sounds.play(Sounds.Effect.Tap)
            } else if (kind != null) {
                val across = horizontal(kind)
                if (place(kind, PerangKapalGame.bowFor(cell, kind, across), across)) {
                    Sounds.play(Sounds.Effect.Tap)
                    selectNext(kind)
                }
            }
        }
    }

    // Placement timer: while a player on this phone places their fleet, a countdown runs; at 0
    // the phone places the ships still missing (PerangKapalGame.timeoutFleet) and taps Ready.
    // The pass cover and waiting on the other side stop it; each placing turn starts afresh.
    val placeTotal = (Krida.PLACE_FLEET_MS / 1000L).toInt()
    var placeSeconds by remember { mutableIntStateOf(0) }
    val canPlaceNow by rememberUpdatedState(canPlace)
    LaunchedEffect(canPlace, viewer) {
        if (!canPlace) {
            placeSeconds = 0
            return@LaunchedEffect
        }
        placeSeconds = placeTotal
        while (placeSeconds > 0) {
            delay(1000L)
            if (!canPlaceNow || !lifetime.active) return@LaunchedEffect
            placeSeconds -= 1
        }
        val seat = viewer
        val fleet = PerangKapalGame.timeoutFleet(table.latest, seat, Random.Default) ?: return@LaunchedEffect
        ghost = null
        dragCell[0] = null
        if (!PerangKapalGame.fleetComplete(table.latest.fleets[seat])) table.setFleet(seat, fleet)
        if (table.ready(seat)) Sounds.play(Sounds.Effect.Tap)
        if (seat !in autoSeats) autoSeats.add(seat)
    }

    // Firing timer: on a local player's shot a countdown runs; at 0 the phone fires the easy
    // computer's shot (PerangKapalGame.timeoutMove), and keeps firing for that player after a
    // short pause until they tap the radar or the autopilot button.
    val fireTotal = (Krida.PLAY_TURN_MS / 1000L).toInt()
    var fireSeconds by remember { mutableIntStateOf(0) }
    val canFireNow by rememberUpdatedState(canFire)
    LaunchedEffect(canFire, state, viewer, autoResume) {
        if (!canFire) {
            fireSeconds = 0
            return@LaunchedEffect
        }
        val seat = viewer
        if (seat in autoSeats) {
            fireSeconds = 0
            delay(Krida.AUTO_PLAY_MS)
        } else {
            fireSeconds = fireTotal
            while (fireSeconds > 0) {
                delay(1000L)
                if (!canFireNow || !lifetime.active) return@LaunchedEffect
                fireSeconds -= 1
            }
            if (seat !in autoSeats) autoSeats.add(seat)
        }
        if (!canFireNow || !lifetime.active || seat !in autoSeats) return@LaunchedEffect
        val move = PerangKapalGame.timeoutMove(table.latest, seat, Random.Default) ?: return@LaunchedEffect
        if (table.fire(seat, move.fire ?: return@LaunchedEffect)) Sounds.play(Sounds.Effect.Tap)
    }

    val activity = LocalActivity.current

    fun leave() {
        lifetime.stop()
        onExit()
    }

    /** Back / Menu: stop the board (and its audio) first, then interstitial, then navigate. */
    fun exit() {
        lifetime.stop()
        activity?.let { Ads.afterGame(it, onExit) } ?: onExit()
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
        // While placing, the gold ring follows the cell under the finger so a mistap is clear.
        if (canPlace || canFire) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        fun hoverAt(at: Offset) {
                            if (!canPlace()) return
                            renderer?.setHover(renderer?.pickCell(at.x, at.y, onRadar = false))
                        }
                        hoverAt(start)
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
                            hoverAt(change.position)
                            if (grabbed == null) continue
                            if (!dragging && (change.position - start).getDistance() >= slop) dragging = true
                            if (dragging) {
                                change.consume()
                                dragTo(grabbed, change.position)
                            }
                        }
                        renderer?.setHover(null)
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
        LaunchedEffect(renderer, state.placing) {
            val r = renderer ?: return@LaunchedEffect
            snapshotFlow { hud.toMap() }.collect { boxes ->
                // Ludo-style: board fills the room and dodges discrete HUD boxes. In battle the
                // left tiles are not obstacles so the mini ocean may sit under them.
                val room = boxes["room"] ?: return@collect
                val keys = if (state.placing) {
                    listOf("back", "players", "buttons", "emote", "tray", "status")
                } else {
                    listOf("buttons", "emote", "fleet", "status")
                }
                val obstacles = keys.mapNotNull { key ->
                    boxes[key]?.let {
                        floatArrayOf(it[0] - margin, it[1] - margin, it[2] + margin, it[3] + margin)
                    }
                }
                r.fitBoard(room, obstacles)
            }
        }

        val you = stringResource(R.string.bot_you)
        fun seatName(seat: Int) = if (seat == table.mySeat) you else table.name(seat)
        fun on(seat: Int) = !state.finished &&
            if (state.placing) !state.ready.getOrElse(seat) { true } else seat == state.turn
        val statusWaiting = stringResource(R.string.congklak_status_waiting)
        val statusThinking = stringResource(R.string.congklak_status_thinking)
        val statusTheirs = stringResource(R.string.congklak_status_their_turn)
        val statusYours = stringResource(R.string.gaple_your_turn)
        val statusPlacing = stringResource(R.string.perang_kapal_status_placing)
        val statusReady = stringResource(R.string.perang_kapal_ready)
        fun status(seat: Int) = when {
            state.placing && !state.finished -> if (!on(seat)) statusReady else if (seat in table.botSeats) statusThinking else statusPlacing
            !on(seat) -> statusWaiting
            seat in table.botSeats -> statusThinking
            table.isLocal(seat) -> statusYours
            else -> statusTheirs
        }
        val near = viewer
        val far = 1 - viewer

        // HUD like ludo: left tiles, right controls + tray/fleet; board fills the room around them.
        Row(
            Modifier.fillMaxSize().windowInsetsPadding(gameInsets).padding(horizontal = 24.dp, vertical = 14.dp).hud("room"),
        ) {
            Column(
                Modifier.width(136.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircleIconButton(
                    R.drawable.ic_chevron_left,
                    stringResource(R.string.congklak_back_to_menu),
                    ::exit,
                    Modifier.hud("back"),
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.hud("players"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1).forEach { seat ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            PlayerTile(
                                name = seatName(seat),
                                color = if (seat == 0) SEAT_0 else SEAT_1,
                                ships = if (state.placing) {
                                    state.fleets.getOrElse(seat) { emptyList() }.size
                                } else {
                                    PerangKapalGame.KINDS - state.lost(seat).size
                                },
                                detail = status(seat),
                                bot = seat in table.botSeats,
                                avatar = table.players.avatarOf(seat),
                                on = on(seat),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Box(
                                Modifier.padding(top = 6.dp).size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!state.placing && !state.finished && table.isLocal(seat)) {
                                    TurnTimer(
                                        if (seat in autoSeats) 0 else if (seat == near && on(seat)) fireSeconds else 0,
                                        fireTotal,
                                        auto = seat in autoSeats,
                                        onResume = ::resumeManual,
                                        size = 36.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.width(148.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.End,
            ) {
                Row(Modifier.hud("buttons"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoundToggles()
                    CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), {
                        showRules = true
                    })
                }
                if (table.canEmote) Spacer(Modifier.height(8.dp))
                EmoteHud(
                    canEmote = table.canEmote,
                    emotes = table.emotes,
                    playerName = { seat -> if (seat == table.mySeat) you else table.name(seat) },
                    onSend = table::sendEmote,
                    modifier = Modifier.hud("emote"),
                )
                Spacer(Modifier.weight(1f))
                if (canPlace) {
                    ShipTray(
                        placed = fleet().map { it.kind }.toSet(),
                        selected = selectedKind,
                        onTouch = { kind, at, moved ->
                            if (canPlace() && (dragCell[0] != null || ghost != null || moved >= slop)) {
                                selectedKind = kind
                                val cell = renderer?.pickCell(at.x, at.y, onRadar = false)
                                renderer?.setHover(cell)
                                ghost = if (cell != null) null else kind to at
                                dragTo(kind, at)
                            }
                        },
                        onRelease = { kind ->
                            renderer?.setHover(null)
                            if (dragCell[0] != null || ghost != null) endDrag()
                            else if (canPlace()) {
                                Sounds.play(Sounds.Effect.Tap)
                                selectedKind = kind
                            }
                        },
                        origin = { Offset(viewOrigin[0], viewOrigin[1]) },
                        canReady = canReady,
                        seconds = placeSeconds,
                        total = placeTotal,
                        onReady = {
                            if (table.ready(viewer)) Sounds.play(Sounds.Effect.Tap)
                        },
                        modifier = Modifier.hud("tray"),
                    )
                }
                if (!state.placing) {
                    Column(Modifier.hud("fleet"), horizontalAlignment = Alignment.End) {
                        FleetStatus(R.string.perang_kapal_enemy_fleet, state.lost(far))
                        Spacer(Modifier.height(8.dp))
                        FleetStatus(R.string.perang_kapal_your_fleet, state.lost(near))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth().hud("status"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val away = table.waiting
                    val notice = table.notice?.let { stringResource(it) }
                    if (away != null) {
                        CountdownNotice(
                            stringResource(R.string.online_waiting_for, away.name),
                            away.seconds,
                            (Krida.HANDOFF_WAIT_MS / 1000).toInt(),
                        )
                    } else if (notice != null) {
                        Text(notice, style = Theme.font(13, FontWeight.Bold, Theme.error), textAlign = TextAlign.Center)
                    } else {
                        ShotLine(state, canPlace, canFire)
                    }
                }
            }
        }

        ghost?.let { (kind, at) ->
            val w = with(LocalDensity.current) { (PerangKapalBoard.shipLength(kind) * TRAY_UNIT).dp.toPx() }
            val h = with(LocalDensity.current) { (PerangKapalBoard.CELL * TRAY_UNIT).dp.toPx() }
            ShipPicture(kind, Modifier.offset { IntOffset((at.x - w / 2).toInt(), (at.y - h / 2).toInt()) })
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
            onExit = ::exit,
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

/** A seat as a square tile like ular tangga: avatar large, ships left in the corner. */
@Composable
private fun PlayerTile(
    name: String,
    color: Color,
    ships: Int,
    detail: String,
    bot: Boolean,
    avatar: String?,
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val face = avatar.takeUnless { bot }
    Column(
        modifier.aspectRatio(1f)
            .then(if (on) Modifier.shadow(8.dp, shape, ambientColor = Theme.gold, spotColor = Theme.gold) else Modifier)
            .background(if (on) Theme.accent else Theme.surface, shape)
            .then(if (on) Modifier else Modifier.border(1.5.dp, Theme.line, shape))
            .padding(4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            if (face != null || bot) {
                if (face != null) {
                    AvatarBadge(face, size = 34.dp, modifier = Modifier.border(1.5.dp, Color.White, CircleShape))
                } else {
                    Box(
                        Modifier.size(34.dp).background(Color.White, CircleShape).padding(2.dp).background(Theme.track, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_computer),
                            contentDescription = null,
                            tint = Theme.ink,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(20.dp)
                        .background(Color.White, CircleShape).padding(1.5.dp).background(color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$ships", style = Theme.font(10, FontWeight.Black, Color.White), maxLines = 1)
                }
            } else {
                Box(
                    Modifier.size(34.dp).background(Color.White, CircleShape).padding(2.dp).background(color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$ships", style = Theme.font(14, FontWeight.Black, Color.White), maxLines = 1)
                }
            }
        }
        Text(
            name,
            style = Theme.font(11, FontWeight.Bold, if (on) Color.White else Theme.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            detail,
            style = Theme.font(10, FontWeight.SemiBold, if (on) Theme.onAccent else Theme.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Dp per board unit in the tray: the longest ship fills the 148 dp column. */
private val TRAY_UNIT = 132f / PerangKapalBoard.shipLength(0)

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
    seconds: Int,
    total: Int,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = rememberUpdatedState(onTouch)
    val release = rememberUpdatedState(onRelease)
    val boardOrigin = rememberUpdatedState(origin)
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (seconds > 0) CountdownRing(seconds, total, size = 30.dp)
            Text(stringResource(R.string.perang_kapal_place_fleet), style = Theme.font(13, FontWeight.Bold, Theme.ink), textAlign = TextAlign.End)
        }
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

/**
 * Voice for whose turn it is. Pass-and-play gets the chime only. A computer's turn stays
 * quiet — the next shot result (then "your turn") carries the beat without stacking
 * "opponent" on top of miss/hit.
 */
private fun announceTurn(seat: Int, me: Int?, botSeats: Set<Int>) {
    when {
        me == null -> Sounds.play(Sounds.Effect.Turn)
        seat == me -> Sounds.say(Sounds.Line.YourTurn)
        seat !in botSeats -> Sounds.say(Sounds.Line.TheirTurn)
    }
}

/**
 * After a snapshot: shot SFX, then result VO, then whose turn; or the result when the game
 * ends. Lines are spaced so miss/hit and turn don't talk over each other.
 */
private suspend fun announce(
    before: PerangKapalState,
    after: PerangKapalState,
    me: Int?,
    botSeats: Set<Int>,
) {
    val shot = !after.placing && after.lastHit != null && (
        after.lastCell != before.lastCell ||
            after.lastHit != before.lastHit ||
            after.lastSunk != before.lastSunk ||
            after.shots.sumOf { it.size } > before.shots.sumOf { it.size }
        )
    when {
        after.finished && !before.finished -> {
            val won = me == null || after.winner == me
            Sounds.play(if (won) Sounds.Effect.Win else Sounds.Effect.Lose)
            delay(400L)
            if (me != null) Sounds.say(if (won) Sounds.Line.Win else Sounds.Line.Lose)
        }
        shot -> {
            when {
                after.lastSunk != null -> {
                    Sounds.play(Sounds.Effect.Sunk)
                    delay(450L)
                    Sounds.say(Sounds.Line.Sunk)
                    delay(1_450L)
                }
                after.lastHit == true -> {
                    Sounds.play(Sounds.Effect.Hit)
                    delay(350L)
                    Sounds.say(Sounds.Line.Hit)
                    delay(900L)
                }
                else -> {
                    Sounds.play(Sounds.Effect.Miss)
                    delay(350L)
                    Sounds.say(Sounds.Line.Miss)
                    delay(1_200L)
                }
            }
            if (!after.finished) {
                delay(250L)
                announceTurn(after.turn, me, botSeats)
            }
        }
        before.placing && !after.placing && !after.finished ->
            announceTurn(after.turn, me, botSeats)
    }
}

/** Under the board: what to do while placing, how the last shot went, or whose turn it is. */
@Composable
private fun ShotLine(state: PerangKapalState, canPlace: Boolean, canFire: Boolean) {
    val text = when {
        state.finished -> null
        state.placing -> stringResource(if (canPlace) R.string.perang_kapal_place_hint else R.string.perang_kapal_waiting_ready)
        state.lastHit == true && state.lastSunk != null ->
            stringResource(R.string.perang_kapal_sunk, stringResource(shipName(state.lastSunk!!)))
        state.lastHit == true -> stringResource(R.string.perang_kapal_hit)
        state.lastHit == false -> stringResource(R.string.perang_kapal_miss)
        canFire -> stringResource(R.string.perang_kapal_your_turn)
        else -> null
    } ?: return
    Text(text, style = Theme.font(13, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center, maxLines = 1)
}

private fun shipName(kind: Int) = when (kind) {
    0 -> R.string.perang_kapal_ship_carrier
    1 -> R.string.perang_kapal_ship_battleship
    2 -> R.string.perang_kapal_ship_cruiser
    3 -> R.string.perang_kapal_ship_sub
    else -> R.string.perang_kapal_ship_destroyer
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
