package com.ardeman.krida.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors and type from the agreed UI mockup (warm cream, wood, terracotta accent); same values as iOS Theme.swift. */
object Theme {
    val cream = Color(0xFFFBF4EA)
    val ink = Color(0xFF2B1D14)
    val muted = Color(0xFF6E5A4B)
    val accent = Color(0xFFA65A26)
    val line = Color(0xFFEADCCB)
    val chip = Color(0xFFF6E6D3)
    val track = Color(0xFFF3EADF)
    val wood = Color(0xFF8A5A2E)
    val woodShadow = Color(0xFF6B4320)
    val woodDark = Color(0xFF6E4422)
    val pit = Color(0xFF3F2611)
    val seed = Color(0xFFF3E3C8)
    val tan = Color(0xFFF1D9B8)
    val gold = Color(0xFFF2B84B)
    val border = Color(0xFFD9C6AF)
    val onAccent = Color(0xFFFBE7D3)
    val error = Color(0xFFB3261E)

    fun font(size: Int, weight: FontWeight = FontWeight.ExtraBold, color: Color = Color.Unspecified) =
        TextStyle(fontSize = size.sp, fontWeight = weight, color = color)
}

/** Filled terracotta button (Mulai, Buat room, Main lagi). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    enabled: Boolean = true,
) = KridaButton(text, onClick, modifier, height, enabled, Theme.accent, Color.White, null)

/** Outlined button (Gabung pakai kode, Ke menu). */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    tint: Color = Theme.accent,
) = KridaButton(text, onClick, modifier, height, true, Color.White, tint, BorderStroke(1.5.dp, if (tint == Theme.accent) Theme.accent else Theme.border))

@Composable
private fun KridaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    enabled: Boolean,
    fill: Color,
    content: Color,
    border: BorderStroke?,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(fill.copy(alpha = if (pressed) 0.8f else 1f))
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Theme.font(16, color = content), maxLines = 1)
    }
}

/** Round 44 dp icon button with a light border (back, help, close). */
@Composable
fun CircleIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.5.dp, Theme.line, CircleShape)
            .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = Theme.ink, modifier = Modifier.size(22.dp))
    }
}

/** Icon tile used on mode cards: accent glyph on a light chip. */
@Composable
fun RowScope.IconTile(@DrawableRes icon: Int) {
    Box(
        Modifier.size(44.dp).background(Theme.chip, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Theme.accent, modifier = Modifier.size(22.dp))
    }
}

/**
 * What screens keep clear of. The system bars are hidden (they only slide in over the app
 * on a swipe), so that is the camera cutout plus the keyboard, not the bars' space.
 */
val gameInsets: WindowInsets
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.ime)
