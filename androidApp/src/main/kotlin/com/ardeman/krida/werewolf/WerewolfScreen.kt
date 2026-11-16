package com.ardeman.krida.werewolf

import com.ardeman.krida.account.AvatarBadge
import com.ardeman.krida.account.BotFace
import com.ardeman.krida.account.SeatAvatar
import com.ardeman.krida.account.avatarOf
import com.ardeman.krida.account.isBot
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.ads.Ads
import com.ardeman.krida.core.games.WerewolfGame
import com.ardeman.krida.core.games.WerewolfMove
import com.ardeman.krida.core.games.WerewolfPhase
import com.ardeman.krida.core.games.WerewolfRole
import com.ardeman.krida.core.games.WerewolfState
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.WerewolfClaims
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.CountdownNotice
import com.ardeman.krida.ui.EmoteHud
import com.ardeman.krida.ui.HostLeaveConfirm
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.SoundToggles
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.TurnTimer
import com.ardeman.krida.ui.gameInsets
import com.ardeman.krida.ui.rememberGameLifetime
import kotlin.random.Random
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

@Composable
fun WerewolfScreen(table: WerewolfTable, onExit: () -> Unit) {
    Board(table, onExit)
}

/** Home card preview: baked night / moon / howling-wolf shadow (mockup `#1A2332`). */
@Composable
fun WerewolfPreview(modifier: Modifier = Modifier, onReady: () -> Unit = {}) {
    LaunchedEffect(Unit) { onReady() }
    Image(
        painter = painterResource(R.drawable.werewolf_preview),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).fillMaxSize(),
    )
}

@Composable
private fun Board(table: WerewolfTable, onExit: () -> Unit) {
    val lifetime = rememberGameLifetime(table) {
        table.onUpdate = null
        table.leave()
        Sounds.stopGameAudio()
    }
    var state by remember { mutableStateOf(table.latest) }
    var showRules by remember { mutableStateOf(false) }
    var showRole by remember { mutableStateOf(false) }
    var roleShown by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val me = table.mySeat
    val autoSeats = remember { mutableStateListOf<Int>() }
    var autoResume by remember { mutableIntStateOf(0) }
    val myRole = state.roles.getOrNull(me).orEmpty()
    LaunchedEffect(myRole, state.day) {
        if (!roleShown && myRole.isNotEmpty() && state.day <= 1) {
            roleShown = true
            showRole = true
        }
    }

    // Phase calls once per phase/day (not on every bot nightDone update).
    var announcedKey by remember { mutableStateOf<String?>(null) }
    val audioKey = werewolfAudioKey(state)
    LaunchedEffect(audioKey, lifetime.active) {
        if (!lifetime.active) return@LaunchedEffect
        if (audioKey == announcedKey) return@LaunchedEffect
        announcedKey = audioKey
        announceWerewolfPhase(state, me)
    }

    DisposableEffect(table) {
        table.onUpdate = { update ->
            if (lifetime.active) {
                state = update.state
                picking = null
            }
        }
        onDispose { table.onUpdate = null }
    }

    LaunchedEffect(state, table.players, lifetime.active) {
        if (WerewolfGame.isFinished(state) || !lifetime.active) return@LaunchedEffect
        delay(400L)
        var guard = 0
        while (lifetime.active && guard++ < 48) {
            if (!table.stepBot()) break
            delay(350L)
        }
    }

    // Bots are not in actingSeats during discuss — pulse claim chips on a timer.
    LaunchedEffect(state.phase, lifetime.active, table.isHost) {
        if (state.phase != WerewolfPhase.DISCUSS || !table.isHost || !lifetime.active) return@LaunchedEffect
        delay(2_000L)
        while (lifetime.active) {
            table.stepDiscussClaim()
            delay(3_500L)
        }
    }

    val activity = LocalActivity.current
    var confirmingLeave by remember { mutableStateOf(false) }
    fun exit() {
        lifetime.stop()
        activity?.let { Ads.afterGame(it, onExit) } ?: onExit()
    }
    fun requestExit() {
        if (table.isHost && table.canEmote) confirmingLeave = true else exit()
    }
    BackHandler(onBack = {
        if (confirmingLeave) confirmingLeave = false else requestExit()
    })

    val phaseTotal = (phaseMs(state.phase) / 1000L).toInt()
    var phaseSeconds by remember { mutableIntStateOf(0) }
    val hostDiscuss = state.phase == WerewolfPhase.DISCUSS && table.isHost
    val myTurn = me in WerewolfGame.actingSeats(state)
    val timerOn = !WerewolfGame.isFinished(state) && lifetime.active && (myTurn || hostDiscuss)
    val timerOnNow by rememberUpdatedState(timerOn)

    LaunchedEffect(timerOn, state.phase, state.day, autoResume) {
        if (!timerOn) {
            phaseSeconds = 0
            return@LaunchedEffect
        }
        val seat = me
        if (hostDiscuss) {
            if (seat !in autoSeats || autoResume > 0) {
                phaseSeconds = phaseTotal
                while (phaseSeconds > 0) {
                    delay(1000L)
                    if (!timerOnNow || !lifetime.active) return@LaunchedEffect
                    phaseSeconds -= 1
                }
                if (table.advanceDiscuss()) Sounds.play(Sounds.Effect.Tap)
            } else {
                phaseSeconds = 0
                delay(Krida.AUTO_PLAY_MS)
                if (timerOnNow && lifetime.active) table.advanceDiscuss()
            }
        } else if (myTurn) {
            if (seat in autoSeats) {
                phaseSeconds = 0
                delay(Krida.AUTO_PLAY_MS)
            } else {
                phaseSeconds = phaseTotal
                while (phaseSeconds > 0) {
                    delay(1000L)
                    if (!timerOnNow || !lifetime.active) return@LaunchedEffect
                    phaseSeconds -= 1
                }
                if (seat !in autoSeats) autoSeats.add(seat)
            }
            if (!timerOnNow || !lifetime.active) return@LaunchedEffect
            val move = WerewolfGame.timeoutMove(state, seat, Random.Default) ?: return@LaunchedEffect
            if (table.play(move)) Sounds.play(Sounds.Effect.Tap)
        }
    }

    fun resumeManual() {
        if (me !in autoSeats) return
        autoSeats.remove(me)
        autoResume += 1
    }

    fun applyTarget(target: Int) {
        autoSeats.remove(me)
        val move = WerewolfMove(target = target)
        if (table.play(move)) Sounds.play(Sounds.Effect.Tap)
        picking = null
    }

    Box(Modifier.fillMaxSize().background(Theme.backdropBrush)) {
        Row(
            Modifier.fillMaxSize().windowInsetsPadding(gameInsets).padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Column(Modifier.width(136.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back_to_menu), ::requestExit)
                Text(stringResource(R.string.werewolf_title), style = Theme.font(16, FontWeight.Black, Theme.ink), maxLines = 1)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.alive.indices.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { seat ->
                                SeatTile(
                                    table, state, seat,
                                    modifier = Modifier.weight(1f),
                                    onShowMyRole = { showRole = true },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.widthIn(min = 220.dp, max = 340.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PhasePanel(
                    table, state, me, picking,
                    onPick = { picking = it },
                    onAction = { table.play(it) },
                    onShowRole = { showRole = true },
                )
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.width(148.dp).fillMaxHeight(), horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoundToggles()
                    CircleIconButton(R.drawable.ic_help, stringResource(R.string.congklak_rules_title), { showRules = true })
                }
                if (table.canEmote) Spacer(Modifier.height(8.dp))
                val youLabel = stringResource(R.string.bot_you)
                EmoteHud(
                    canEmote = table.canEmote,
                    emotes = table.emotes,
                    playerName = { seat -> if (seat == me) youLabel else table.name(seat) },
                    onSend = table::sendEmote,
                )
                ClaimFeed(table, me, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
                if (timerOn && phaseTotal > 0) {
                    val night = state.phase == WerewolfPhase.NIGHT
                    val timerInk = if (night) Color(0xFFF6EADC) else Color(0xFF2B1D14)
                    TurnTimer(
                        if (me in autoSeats && !hostDiscuss) 0 else phaseSeconds,
                        phaseTotal,
                        auto = me in autoSeats && !hostDiscuss,
                        onResume = ::resumeManual,
                        size = 52.dp,
                        ink = timerInk,
                    )
                }
                Spacer(Modifier.height(8.dp))
                StatusLine(table, state, modifier = Modifier.fillMaxWidth())
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

        if (showRole && myRole.isNotEmpty() && !WerewolfGame.isFinished(state)) {
            RoleCard(myRole) { showRole = false }
        }
        if (WerewolfGame.isFinished(state)) {
            ResultOverlay(table, state, onExit = ::requestExit, onRestart = {
                roleShown = false
                showRole = false
                announcedKey = null
                activity?.let { Ads.afterGame(it, table::restart) } ?: table.restart()
            })
        }
        if (confirmingLeave) {
            HostLeaveConfirm(
                onCancel = { confirmingLeave = false },
                onConfirm = { confirmingLeave = false; exit() },
            )
        }
        if (showRules) RulesSheet { showRules = false }
    }
}

private fun phaseMs(phase: String) = when (phase) {
    WerewolfPhase.NIGHT -> Krida.WEREWOLF_NIGHT_MS
    WerewolfPhase.DISCUSS -> Krida.WEREWOLF_DISCUSS_MS
    WerewolfPhase.VOTE, WerewolfPhase.SHERIFF_ELECT, WerewolfPhase.HUNTER, WerewolfPhase.SUCCESSOR ->
        Krida.WEREWOLF_VOTE_MS
    else -> 0L
}

private fun seatTint(seat: Int): Color {
    val colors = longArrayOf(
        0xFF4A5D78, 0xFF6B4E3D, 0xFF3D5A45, 0xFF5A4568,
        0xFF6B5A3D, 0xFF3D5568, 0xFF684545, 0xFF456068,
    )
    return Color(colors[seat % colors.size])
}

@Composable
private fun SeatTile(
    table: WerewolfTable,
    state: WerewolfState,
    seat: Int,
    modifier: Modifier = Modifier,
    onShowMyRole: (() -> Unit)? = null,
) {
    val alive = state.alive.getOrElse(seat) { false }
    val me = seat == table.mySeat
    val sheriff = state.sheriff == seat
    val acting = seat in WerewolfGame.actingSeats(state)
    val voting = state.phase == WerewolfPhase.VOTE || state.phase == WerewolfPhase.SHERIFF_ELECT
    val voted = voting && alive && state.votes.getOrNull(seat) != null
    val myRole = if (me) state.roles.getOrNull(seat).orEmpty() else ""
    val ink = if (alive) Theme.ink else Theme.muted
    val face = when {
        !alive -> Color(0xFF2A3344)
        voted -> Theme.chip
        acting -> Theme.chip
        else -> Theme.surface
    }
    val number = (seat + 1).toString()
    val tint = seatTint(seat)
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(face)
            .border(
                if ((acting || voted) && alive) 2.dp else 1.5.dp,
                when {
                    voted -> Theme.accent
                    acting -> Theme.accent
                    else -> Theme.line
                },
                RoundedCornerShape(12.dp),
            )
            .then(
                if (me && myRole.isNotEmpty() && onShowMyRole != null) {
                    Modifier.clickable(role = Role.Button, onClick = onShowMyRole)
                } else Modifier,
            )
            .padding(6.dp)
            .then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bot = seat in table.botSeats
        val avatar = table.players.avatarOf(seat)
        Box(contentAlignment = Alignment.Center) {
            when {
                bot -> BotFace(avatar, 26.dp)
                avatar != null -> SeatAvatar(avatar, if (alive) tint else Theme.border, 26.dp)
                else -> Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (alive) tint else Theme.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(number, style = Theme.font(13, FontWeight.Black, Color(0xFFFFF6E8).copy(alpha = if (alive) 1f else 0.5f)))
                }
            }
            if (bot || avatar != null) {
                Text(
                    number,
                    style = Theme.font(9, FontWeight.Black, Color.White),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tint)
                        .padding(horizontal = 3.dp),
                )
            }
        }
        Text(
            if (me) stringResource(R.string.bot_you) else table.name(seat),
            style = Theme.font(11, FontWeight.Bold, ink),
            maxLines = 1,
        )
        if (myRole.isNotEmpty() && !WerewolfGame.isFinished(state)) {
            Text(roleLabel(myRole), style = Theme.font(9, FontWeight.Bold, Theme.accent), maxLines = 1)
        }
        if (sheriff && alive) {
            Text(stringResource(R.string.werewolf_sheriff), style = Theme.font(9, FontWeight.Bold, Theme.accent))
        }
        if (voted) {
            Text(stringResource(R.string.werewolf_voted), style = Theme.font(9, FontWeight.Bold, Theme.accent))
        }
        if (!alive) {
            Text(stringResource(R.string.werewolf_dead), style = Theme.font(9, FontWeight.Bold, Theme.muted))
        }
    }
}

@Composable
private fun PhasePanel(
    table: WerewolfTable,
    state: WerewolfState,
    me: Int,
    picking: Int?,
    onPick: (Int) -> Unit,
    onAction: (WerewolfMove) -> Unit,
    onShowRole: () -> Unit = {},
) {
    val night = state.phase == WerewolfPhase.NIGHT
    // Day stays a light panel with dark ink so it stays readable in dark appearance too.
    val skyTop = if (night) Color(0xFF1A2332) else Color(0xFFB9D4EF)
    val skyBottom = if (night) Color(0xFF0F1520) else Color(0xFFF3E6D2)
    val ink = if (night) Color(0xFFF6EADC) else Color(0xFF2B1D14)
    val muted = if (night) Color(0xFFB7A593) else Color(0xFF5A4537)
    // Day chips stay white with a stronger rim so they don’t melt into the cream panel.
    val chip = if (night) Color.White.copy(alpha = 0.22f) else Color.White
    val line = if (night) Color.White.copy(alpha = 0.4f) else Color(0xFF8B6F52)
    val roleTint = if (night) Color(0xFFFFF0D0) else Color(0xFF7A4A1E)
    val shape = RoundedCornerShape(20.dp)
    val youLabel = stringResource(R.string.bot_you)
    fun seatLabel(seat: Int) = if (seat == me) youLabel else table.name(seat)
    Column(
        Modifier.fillMaxWidth().fillMaxHeight().clip(shape)
            .background(Brush.verticalGradient(listOf(skyTop, skyBottom)))
            .border(1.5.dp, line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(phaseTitle(state), style = Theme.font(18, FontWeight.Black, ink), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.werewolf_day_n, state.day),
            style = Theme.font(13, FontWeight.Bold, muted),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        val role = state.roles.getOrNull(me).orEmpty()
        if (role.isNotEmpty() && !WerewolfGame.isFinished(state)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onShowRole)
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    stringResource(R.string.werewolf_your_role, roleLabel(role)),
                    style = Theme.font(14, FontWeight.Bold, roleTint),
                )
                Text(
                    roleBlurb(role),
                    style = Theme.font(12, FontWeight.Bold, muted),
                )
            }
        }
        state.seerPeekRole?.let { peek ->
            val target = state.seerPeekTarget
            if (target != null) {
                Text(
                    stringResource(R.string.werewolf_seer_result, seatLabel(target), roleLabel(peek)),
                    style = Theme.font(13, FontWeight.Bold, ink),
                )
            }
        }
        if (state.phase != WerewolfPhase.NIGHT) {
            if (state.lastDeaths.isNotEmpty()) {
                val names = state.lastDeaths.joinToString { seatLabel(it) }
                Text(
                    stringResource(R.string.werewolf_dawn_deaths, names),
                    style = Theme.font(13, FontWeight.Bold, Color.White),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFFC0392B))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else if (state.day > 0) {
                Text(
                    stringResource(R.string.werewolf_peaceful_night),
                    style = Theme.font(13, FontWeight.Bold, ink),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(chip)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ActionArea(table, state, me, picking, onPick, onAction, ink = ink, muted = muted, chip = chip, line = line)
    }
}

@Composable
private fun ActionArea(
    table: WerewolfTable,
    state: WerewolfState,
    me: Int,
    picking: Int?,
    onPick: (Int) -> Unit,
    onAction: (WerewolfMove) -> Unit,
    ink: Color,
    muted: Color,
    chip: Color,
    line: Color,
) {
    val myTurn = me in WerewolfGame.actingSeats(state)
    when (state.phase) {
        WerewolfPhase.FINISHED -> Unit
        WerewolfPhase.DISCUSS -> {
            Text(
                stringResource(R.string.werewolf_discuss_hint),
                style = Theme.font(13, FontWeight.Bold, muted),
            )
            if (table.canClaim && state.alive.getOrElse(me) { false }) {
                ClaimChips(table, me, ink = ink, muted = muted, chip = chip, line = line)
            }
        }
        WerewolfPhase.NIGHT -> nightActions(table, state, me, myTurn, picking, onPick, onAction, ink, muted, chip, line)
        WerewolfPhase.VOTE, WerewolfPhase.SHERIFF_ELECT, WerewolfPhase.HUNTER, WerewolfPhase.SUCCESSOR ->
            voteActions(
                table, state, me, myTurn, picking, onPick, onAction,
                abstain = state.phase == WerewolfPhase.VOTE, ink = ink, muted = muted, chip = chip, line = line,
            )
        else -> {
            if (!myTurn) Text(stringResource(R.string.werewolf_waiting_others), style = Theme.font(13, FontWeight.Bold, muted))
        }
    }
}

@Composable
private fun nightActions(
    table: WerewolfTable,
    state: WerewolfState,
    me: Int,
    myTurn: Boolean,
    picking: Int?,
    onPick: (Int) -> Unit,
    onAction: (WerewolfMove) -> Unit,
    ink: Color,
    muted: Color,
    chip: Color,
    line: Color,
) {
    val role = state.roles.getOrNull(me).orEmpty()
    if (!myTurn) {
        Text(stringResource(R.string.werewolf_waiting_others), style = Theme.font(13, FontWeight.Bold, muted))
        return
    }
    when (role) {
        WerewolfRole.WITCH -> WitchPanel(table, state, me, picking, onPick, onAction, ink, muted, chip, line)
        WerewolfRole.WEREWOLF, WerewolfRole.SEER, WerewolfRole.DOCTOR -> {
            Text(promptForRole(role), style = Theme.font(13, FontWeight.Bold, ink))
            Text(stringResource(R.string.werewolf_tap_hint), style = Theme.font(12, FontWeight.Bold, muted))
            TargetGrid(table, state, me, picking, onPick, allowSelf = role == WerewolfRole.DOCTOR, ink = ink, chip = chip, line = line) { target ->
                onAction(WerewolfMove(target = target))
            }
            SecondaryButton(stringResource(R.string.werewolf_skip), { onAction(WerewolfMove(target = -1, done = true)) }, tint = muted)
        }
        else -> Text(stringResource(R.string.werewolf_waiting_others), style = Theme.font(13, FontWeight.Bold, muted))
    }
}

@Composable
private fun WitchPanel(
    table: WerewolfTable,
    state: WerewolfState,
    me: Int,
    picking: Int?,
    onPick: (Int) -> Unit,
    onAction: (WerewolfMove) -> Unit,
    ink: Color,
    muted: Color,
    chip: Color,
    line: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.witchHealLeft) {
            SecondaryButton(stringResource(R.string.werewolf_heal), { onAction(WerewolfMove(witchHeal = true)) }, tint = muted)
            SecondaryButton(stringResource(R.string.werewolf_skip_heal), { onAction(WerewolfMove(witchHeal = false)) }, tint = muted)
        }
        if (state.witchPoisonLeft) {
            Text(stringResource(R.string.werewolf_poison_someone), style = Theme.font(12, FontWeight.Bold, ink))
            Text(stringResource(R.string.werewolf_tap_hint), style = Theme.font(12, FontWeight.Bold, muted))
            TargetGrid(table, state, me, picking, onPick, allowSelf = false, ink = ink, chip = chip, line = line) { target ->
                onAction(WerewolfMove(target = target, done = true))
            }
            SecondaryButton(stringResource(R.string.werewolf_skip_poison), { onAction(WerewolfMove(target = -1, done = true)) }, tint = muted)
        } else {
            SecondaryButton(stringResource(R.string.werewolf_skip), { onAction(WerewolfMove(done = true)) }, tint = muted)
        }
    }
}

@Composable
private fun voteActions(
    table: WerewolfTable,
    state: WerewolfState,
    me: Int,
    myTurn: Boolean,
    picking: Int?,
    onPick: (Int) -> Unit,
    onAction: (WerewolfMove) -> Unit,
    abstain: Boolean,
    ink: Color,
    muted: Color,
    chip: Color,
    line: Color,
) {
    val living = state.living()
    val cast = living.count { state.votes.getOrNull(it) != null }
    if (state.phase == WerewolfPhase.VOTE || state.phase == WerewolfPhase.SHERIFF_ELECT) {
        Text(
            stringResource(R.string.werewolf_vote_progress, cast, living.size),
            style = Theme.font(12, FontWeight.Bold, muted),
        )
    }
    if (!myTurn) {
        Text(stringResource(R.string.werewolf_waiting_others), style = Theme.font(13, FontWeight.Bold, muted))
        return
    }
    val prompt = when (state.phase) {
        WerewolfPhase.VOTE -> R.string.werewolf_vote_lynch
        WerewolfPhase.SHERIFF_ELECT -> R.string.werewolf_vote_sheriff
        WerewolfPhase.HUNTER -> R.string.werewolf_hunter_shoot
        WerewolfPhase.SUCCESSOR -> R.string.werewolf_name_successor
        else -> R.string.werewolf_pick_target
    }
    Text(stringResource(prompt), style = Theme.font(13, FontWeight.Bold, ink))
    Text(stringResource(R.string.werewolf_tap_hint), style = Theme.font(12, FontWeight.Bold, muted))
    TargetGrid(table, state, me, picking, onPick, allowSelf = false, ink = ink, chip = chip, line = line) { target ->
        onAction(WerewolfMove(target = target))
    }
    if (abstain) {
        SecondaryButton(stringResource(R.string.werewolf_abstain), { onAction(WerewolfMove(target = -1)) }, tint = muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetGrid(
    table: WerewolfTable,
    state: WerewolfState,
    me: Int,
    picking: Int?,
    onPick: (Int) -> Unit,
    allowSelf: Boolean = false,
    ink: Color,
    chip: Color,
    line: Color,
    onChoose: (Int) -> Unit,
) {
    val youLabel = stringResource(R.string.bot_you)
    val living = state.living().filter { allowSelf || it != me }
    // Face + short label; full “Computer N” truncates in these chips.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        living.forEach { seat ->
            val selected = picking == seat
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Theme.accent else chip)
                    .border(1.5.dp, if (selected) Theme.accent else line, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) {
                        Sounds.play(Sounds.Effect.Tap)
                        onPick(seat)
                        onChoose(seat)
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SeatChipFace(table, seat)
                Text(
                    seatChipLabel(table, seat, me, youLabel),
                    style = Theme.font(12, FontWeight.Bold, if (selected) Color.White else ink),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Bots: seat number only (face carries identity). Humans: number · name. */
private fun seatChipLabel(table: WerewolfTable, seat: Int, me: Int, youLabel: String): String = when {
    seat == me -> youLabel
    table.players.isBot(seat) -> "${seat + 1}"
    else -> "${seat + 1} · ${table.name(seat)}"
}

/** Claim feed labels always include the seat number (bots: “7 · Computer”). */
private fun shortSeatName(
    table: WerewolfTable,
    seat: Int,
    me: Int,
    youLabel: String,
    computerLabel: String,
): String = when {
    seat == me -> youLabel
    table.players.isBot(seat) -> "${seat + 1} · $computerLabel"
    else -> "${seat + 1} · ${table.name(seat)}"
}

@Composable
private fun SeatChipFace(table: WerewolfTable, seat: Int) {
    val avatar = table.players.avatarOf(seat)
    when {
        table.players.isBot(seat) -> BotFace(avatar, 18.dp)
        avatar != null -> AvatarBadge(avatar, size = 18.dp)
    }
}

@Composable
private fun StatusLine(table: WerewolfTable, state: WerewolfState, modifier: Modifier = Modifier) {
    val me = table.mySeat
    val text = when {
        WerewolfGame.isFinished(state) -> ""
        state.phase == WerewolfPhase.DISCUSS -> stringResource(R.string.werewolf_phase_discuss)
        me in WerewolfGame.actingSeats(state) -> stringResource(R.string.werewolf_your_action)
        else -> stringResource(R.string.werewolf_waiting_others)
    }
    if (text.isNotEmpty()) {
        Text(text, style = Theme.font(13, FontWeight.Bold, Theme.ink), textAlign = TextAlign.End, modifier = modifier)
    }
}

@Composable
private fun RoleCard(role: String, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(
            role = Role.Button,
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.75f)
                .clip(RoundedCornerShape(20.dp))
                .background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .clickable(enabled = false, onClick = {})
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.werewolf_role_title), style = Theme.font(20, FontWeight.Black, Theme.ink))
            Text(roleLabel(role), style = Theme.font(18, FontWeight.Bold, Theme.ink))
            Text(roleBlurb(role), style = Theme.font(13, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.congklak_rules_close), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultOverlay(table: WerewolfTable, state: WerewolfState, onExit: () -> Unit, onRestart: () -> Unit) {
    val town = state.winnerSide == "town"
    val title = when {
        town && state.teamOf(table.mySeat) == "town" -> stringResource(R.string.werewolf_town_wins_you)
        !town && state.teamOf(table.mySeat) == "wolves" -> stringResource(R.string.werewolf_wolves_win_you)
        town -> stringResource(R.string.werewolf_town_wins)
        else -> stringResource(R.string.werewolf_wolves_win)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(0.75f)
                .clip(RoundedCornerShape(20.dp)).background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = Theme.font(22, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center)
            state.alive.indices.filter { !state.alive[it] }.forEach { seat ->
                val revealed = state.revealed.getOrNull(seat)?.let { roleLabel(it) } ?: "?"
                Text(
                    "${table.name(seat)} · $revealed",
                    style = Theme.font(14, FontWeight.Bold, Theme.muted),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(stringResource(R.string.congklak_to_menu), onExit, Modifier.weight(1f), tint = Theme.muted)
                if (table.canRestart) {
                    PrimaryButton(stringResource(R.string.congklak_play_again), onRestart, Modifier.weight(1f))
                } else {
                    Text(
                        stringResource(R.string.congklak_wait_host),
                        style = Theme.font(14, FontWeight.Bold, Theme.muted),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Stable key so night bots finishing actions do not re-trigger “Night!”. */
private fun werewolfAudioKey(state: WerewolfState): String {
    val deaths = state.lastDeaths.sorted().joinToString(",")
    return "${state.phase}-${state.day}-$deaths-${state.winnerSide}"
}

/** Night / dawn / day / vote calls with transition SFX; spaced so lines don’t overlap. */
private suspend fun announceWerewolfPhase(state: WerewolfState, me: Int) {
    when (state.phase) {
        WerewolfPhase.NIGHT -> {
            Sounds.play(Sounds.Effect.Night)
            delay(350L)
            Sounds.say(Sounds.Line.Night)
        }
        WerewolfPhase.DISCUSS -> {
            Sounds.play(Sounds.Effect.Dawn)
            delay(350L)
            if (state.lastDeaths.isEmpty()) Sounds.say(Sounds.Line.Peaceful)
            else Sounds.say(Sounds.Line.Dawn)
            delay(1_050L)
            if (me in state.lastDeaths) {
                Sounds.say(Sounds.Line.YouDied)
                delay(950L)
            }
            Sounds.say(Sounds.Line.Day)
        }
        WerewolfPhase.VOTE -> Sounds.say(Sounds.Line.Vote)
        WerewolfPhase.SHERIFF_ELECT -> Sounds.say(Sounds.Line.Election)
        WerewolfPhase.FINISHED -> {
            val town = state.winnerSide == "town"
            val won = (town && state.teamOf(me) == "town") || (!town && state.teamOf(me) == "wolves")
            Sounds.play(if (won) Sounds.Effect.Win else Sounds.Effect.Lose)
            delay(400L)
            Sounds.say(if (town) Sounds.Line.TownWin else Sounds.Line.WolvesWin)
        }
        else -> Unit
    }
}

@Composable
private fun RulesSheet(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.werewolf_rules_title), style = Theme.font(20, FontWeight.Black, Theme.ink))
            listOf(
                R.string.werewolf_rule_setup,
                R.string.werewolf_rule_wolves,
                R.string.werewolf_rule_town,
                R.string.werewolf_rule_sheriff,
                R.string.werewolf_rule_win,
            ).forEach {
                Text(stringResource(it), style = Theme.font(14, FontWeight.Bold, Theme.muted))
            }
            PrimaryButton(stringResource(R.string.congklak_rules_close), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun phaseTitle(state: WerewolfState) = stringResource(
    when (state.phase) {
        WerewolfPhase.NIGHT -> R.string.werewolf_phase_night
        WerewolfPhase.DISCUSS -> R.string.werewolf_phase_discuss
        WerewolfPhase.VOTE -> R.string.werewolf_phase_vote
        WerewolfPhase.SHERIFF_ELECT -> R.string.werewolf_phase_election
        WerewolfPhase.HUNTER -> R.string.werewolf_phase_hunter
        WerewolfPhase.SUCCESSOR -> R.string.werewolf_phase_successor
        WerewolfPhase.FINISHED -> R.string.werewolf_phase_result
        else -> R.string.werewolf_title
    },
)

@Composable
private fun promptForRole(role: String) = stringResource(
    when (role) {
        WerewolfRole.WEREWOLF -> R.string.werewolf_pick_victim
        WerewolfRole.SEER -> R.string.werewolf_peek
        WerewolfRole.DOCTOR -> R.string.werewolf_protect
        else -> R.string.werewolf_pick_target
    },
)

@Composable
private fun roleLabel(role: String) = stringResource(
    when (role) {
        WerewolfRole.WEREWOLF -> R.string.werewolf_role_werewolf
        WerewolfRole.VILLAGER -> R.string.werewolf_role_villager
        WerewolfRole.SEER -> R.string.werewolf_role_seer
        WerewolfRole.DOCTOR -> R.string.werewolf_role_doctor
        WerewolfRole.HUNTER -> R.string.werewolf_role_hunter
        WerewolfRole.WITCH -> R.string.werewolf_role_witch
        else -> R.string.werewolf_role_unknown
    },
)

@Composable
private fun roleBlurb(role: String) = stringResource(
    when (role) {
        WerewolfRole.WEREWOLF -> R.string.werewolf_role_blurb_werewolf
        WerewolfRole.SEER -> R.string.werewolf_role_blurb_seer
        WerewolfRole.DOCTOR -> R.string.werewolf_role_blurb_doctor
        WerewolfRole.HUNTER -> R.string.werewolf_role_blurb_hunter
        WerewolfRole.WITCH -> R.string.werewolf_role_blurb_witch
        else -> R.string.werewolf_role_blurb_villager
    },
)

@Composable
private fun claimLine(id: String, targetName: String?): String = when (id) {
    "suspect" -> stringResource(R.string.werewolf_claim_suspect, targetName.orEmpty())
    "trust" -> stringResource(R.string.werewolf_claim_trust, targetName.orEmpty())
    "saw_wolf" -> stringResource(R.string.werewolf_claim_saw_wolf, targetName.orEmpty())
    "i_seer" -> stringResource(R.string.werewolf_claim_i_seer)
    "i_doctor" -> stringResource(R.string.werewolf_claim_i_doctor)
    "i_villager" -> stringResource(R.string.werewolf_claim_i_villager)
    "i_hunter" -> stringResource(R.string.werewolf_claim_i_hunter)
    else -> stringResource(R.string.werewolf_claim_pass)
}

@Composable
private fun claimChipLabel(id: String): String = when (id) {
    "suspect" -> stringResource(R.string.werewolf_claim_suspect, "…")
    "trust" -> stringResource(R.string.werewolf_claim_trust, "…")
    "saw_wolf" -> stringResource(R.string.werewolf_claim_saw_wolf, "…")
    "i_seer" -> stringResource(R.string.werewolf_claim_i_seer)
    "i_doctor" -> stringResource(R.string.werewolf_claim_i_doctor)
    "i_villager" -> stringResource(R.string.werewolf_claim_i_villager)
    "i_hunter" -> stringResource(R.string.werewolf_claim_i_hunter)
    else -> stringResource(R.string.werewolf_claim_pass)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClaimChips(
    table: WerewolfTable,
    me: Int,
    ink: Color,
    muted: Color,
    chip: Color,
    line: Color,
) {
    var pending by remember { mutableStateOf<String?>(null) }
    val youLabel = stringResource(R.string.bot_you)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.werewolf_claims_title), style = Theme.font(12, FontWeight.Bold, muted))
        val pick = pending
        if (pick != null && WerewolfClaims.needsTarget(pick)) {
            Text(stringResource(R.string.werewolf_claim_pick_target), style = Theme.font(12, FontWeight.Bold, ink))
            // Same packing as night/election targets: as many as fit across the panel.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                table.latest.living().filter { it != me }.forEach { seat ->
                    Row(
                        Modifier
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(chip)
                            .border(1.5.dp, line, RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) {
                                Sounds.play(Sounds.Effect.Tap)
                                table.sendClaim(pick, seat)
                                pending = null
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SeatChipFace(table, seat)
                        Text(
                            seatChipLabel(table, seat, me, youLabel),
                            style = Theme.font(12, FontWeight.Bold, ink),
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(1.5.dp, line, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { pending = null }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.congklak_rules_close), style = Theme.font(16, FontWeight.Bold, Theme.inkOnLight))
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WerewolfClaims.ALL.forEach { item ->
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(chip)
                            .border(1.dp, line, RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) {
                                Sounds.play(Sounds.Effect.Tap)
                                if (item.needsTarget) pending = item.id
                                else table.sendClaim(item.id, null)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(claimChipLabel(item.id), style = Theme.font(11, FontWeight.Bold, ink), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimFeed(table: WerewolfTable, me: Int, modifier: Modifier = Modifier) {
    if (table.claims.isEmpty()) {
        Spacer(modifier)
        return
    }
    val youLabel = stringResource(R.string.bot_you)
    val computerLabel = stringResource(R.string.bot_name)
    val scroll = rememberScrollState()
    val newest = table.claims.lastOrNull()?.n
    LaunchedEffect(newest) {
        if (newest != null) scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier.verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        table.claims.forEach { event ->
            val speaker = shortSeatName(table, event.seat, me, youLabel, computerLabel)
            val target = event.target?.let { shortSeatName(table, it, me, youLabel, computerLabel) }
            val avatar = table.players.avatarOf(event.seat)
            Row(
                Modifier
                    .background(Theme.surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    table.players.isBot(event.seat) -> BotFace(avatar, 16.dp)
                    avatar != null -> AvatarBadge(avatar, size = 16.dp)
                }
                Text(
                    stringResource(R.string.werewolf_claim_bubble, speaker, claimLine(event.id, target)),
                    style = Theme.font(11, FontWeight.Bold, Theme.ink),
                )
            }
        }
    }
}