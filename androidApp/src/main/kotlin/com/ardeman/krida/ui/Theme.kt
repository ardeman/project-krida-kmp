package com.ardeman.krida.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colors and type from the agreed UI mockup (terracotta backdrop, wood, cream cards); same values as
 * iOS Theme.swift. The screen colors follow the system's light or dark theme ([dark], set when
 * the activity is created; a theme change recreates it). Wood, pit, seed and gold are the
 * boards' own colors and stay the same in both themes.
 */
object Theme {
    /** True in the system's dark theme. */
    var dark = false

    private fun pick(light: Long, night: Long) = Color(if (dark) night else light)

    /** The backdrop's glow, top-left: a light terracotta, or a warm brown in the dark theme. */
    val backdropGlow get() = pick(0xFFF7D7B5, 0xFF3A2A1E)
    /** The backdrop's edges and the solid stand-in for it (fades, window background). */
    val backdrop get() = pick(0xFFE39A5C, 0xFF120E0B)

    /**
     * Screen backdrop behind menus and boards: a radial glow from the top-left, so the cream die
     * and the logo stand out. Same gradient as iOS and the website.
     */
    val backdropBrush: Brush
        get() {
            val colors = listOf(backdropGlow, backdrop)
            return object : ShaderBrush() {
                override fun createShader(size: Size) = RadialGradientShader(
                    center = Offset(size.width * 0.35f, size.height * 0.25f),
                    radius = size.maxDimension * 0.9f,
                    colors = colors,
                )
            }
        }
    /** Cards, buttons and dialogs on the backdrop. */
    val surface get() = pick(0xFFFFFFFF, 0xFF2A221C)
    /** A softer card: the menu's game list, rows in a result list. */
    val surfaceSoft get() = pick(0xFFFFFBF5, 0xFF241D17)
    /** The highlighted row in a result list. */
    val highlight get() = pick(0xFFFBEFE3, 0xFF3A2C20)
    /** The chosen segment of a toggle, raised above [track]. */
    val raised get() = pick(0xFFFFFFFF, 0xFF4A3C30)
    val ink get() = pick(0xFF2B1D14, 0xFFF6EADC)
    /** Text on the boards' fixed light colors (tan, gold, seed), the same in both themes. */
    val inkOnLight = Color(0xFF2B1D14)
    val muted get() = pick(0xFF5A4537, 0xFFB7A593)
    val accent get() = pick(0xFFA65A26, 0xFFC9783A)
    val line get() = pick(0xFFE2CFB7, 0xFF3A3027)
    val chip get() = pick(0xFFF6E6D3, 0xFF3A2C20)
    val track get() = pick(0xFFE8D7C1, 0xFF2F2720)
    val border get() = pick(0xFFD9C6AF, 0xFF4A3D31)
    val error get() = pick(0xFFB3261E, 0xFFF2B8B5)
    val wood = Color(0xFF8A5A2E)
    val woodShadow = Color(0xFF6B4320)
    val woodDark = Color(0xFF6E4422)
    val pit = Color(0xFF3F2611)
    val seed = Color(0xFFF3E3C8)
    val tan = Color(0xFFF1D9B8)
    val gold = Color(0xFFF2B84B)
    val onAccent = Color(0xFFFBE7D3)

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
) = KridaButton(text, onClick, modifier, height, true, Theme.surface, tint, BorderStroke(1.5.dp, if (tint == Theme.accent) Theme.accent else Theme.border))

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
            .background(Theme.surface)
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

/** True inside a [LandscapeStage] that is turned a quarter turn (see there). */
val LocalStageTurned = staticCompositionLocalOf { false }

/** The phone design's landscape size, in dp (the same 874×402 as iOS). */
private const val DESIGN_WIDTH = 874f
private const val DESIGN_HEIGHT = 402f
/** The tallest stage allowed, as height / width. */
private const val MAX_HEIGHT_RATIO = 3f / 4f
/** The most the phone design is grown on big screens. */
private const val MAX_SCALE = 1.5f

/**
 * Keeps every screen in landscape, whatever shape the window has. Android 16 ignores the
 * landscape-only orientation on large screens (tablets, an unfolded foldable) and in split
 * screen, so the window can be portrait: the stage then turns the layout a quarter turn, as if
 * the screen were locked to landscape. A landscape window taller than 4:3 gets a centered
 * stage with cream bars above and below. Inside the stage the density grows with the stage
 * (up to [MAX_SCALE]), so a tablet shows the phone design bigger rather than emptier.
 *
 * A turned stage keeps clear of the window's cutout itself, so [gameInsets] are consumed
 * inside it (they would land on the wrong sides). A SurfaceView can't be turned with the
 * layout; screens check [LocalStageTurned] and use a TextureView then.
 */
@Composable
fun LandscapeStage(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Theme.backdropBrush)) {
        val turned = maxHeight > maxWidth
        Box(
            Modifier.fillMaxSize().then(if (turned) Modifier.windowInsetsPadding(gameInsets) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val outerW = constraints.maxWidth
                val outerH = constraints.maxHeight
                val w = if (turned) outerH else outerW
                val h = minOf(if (turned) outerW else outerH, (w * MAX_HEIGHT_RATIO).toInt())
                val density = LocalDensity.current
                val scale = (minOf(w / density.density / DESIGN_WIDTH, h / density.density / DESIGN_HEIGHT)).coerceIn(1f, MAX_SCALE)
                Box(
                    Modifier
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(outerW, outerH) { placeable.place((outerW - w) / 2, (outerH - h) / 2) }
                        }
                        .graphicsLayer { rotationZ = if (turned) 90f else 0f },
                ) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density * scale, density.fontScale),
                        LocalStageTurned provides turned,
                    ) { content() }
                }
            }
        }
    }
}
