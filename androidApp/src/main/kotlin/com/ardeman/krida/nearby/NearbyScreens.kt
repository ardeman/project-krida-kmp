package com.ardeman.krida.nearby

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.qr.QrCode
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.ui.LoadingIndicator
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.gameInsets
import com.ardeman.krida.ulartangga.pawnColor

/*
 * The "Teman dekat" screens from the nearby-play mockup: the room lobby (host and guest), the
 * join screen (scan the host's QR or type the code) and the connecting / failure card.
 */

private val cardShape = RoundedCornerShape(20.dp)
private val okGreen = Color(0xFF3F7D2E)

internal fun Modifier.card() = clip(cardShape).background(Theme.surface).border(1.5.dp, Theme.line, cardShape)

/** Back button, title and the "Teman dekat" or "Online" line, over the screen's cards. */
@Composable
internal fun NearbyFrame(
    title: String,
    subtitle: String,
    online: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Theme.backdropBrush).windowInsetsPadding(gameInsets), contentAlignment = Alignment.Center) {
        Column(
            Modifier.sizeIn(maxWidth = 874.dp, maxHeight = 460.dp).fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, backLabel, onBack)
                Column {
                    Text(title, style = Theme.font(20, FontWeight.Black, Theme.ink), maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painterResource(if (online) R.drawable.ic_globe else R.drawable.ic_bluetooth), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(14.dp))
                        Text(subtitle, style = Theme.font(13, FontWeight.Bold, Theme.muted), maxLines = 1)
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
internal fun gameTitle(game: String?) = when (game) {
    UlarTanggaGame.id -> stringResource(R.string.ular_title)
    CongklakGame.id -> stringResource(R.string.congklak_title)
    else -> stringResource(R.string.nearby_join_title)
}

@Composable
private fun colorNames() = listOf(R.string.ular_player_red, R.string.ular_player_blue, R.string.ular_player_green, R.string.ular_player_yellow)
    .map { stringResource(it) }

/** Text on a pawn colour: dark on yellow, white on the rest. */
private fun inkOn(color: Color) = if (color.luminance() > 0.5f) Theme.inkOnLight else Color.White

/**
 * The room before the game starts. Host: the code and its QR on the left; guest: "Sudah
 * masuk". On the right the seats, the pawn colours (ular tangga) and Mulai or Keluar room.
 */
@Composable
fun RoomLobby(room: NearbyRoom, onLeave: () -> Unit) {
    val names = colorNames()
    val title = gameTitle(room.game)
    val subtitle = if (room.isHost) {
        subtitle(room.online)
    } else {
        stringResource(R.string.nearby_room_host, room.code, room.players.firstOrNull { it.seat == 0 }?.name ?: "")
    }
    NearbyFrame(
        title,
        subtitle,
        room.online,
        stringResource(if (room.isHost) R.string.congklak_back else R.string.nearby_leave_room),
        onLeave,
    ) {
        Column(
            // Shares the width with the seats, so a narrow phone still fits "+ Komputer".
            Modifier.weight(0.37f).widthIn(max = 290.dp).fillMaxHeight().card().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            if (room.isHost) HostCode(room.code, room.online) else JoinedNote(room, names)
        }
        Column(
            Modifier.weight(0.63f).fillMaxHeight().card().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayersHeader(room, onLeave)
            if (room.colorCount > 0) ColorRow(room, names)
            Seats(room, names)
        }
    }
}

@Composable
private fun ColumnScope.HostCode(code: String, online: Boolean) {
    Text(
        stringResource(R.string.nearby_code).uppercase(),
        style = Theme.font(13, FontWeight.ExtraBold, Theme.muted),
    )
    Row(
        Modifier.widthIn(max = if (online) 246.dp else 208.dp).fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = code.toList().joinToString(" ") },
        horizontalArrangement = Arrangement.spacedBy(if (online) 6.dp else 8.dp),
    ) {
        // Share the available width so all letters fit even on a narrow phone.
        code.forEach { ch ->
            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(12.dp)).background(Theme.chip), contentAlignment = Alignment.Center) {
                Text(ch.toString(), style = Theme.font(if (online) 26 else 30, FontWeight.Black, Theme.ink).copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
    RoomQr(if (online) RoomCode.shareUrl(code) else RoomCode.joinUrl(code))
    Text(
        stringResource(if (online) R.string.online_host_hint else R.string.nearby_host_hint),
        style = Theme.font(12, FontWeight.Bold, Theme.muted),
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun subtitle(online: Boolean) = stringResource(if (online) R.string.online_subtitle else R.string.nearby_subtitle)

/** The room link as a QR code: dark modules on white in both themes, so any camera reads it. */
@Composable
private fun RoomQr(link: String) {
    val qr = remember(link) { QrCode.encode(link) }
    val label = stringResource(R.string.nearby_qr_label)
    val shape = RoundedCornerShape(12.dp)
    Canvas(
        Modifier.size(132.dp).clip(shape).background(Color.White).border(1.dp, Theme.line, shape).padding(10.dp)
            .semantics { contentDescription = label },
    ) {
        val cell = size.minDimension / qr.size
        for (r in 0 until qr.size) for (c in 0 until qr.size) {
            // A hair wider than a cell, so neighbouring modules leave no seams.
            if (qr.isDark(r, c)) drawRect(Theme.inkOnLight, Offset(c * cell, r * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

@Composable
private fun ColumnScope.JoinedNote(room: NearbyRoom, names: List<String>) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(Theme.chip), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = if (Theme.dark) Color(0xFF8BC77A) else okGreen, modifier = Modifier.size(40.dp))
    }
    Text(stringResource(R.string.nearby_joined_title), style = Theme.font(22, FontWeight.Black, Theme.ink))
    val seat = room.mySeat
    val color = room.players.firstOrNull { it.seat == seat }?.color
    val line = when {
        seat == null -> null
        color != null -> stringResource(R.string.nearby_joined_seat_pawn, seat + 1, names[color].lowercase())
        else -> stringResource(R.string.nearby_joined_seat, seat + 1)
    }
    Text(
        stringResource(R.string.nearby_joined_waiting) + (line?.let { "\n$it" } ?: ""),
        style = Theme.font(14, FontWeight.Bold, Theme.muted),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PlayersHeader(room: NearbyRoom, onLeave: () -> Unit) {
    val count = room.players.size
    val hint = when {
        !room.isHost -> R.string.nearby_hint_guest
        room.canStart && count < room.maxPlayers -> R.string.nearby_hint_empty_seats
        room.canStart -> R.string.nearby_hint_full
        room.canAddBots -> R.string.nearby_hint_wait_or_computer
        else -> R.string.nearby_hint_wait
    }
    Row(Modifier.fillMaxWidth().heightIn44(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.nearby_players_title, count, room.maxPlayers), style = Theme.font(17, FontWeight.Black, Theme.ink), maxLines = 1)
            Text(stringResource(hint), style = Theme.font(12, FontWeight.Bold, Theme.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (room.isHost) {
            PrimaryButton(stringResource(R.string.nearby_start), onClick = room::start, enabled = room.canStart, modifier = Modifier.widthIn(min = 130.dp))
        } else {
            SecondaryButton(stringResource(R.string.nearby_leave_room), onClick = onLeave, tint = Theme.muted, modifier = Modifier.widthIn(min = 130.dp))
        }
    }
}

private fun Modifier.heightIn44() = this.then(Modifier.sizeIn(minHeight = 44.dp))

/** "Warna pionmu": one swatch per colour; another player's colour shows their seat number. */
@Composable
private fun ColorRow(room: NearbyRoom, names: List<String>) {
    val mine = room.players.firstOrNull { it.seat == room.mySeat }?.color
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Theme.surfaceSoft).border(1.dp, Theme.line, shape)
            .padding(start = 12.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.nearby_your_color), style = Theme.font(13, FontWeight.ExtraBold, Theme.muted), modifier = Modifier.weight(1f))
        for (c in 0 until room.colorCount) {
            val color = pawnColor(c)
            val holder = room.players.firstOrNull { it.color == c && it.seat != room.mySeat }
            val name = names.getOrElse(c) { "${c + 1}" }
            when {
                c == mine -> Swatch(color, stringResource(R.string.nearby_color_chosen, name), selected = true, onClick = null) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = inkOn(color), modifier = Modifier.size(24.dp))
                }
                holder != null -> Swatch(color, stringResource(R.string.nearby_color_taken, name, holder.seat + 1), selected = false, onClick = null, dim = true) {
                    Text("${holder.seat + 1}", style = Theme.font(15, FontWeight.Black, inkOn(color)))
                }
                else -> Swatch(color, name, selected = false, onClick = { room.pickColor(c) }) {}
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    dim: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(44.dp)
            .then(if (selected) Modifier.border(2.dp, Theme.ink, CircleShape).padding(4.dp) else Modifier)
            .alpha(if (dim) 0.4f else 1f)
            .clip(CircleShape)
            .background(color)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.RadioButton) { Sounds.play(Sounds.Effect.Tap); onClick() }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = label
                this.selected = selected
                if (dim) disabled()
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Two columns of seats: filled, a computer (host can remove it) or empty (dashed). */
@Composable
private fun Seats(room: NearbyRoom, names: List<String>) {
    val ular = room.colorCount > 0
    val computers = (1 until room.maxPlayers).map { stringResource(R.string.ular_computer_n, it) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (0 until room.maxPlayers).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { seat ->
                    val p = room.players.firstOrNull { it.seat == seat }
                    val m = Modifier.weight(1f).height(64.dp)
                    when {
                        p == null -> EmptySeat(seat, room.isHost && room.canAddBots, m) {
                            room.addBot(computers.firstOrNull { name -> room.players.none { it.name == name } } ?: computers.first())
                        }
                        else -> FilledSeat(room, p, ular, names, m)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilledSeat(room: NearbyRoom, p: PlayerInfo, ular: Boolean, names: List<String>, modifier: Modifier) {
    val me = p.seat == room.mySeat
    val shape = RoundedCornerShape(14.dp)
    val circle = when {
        ular -> pawnColor(p.color ?: p.seat)
        p.seat == 0 -> Theme.wood
        else -> Theme.seed
    }
    val pawn = p.color?.let { names.getOrNull(it)?.lowercase() }
    fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    val sub = when {
        // The host knows its computers run on its own phone; guests are told.
        p.bot && room.isHost && pawn != null -> cap(stringResource(R.string.nearby_seat_pawn, pawn))
        p.bot -> cap(stringResource(R.string.nearby_seat_bot, pawn ?: ""))
        me && room.isHost -> stringResource(R.string.nearby_seat_host_here)
        p.seat == 0 && pawn != null -> stringResource(R.string.nearby_seat_host_pawn, pawn)
        p.seat == 0 -> stringResource(R.string.nearby_seat_host)
        me -> stringResource(R.string.nearby_seat_this_phone)
        pawn != null -> cap(stringResource(R.string.nearby_seat_pawn, pawn))
        else -> stringResource(R.string.nearby_seat_joined)
    }
    Row(
        modifier.clip(shape).background(if (me) Theme.highlight else Theme.surfaceSoft)
            .border(if (me) 2.dp else 1.dp, if (me) Theme.accent else Theme.line, shape)
            .padding(start = 12.dp, end = if (p.bot && room.isHost && room.canAddBots) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
            if (p.bot) {
                Icon(painterResource(R.drawable.ic_computer), contentDescription = null, tint = inkOn(circle), modifier = Modifier.size(22.dp))
            } else {
                Text("${p.seat + 1}", style = Theme.font(18, FontWeight.Black, inkOn(circle)))
            }
        }
        Column(Modifier.weight(1f)) {
            val name = if (p.bot) stringResource(R.string.bot_name) else p.name
            Text(
                if (me) stringResource(R.string.nearby_you, name) else name,
                style = Theme.font(15, FontWeight.ExtraBold, Theme.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(sub, style = Theme.font(12, FontWeight.Bold, Theme.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.bot && room.isHost && room.canAddBots) {
            val label = stringResource(R.string.nearby_remove_computer, p.seat + 1)
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = label) { Sounds.play(Sounds.Effect.Tap); room.removeBot(p.seat) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptySeat(seat: Int, canAddBot: Boolean, modifier: Modifier, onAddBot: () -> Unit) {
    Row(
        modifier.dashedBorder(2.dp, Theme.line, 14.dp).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).dashedBorder(2.dp, Theme.border, 18.dp), contentAlignment = Alignment.Center) {
            Text("${seat + 1}", style = Theme.font(16, FontWeight.Black, Theme.muted))
        }
        if (canAddBot) {
            Spacer(Modifier.weight(1f))
            val label = stringResource(R.string.nearby_add_computer_label, seat + 1)
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Theme.chip)
                    .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onAddBot() }
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.nearby_add_computer), style = Theme.font(14, FontWeight.Black, Theme.ink), maxLines = 1, softWrap = false)
            }
        } else {
            Text(stringResource(R.string.nearby_waiting_friend), style = Theme.font(14, FontWeight.Bold, Theme.muted), maxLines = 1)
        }
    }
}

internal fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp) = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/**
 * Join a room: scan the host's QR (left) or type its code (right), 4 letters nearby or 6
 * [online]. [error] is a
 * string resource shown under the code (scanner or permission trouble), or null.
 */
@Composable
fun JoinScreen(
    code: String,
    onCode: (String) -> Unit,
    onScan: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
    error: Int?,
    online: Boolean,
) {
    val valid = if (online) RoomCode.isValidOnline(code) else RoomCode.isValid(code)
    NearbyFrame(stringResource(R.string.nearby_join_title), subtitle(online), online, stringResource(R.string.congklak_back), onBack) {
        Column(
            Modifier.weight(1f).fillMaxHeight().card().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(Theme.chip), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null, tint = Theme.accent, modifier = Modifier.size(50.dp))
            }
            Text(stringResource(R.string.nearby_scan_title), style = Theme.font(22, FontWeight.Black, Theme.ink))
            Text(
                stringResource(R.string.nearby_scan_detail),
                style = Theme.font(14, FontWeight.Bold, Theme.muted),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp),
            )
            IconPrimaryButton(R.drawable.ic_camera, stringResource(R.string.nearby_open_camera), onScan)
        }
        Column(
            Modifier.width(28.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(2.dp).weight(1f).background(Theme.ink.copy(alpha = 0.18f)))
            Text(stringResource(R.string.nearby_or), style = Theme.font(12, FontWeight.Black, Theme.ink))
            Box(Modifier.width(2.dp).weight(1f).background(Theme.ink.copy(alpha = 0.18f)))
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().card().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.nearby_type_title), style = Theme.font(22, FontWeight.Black, Theme.ink))
            Text(stringResource(R.string.nearby_code_hint), style = Theme.font(14, FontWeight.Bold, Theme.muted))
            CodeBoxes(code, if (online) Krida.ONLINE_ROOM_CODE_LENGTH else Krida.ROOM_CODE_LENGTH, valid, onCode, onJoin)
            PrimaryButton(stringResource(R.string.nearby_join), onClick = onJoin, enabled = valid, height = 48.dp, modifier = Modifier.widthIn(min = 200.dp))
            // Keeps its line when empty, so the boxes don't jump once the code is complete.
            val note = error ?: (if (online) R.string.online_join_needs_code else R.string.nearby_join_needs_code).takeUnless { valid }
            Text(
                note?.let { stringResource(it) } ?: "",
                style = Theme.font(12, FontWeight.Bold, if (error != null) Theme.error else Theme.muted),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One text field drawn as [length] letter boxes; the next box to fill is outlined while typing. */
@Composable
private fun CodeBoxes(code: String, length: Int, valid: Boolean, onCode: (String) -> Unit, onDone: () -> Unit) {
    val hint = stringResource(R.string.nearby_code_hint)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = code,
        onValueChange = { onCode(it.uppercase().filter(Char::isLetterOrDigit).take(length)) },
        singleLine = true,
        interactionSource = source,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (valid) onDone() }),
        modifier = Modifier.semantics { contentDescription = hint },
        decorationBox = {
            val wide = length <= Krida.ROOM_CODE_LENGTH
            Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 6.dp)) {
                repeat(length) { i ->
                    val active = focused && i == code.length.coerceAtMost(length - 1)
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.size(if (wide) 54.dp else 42.dp, 64.dp).clip(shape)
                            .background(if (active) Theme.surface else Theme.chip)
                            .border(2.dp, if (active) Theme.accent else Theme.line, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            code.getOrNull(i)?.toString() ?: "",
                            style = Theme.font(32, FontWeight.Black, Theme.ink).copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun IconPrimaryButton(@DrawableRes icon: Int, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.height(48.dp).widthIn(min = 200.dp).clip(shape).background(Theme.accent)
            .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onClick() }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, style = Theme.font(16, color = Color.White), maxLines = 1)
    }
}

/**
 * A guest on its way into a room, or stopped: one card with what is happening, the room code
 * and Batal / Kembali, plus Coba lagi or Sambung lagi where trying again can help.
 */
@Composable
fun ConnectingScreen(room: NearbyRoom, onCancel: () -> Unit, onRetry: () -> Unit) {
    val (title, detail) = when (room.stage) {
        NearbyRoom.Stage.SEARCHING -> if (room.online) {
            R.string.online_searching_title to R.string.online_searching_detail
        } else {
            R.string.nearby_searching_title to R.string.nearby_searching_detail
        }
        NearbyRoom.Stage.CONNECTING, NearbyRoom.Stage.LOBBY -> R.string.nearby_connecting_title to R.string.nearby_connecting_detail
        NearbyRoom.Stage.FULL -> R.string.nearby_rejected_full to R.string.nearby_full_detail
        NearbyRoom.Stage.STARTED -> R.string.nearby_rejected_started to R.string.nearby_started_detail
        NearbyRoom.Stage.VERSION -> R.string.nearby_version_title to R.string.nearby_version_detail
        NearbyRoom.Stage.NOT_FOUND -> R.string.nearby_not_found_title to
            if (room.online) R.string.online_not_found_detail else R.string.nearby_not_found_detail
        NearbyRoom.Stage.LOST -> R.string.nearby_host_lost to if (room.online) R.string.online_lost_detail else R.string.nearby_lost_detail
        NearbyRoom.Stage.OFFLINE -> R.string.online_offline_title to R.string.online_offline_detail
        NearbyRoom.Stage.UNKNOWN_GAME -> R.string.nearby_rejected to R.string.nearby_unknown_game
        NearbyRoom.Stage.REFUSED -> R.string.nearby_rejected to R.string.nearby_refused_detail
    }
    val retry = when (room.stage) {
        NearbyRoom.Stage.NOT_FOUND, NearbyRoom.Stage.REFUSED, NearbyRoom.Stage.OFFLINE -> R.string.nearby_try_again
        NearbyRoom.Stage.LOST -> R.string.nearby_reconnect
        else -> null
    }
    val failed = room.failed
    val cancel = stringResource(if (failed) R.string.congklak_back else R.string.nearby_cancel)
    NearbyFrame(gameTitle(room.game), subtitle(room.online), room.online, cancel, onCancel) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(480.dp).card().padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(64.dp).background(if (failed) Theme.error.copy(alpha = 0.14f) else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (failed) {
                        Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = Theme.error, modifier = Modifier.size(34.dp))
                    } else {
                        LoadingIndicator()
                    }
                }
                Text(stringResource(title), style = Theme.font(22, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center)
                Text(
                    stringResource(detail),
                    style = Theme.font(14, FontWeight.Bold, Theme.muted),
                    textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nearby_code), style = Theme.font(13, FontWeight.ExtraBold, Theme.muted))
                    Text(
                        room.code,
                        style = Theme.font(16, FontWeight.Black, Theme.ink).copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Theme.chip).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(cancel, onClick = onCancel, tint = Theme.muted, modifier = Modifier.widthIn(min = 130.dp))
                    if (retry != null) PrimaryButton(stringResource(retry), onClick = onRetry, modifier = Modifier.widthIn(min = 130.dp))
                }
            }
        }
    }
}
