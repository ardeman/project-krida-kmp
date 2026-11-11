package com.ardeman.krida.account

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.nearby.card
import com.ardeman.krida.nearby.gameTitle
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Theme
import kotlinx.coroutines.delay

/**
 * A friend's invite, over whichever menu screen is showing: who invites, the game, the room
 * and its players, with Not now and Join. Goes away by itself when the invite runs out.
 */
@Composable
fun InviteCard(invite: Invites.Invite, onNotNow: () -> Unit, onJoin: () -> Unit) {
    LaunchedEffect(invite.id) {
        delay((invite.until - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        Invites.expire(invite.id)
    }
    BackHandler(onBack = onNotNow)
    Box(
        // The scrim takes the taps, so the menu underneath can't be used by accident.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).width(420.dp).card().padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(48.dp).background(Theme.chip, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_people), contentDescription = null, tint = Theme.accent, modifier = Modifier.size(26.dp))
            }
            Text(
                stringResource(R.string.invite_card_title, invite.from.ifEmpty { stringResource(R.string.account_player) }),
                style = Theme.font(22, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.invite_card_detail, gameTitle(invite.game), invite.code, invite.players, invite.seats),
                style = Theme.font(14, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.invite_not_now), onClick = onNotNow, modifier = Modifier.weight(1f), tint = Theme.muted)
                PrimaryButton(stringResource(R.string.nearby_join), onClick = onJoin, modifier = Modifier.weight(1f))
            }
        }
    }
}
