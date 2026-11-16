package com.ardeman.krida.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R

/** Host of a linked room: warn that leaving ends the room for every guest. */
@Composable
fun HostLeaveConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(role = Role.Button, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.75f)
                .clip(RoundedCornerShape(20.dp))
                .background(Theme.surface)
                .border(1.5.dp, Theme.line, RoundedCornerShape(20.dp))
                .clickable(enabled = false, onClick = {})
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.nearby_host_leave_title),
                style = Theme.font(22, FontWeight.Black, Theme.ink),
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.nearby_host_leave_detail),
                style = Theme.font(14, FontWeight.Bold, Theme.muted),
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    stringResource(R.string.nearby_cancel),
                    onClick = onCancel,
                    Modifier.weight(1f),
                    tint = Theme.muted,
                )
                PrimaryButton(
                    stringResource(R.string.nearby_leave_room),
                    onClick = onConfirm,
                    Modifier.weight(1f),
                )
            }
        }
    }
}
