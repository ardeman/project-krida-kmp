package com.ardeman.krida.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R

/** Holes in the loading row, like one side of a congklak board. */
private const val HOLES = 7

/**
 * Backdrop-colored screen with the wordmark and a row of seeds filling one by one, as if sown. Shown at
 * app start until the menu's board snapshot is ready, and before each game until the board's
 * first frame is on screen. It swallows taps so nothing underneath reacts.
 */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.loading)
    val sown by rememberInfiniteTransition(label = "sowing").animateFloat(
        initialValue = 0f,
        targetValue = HOLES + 2f, // a short pause with every hole full before starting over
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "sown",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Theme.backdropBrush)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Image(rememberWordmark(), contentDescription = null, modifier = Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(HOLES) { i ->
                    Box(
                        Modifier.size(12.dp).alpha(if (sown > i) 1f else 0.22f).background(if (Theme.dark) Color(0xFFB8743F) else Theme.wood, CircleShape),
                    )
                }
            }
        }
    }
}

/** The wordmark; its letters follow the system's light or dark theme (@color/krida_ink). */
@Composable
fun rememberWordmark(): ImageVector = ImageVector.vectorResource(R.drawable.krida_wordmark)
