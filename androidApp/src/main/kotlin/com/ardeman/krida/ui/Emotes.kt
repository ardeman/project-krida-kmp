package com.ardeman.krida.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** How long a received reaction stays on screen. */
private const val SHOW_MS = 2_500L

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
 * calls [onSend]. [emote] is the latest reaction to float above the board.
 */
@Composable
fun EmoteHud(
    canEmote: Boolean,
    emote: Pair<Int, String>?,
    playerName: (Int) -> String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canEmote) return
    var open by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(emote) {
        if (emote == null) return@LaunchedEffect
        shown = emote
        open = false
        delay(SHOW_MS)
        if (shown == emote) shown = null
    }
    Box(modifier, contentAlignment = Alignment.BottomEnd) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedVisibility(
                visible = shown != null,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                val current = shown
                if (current != null) {
                    EmoteBubble(name = playerName(current.first), id = current.second)
                }
            }
            AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
                EmotePicker(
                    onPick = { id ->
                        open = false
                        onSend(id)
                    },
                )
            }
            val openLabel = stringResource(R.string.emote_open)
            Box(
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
            }
        }
    }
}

@Composable
private fun EmoteBubble(name: String, id: String) {
    val glyph = Emotes.glyph(id) ?: return
    Column(
        Modifier
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
    FlowRow(
        Modifier
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
