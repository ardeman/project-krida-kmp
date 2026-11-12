package com.ardeman.krida.gaple

import android.view.TextureView
import com.ardeman.krida.congklak.GlbWriter
import com.ardeman.krida.core.games.GapleBoard
import com.ardeman.krida.core.games.GapleGame
import com.ardeman.krida.core.games.GapleScene
import com.ardeman.krida.core.games.GapleState
import com.ardeman.krida.core.games.GapleTile
import com.ardeman.krida.ui.VoxelStage
import com.google.android.filament.Camera
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

object GapleGlb {
    const val TABLE = "table"
    fun tile(i: Int) = "tile$i"
    fun back(i: Int) = "back$i"
    fun ring(end: Int) = "ring$end"
    /** Face-down tiles: up to three other hands of seven. */
    const val BACKS = 3 * GapleGame.HAND

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node(TABLE, glb.mesh(GapleScene.tableMesh()))
        GapleGame.SET.forEachIndexed { i, t -> glb.node(tile(i), glb.mesh(GapleScene.tileMesh(t.a, t.b))) }
        val back = glb.mesh(GapleScene.backMesh())
        for (i in 0 until BACKS) glb.node(back(i), back)
        val ring = glb.mesh(GapleScene.ringMesh())
        for (end in 0..1) glb.node(ring(end), ring)
        return glb.build()
    }
}

/**
 * Filament gaple table: the line of play on the felt, the other hands face down on the rim
 * (face up at a round end), and dashed gold rings on the open ends. Your own hand is drawn by
 * the screen. Main thread only.
 */
class GapleRenderer(textureView: TextureView) : VoxelStage(textureView, GapleGlb.build()) {

    private val tiles = IntArray(GapleGame.SET.size) { entity(GapleGlb.tile(it)) }
    private val backs = IntArray(GapleGlb.BACKS) { entity(GapleGlb.back(it)) }
    private val rings = IntArray(2) { entity(GapleGlb.ring(it)) }

    private var room: FloatArray? = null
    private var obstacles: List<FloatArray> = emptyList()

    /** Points to frame instead of the whole table (the menu card zooms on a few tiles). */
    var focus: List<FloatArray>? = null
        set(value) {
            field = value
            if (width > 1) {
                onResize()
                invalidate()
            }
        }

    /** Ring centres on the felt, for taps; null when an end has no ring. */
    private val ringAt = arrayOfNulls<FloatArray>(2)
    /** Both rings pulse while you choose an end. */
    private var pulsing = false
    private var pulseTime = 0f

    /** A tile flying from its player's side to its place in the line. */
    private class Flight(val entity: Int, val from: FloatArray, val to: FloatArray, var t: Float = 0f)
    private var flight: Flight? = null

    init {
        (tiles + backs + rings).forEach { show(it, false) }
    }

    /** Fits the table between the HUD boxes on the left, right and bottom of [room]. */
    fun fitBoard(room: FloatArray, obstacles: List<FloatArray>) {
        if (room.contentEquals(this.room) && obstacles.size == this.obstacles.size &&
            obstacles.indices.all { obstacles[it].contentEquals(this.obstacles[it]) }) return
        this.room = room
        this.obstacles = obstacles
        if (width > 1) {
            onResize()
            invalidate()
        }
    }

    /**
     * Shows [state] as [viewer] sees it. With [animate], the last played tile flies in from
     * its player's side over [FLIGHT_SECONDS].
     */
    fun applyState(state: GapleState, viewer: Int, animate: Boolean) {
        flight = null
        val shown = BooleanArray(tiles.size)
        val layout = GapleBoard.layout(state.line, state.origin)
        state.line.forEachIndexed { i, placed ->
            val tile = GapleTile(min(placed.left, placed.right), max(placed.left, placed.right))
            val index = GapleGame.SET.indexOf(tile)
            if (index < 0) return@forEachIndexed
            val m = GapleScene.tileMatrix(tile, placed, layout.tiles[i])
            setTransform(tiles[index], m)
            shown[index] = true
            if (animate && i == lastIndex(state) && state.lastSeat != null) {
                val from = GapleScene.handCenter(GapleScene.side(state.lastSeat!!, viewer, state.players))
                flight = Flight(tiles[index], from, m)
                setTransform(tiles[index], moved(m, from[0], 1.5f, from[2]))
            }
        }
        // Other hands on the rim: face down while the round runs, face up at its end.
        var b = 0
        for (seat in 0 until state.players) {
            if (seat == viewer) continue
            val side = GapleScene.side(seat, viewer, state.players)
            val hand = state.hands.getOrNull(seat).orEmpty()
            if (state.roundOver && hand.isNotEmpty()) {
                hand.forEachIndexed { i, tile ->
                    val index = GapleGame.SET.indexOf(tile)
                    if (index < 0 || shown[index]) return@forEachIndexed
                    setTransform(tiles[index], GapleScene.handMatrix(side, i, hand.size))
                    shown[index] = true
                }
            } else {
                val count = state.handCounts.getOrElse(seat) { 0 }
                for (i in 0 until count) {
                    if (b >= backs.size) break
                    setTransform(backs[b], GapleScene.handMatrix(side, i, count))
                    show(backs[b], true)
                    b++
                }
            }
        }
        for (i in b until backs.size) show(backs[i], false)
        tiles.forEachIndexed { i, e -> show(e, shown[i]) }

        val ends = if (state.roundOver || state.line.isEmpty()) listOf(null, null) else listOf(layout.left, layout.right)
        ends.forEachIndexed { end, pose ->
            val c = pose?.let(GapleScene::ringCenter)
            ringAt[end] = c
            if (c == null) show(rings[end], false) else {
                setTransform(rings[end], scaled(c, 1f))
                show(rings[end], true)
            }
        }
        invalidate()
    }

    /** Pulses both end rings while the player picks an end. */
    fun pulseEnds(on: Boolean) {
        if (pulsing == on) return
        pulsing = on
        pulseTime = 0f
        if (!on) ringAt.forEachIndexed { end, c -> if (c != null) setTransform(rings[end], scaled(c, 1f)) }
        invalidate()
    }

    /** Screen point of [end]'s ring (0 left, 1 right), or null when it has none. */
    fun endOnScreen(end: Int): FloatArray? = ringAt[end]?.let {
        val p = project(it[0], 0f, it[2])
        if (shiftInProjection()) p else floatArrayOf(p[0] + shiftX, p[1] + shiftY)
    }

    /** Whether Filament's projection matrix already carries the shift (a symmetric one has 0 there). */
    private fun shiftInProjection(): Boolean {
        val m = camera.getProjectionMatrix(DoubleArray(16))
        return abs(m[8]) > 1e-6 || abs(m[9]) > 1e-6
    }

    override fun step(dt: Float): Boolean {
        var moving = false
        flight?.let { f ->
            f.t = (f.t + dt / FLIGHT_SECONDS).coerceAtMost(1f)
            val e = 1 - (1 - f.t) * (1 - f.t)
            val x = f.from[0] + (f.to[12] - f.from[0]) * e
            val z = f.from[2] + (f.to[14] - f.from[2]) * e
            val y = 1.5f * (1 - e) + sin(e * Math.PI.toFloat()) * 0.6f
            setTransform(f.entity, moved(f.to, x, y, z))
            if (f.t >= 1f) {
                setTransform(f.entity, f.to)
                flight = null
            }
            moving = true
        }
        if (pulsing) {
            pulseTime += dt
            val s = 1f + 0.12f * sin(pulseTime * 6f)
            ringAt.forEachIndexed { end, c -> if (c != null) setTransform(rings[end], scaled(c, s)) }
            moving = true
        }
        return moving
    }

    private fun lastIndex(state: GapleState): Int = when {
        state.lastEnd == GapleGame.LEFT -> 0
        state.lastEnd == GapleGame.RIGHT -> state.line.lastIndex
        else -> state.origin
    }

    private fun moved(m: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        m.copyOf().also { it[12] = x; it[13] = y; it[14] = z }

    private fun scaled(c: FloatArray, s: Float) = floatArrayOf(
        s, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, s, 0f,
        c[0], 0f, c[2], 1f,
    )

    override fun onResize() {
        val eye = GapleScene.cameraEye
        val target = GapleScene.cameraTarget
        camera.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            target[0].toDouble(), target[1].toDouble(), target[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val base = GapleScene.CAMERA_VERTICAL_FOV_DEGREES.toDouble()
        camera.setProjection(base, aspect, 0.5, 120.0, Camera.Fov.VERTICAL)

        // The narrowest field of view that keeps the table inside the room the HUD leaves,
        // centred where the camera looks (same approach as perang kapal).
        val cx = width / 2f
        val cy = height / 2f
        val room = room ?: floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        var left = room[0]
        var right = room[2]
        var top = room[1]
        var bottom = room[3]
        for (o in obstacles) {
            when {
                // Your hand along the bottom, the event pills along the top, the side columns.
                o[1] > cy -> bottom = min(bottom, o[1])
                o[3] < cy && o[0] < cx && o[2] > cx -> top = max(top, o[3])
                (o[0] + o[2]) / 2 < cx -> left = max(left, o[2])
                else -> right = min(right, o[0])
            }
        }
        // The free box left by the HUD. The picture is scaled to fill it and then shifted so
        // the table's centre sits in the box's centre.
        val halfW = (right - left) / 2
        val halfH = (bottom - top) / 2
        if (halfW <= 1f || halfH <= 1f) return
        camera.setShift(0.0, 0.0)
        val shot = (focus ?: GapleScene.bounds()).map { project(it[0], it[1], it[2]) }
        val needW = shot.maxOf { abs(it[0] - cx) }.coerceAtLeast(1f)
        val needH = shot.maxOf { abs(it[1] - cy) }.coerceAtLeast(1f)
        val k = min(halfW / needW, halfH / needH)
        val fov = Math.toDegrees(2 * atan(tan(Math.toRadians(base / 2)) / k))
        camera.setProjection(fov, aspect, 0.5, 120.0, Camera.Fov.VERTICAL)
        shiftX = (left + right) / 2 - cx
        shiftY = (top + bottom) / 2 - cy
        camera.setShift((shiftX / (width / 2f)).toDouble(), (-shiftY / (height / 2f)).toDouble())
    }

    /** Screen pixels the picture is shifted by (see [onResize]); [project] does not include it. */
    private var shiftX = 0f
    private var shiftY = 0f

    companion object {
        const val FLIGHT_SECONDS = 0.35f
    }
}
