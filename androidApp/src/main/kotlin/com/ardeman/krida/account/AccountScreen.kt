package com.ardeman.krida.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.ardeman.krida.ui.Sounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.nearby.NearbyFrame
import com.ardeman.krida.nearby.card
import com.ardeman.krida.nearby.subtitle
import com.ardeman.krida.ui.LoadingIndicator
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Theme

// Google's sign-in branding: the button's own fill, outline and text colours, light and dark.
private val googleFill get() = Color(if (Theme.dark) 0xFF131314 else 0xFFFFFFFF)
private val googleLine get() = Color(if (Theme.dark) 0xFF8E918F else 0xFF747775)
private val googleText get() = Color(if (Theme.dark) 0xFFE3E3E3 else 0xFF1F1F1F)

/** "Sign in with Google" with the "G" mark, as Google's branding guidelines require; shared button size. */
@Composable
private fun GoogleButton(onClick: () -> Unit, enabled: Boolean) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(googleFill.copy(alpha = if (pressed) 0.8f else 1f))
            .border(1.dp, googleLine, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.account_google), style = Theme.font(16, color = googleText), maxLines = 1)
    }
}

/** Sign in, or see the signed-in account with sign out and delete: one status card. */
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val user = Account.user
    var confirmingDelete by remember { mutableStateOf(false) }
    // The question only makes sense while signed in and idle.
    val confirming = confirmingDelete && user != null && !Account.busy
    val back = stringResource(R.string.congklak_back)
    val leave = { if (confirming) confirmingDelete = false else onBack() }
    NearbyFrame(stringResource(R.string.account_title), subtitle(true), true, back, leave) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(480.dp).card().padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(64.dp).background(
                        when {
                            Account.busy -> Color.Transparent
                            confirming -> Theme.error.copy(alpha = 0.14f)
                            else -> Theme.chip
                        },
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        Account.busy -> LoadingIndicator()
                        confirming -> Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = Theme.error, modifier = Modifier.size(34.dp))
                        else -> Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(34.dp))
                    }
                }
                val title = when {
                    confirming -> stringResource(R.string.account_delete_title)
                    user != null -> user.name.ifEmpty { stringResource(R.string.account_player) }
                    else -> stringResource(R.string.account_sign_in_title)
                }
                val detail = when {
                    confirming -> stringResource(R.string.account_delete_detail)
                    user != null -> stringResource(if (user.provider == "apple") R.string.account_with_apple else R.string.account_with_google)
                    else -> stringResource(R.string.account_sign_in_detail)
                }
                Text(title, style = Theme.font(22, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center, maxLines = 1)
                Text(detail, style = Theme.font(14, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center)
                if (Account.failed) {
                    Text(stringResource(R.string.account_failed), style = Theme.font(14, FontWeight.Bold, Theme.error), textAlign = TextAlign.Center)
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val wide = Modifier.widthIn(min = 130.dp)
                    when {
                        confirming -> {
                            SecondaryButton(stringResource(R.string.nearby_cancel), onClick = { confirmingDelete = false }, tint = Theme.muted, modifier = wide)
                            PrimaryButton(stringResource(R.string.account_delete), onClick = { confirmingDelete = false; Account.delete() }, modifier = wide)
                        }
                        user != null -> {
                            SecondaryButton(stringResource(R.string.account_delete), onClick = { if (!Account.busy) confirmingDelete = true }, tint = Theme.muted, modifier = wide)
                            PrimaryButton(stringResource(R.string.account_sign_out), onClick = Account::signOut, modifier = wide, enabled = !Account.busy)
                        }
                        else -> {
                            SecondaryButton(back, onClick = onBack, tint = Theme.muted, modifier = wide)
                            GoogleButton(onClick = { Account.signInWithGoogle(context) }, enabled = !Account.busy)
                        }
                    }
                }
            }
        }
    }
}
