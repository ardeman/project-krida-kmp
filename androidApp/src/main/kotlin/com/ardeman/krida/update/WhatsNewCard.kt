package com.ardeman.krida.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.nearby.card
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.Theme

/** Home-menu status card for the current marketing version’s bullets. */
@Composable
fun WhatsNewCard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bullets = stringArrayResource(R.array.whats_new_bullets)
    BackHandler(onBack = {
        WhatsNew.dismiss(context)
        onDismiss()
    })
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .width(420.dp)
                .card()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.whats_new_title),
                style = Theme.font(22, FontWeight.Black, Theme.ink),
                textAlign = TextAlign.Center,
            )
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                bullets.forEach { line ->
                    Text(
                        "• $line",
                        style = Theme.font(14, FontWeight.Bold, Theme.muted),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            PrimaryButton(
                stringResource(R.string.whats_new_got_it),
                onClick = {
                    WhatsNew.dismiss(context)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}
