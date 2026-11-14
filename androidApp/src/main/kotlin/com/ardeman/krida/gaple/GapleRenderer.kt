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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot
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
    /** Gold rings under your playable hand tiles on the rim. */
    const val HAND_RINGS = GapleGame.HAND

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node(TABLE, glb.mesh(GapleScene.tableMesh()))
        GapleGame.SET.forEachIndexed { i, t -> glb.node(tile(i), glb.mesh(GapleScene.tileMesh(t.a, t.b))) }
        val back = glb.mesh(GapleScene.backMesh())
        for (i in 0 until BACKS) glb.node(back(i), back)
        val ring = glb.mesh(GapleScene.ringMesh())
        for (end in 0..1) glb.node(ring(end), ring)
        for (i in 0 until HAND_RINGS) glb.node(handRing(i), ring)
        return glb.build()
    }

    fun handRing(i: Int) = "handRing$i"
}

/**
 * Filament gaple table: the line of play on the felt, every hand as voxels on the rim
 * (yours face up; others face down, face up at a round end), and dashed gold rings on the
 * open ends and under your playable tiles. Main thread only.
 */
class GapleRenderer(textureView: TextureView) : VoxelStage(textureView, GapleGlb.build()) {

    private val tiles = IntArray(GapleGame.SET.size) { entity(GapleGlb.tile(it)) }
    private val backs = IntArray(GapleGlb.BACKS) { entity(GapleGlb.back(it)) }
    private val rings = IntArray(2) { entity(GapleGlb.ring(it)) }
    private val handRings = IntArray(GapleGlb.HAND_RINGS) { entity(GapleGlb.handRing(it)) }

    private var room: FloatArray? = null
    private var obstacles: List<FloatArray> = emptyList()
    /** Free box left by the HUD after carving [obstacles] (left, top, right, bottom). */
    private var free = floatArrayOf(0f, 0f, 1f, 1f)

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
    /** Ends whose rings are shown and pulsing while the player picks; empty when idle. */
    private var activeEnds: Set<Int> = emptySet()
    private var pulseTime = 0f
    /** Your hand tiles on the near rim: tile + world centre, for taps. */
    private var handAt: List<Pair<GapleTile, FloatArray>> = emptyList()
    private var playableHand: Set<GapleTile> = emptySet()
    private var pickingHand: GapleTile? = null
    private var handViewer = 0
    private var handState: GapleState? = null

    /** Gameplay zoom onto the open ends while a tile is picked; null = whole table. */
    private var watchPoints: List<FloatArray>? = null
    private var fovDegrees = GapleScene.CAMERA_VERTICAL_FOV_DEGREES
    private var shiftX = 0f
    private var shiftY = 0f
    private var targetFov = GapleScene.CAMERA_VERTICAL_FOV_DEGREES
    private var targetShiftX = 0f
    private var targetShiftY = 0f
    private var camSettled = true

    /** A tile flying from its player's side to its place in the line. */
    private class Flight(val entity: Int, val from: FloatArray, val to: FloatArray, var t: Float = 0f)
    private var flight: Flight? = null

    init {
        (tiles + backs + rings + handRings).forEach { show(it, false) }
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
     * Ease the camera onto [points] (the open ends while a tile is picked), or back to the
     * whole table when null. Unpicking or placing calls this with null.
     */
    fun watch(points: List<FloatArray>?) {
        // Already on (or easing to) the overview: a second null must not restart the ease.
        if (points == null && watchPoints == null) return
        watchPoints = points?.let { padded(it) }
        if (width > 1) {
            val view = computeView(watchPoints)
            targetFov = view[0]
            targetShiftX = view[1]
            targetShiftY = view[2]
            camSettled = false
            invalidate()
        }
    }

    /** Zoom onto the highlighted end rings, or ease back out when none. */
    fun watchPicking(on: Boolean) {
        val pts = if (on) activeEnds.mapNotNull { ringAt.getOrNull(it) } else null
        watch(pts?.takeIf { it.isNotEmpty() })
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
        // Hands on the rim: yours face up; others face down (face up at a round end).
        var b = 0
        val mine = state.hands.getOrNull(viewer).orEmpty()
        val mySide = GapleScene.side(viewer, viewer, state.players)
        val handCenters = ArrayList<Pair<GapleTile, FloatArray>>(mine.size)
        mine.forEachIndexed { i, tile ->
            val index = GapleGame.SET.indexOf(tile)
            if (index < 0 || shown[index]) return@forEachIndexed
            val lift = when {
                pickingHand == tile -> GapleScene.HAND_LIFT_PICKING
                tile in playableHand -> GapleScene.HAND_LIFT_PLAYABLE
                else -> 0f
            }
            val m = GapleScene.handMatrix(mySide, i, mine.size, lift, GapleScene.HAND_SCALE)
            setTransform(tiles[index], m)
            shown[index] = true
            handCenters.add(tile to floatArrayOf(m[12], m[13], m[14]))
        }
        handAt = handCenters
        handViewer = viewer
        handState = state
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
        placeHandRings()

        val ends = if (state.roundOver || state.line.isEmpty()) listOf(null, null) else listOf(layout.left, layout.right)
        ends.forEachIndexed { end, pose ->
            ringAt[end] = pose?.let(GapleScene::ringCenter)
        }
        refreshRings()
    }

    /**
     * Raises playable tiles and puts gold rings under them; [picking] gets the higher lift.
     * Call after [applyState] when the legal set changes.
     */
    fun highlightHand(playable: Collection<GapleTile>, picking: GapleTile?) {
        val same = playableHand == playable.toSet() && pickingHand == picking
        if (same) return
        playableHand = playable.toSet()
        pickingHand = picking
        val state = handState ?: return
        applyState(state, handViewer, animate = false)
    }

    private fun placeHandRings() {
        var r = 0
        for ((tile, c) in handAt) {
            if (tile !in playableHand && tile != pickingHand) continue
            if (r >= handRings.size) break
            val s = GapleScene.HAND_SCALE * if (tile == pickingHand) 1.15f else 1f
            setTransform(handRings[r], scaled(floatArrayOf(c[0], 0f, c[2]), s))
            show(handRings[r], true)
            r++
        }
        for (i in r until handRings.size) show(handRings[i], false)
    }

    /** Nearest hand tile under ([x], [y]) within [reach] px, or null. */
    fun handTileAt(x: Float, y: Float, reach: Float): GapleTile? {
        if (width <= 1 || handAt.isEmpty()) return null
        var best: GapleTile? = null
        var bestD = reach
        val shiftOk = shiftInProjection()
        for ((tile, c) in handAt) {
            val p = project(c[0], c[1], c[2])
            val s = if (shiftOk) p else floatArrayOf(p[0] + shiftX, p[1] + shiftY)
            val d = hypot(s[0] - x, s[1] - y)
            if (d < bestD) {
                bestD = d
                best = tile
            }
        }
        return best
    }

    /**
     * Shows and pulses only the given end rings (0 left, 1 right) so a picked tile's legal
     * spots are clear. Pass an empty list to hide them (idle / not picking).
     */
    fun highlightEnds(ends: Collection<Int>) {
        activeEnds = ends.toSet()
        pulseTime = 0f
        refreshRings()
    }

    private fun refreshRings() {
        for (end in 0..1) {
            val c = ringAt[end]
            if (c != null && end in activeEnds) {
                setTransform(rings[end], scaled(c, 1f))
                show(rings[end], true)
            } else {
                show(rings[end], false)
            }
        }
        invalidate()
    }

    /** Screen point of [end]'s ring (0 left, 1 right), or null when that end is not highlighted. */
    fun endOnScreen(end: Int): FloatArray? {
        if (end !in activeEnds) return null
        val at = ringAt[end] ?: return null
        val p = project(at[0], 0f, at[2])
        return if (shiftInProjection()) p else floatArrayOf(p[0] + shiftX, p[1] + shiftY)
    }

    /** Whether Filament's projection matrix already carries the shift (a symmetric one has 0 there). */
    private fun shiftInProjection(): Boolean {
        val m = camera.getProjectionMatrix(DoubleArray(16))
        return abs(m[8]) > 1e-6 || abs(m[9]) > 1e-6
    }

    override fun step(dt: Float): Boolean {
        var moving = stepCamera(dt)
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
        if (activeEnds.isNotEmpty()) {
            pulseTime += dt
            val s = 1f + 0.12f * sin(pulseTime * 6f)
            for (end in activeEnds) {
                val c = ringAt.getOrNull(end) ?: continue
                setTransform(rings[end], scaled(c, s))
            }
            moving = true
        }
        return moving || !camSettled
    }

    /** Ease FOV and shift toward the watch target. */
    private fun stepCamera(dt: Float): Boolean {
        if (camSettled || width <= 1) return false
        val k = 1f - exp(-min(dt, MAX_CAMERA_DT) / CAMERA_TAU)
        fovDegrees += (targetFov - fovDegrees) * k
        shiftX += (targetShiftX - shiftX) * k
        shiftY += (targetShiftY - shiftY) * k
        applyProjection()
        val done = abs(targetFov - fovDegrees) < 0.05f &&
            abs(targetShiftX - shiftX) < 0.5f && abs(targetShiftY - shiftY) < 0.5f
        if (done) {
            fovDegrees = targetFov
            shiftX = targetShiftX
            shiftY = targetShiftY
            applyProjection()
            camSettled = true
        }
        return !done
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
        // The free box left by the HUD (hand, pills, side columns).
        val cx = width / 2f
        val cy = height / 2f
        val room = room ?: floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        var left = room[0]
        var right = room[2]
        var top = room[1]
        var bottom = room[3]
        for (o in obstacles) {
            when {
                o[1] > cy -> bottom = min(bottom, o[1])
                o[3] < cy && o[0] < cx && o[2] > cx -> top = max(top, o[3])
                (o[0] + o[2]) / 2 < cx -> left = max(left, o[2])
                else -> right = min(right, o[0])
            }
        }
        free = floatArrayOf(left, top, right, bottom)
        if ((right - left) / 2 <= 1f || (bottom - top) / 2 <= 1f) return
        // Menu preview snaps; gameplay eases via [watch].
        val view = computeView(focus ?: watchPoints)
        if (focus != null || camSettled) {
            fovDegrees = view[0]
            shiftX = view[1]
            shiftY = view[2]
            targetFov = fovDegrees
            targetShiftX = shiftX
            targetShiftY = shiftY
            camSettled = true
        } else {
            targetFov = view[0]
            targetShiftX = view[1]
            targetShiftY = view[2]
        }
        applyProjection()
    }

    private fun applyProjection() {
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        camera.setProjection(fovDegrees.toDouble(), aspect, 0.5, 120.0, Camera.Fov.VERTICAL)
        camera.setShift((shiftX / (width / 2f)).toDouble(), (-shiftY / (height / 2f)).toDouble())
    }

    /**
     * Vertical FOV and pixel shift that frames [points] (or the whole table) in the free box.
     * Returns floatArrayOf(fov, shiftX, shiftY). Projects at the base FOV in code — never
     * through Filament's live matrix — so a zoomed-in camera cannot bake into the next fit.
     *
     * Overview fits around the look-at (screen centre) then shifts that centre into the free
     * box. The projected AABB mid sits below the look-at under perspective; centering on that
     * mid lifts the table and clips the far edge.
     */
    private fun computeView(points: List<FloatArray>?): FloatArray {
        val base = GapleScene.CAMERA_VERTICAL_FOV_DEGREES
        val cx = width / 2f
        val cy = height / 2f
        val left = free[0]; val top = free[1]; val right = free[2]; val bottom = free[3]
        val halfW = (right - left) / 2
        val halfH = (bottom - top) / 2
        if (halfW <= 1f || halfH <= 1f || width <= 1) {
            return floatArrayOf(base, 0f, 0f)
        }
        val tanBase = tan(base * PI.toFloat() / 360f)
        val aspect = width.toFloat() / height
        val eye = GapleScene.cameraEye
        val at = GapleScene.cameraTarget
        fun norm(v: FloatArray): FloatArray {
            val n = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }
        fun cross(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val fwd = norm(FloatArray(3) { at[it] - eye[it] })
        val rightV = norm(cross(fwd, floatArrayOf(0f, 1f, 0f)))
        val up = cross(rightV, fwd)
        fun screen(p: FloatArray): FloatArray {
            val dx = p[0] - eye[0]; val dy = p[1] - eye[1]; val dz = p[2] - eye[2]
            val depth = dx * fwd[0] + dy * fwd[1] + dz * fwd[2]
            val nx = (dx * rightV[0] + dy * rightV[1] + dz * rightV[2]) / (depth * tanBase * aspect)
            val ny = (dx * up[0] + dy * up[1] + dz * up[2]) / (depth * tanBase)
            return floatArrayOf(cx * (1 + nx), cy * (1 - ny))
        }
        val freeMidX = (left + right) / 2
        val freeMidY = (top + bottom) / 2
        if (points == null || points.isEmpty()) {
            // Felt only: rim may sit under the HUD so the playable table fills the strip.
            val shot = GapleScene.fitBounds().map(::screen)
            val needW = shot.maxOf { abs(it[0] - cx) }.coerceAtLeast(1f)
            val needH = shot.maxOf { abs(it[1] - cy) }.coerceAtLeast(1f)
            val k = min(halfW / needW, halfH / needH).coerceAtLeast(1e-3f)
            // May be wider than [base]: the felt has to fit the free box.
            val fov = (atan(tanBase / k) * 360f / PI.toFloat()).coerceAtLeast(MIN_FOV)
            return floatArrayOf(fov, freeMidX - cx, freeMidY - cy)
        }
        val shot = points.map(::screen)
        val midX = (shot.minOf { it[0] } + shot.maxOf { it[0] }) / 2
        val midY = (shot.minOf { it[1] } + shot.maxOf { it[1] }) / 2
        val needW = shot.maxOf { abs(it[0] - midX) }.coerceAtLeast(1f)
        val needH = shot.maxOf { abs(it[1] - midY) }.coerceAtLeast(1f)
        val k = FRAME_FILL * min(halfW / needW, halfH / needH)
        val overview = computeView(null)
        val overviewK = tanBase / tan(overview[0] * PI.toFloat() / 360f)
        // Never zoom out past the whole-table fit: a wide shot uses that view instead.
        if (k <= overviewK) return overview
        val fov = (atan(tanBase / k) * 360f / PI.toFloat()).coerceIn(MIN_FOV, base)
        val scale = tanBase / tan(fov * PI.toFloat() / 360f)
        val sx = freeMidX - (cx + (midX - cx) * scale)
        val sy = freeMidY - (cy + (midY - cy) * scale)
        return floatArrayOf(fov, sx, sy)
    }

    /** Corners around [points] so a single end ring still has air to breathe. */
    private fun padded(points: List<FloatArray>): List<FloatArray> {
        if (points.isEmpty()) return points
        val pad = 2.2f
        val x0 = points.minOf { it[0] } - pad
        val x1 = points.maxOf { it[0] } + pad
        val z0 = points.minOf { it[2] } - pad
        val z1 = points.maxOf { it[2] } + pad
        val y = points.map { it[1] }.average().toFloat()
        return listOf(
            floatArrayOf(x0, y, z0), floatArrayOf(x1, y, z0),
            floatArrayOf(x0, y, z1), floatArrayOf(x1, y, z1),
        )
    }

    companion object {
        const val FLIGHT_SECONDS = 0.35f
        private const val MIN_FOV = 18f
        private const val FRAME_FILL = 0.7f
        private const val CAMERA_TAU = 0.35f
        private const val MAX_CAMERA_DT = 1f / 30f
    }
}
