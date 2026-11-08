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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How wide the rendered game view is, in card heights: the board then fills about the
 *  card's height and only its left end shows. Same as iOS. */
private const val WIDTH_PER_HEIGHT = 4.4f

/** The last snapshot and the size it was rendered at, kept for the next visit to the menu. */
private var cached: Pair<IntSize, Bitmap>? = null

/**
 * The real voxel board as a still picture, for the menu's congklak card: the start position
 * from the near side, cropped to the left end (your store and the first holes). It is drawn
 * once with Filament on a [TextureView], snapshotted, and then shown as an image, so the menu
 * doesn't keep a second renderer running. [onReady] is called once the snapshot is shown.
 */
@Composable
fun BoardPreview(modifier: Modifier = Modifier, onReady: () -> Unit = {}) {
    BoxWithConstraints(modifier.background(Theme.cream).clipToBounds().clearAndSetSemantics {}) {
        val size = IntSize((constraints.maxHeight * WIDTH_PER_HEIGHT).roundToInt(), constraints.maxHeight)
        if (size.height <= 0) return@BoxWithConstraints
        val widthDp = with(LocalDensity.current) { size.width.toDp() }
        val fill = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(widthDp).fillMaxHeight()

        // A new size (the ad banner loading moves the card) starts over with a fresh renderer.
        key(size) { Snapshot(size, fill, onReady) }
    }
}

/** Renders the board at [size] pixels once, then shows the snapshot in its place. */
@Composable
private fun Snapshot(size: IntSize, fill: Modifier, onReady: () -> Unit) {
    var image by remember { mutableStateOf(cached?.takeIf { it.first == size }?.second) }
    val shown = image
    if (shown != null) {
        LaunchedEffect(Unit) { onReady() }
        Image(shown.asImageBitmap(), contentDescription = null, modifier = fill, contentScale = ContentScale.FillBounds)
        return
    }

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var renderer by remember { mutableStateOf<BoardRenderer?>(null) }
    AndroidView(
        modifier = fill,
        factory = { ctx ->
            TextureView(ctx).also { view ->
                textureView = view
                renderer = BoardRenderer(view).apply {
                    setSeedTargets(CongklakSeeds(CongklakGame.initialState(tembakAfterStore = false).pits).targets())
                    resume()
                }
            }
        },
        onRelease = { renderer?.destroy(); renderer = null; textureView = null },
    )
    // Covers the live view until the snapshot replaces it: an empty TextureView shows white,
    // and hiding the view itself (alpha 0) stops it from taking new frames.
    Box(Modifier.fillMaxSize().background(Theme.cream))
    LaunchedEffect(renderer) {
        val board = renderer ?: return@LaunchedEffect
        // A rendered frame takes a moment to reach the texture, and the first one can be
        // dropped: keep asking for frames until the snapshot has the board in it.
        repeat(40) {
            delay(100)
            if (!board.hasDrawn) return@repeat
            val shot = textureView?.bitmap
            if (shot != null && shot.getPixel(shot.width / 2, shot.height / 2) ushr 24 != 0) {
                cached = size to shot
                image = shot
                return@LaunchedEffect
            }
            board.invalidate()
        }
    }
}
