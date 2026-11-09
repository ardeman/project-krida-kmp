package com.ardeman.krida.congklak

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.CongklakSeeds
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.VoxelStage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How wide the rendered game view is, in card heights: the board then fills about the
 *  card's height and only its left end shows. Same as iOS. */
private const val WIDTH_PER_HEIGHT = 4.4f

/** The last snapshot per game and the size it was rendered at, kept for the next visit to the menu. */
private val cached = HashMap<String, Pair<IntSize, Bitmap>>()

/**
 * The real voxel board as a still picture, for the menu's congklak card: the start position
 * from the near side, cropped to the left end (your store and the first holes). It is drawn
 * once with Filament on a [TextureView], snapshotted, and then shown as an image, so the menu
 * doesn't keep a second renderer running. [onReady] is called once the snapshot is shown.
 */
@Composable
fun BoardPreview(modifier: Modifier = Modifier, onReady: () -> Unit = {}) = VoxelPreview("congklak", WIDTH_PER_HEIGHT, modifier, onReady) { view ->
    BoardRenderer(view).apply {
        setSeedTargets(CongklakSeeds(CongklakGame.initialState(tembakAfterStore = false).pits).targets())
    }
}

/**
 * A game's voxel scene as a still picture for the menu: [create] builds the renderer on a
 * [TextureView], which is drawn once, snapshotted (cached under [key]) and shown as an image.
 * With [widthPerHeight] the view is that many heights wide and cropped to its left end;
 * without, it fills the box.
 */
@Composable
fun VoxelPreview(
    key: String,
    widthPerHeight: Float?,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    create: (TextureView) -> VoxelStage,
) {
    BoxWithConstraints(modifier.background(Theme.backdropBrush).clipToBounds().clearAndSetSemantics {}) {
        val w = widthPerHeight?.let { (constraints.maxHeight * it).roundToInt() } ?: constraints.maxWidth
        val size = IntSize(w, constraints.maxHeight)
        if (size.height <= 0 || size.width <= 0) return@BoxWithConstraints
        val widthDp = with(LocalDensity.current) { size.width.toDp() }
        val fill = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(widthDp).fillMaxHeight()

        // A new size (the ad banner loading moves the card) starts over with a fresh renderer.
        key(key, size) { Snapshot(key, size, fill, onReady, create) }
    }
}

/** Renders the scene at [size] pixels once, then shows the snapshot in its place. */
@Composable
private fun Snapshot(key: String, size: IntSize, fill: Modifier, onReady: () -> Unit, create: (TextureView) -> VoxelStage) {
    var image by remember { mutableStateOf(cached[key]?.takeIf { it.first == size }?.second) }
    val shown = image
    if (shown != null) {
        LaunchedEffect(Unit) { onReady() }
        Image(shown.asImageBitmap(), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds)
        return
    }

    // Building the Filament engine blocks the main thread for a moment: let the loading
    // screen reach the screen first.
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        withFrameNanos {}
        mounted = true
    }
    if (!mounted) {
        Box(fill.background(Theme.backdropBrush))
        return
    }

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var renderer by remember { mutableStateOf<VoxelStage?>(null) }
    AndroidView(
        modifier = fill,
        factory = { ctx ->
            TextureView(ctx).also { view ->
                textureView = view
                renderer = create(view).apply { resume() }
            }
        },
        onRelease = { renderer?.destroy(); renderer = null; textureView = null },
    )
    // Covers the live view until the snapshot replaces it: an empty TextureView shows white,
    // and hiding the view itself (alpha 0) stops it from taking new frames.
    Box(Modifier.fillMaxSize().background(Theme.backdropBrush))
    LaunchedEffect(renderer) {
        val board = renderer ?: return@LaunchedEffect
        // A rendered frame takes a moment to reach the texture, and the first one can be
        // dropped: keep asking for frames until the snapshot has the board in it.
        repeat(40) {
            delay(100)
            if (!board.hasDrawn) return@repeat
            val shot = textureView?.bitmap
            if (shot != null && shot.getPixel(shot.width / 2, shot.height / 2) ushr 24 != 0) {
                cached[key] = size to shot
                image = shot
                return@LaunchedEffect
            }
            board.invalidate()
        }
    }
}
