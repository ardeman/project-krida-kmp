package com.ardeman.krida.ulartangga

import android.view.SurfaceView
import android.view.TextureView
import com.ardeman.krida.congklak.GlbWriter
import com.ardeman.krida.core.games.UlarTanggaBoard
import com.ardeman.krida.ui.VoxelStage
import com.google.android.filament.Camera
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import kotlin.math.sqrt

/** The ular tangga board and one pawn per seat as one .glb; pawn nodes are named by [pieceName]. */
object UlarTanggaGlb {
    fun pieceName(seat: Int) = "piece$seat"

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node("board", glb.mesh(UlarTanggaBoard.boardMesh()))
        repeat(UlarTanggaBoard.PIECES) { glb.node(pieceName(it), glb.mesh(UlarTanggaBoard.pieceMesh(it))) }
        return glb.build()
    }
}

/**
 * Draws the voxel ular tangga board with Filament. Pawns move along paths given to [move]
 * (a hop per square, a slide down a snake, a climb up a ladder); frames are only rendered
 * while something moves. Call everything on the main thread.
 */
class UlarTanggaRenderer private constructor(attach: (UiHelper) -> Unit) :
    VoxelStage(attach, UlarTanggaGlb.build(), UlarTanggaBoard.background) {

    constructor(surfaceView: SurfaceView) : this({ it.attachTo(surfaceView) })
    constructor(textureView: TextureView) : this({ it.attachTo(textureView) })

    private class Motion(val path: List<FloatArray>, val seconds: Float, val hop: Float) {
        var elapsed = 0f
        /** Cumulative length at each waypoint. */
        val lengths = FloatArray(path.size).also { l ->
            for (i in 1 until path.size) l[i] = l[i - 1] + dist(path[i - 1], path[i])
        }
    }

    /**
     * Screen room the HUD takes on each side, in pixels: left, top, right, bottom. The camera
     * widens and shifts so the whole board fits between them.
     */
    var insets = FloatArray(4)
        set(value) {
            if (value.contentEquals(field)) return
            field = value
            if (width > 1) { onResize(); invalidate() }
        }

    private val pieces = IntArray(UlarTanggaBoard.PIECES) { entity(UlarTanggaGlb.pieceName(it)) }
    private val position = Array(UlarTanggaBoard.PIECES) { FloatArray(3) }
    private val motions = arrayOfNulls<Motion>(UlarTanggaBoard.PIECES)
    private var players = UlarTanggaBoard.PIECES

    /** Shows the pawns of seats 0 until [count] only. */
    fun setPlayers(count: Int) {
        if (count == players) return
        for (seat in pieces.indices) {
            if (seat < count && seat >= players) show(pieces[seat], true)
            if (seat >= count && seat < players) show(pieces[seat], false)
        }
        players = count
    }

    /** Puts [seat]'s pawn at [xyz] at once. */
    fun place(seat: Int, xyz: FloatArray) {
        motions[seat] = null
        xyz.copyInto(position[seat])
        apply(seat)
        invalidate()
    }

    /**
     * Moves [seat]'s pawn from where it is along [path] (x, y, z waypoints) in [seconds], at
     * an even speed, lifting it [hop] units at the middle of the move.
     */
    fun move(seat: Int, path: List<FloatArray>, seconds: Float, hop: Float = 0f) {
        motions[seat] = Motion(listOf(position[seat].copyOf()) + path, seconds.coerceAtLeast(0.01f), hop)
        invalidate()
    }

    override fun step(dt: Float): Boolean {
        var moving = false
        for (seat in motions.indices) {
            val m = motions[seat] ?: continue
            m.elapsed += dt
            val t = (m.elapsed / m.seconds).coerceAtMost(1f)
            val total = m.lengths.last()
            val at = t * total
            var i = 1
            while (i < m.path.lastIndex && m.lengths[i] < at) i++
            val a = m.path[i - 1]
            val b = m.path[i]
            val span = m.lengths[i] - m.lengths[i - 1]
            val u = if (span <= 0f) 1f else ((at - m.lengths[i - 1]) / span).coerceIn(0f, 1f)
            val p = position[seat]
            for (k in 0..2) p[k] = a[k] + (b[k] - a[k]) * u
            p[1] += m.hop * 4 * t * (1 - t)
            apply(seat)
            if (t >= 1f) motions[seat] = null else moving = true
        }
        return moving || motions.any { it != null }
    }

    private fun apply(seat: Int) {
        val p = position[seat]
        setTransform(pieces[seat], floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, p[0], p[1], p[2], 1f))
    }

    override fun onResize() {
        val eye = UlarTanggaBoard.cameraEye
        val at = UlarTanggaBoard.cameraTarget
        camera.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            at[0].toDouble(), at[1].toDouble(), at[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        camera.setShift(0.0, 0.0)
        // Widen the view until the board's bounds fit the room the HUD leaves, then shift the
        // image so they sit in the middle of that room.
        val (l, t, r, b) = insets.toList()
        val roomW = (width - l - r).coerceAtLeast(width * 0.3f)
        val roomH = (height - t - b).coerceAtLeast(height * 0.3f)
        val bounds = UlarTanggaBoard.bounds()
        var fov = UlarTanggaBoard.CAMERA_VERTICAL_FOV_DEGREES
        var box: FloatArray
        while (true) {
            camera.setProjection(fov.toDouble(), width.toDouble() / height, 0.5, 120.0, Camera.Fov.VERTICAL)
            box = screenBox(bounds)
            if ((box[2] - box[0] <= roomW && box[3] - box[1] <= roomH) || fov >= 90f) break
            fov += 0.5f
        }
        val dx = (l + roomW / 2) - (box[0] + box[2]) / 2
        val dy = (t + roomH / 2) - (box[1] + box[3]) / 2
        camera.setShift(dx * 2.0 / width, -dy * 2.0 / height)
    }

    /** min x, min y, max x, max y of [points] on screen. */
    private fun screenBox(points: List<FloatArray>): FloatArray {
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        points.forEach { (x, y, z) ->
            val p = project(x, y, z)
            box[0] = minOf(box[0], p[0]); box[1] = minOf(box[1], p[1])
            box[2] = maxOf(box[2], p[0]); box[3] = maxOf(box[3], p[1])
        }
        return box
    }

    private companion object {
        fun dist(a: FloatArray, b: FloatArray): Float {
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
