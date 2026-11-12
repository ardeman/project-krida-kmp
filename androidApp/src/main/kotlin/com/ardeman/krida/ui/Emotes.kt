package com.ardeman.krida.ui

import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ardeman.krida.R
import com.ardeman.krida.core.protocol.Emotes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a received reaction stays on screen. */
private const val SHOW_MS = 2_500L

/** At most this many reactions show at once; older ones drop first. */
private const val MAX_SHOWN = 4

/**
 * One reaction from [seat]. [n] numbers every reaction on this device, so two equal
 * reactions in a row (or two in the same frame) are still told apart.
 */
data class EmoteEvent(val seat: Int, val id: String, val n: Int = next++) {
    private companion object { var next = 1 }
}

/** Appends a reaction to a table's recent list, keeping only the last few. */
fun List<EmoteEvent>.plusEmote(seat: Int, id: String): List<EmoteEvent> =
    (this + EmoteEvent(seat, id)).takeLast(MAX_SHOWN)

/** Localized label for a catalog [id]; falls back to the id itself. */
@Composable
fun emoteLabel(id: String): String = stringResource(
    when (id) {
        "nice" -> R.string.emote_nice
        "lets_go" -> R.string.emote_lets_go
        "awesome" -> R.string.emote_awesome
        "oops" -> R.string.emote_oops
        "hmm" -> R.string.emote_hmm
        "ready" -> R.string.emote_ready
        else -> R.string.emote_nice
    },
)

/**
 * HUD control for nearby/online games: a smile button opens the preset picker; choosing one
 * calls [onSend]. [emotes] is the table's recent reactions; each new one floats beside the
 * button for [SHOW_MS], stacked when several arrive together. Only the button takes layout
 * space: the bubbles and picker float in a popup to its left, so they never move the button.
 * They open downward from the button's top, or upward from its bottom when [opensUp] (a
 * button near the bottom of the screen).
 */
@Composable
fun EmoteHud(
    canEmote: Boolean,
    emotes: List<EmoteEvent>,
    playerName: (Int) -> String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    opensUp: Boolean = false,
) {
    if (!canEmote) return
    var open by remember { mutableStateOf(false) }
    val shown = remember { mutableStateListOf<EmoteEvent>() }
    // Reactions already on show (or that were there before this HUD appeared).
    var seen by remember { mutableIntStateOf(emotes.maxOfOrNull { it.n } ?: 0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(emotes) {
        val fresh = emotes.filter { it.n > seen }
        if (fresh.isEmpty()) return@LaunchedEffect
        seen = fresh.maxOf { it.n }
        open = false
        for (e in fresh) {
            shown += e
            while (shown.size > MAX_SHOWN) shown.removeAt(0)
            // Each bubble keeps its own timer; the scope ends with the HUD.
            scope.launch {
                delay(SHOW_MS)
                shown.remove(e)
            }
        }
    }
    val openLabel = stringResource(R.string.emote_open)
    // The popup anchors to the inner button, not to [modifier]'s padding.
    Box(modifier) { Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Theme.surface)
            .border(1.5.dp, Theme.line, CircleShape)
            .semantics { contentDescription = openLabel }
            .clickable(role = Role.Button) {
                Sounds.play(Sounds.Effect.Tap)
                open = !open
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("😊", fontSize = 20.sp)
        if (shown.isNotEmpty() || open) {
            // Right edge 8 dp left of the button.
            val shift = with(LocalDensity.current) { (44.dp + 8.dp).roundToPx() }
            Popup(
                alignment = if (opensUp) Alignment.BottomEnd else Alignment.TopEnd,
                offset = IntOffset(-shift, 0),
            ) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (e in shown) {
                        key(e.n) { EmoteBubble(name = playerName(e.seat), id = e.id) }
                    }
                    if (open) {
                        EmotePicker(
                            onPick = { id ->
                                open = false
                                onSend(id)
                            },
                        )
                    }
                }
            }
        }
    } }
}

@Composable
private fun EmoteBubble(name: String, id: String) {
    val glyph = Emotes.glyph(id) ?: return
    Column(
        Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.surface)
            .border(1.5.dp, Theme.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(glyph, fontSize = 28.sp)
        Text(
            "$name · ${emoteLabel(id)}",
            style = Theme.font(12, FontWeight.Bold, Theme.muted),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmotePicker(onPick: (String) -> Unit) {
    // Fixed width so the right-hand HUD column (≈148 dp) end-aligns it and the panel
    // opens left onto the board instead of spilling off the screen.
    FlowRow(
        Modifier
            .requiredWidth(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Theme.surface)
            .border(1.5.dp, Theme.line, RoundedCornerShape(16.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 3,
    ) {
        Emotes.ALL.forEach { item ->
            Column(
                Modifier
                    .widthIn(min = 60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Theme.chip)
                    .clickable(role = Role.Button) {
                        Sounds.play(Sounds.Effect.Tap)
                        onPick(item.id)
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(item.glyph, fontSize = 22.sp)
                Text(emoteLabel(item.id), style = Theme.font(11, FontWeight.Bold, Theme.ink), maxLines = 1)
            }
        }
    }
}
