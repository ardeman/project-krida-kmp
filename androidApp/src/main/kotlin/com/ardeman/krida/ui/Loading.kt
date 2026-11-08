package com.ardeman.krida.ui

import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R

/** Holes in the loading row, like one side of a congklak board. */
private const val HOLES = 7

/**
 * Cream screen with the wordmark and a row of seeds filling one by one, as if sown. Shown at
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
            .background(Theme.cream)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Image(rememberLightWordmark(), contentDescription = null, modifier = Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(HOLES) { i ->
                    Box(
                        Modifier.size(12.dp).alpha(if (sown > i) 1f else 0.22f).background(Theme.wood, CircleShape),
                    )
                }
            }
        }
    }
}

/** The wordmark in its light-mode colors: the cream screens stay cream even when the system is dark. */
@Composable
fun rememberLightWordmark(): ImageVector {
    val context = LocalContext.current
    return remember(context) {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        ImageVector.vectorResource(null, context.createConfigurationContext(config).resources, R.drawable.krida_wordmark)
    }
}
