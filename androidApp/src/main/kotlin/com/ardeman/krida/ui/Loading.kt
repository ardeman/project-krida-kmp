package com.ardeman.krida.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Backdrop-colored screen with the Krida die turning around and the wordmark's letters filling from
 * left to right like a progress bar. Shown at app start until the menu's board snapshot is ready,
 * and before each game until the board's first frame is on screen. It swallows taps so nothing
 * underneath reacts. Same as iOS.
 */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.loading)
    val transition = rememberInfiniteTransition(label = "loading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "fill",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Theme.backdropBrush)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LoadingIndicator(Modifier.size(130.dp))
            ProgressWord(progress = (progress / 0.85f).coerceAtMost(1f))
        }
    }
}

/** The startup cube/K animation, reused by every compact waiting indicator. */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier.size(64.dp)) {
    val label = stringResource(R.string.loading)
    val transition = rememberInfiniteTransition(label = "loading cube")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    SpinningDie(
        angle = angle.toDouble(),
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
    )
}

/**
 * The wordmark's "Krida" letters (the asset minus its die), dimmed, with the same letters in full
 * color revealed from left to right. The wordmark's viewBox is 27.255 x 7.3 and its letters start
 * at x = 7.6, after the die.
 */
@Composable
private fun ProgressWord(progress: Float) {
    val height = 52.dp
    val box = Modifier.size(height * ((27.255f - 7.6f) / 7.3f), height)
    Box(box) {
        Image(
            rememberWordmark(), contentDescription = null, contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd, modifier = Modifier.matchParentSize().alpha(0.2f),
        )
        Image(
            rememberWordmark(), contentDescription = null, contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            modifier = Modifier.matchParentSize().drawWithContent {
                clipRect(right = size.width * progress) { this@drawWithContent.drawContent() }
            },
        )
    }
}

private data class V(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
    operator fun times(k: Double) = V(x * k, y * k, z * k)
}

private class Mark(val u0: Double, val v0: Double, val u1: Double, val v1: Double, val color: Color)

private class DieFace(val o: V, val u: V, val v: V, val n: V, val marks: List<Mark>, val top: Boolean)

private val brown = Color(0xFF8F552A)
private const val CELL = 0.13
private val voxelK = listOf(
    0.24 to 0.695, 0.24 to 0.565, 0.24 to 0.435, 0.24 to 0.305, 0.24 to 0.175,
    0.37 to 0.435, 0.5 to 0.305, 0.63 to 0.175, 0.5 to 0.565, 0.63 to 0.695,
).map { (u, v) -> Mark(u, v, u + CELL, v + CELL, brown) }
private val square = listOf(Mark(0.33, 0.33, 0.67, 0.67, brown))
private fun pips(centers: List<Pair<Double, Double>>) = centers.map { (u, v) ->
    Mark(u - 0.08, v - 0.08, u + 0.08, v + 0.08, brown)
}
private val two = pips(listOf(0.23 to 0.23, 0.77 to 0.77))
private val six = pips(listOf(
    0.23 to 0.23, 0.77 to 0.23, 0.23 to 0.5,
    0.77 to 0.5, 0.23 to 0.77, 0.77 to 0.77,
))
private val tiles = listOf(
    Mark(0.17, 0.17, 0.39, 0.39, Color(0xFFD6453D)),
    Mark(0.61, 0.17, 0.83, 0.39, Color(0xFF2F6FD1)),
    Mark(0.17, 0.61, 0.39, 0.83, Color(0xFFF0B429)),
    Mark(0.61, 0.61, 0.83, 0.83, Color(0xFF3E9A4F)),
)

/** The cube's faces; marks use the logo's own coordinates (die.svg), measured from each face's top-left corner as seen from outside. */
private val dieFaces = listOf(
    DieFace(V(-1.0, 1.0, -1.0), V(2.0, 0.0, 0.0), V(0.0, 0.0, 2.0), V(0.0, 1.0, 0.0), tiles, true),
    DieFace(V(-1.0, 1.0, 1.0), V(2.0, 0.0, 0.0), V(0.0, -2.0, 0.0), V(0.0, 0.0, 1.0), voxelK, false),
    DieFace(V(1.0, 1.0, 1.0), V(0.0, 0.0, -2.0), V(0.0, -2.0, 0.0), V(1.0, 0.0, 0.0), square, false),
    DieFace(V(1.0, 1.0, -1.0), V(-2.0, 0.0, 0.0), V(0.0, -2.0, 0.0), V(0.0, 0.0, -1.0), two, false),
    DieFace(V(-1.0, 1.0, -1.0), V(0.0, 0.0, 2.0), V(0.0, -2.0, 0.0), V(-1.0, 0.0, 0.0), six, false),
)
private val topColor = Color(0xFFFFF7EC)
private val lightSide = Color(0xFFE8D5BD)
private val darkSide = Color(0xFFCDB597)

/** The logo's isometric view: the cube seen from 35.26 degrees above. */
private const val TILT = 0.6155

/**
 * The die of the Krida logo as a real cube, so it turns around its vertical axis: cream faces, four
 * colored tiles on top, the voxel "k", and pip faces on the sides. An angle of 0 is the logo's pose.
 */
@Composable
private fun SpinningDie(angle: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // A corner turned toward the viewer reaches 1.63 units above or below the centre
        // (cos tilt + sin tilt · √2), so 3.4 units keep the whole turn inside the canvas.
        val scale = minOf(size.width, size.height) / 3.4f
        val cx = size.width / 2
        val cy = size.height / 2
        val theta = angle - Math.PI / 4
        val c = cos(theta)
        val s = sin(theta)
        val ct = cos(TILT)
        val st = sin(TILT)
        fun rotate(p: V): V {
            val x = p.x * c + p.z * s
            val z = -p.x * s + p.z * c
            return V(x, p.y * ct - z * st, p.y * st + z * ct)
        }
        fun polygon(pts: List<V>) = Path().apply {
            pts.forEachIndexed { i, p ->
                val r = rotate(p)
                val x = cx + (r.x * scale).toFloat()
                val y = cy - (r.y * scale).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        for (f in dieFaces) {
            val n = rotate(f.n)
            if (n.z <= 0.001) continue
            val color = if (f.top) {
                topColor
            } else {
                val t = (0.5 - n.x).coerceIn(0.0, 1.0).toFloat()
                Color(
                    darkSide.red + (lightSide.red - darkSide.red) * t,
                    darkSide.green + (lightSide.green - darkSide.green) * t,
                    darkSide.blue + (lightSide.blue - darkSide.blue) * t,
                )
            }
            fun at(a: Double, b: Double) = f.o + f.u * a + f.v * b
            val outline = polygon(listOf(at(0.0, 0.0), at(1.0, 0.0), at(1.0, 1.0), at(0.0, 1.0)))
            drawPath(outline, color)
            drawPath(outline, color, style = Stroke(0.6f))
            for (m in f.marks) {
                drawPath(polygon(listOf(at(m.u0, m.v0), at(m.u1, m.v0), at(m.u1, m.v1), at(m.u0, m.v1))), m.color)
            }
        }
    }
}

/** The wordmark; its letters follow the system's light or dark theme (@color/krida_ink). */
@Composable
fun rememberWordmark(): ImageVector = ImageVector.vectorResource(R.drawable.krida_wordmark)
