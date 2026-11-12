package com.ardeman.krida.congklak

import android.view.TextureView
import com.ardeman.krida.core.games.CongklakBoard
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.ui.VoxelStage
import com.google.android.filament.Camera
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the voxel congklak board with Filament on a transparent [TextureView] (the game, or the
 * menu card's preview, which Compose clips and snapshots). Seeds glide toward the
 * targets set with [setSeedTargets]; frames are only rendered while something changes.
 * Call everything on the main thread.
 */
class BoardRenderer(textureView: TextureView) : VoxelStage(textureView, CongklakGlb.build()) {

    /** Pit centers on screen, in pixels; updated on resize. */
    var onPitsProjected: ((List<FloatArray>) -> Unit)? = null

    /** The seat on the near side of the board (0 or 1). */
    var viewSeat = 0
        set(value) {
            field = value
            if (width > 1) { onResize(); invalidate() }
        }

    private val seedEntities = IntArray(CongklakBoard.SEED_COUNT) { entity(CongklakGlb.seedName(it)) }
    /** Gold ring entity per playable pit; only the shown ones are in the scene. */
    private val ringEntities: Map<Int, Int> = (0 until CongklakGame.PITS).filterNot(CongklakGame::isStore)
        .associateWith { entity(CongklakGlb.ringName(it)) }
    private var shownRings = emptySet<Int>()

    private val current = Array(CongklakBoard.SEED_COUNT) { FloatArray(3) }
    private val target = Array(CongklakBoard.SEED_COUNT) { FloatArray(3) }
    private var placed = false

    private var watchPoints: List<FloatArray>? = null
    private var fovDegrees = CongklakBoard.CAMERA_HORIZONTAL_FOV_DEGREES
    private var shiftX = 0.0
    private var shiftY = 0.0
    private var targetFov = CongklakBoard.CAMERA_HORIZONTAL_FOV_DEGREES
    private var targetShiftX = 0.0
    private var targetShiftY = 0.0
    private var camSettled = true

    init {
        ringEntities.values.forEach { show(it, false) }
    }

    /**
     * Gameplay: ease the camera onto [points] (a whole sow path), or back to the whole board
     * when null. Prefer one call per move — retargeting every pit feels dizzy at low FPS.
     */
    fun watch(points: List<FloatArray>?) {
        // Already on (or easing to) the overview: a second null must not restart the ease.
        if (points == null && watchPoints == null) return
        watchPoints = points?.let { padded(it) }
        if (width > 1) {
            val view = computeView(watchPoints)
            targetFov = view[0]
            targetShiftX = view[1].toDouble()
            targetShiftY = view[2].toDouble()
            // Ease both ways: in onto the move and back out to the whole board.
            camSettled = false
            invalidate()
        }
    }

    /**
     * Sets where every seed should rest. The first call places them without animation.
     * [targets] holds x, y, z per seed, as [com.ardeman.krida.core.games.CongklakSeeds.targets] returns.
     */
    fun setSeedTargets(targets: FloatArray) {
        for (i in target.indices) targets.copyInto(target[i], 0, i * 3, i * 3 + 3)
        if (!placed) {
            for (i in target.indices) target[i].copyInto(current[i])
            placed = true
        }
        invalidate()
    }

    /** Shows gold rings around exactly these pits. */
    fun showRings(pits: Set<Int>) {
        if (pits == shownRings) return
        ringEntities.forEach { (pit, e) ->
            if (pit in pits && pit !in shownRings) show(e, true)
            if (pit !in pits && pit in shownRings) show(e, false)
        }
        shownRings = pits
    }

    /** Eases seeds toward their targets. Returns true while any seed is still moving. */
    override fun step(dt: Float): Boolean {
        var moving = stepCamera(dt)
        if (!placed) return moving
        val t = min(1f, dt * EASE_PER_SECOND)
        val m = FloatArray(16)
        for (i in current.indices) {
            val c = current[i]
            val g = target[i]
            var changed = false
            for (a in 0..2) {
                val d = g[a] - c[a]
                if (abs(d) > 0.002f) { c[a] += d * t; changed = true } else if (d != 0f) { c[a] = g[a]; changed = true }
            }
            if (changed || redrawPending) {
                if (changed) moving = true
                yawMatrix(CongklakBoard.yaw(i), c, m)
                setTransform(seedEntities[i], m)
            }
        }
        return moving || !camSettled
    }

    /** Ease FOV and shift toward the watch target. */
    private fun stepCamera(dt: Float): Boolean {
        if (camSettled || width <= 1) return false
        // Cap so a hitch never snaps the camera most of the way in one frame.
        val k = 1f - exp(-min(dt, MAX_CAMERA_DT) / CAMERA_TAU)
        fovDegrees += (targetFov - fovDegrees) * k
        shiftX += (targetShiftX - shiftX) * k
        shiftY += (targetShiftY - shiftY) * k
        applyProjection()
        // Labels and tap targets follow the camera every frame, not only once it settles.
        projectPits()
        val done = abs(targetFov - fovDegrees) < 0.05f &&
            abs(targetShiftX - shiftX) < 0.002 && abs(targetShiftY - shiftY) < 0.002
        if (done) {
            fovDegrees = targetFov
            shiftX = targetShiftX
            shiftY = targetShiftY
            applyProjection()
            camSettled = true
            projectPits()
        }
        return !done
    }

    override fun onResize() {
        aimCamera()
        camera.setShift(0.0, 0.0)
        camera.setProjection(
            CongklakBoard.CAMERA_HORIZONTAL_FOV_DEGREES.toDouble(),
            width.toDouble() / height,
            0.5,
            100.0,
            Camera.Fov.HORIZONTAL,
        )
        val view = computeView(watchPoints)
        if (camSettled) {
            fovDegrees = view[0]
            shiftX = view[1].toDouble()
            shiftY = view[2].toDouble()
            targetFov = fovDegrees
            targetShiftX = shiftX
            targetShiftY = shiftY
        } else {
            targetFov = view[0]
            targetShiftX = view[1].toDouble()
            targetShiftY = view[2].toDouble()
        }
        applyProjection()
        projectPits()
    }

    private fun aimCamera() {
        // Seat 1 looks from the other long side: turn the camera 180 degrees around the y axis.
        val flip = if (viewSeat == 1) -1f else 1f
        val eye = CongklakBoard.cameraEye.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        val at = CongklakBoard.cameraTarget.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        camera.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            at[0].toDouble(), at[1].toDouble(), at[2].toDouble(),
            0.0, 1.0, 0.0,
        )
    }

    private fun applyProjection() {
        camera.setProjection(fovDegrees.toDouble(), width.toDouble() / height, 0.5, 100.0, Camera.Fov.HORIZONTAL)
        // shiftX/shiftY are in NDC (2 across the view); Filament's shift is in whole views.
        camera.setShift(shiftX / 2, shiftY / 2)
    }

    private fun projectPits() {
        // Filament's projection matrix leaves out setShift, so add the shift (NDC) by hand.
        val dx = (shiftX * width / 2).toFloat()
        val dy = (shiftY * height / 2).toFloat()
        onPitsProjected?.invoke(
            List(CongklakGame.PITS) {
                CongklakBoard.pitAnchor(it).let { (x, y, z) -> project(x, y, z) }.let { (px, py) -> floatArrayOf(px + dx, py - dy) }
            },
        )
    }

    /**
     * Horizontal FOV and screen shift (NDC) that frames [points], or the whole board when null.
     * Returns floatArrayOf(fov, shiftX, shiftY).
     *
     * Projects at the opening FOV in code, never through Filament's live matrix — otherwise
     * a zoomed-in camera bakes into the next fit.
     */
    private fun computeView(points: List<FloatArray>?): FloatArray {
        val base = CongklakBoard.CAMERA_HORIZONTAL_FOV_DEGREES
        if (points == null || points.isEmpty() || width <= 1) {
            return floatArrayOf(base, 0f, 0f)
        }
        val cx = width / 2f
        val cy = height / 2f
        val tanBase = tan(base * PI.toFloat() / 360f)
        val aspect = width.toFloat() / height
        val flip = if (viewSeat == 1) -1f else 1f
        val eye = CongklakBoard.cameraEye.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        val at = CongklakBoard.cameraTarget.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        fun norm(v: FloatArray): FloatArray {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }
        fun cross(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val fwd = norm(FloatArray(3) { at[it] - eye[it] })
        val right = norm(cross(fwd, floatArrayOf(0f, 1f, 0f)))
        val up = cross(right, fwd)
        fun screen(x: Float, y: Float, z: Float): FloatArray {
            val dx = x - eye[0]; val dy = y - eye[1]; val dz = z - eye[2]
            val depth = dx * fwd[0] + dy * fwd[1] + dz * fwd[2]
            val nx = (dx * right[0] + dy * right[1] + dz * right[2]) / (depth * tanBase)
            val ny = (dx * up[0] + dy * up[1] + dz * up[2]) / (depth * tanBase / aspect)
            return floatArrayOf(cx * (1 + nx), cy * (1 - ny))
        }
        val shot = points.map { (x, y, z) -> screen(x, y, z) }
        val x0 = shot.minOf { it[0] }; val x1 = shot.maxOf { it[0] }
        val y0 = shot.minOf { it[1] }; val y1 = shot.maxOf { it[1] }
        val spanX = (x1 - x0).coerceAtLeast(1f)
        val spanY = (y1 - y0).coerceAtLeast(1f)
        val k = FRAME_FILL * min(width / spanX, height / spanY)
        val hi = (atan(tanBase / k) * 360f / PI.toFloat()).coerceIn(MIN_FOV, base)
        val dx = cx - (cx + ((x0 + x1) / 2 - cx) * k)
        val dy = cy - (cy + ((y0 + y1) / 2 - cy) * k)
        return floatArrayOf(hi, dx * 2f / width, -dy * 2f / height)
    }

    /** Corners around [points] so a single pit still has air to breathe. */
    private fun padded(points: List<FloatArray>): List<FloatArray> {
        if (points.isEmpty()) return points
        val pad = 1.1f
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

    private fun yawMatrix(yaw: Float, pos: FloatArray, out: FloatArray) {
        val c = cos(yaw)
        val s = sin(yaw)
        out.fill(0f)
        out[0] = c; out[2] = -s
        out[5] = 1f
        out[8] = s; out[10] = c
        out[12] = pos[0]; out[13] = pos[1]; out[14] = pos[2]; out[15] = 1f
    }

    companion object {
        private const val EASE_PER_SECOND = 14f
        private const val MIN_FOV = 18f
        private const val FRAME_FILL = 0.72f
        /** Slower ease so sowing stays comfortable when the phone drops frames. */
        private const val CAMERA_TAU = 0.5f
        private const val MAX_CAMERA_DT = 1f / 30f
    }
}
