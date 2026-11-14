package com.ardeman.krida.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.account.AvatarBadge

/**
 * The player indicator of congklak (2 seats): the opponent in a pill above the board, this
 * device's player in a larger pill below it. Ular tangga, ludo, gaple and perang kapal use
 * left-column tiles instead.
 */
class PillSeat(val name: String, val avatar: String?, val bot: Boolean)

/** The far seat, above the board: who you play against and what they're doing. */
@Composable
fun OpponentPill(seat: PillSeat, on: Boolean, detail: String, modifier: Modifier = Modifier) {
    Pill(on, height = 40.dp, glow = on, modifier = modifier) {
        PillAvatar(seat, on, 30.dp)
        Text(seat.name, style = Theme.font(16, color = if (on) Color.White else Theme.ink), maxLines = 1)
        // weight so a long detail ellipsises instead of shoving past a countdown beside the pill.
        Text(
            detail,
            Modifier.weight(1f, fill = false),
            style = Theme.font(13, FontWeight.Bold, if (on) Theme.onAccent else Theme.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The near seat, below the board; glows on its turn. */
@Composable
fun PlayerPill(seat: PillSeat, on: Boolean, status: String, modifier: Modifier = Modifier) {
    val ring = if (on) Modifier.border(4.dp, Theme.gold.copy(alpha = 0.45f), CircleShape).padding(2.dp) else Modifier
    Box(modifier.then(ring)) {
        Pill(on, height = 46.dp, glow = false, endPadding = 20.dp) {
            PillAvatar(seat, on, 34.dp)
            Text(seat.name, style = Theme.font(17, FontWeight.Black, if (on) Color.White else Theme.ink), maxLines = 1)
            // weight so long status ("Your turn · …") ellipsises instead of covering the timer.
            Text(
                status,
                Modifier.weight(1f, fill = false),
                style = Theme.font(15, FontWeight.Bold, if (on) Theme.onAccent else Theme.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Pill(
    on: Boolean,
    height: Dp,
    glow: Boolean,
    modifier: Modifier = Modifier,
    endPadding: Dp = 16.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .then(if (glow) Modifier.shadow(10.dp, CircleShape, ambientColor = Theme.gold, spotColor = Theme.gold) else Modifier)
            .height(height)
            .clip(CircleShape)
            .background(if (on) Theme.accent else Theme.surface)
            .then(if (on) Modifier else Modifier.border(1.5.dp, Theme.line, CircleShape))
            .padding(start = 6.dp, end = endPadding)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** The account avatar in multi-device games; otherwise the name's initial, or the bot icon. */
@Composable
private fun PillAvatar(seat: PillSeat, on: Boolean, size: Dp) {
    if (!seat.bot) seat.avatar?.let { return AvatarBadge(it, size = size, modifier = Modifier.clearAndSetSemantics {}) }
    val tint = if (on) Theme.accent else Theme.muted
    Box(
        Modifier.size(size).background(if (on) Theme.raised else Theme.track, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (seat.bot) {
            Icon(painterResource(R.drawable.ic_computer), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(seat.name.take(1).uppercase(), style = Theme.font((size.value * 0.45f).toInt(), FontWeight.Black, tint))
        }
    }
}

/**
 * Back button, opponent pill, buttons: the pill is centered on the bar when it fits and slides
 * aside (and shrinks) when it doesn't, so it never covers a button. Expects exactly three children.
 */
@Composable
fun PillTopBar(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = measurables[0].measure(loose)
        val trail = measurables[2].measure(loose)
        val gapPx = gap.roundToPx()
        val room = (constraints.maxWidth - lead.width - trail.width - 2 * gapPx).coerceAtLeast(0)
        val center = measurables[1].measure(loose.copy(maxWidth = room))
        val height = maxOf(lead.height, center.height, trail.height)
        layout(constraints.maxWidth, height) {
            val minX = lead.width + gapPx
            val x = ((constraints.maxWidth - center.width) / 2).coerceIn(minX, (minX + room - center.width).coerceAtLeast(minX))
            lead.place(0, (height - lead.height) / 2)
            center.place(x, (height - center.height) / 2)
            trail.place(constraints.maxWidth - trail.width, (height - trail.height) / 2)
        }
    }
