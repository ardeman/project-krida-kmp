package com.ardeman.krida.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R

/**
 * Every countdown in the app (search, vote, a dropped player's wait): a ring that drains
 * smoothly from full to empty with the seconds in the middle, turning red for the last 5 s.
 * Same as iOS's `CountdownRing`.
 */
@Composable
fun CountdownRing(seconds: Int, total: Int, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    // Aim one second ahead and animate linearly over that second, so the ring never jumps.
    val target = ((seconds - 1).coerceAtLeast(0).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val progress by animateFloatAsState(target, tween(if (seconds > 0) 1000 else 200, easing = LinearEasing), label = "countdown")
    val urgent = seconds in 1..5
    val color by animateColorAsState(if (urgent) Theme.error else Theme.accent, label = "countdown color")
    val track = Theme.line
    val label = pluralStringResource(R.plurals.seconds_left, seconds, seconds)
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val width = this.size.minDimension * 0.1f
            val inset = width / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - width, this.size.height - width)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
            drawArc(color, -90f, 360f * progress, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
        }
        Text("$seconds", style = Theme.font((size.value * 0.36f).toInt(), FontWeight.Black, if (urgent) Theme.error else Theme.ink))
    }
}

/** A short line with its countdown ring in front, e.g. "Waiting for Ayu…" while a seat waits. */
@Composable
fun CountdownNotice(text: String, seconds: Int, total: Int, modifier: Modifier = Modifier, fontSize: Int = 14) = Row(
    modifier, verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
) {
    CountdownRing(seconds, total, size = (fontSize * 2.2f).dp)
    Text(text, style = Theme.font(fontSize, FontWeight.Bold, Theme.error), textAlign = TextAlign.Center, maxLines = 2)
}
