package com.ardeman.krida.congklak

import android.view.SurfaceView
import android.view.TextureView
import com.ardeman.krida.core.games.CongklakBoard
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.ui.VoxelStage
import com.google.android.filament.Camera
import com.google.android.filament.android.UiHelper
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the voxel congklak board with Filament on a [SurfaceView] (the game) or a
 * [TextureView] (the menu card's preview, which Compose can clip and snapshot). Seeds glide toward the
 * targets set with [setSeedTargets]; frames are only rendered while something changes.
 * Call everything on the main thread.
 */
class BoardRenderer private constructor(attach: (UiHelper) -> Unit) :
    VoxelStage(attach, CongklakGlb.build(), CongklakBoard.background) {

    constructor(surfaceView: SurfaceView) : this({ it.attachTo(surfaceView) })
    constructor(textureView: TextureView) : this({ it.attachTo(textureView) })

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

    init {
        ringEntities.values.forEach { show(it, false) }
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
        if (!placed) return false
        val t = min(1f, dt * EASE_PER_SECOND)
        var moving = false
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
        return moving
    }

    override fun onResize() {
        val aspect = width.toDouble() / height
        // Seat 1 looks from the other long side: turn the camera 180 degrees around the y axis.
        val flip = if (viewSeat == 1) -1f else 1f
        val eye = CongklakBoard.cameraEye.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        val at = CongklakBoard.cameraTarget.let { floatArrayOf(it[0] * flip, it[1], it[2] * flip) }
        camera.setProjection(CongklakBoard.CAMERA_HORIZONTAL_FOV_DEGREES.toDouble(), aspect, 0.5, 100.0, Camera.Fov.HORIZONTAL)
        camera.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            at[0].toDouble(), at[1].toDouble(), at[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        onPitsProjected?.invoke(List(CongklakGame.PITS) { CongklakBoard.pitAnchor(it).let { (x, y, z) -> project(x, y, z) } })
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
    }
}
