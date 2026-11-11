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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import android.content.Intent
import com.ardeman.krida.R
import com.ardeman.krida.core.protocol.Krida
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

/**
 * Sign in, or see the signed-in account with sign out and delete: one status card. Signed in,
 * the Friends card sits beside it (docs/mockups/friends.md).
 */
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val user = Account.user
    var confirmingDelete by remember { mutableStateOf(false) }
    // The question only makes sense while signed in and idle.
    val confirming = confirmingDelete && user != null && !Account.busy
    val back = stringResource(R.string.congklak_back)
    val leave = { if (confirming) confirmingDelete = false else onBack() }
    // The friends card shows while signed in, except over the delete question.
    val withFriends = user != null && !confirming
    LaunchedEffect(user?.id) { Account.loadFriends() }
    NearbyFrame(stringResource(R.string.account_title), subtitle(true), true, back, leave) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(
                (if (withFriends) Modifier.fillMaxSize().card().verticalScroll(rememberScrollState()) else Modifier.width(480.dp).card())
                    .padding(horizontal = 20.dp, vertical = if (withFriends) 16.dp else 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (withFriends) 8.dp else 12.dp, Alignment.CenterVertically),
            ) {
                val avatar = if (withFriends) 48.dp else 64.dp
                Box(
                    Modifier.size(avatar).background(
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
                        else -> Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(avatar * 0.53f))
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
                if (withFriends) OwnCode(Account.friendCode)
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
        if (withFriends) FriendsCard(Modifier.weight(1f).fillMaxHeight())
    }
}

/** The player's own friend code in two groups of four, with a Share pill for its link. */
@Composable
private fun OwnCode(code: String?) {
    val context = LocalContext.current
    val title = stringResource(R.string.online_share)
    val text = stringResource(R.string.account_friend_share_text, Krida.FRIEND_URL_PREFIX + (code ?: ""))
    Text(stringResource(R.string.account_friend_code), style = Theme.font(13, FontWeight.Bold, Theme.muted))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier.height(44.dp).widthIn(min = 170.dp).clip(shape).background(Theme.chip).border(1.5.dp, Theme.line, shape).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                code?.chunked(4)?.joinToString(" ") ?: "…",
                style = Theme.font(18, FontWeight.Black, Theme.ink).copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
        }
        if (code != null) {
            SecondaryButton(title, onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, title))
            })
        }
    }
}

/** Add a friend by code, then the list; a row's × asks before removing. */
@Composable
private fun FriendsCard(modifier: Modifier) {
    var removing by remember { mutableStateOf<String?>(null) }
    val ready = Account.friendDraft.length == Krida.FRIEND_CODE_LENGTH && !Account.addingFriend
    Column(modifier.card().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.account_friends), style = Theme.font(20, FontWeight.Black, Theme.ink))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FriendCodeField(Modifier.weight(1f), onDone = { if (ready) Account.addFriend() })
            PrimaryButton(stringResource(R.string.account_friend_add), onClick = Account::addFriend, enabled = ready)
        }
        Account.friendError?.let { error ->
            val message = when (error) {
                Account.FriendError.NotFound -> R.string.account_friend_not_found
                Account.FriendError.Own -> R.string.account_friend_own
                Account.FriendError.Already -> R.string.account_friend_already
                Account.FriendError.Full -> R.string.account_friend_full
                Account.FriendError.TooMany -> R.string.account_friend_too_many
                Account.FriendError.Failed -> R.string.account_failed
            }
            Text(stringResource(message), style = Theme.font(13, FontWeight.Bold, Theme.error))
        }
        val friends = Account.friends
        if (friends.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                // Until the first answer arrives the list is unknown, not empty.
                if (Account.friendCode == null) LoadingIndicator() else {
                    Text(stringResource(R.string.account_friends_empty_title), style = Theme.font(16, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center)
                    Text(stringResource(R.string.account_friends_empty_detail), style = Theme.font(14, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center)
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                friends.forEach { friend ->
                    FriendRow(
                        friend,
                        asking = removing == friend.id,
                        onAsk = { removing = friend.id },
                        onCancel = { removing = null },
                        onRemove = { removing = null; Account.removeFriend(friend.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendCodeField(modifier: Modifier, onDone: () -> Unit) {
    val hint = stringResource(R.string.account_friend_hint)
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = Account.friendDraft,
        onValueChange = { Account.friendDraft = Account.cleanFriendCode(it); Account.friendError = null },
        singleLine = true,
        textStyle = Theme.font(18, FontWeight.Black, Theme.ink).copy(fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Theme.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.semantics { contentDescription = hint },
        decorationBox = { field ->
            Box(
                Modifier.height(44.dp).clip(shape).background(Theme.chip).border(1.5.dp, Theme.line, shape).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (Account.friendDraft.isEmpty()) Text(hint, style = Theme.font(16, FontWeight.Bold, Theme.muted), maxLines = 1)
                field()
            }
        },
    )
}

@Composable
private fun FriendRow(friend: Account.Friend, asking: Boolean, onAsk: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) {
    val name = friend.name.ifEmpty { stringResource(R.string.account_player) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(shape).background(Theme.chip).border(1.5.dp, Theme.line, shape).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (asking) {
            Text(
                stringResource(R.string.account_friend_remove_title, name),
                style = Theme.font(15, FontWeight.Bold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RowAction(stringResource(R.string.nearby_cancel), Theme.muted, onCancel)
            RowAction(stringResource(R.string.account_friend_remove), Theme.error, onRemove)
        } else {
            Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(20.dp))
            Text(name, style = Theme.font(16, FontWeight.Bold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val label = stringResource(R.string.account_friend_remove_named, name)
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onAsk() }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A text action inside a friend row, as tall as the row. */
@Composable
private fun RowAction(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onClick() }.padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Theme.font(14, FontWeight.ExtraBold, color), maxLines = 1)
    }
}
