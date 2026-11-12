package com.ardeman.krida.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.ardeman.krida.R
import com.ardeman.krida.core.account.Avatar
import com.ardeman.krida.nearby.card
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.Theme

private enum class AvatarTab { Shape, Background, Symbol }

/**
 * Edit profile: name field on the left, shape / background / symbol picker on the right
 * (docs/mockups/profile.md). [onDone] leaves edit mode after a successful save or cancel.
 */
@Composable
fun AccountEdit(user: Account.User, onDone: () -> Unit) {
    val initial = remember(user.id, user.avatar, user.picture) {
        when (val r = Avatar.resolve(user.avatar, user.id, user.picture)) {
            is Avatar.Resolved.Drawn -> r.mix
            is Avatar.Resolved.Google -> Avatar.defaultMix(user.id)
            Avatar.Resolved.Neutral -> Avatar.defaultMix(user.id)
        }
    }
    var name by remember(user.id) { mutableStateOf(user.name) }
    var mix by remember(user.id) { mutableStateOf(initial) }
    var useGoogle by remember(user.id) {
        mutableStateOf(user.avatar != null && Avatar.isGooglePictureUrl(user.avatar))
    }
    var tab by remember { mutableStateOf(AvatarTab.Shape) }
    val google = user.provider == "google" && Avatar.isGooglePictureUrl(user.picture)
    val canSave = name.trim().isNotEmpty() && !Account.savingProfile

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().card().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            if (useGoogle && google) {
                AvatarBadge(user.picture, user.id, user.picture, 64.dp)
            } else {
                AvatarBadge(mix.encode(), user.id, size = 64.dp)
            }
            NameField(name, onChange = { name = it; Account.profileError = null })
            Text(
                stringResource(R.string.account_edit_hint),
                style = Theme.font(13, FontWeight.Bold, Theme.muted),
                textAlign = TextAlign.Center,
            )
            Account.profileError?.let { error ->
                val message = when (error) {
                    Account.ProfileError.BadName -> R.string.account_edit_bad_name
                    Account.ProfileError.BadAvatar -> R.string.account_edit_bad_avatar
                    Account.ProfileError.Failed -> R.string.account_failed
                }
                Text(stringResource(message), style = Theme.font(13, FontWeight.Bold, Theme.error), textAlign = TextAlign.Center)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val wide = Modifier.widthIn(min = 130.dp)
                SecondaryButton(stringResource(R.string.nearby_cancel), onClick = onDone, tint = Theme.muted, modifier = wide)
                PrimaryButton(stringResource(R.string.account_edit_save), onClick = {
                    val avatar = if (useGoogle && google) Avatar.GOOGLE else mix.encode()
                    Account.saveProfile(name.trim(), avatar) { ok -> if (ok) onDone() }
                }, modifier = wide, enabled = canSave)
            }
        }
        AvatarPicker(
            Modifier.weight(1f).fillMaxHeight(),
            mix = mix,
            tab = tab,
            onTab = { tab = it },
            onMix = { mix = it; useGoogle = false; Account.profileError = null },
            googlePicture = user.picture.takeIf { google },
            useGoogle = useGoogle,
            onGoogle = { useGoogle = true; Account.profileError = null },
        )
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val clipped = value.take(24)
    BasicTextField(
        value = clipped,
        onValueChange = { onChange(it.take(24)) },
        singleLine = true,
        textStyle = Theme.font(16, FontWeight.Bold, Theme.ink),
        cursorBrush = SolidColor(Theme.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            // Row so the counter sits on the trailing edge (padding on the Box left a gap
            // after "n/24" because CenterEnd was inside end padding reserved for the counter).
            Row(
                Modifier
                    .height(44.dp)
                    .clip(shape)
                    .background(Theme.chip)
                    .border(2.dp, Theme.accent, shape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { field() }
                Text(
                    "${clipped.length}/24",
                    style = Theme.font(13, FontWeight.Bold, Theme.muted),
                )
            }
        },
    )
}

@Composable
private fun AvatarPicker(
    modifier: Modifier,
    mix: Avatar.Mix,
    tab: AvatarTab,
    onTab: (AvatarTab) -> Unit,
    onMix: (Avatar.Mix) -> Unit,
    googlePicture: String?,
    useGoogle: Boolean,
    onGoogle: () -> Unit,
) {
    Column(modifier.card().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.account_avatar), style = Theme.font(20, FontWeight.Black, Theme.ink))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AvatarTab.entries.forEach { t ->
                val on = t == tab
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(shape)
                        .background(if (on) Theme.accent else Theme.chip)
                        .border(1.5.dp, if (on) Theme.accent else Theme.line, shape)
                        .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onTab(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    val label = when (t) {
                        AvatarTab.Shape -> R.string.account_avatar_shape
                        AvatarTab.Background -> R.string.account_avatar_background
                        AvatarTab.Symbol -> R.string.account_avatar_symbol
                    }
                    Text(stringResource(label), style = Theme.font(15, FontWeight.Bold, if (on) Color.White else Theme.muted), maxLines = 1)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                AvatarTab.Shape -> ShapeGrid(mix, onMix)
                AvatarTab.Background -> ColourGrid(mix.background, forbidden = mix.symbol) { onMix(mix.copy(background = it)) }
                AvatarTab.Symbol -> ColourGrid(mix.symbol, forbidden = mix.background) { onMix(mix.copy(symbol = it)) }
            }
        }
        if (googlePicture != null) {
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(shape)
                    .background(Theme.surface)
                    .border(1.5.dp, if (useGoogle) Theme.accent else Theme.line, shape)
                    .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onGoogle() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AvatarBadge(googlePicture, size = 28.dp)
                Text(stringResource(R.string.account_avatar_google), style = Theme.font(15, FontWeight.Bold, Theme.muted), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AvatarChoiceGrid(
    count: Int,
    columns: Int,
    content: @Composable (index: Int, cell: Dp, badge: Dp) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Leave a little slack so rounding cannot clip the first/last cell.
        val cell = min(48.dp, (maxWidth - 2.dp) / columns)
        val badge = min(44.dp, cell - 4.dp)
        val rows = (0 until count).chunked(columns)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { index -> content(index, cell, badge) }
                    repeat(columns - row.size) { Box(Modifier.size(cell)) }
                }
            }
        }
    }
}

@Composable
private fun ShapeGrid(mix: Avatar.Mix, onMix: (Avatar.Mix) -> Unit) {
    // Six across so a half-width card on Pixel XL / island-narrowed iPhones does not clip.
    AvatarChoiceGrid(Avatar.SHAPE_COUNT, columns = 6) { shape, cell, badge ->
        val selected = shape == mix.shape
        val mixCell = Avatar.Mix(shape, mix.background, mix.symbol)
        Box(
            Modifier
                .size(cell)
                .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onMix(mixCell) },
            contentAlignment = Alignment.Center,
        ) {
            AvatarBadge(mixCell.encode(), size = if (selected) badge - 6.dp else badge, ring = selected)
        }
    }
}

@Composable
private fun ColourGrid(selected: Int, forbidden: Int, onPick: (Int) -> Unit) {
    AvatarChoiceGrid(Avatar.COLOUR_COUNT, columns = 7) { i, cell, badge ->
        val disabled = i == forbidden
        val on = i == selected
        Box(
            Modifier
                .size(cell)
                .alpha(if (disabled) 0.25f else 1f)
                .clickable(enabled = !disabled, role = Role.Button) {
                    Sounds.play(Sounds.Effect.Tap); onPick(i)
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(if (on) badge - 6.dp else badge)
                    .then(if (on) Modifier.border(3.dp, Theme.accent, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(Color(Avatar.COLOURS[i]))
                    .border(1.5.dp, Theme.line, CircleShape),
            )
        }
    }
}
