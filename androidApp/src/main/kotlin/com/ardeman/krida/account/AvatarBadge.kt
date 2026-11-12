package com.ardeman.krida.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.core.account.Avatar
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.ui.Theme
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Circular avatar: a drawn shape+colour mix, a Google picture (only from the allowed host),
 * or the neutral person icon. Drawn in code; no image assets for the shapes.
 */
@Composable
fun AvatarBadge(
    avatar: String?,
    accountId: String? = null,
    picture: String? = null,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Boolean = false,
) {
    when (val shown = Avatar.resolve(avatar, accountId, picture)) {
        is Avatar.Resolved.Drawn -> DrawnAvatar(shown.mix, size, modifier, ring)
        is Avatar.Resolved.Google -> GoogleAvatar(shown.url, size, modifier, ring)
        Avatar.Resolved.Neutral -> NeutralAvatar(size, modifier, ring)
    }
}

/** The avatar of the player in [seat], or null for bots and players without one. */
fun List<PlayerInfo>.avatarOf(seat: Int): String? = firstOrNull { it.seat == seat }?.takeIf { !it.bot }?.avatar

/**
 * In-game seat marker: the player's avatar inside a ring of the seat [color], or the plain
 * colour dot when the seat has no avatar (signed out, bot, pass-and-play).
 */
@Composable
fun SeatAvatar(avatar: String?, color: Color, size: Dp, modifier: Modifier = Modifier) {
    if (avatar == null) {
        Box(modifier.size(size).clip(CircleShape).background(color))
    } else {
        val ring = if (size < 24.dp) 2.dp else 3.dp
        Box(modifier.size(size).background(color, CircleShape).padding(ring), contentAlignment = Alignment.Center) {
            AvatarBadge(avatar, size = size - ring * 2)
        }
    }
}

@Composable
private fun DrawnAvatar(mix: Avatar.Mix, size: Dp, modifier: Modifier, ring: Boolean) {
    val bg = Color(mix.backgroundArgb)
    val fg = Color(mix.symbolArgb)
    Box(
        modifier
            .size(size)
            .then(if (ring) Modifier.border(3.dp, Theme.accent, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.72f)) {
            val r = min(this.size.width, this.size.height) / 2f
            translate(this.size.width / 2f, this.size.height / 2f) {
                scale(r / 22f, r / 22f, Offset.Zero) {
                    drawShape(mix.shapeKind, fg, bg)
                }
            }
        }
    }
}

@Composable
private fun GoogleAvatar(url: String, size: Dp, modifier: Modifier, ring: Boolean) {
    var bitmap by remember(url) { mutableStateOf(GooglePictures.get(url)) }
    DisposableEffect(url) {
        if (bitmap != null) return@DisposableEffect onDispose { }
        GooglePictures.load(url) { bitmap = it }
        onDispose { }
    }
    Box(
        modifier
            .size(size)
            .then(if (ring) Modifier.border(3.dp, Theme.accent, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Theme.chip),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(size * 0.53f))
        }
    }
}

@Composable
private fun NeutralAvatar(size: Dp, modifier: Modifier, ring: Boolean) {
    Box(
        modifier
            .size(size)
            .then(if (ring) Modifier.border(3.dp, Theme.accent, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Theme.chip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(size * 0.53f))
    }
}

/** Small in-memory cache of Google profile pictures; loads only allowed URLs. */
private object GooglePictures {
    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { OkHttpClient() }
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val waiting = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun get(url: String): Bitmap? = cache[url]

    fun load(url: String, done: (Bitmap?) -> Unit) {
        if (!Avatar.isGooglePictureUrl(url)) {
            done(null)
            return
        }
        cache[url]?.let { done(it); return }
        val queue = waiting.getOrPut(url) { mutableListOf() }
        queue += done
        if (queue.size > 1) return
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finish(url, null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bytes = response.use { if (it.isSuccessful) it.body?.bytes() else null }
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp != null) cache[url] = bmp
                // Bound the cache so a long friends list cannot keep every photo forever.
                if (cache.size > 40) cache.keys.take(10).forEach { cache.remove(it) }
                finish(url, bmp)
            }
        })
    }

    private fun finish(url: String, bitmap: Bitmap?) = main.post {
        waiting.remove(url)?.forEach { it(bitmap) }
    }
}

private fun DrawScope.drawShape(shape: Avatar.Shape, fg: Color, bg: Color) {
    when (shape) {
        Avatar.Shape.SEED -> {
            // Pivot at local origin: callers translate to the badge centre first. Default
            // rotate() pivots on the Canvas centre and throws seed/sun/flower off the circle.
            rotate(25f, pivot = Offset.Zero) {
                drawOval(fg, Offset(-8f, -11f), Size(16f, 22f))
                val vein = Path().apply { moveTo(-2f, -8f); quadraticTo(3f, 0f, -1f, 8f) }
                drawPath(vein, bg, style = Stroke(width = 2f))
            }
        }
        Avatar.Shape.DIE -> {
            drawRoundRect(fg, Offset(-10f, -10f), Size(20f, 20f), CornerRadius(5f, 5f))
            for ((x, y) in listOf(-5f to -5f, 5f to -5f, 0f to 0f, -5f to 5f, 5f to 5f)) {
                drawCircle(bg, 2.2f, Offset(x, y))
            }
        }
        Avatar.Shape.KITE -> {
            val kite = Path().apply {
                moveTo(0f, -12f); lineTo(8f, -2f); lineTo(0f, 8f); lineTo(-8f, -2f); close()
            }
            drawPath(kite, fg)
            val tail = Path().apply { moveTo(0f, 8f); quadraticTo(4f, 11f, 0f, 14f) }
            drawPath(tail, fg, style = Stroke(width = 2f))
        }
        Avatar.Shape.STAR -> {
            val star = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 12f else 5.2f
                val a = i * PI / 5.0 - PI / 2.0
                val x = (r * cos(a)).toFloat()
                val y = (r * sin(a)).toFloat()
                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            drawPath(star, fg)
        }
        Avatar.Shape.MOON -> {
            // Crescent: outer disk minus an offset disk of the background colour.
            drawCircle(fg, 11f, Offset(0f, 0f))
            drawCircle(bg, 9f, Offset(5f, -2f))
        }
        Avatar.Shape.SUN -> {
            drawCircle(fg, 6f)
            for (a in 0 until 360 step 45) {
                rotate(a.toFloat(), pivot = Offset.Zero) {
                    drawLine(fg, Offset(0f, -9f), Offset(0f, -12.5f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
            }
        }
        Avatar.Shape.FLOWER -> {
            for (a in 0 until 360 step 72) {
                rotate(a.toFloat(), pivot = Offset.Zero) { drawCircle(fg, 5f, Offset(0f, -6.5f)) }
            }
            drawCircle(bg, 3.2f)
        }
        Avatar.Shape.LEAF -> {
            val leaf = Path().apply {
                moveTo(-9f, 9f); quadraticTo(-10f, -9f, 9f, -10f); quadraticTo(10f, 9f, -9f, 9f); close()
            }
            drawPath(leaf, fg)
            drawLine(bg, Offset(-9f, 9f), Offset(4f, -4f), strokeWidth = 2f)
        }
        Avatar.Shape.HEART -> {
            val heart = Path().apply {
                moveTo(0f, 10f)
                cubicTo(-14f, 0f, -10f, -11f, -4f, -10f)
                cubicTo(-1f, -10f, 0f, -7f, 0f, -6f)
                cubicTo(0f, -7f, 1f, -10f, 4f, -10f)
                cubicTo(10f, -11f, 14f, 0f, 0f, 10f)
                close()
            }
            drawPath(heart, fg)
        }
        Avatar.Shape.CROWN -> {
            val crown = Path().apply {
                moveTo(-11f, 8f); lineTo(-12f, -6f); lineTo(-5f, 0f); lineTo(0f, -9f)
                lineTo(5f, 0f); lineTo(12f, -6f); lineTo(11f, 8f); close()
            }
            drawPath(crown, fg)
        }
        Avatar.Shape.DROP -> {
            val drop = Path().apply {
                moveTo(0f, -12f)
                cubicTo(6f, -3f, 9f, 1f, 9f, 5f)
                // Approximate the arc with a cubic so we stay on Path.
                cubicTo(9f, 10f, 4.5f, 14f, 0f, 14f)
                cubicTo(-4.5f, 14f, -9f, 10f, -9f, 5f)
                cubicTo(-9f, 1f, -6f, -3f, 0f, -12f)
                close()
            }
            drawPath(drop, fg)
        }
        Avatar.Shape.HILL -> {
            val hill = Path().apply {
                moveTo(-13f, 9f); lineTo(-4f, -7f); lineTo(1f, 1f); lineTo(5f, -4f); lineTo(13f, 9f); close()
            }
            drawPath(hill, fg)
        }
    }
}
