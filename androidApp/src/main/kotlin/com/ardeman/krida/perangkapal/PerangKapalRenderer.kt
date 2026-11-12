package com.ardeman.krida.perangkapal

import android.view.TextureView
import com.ardeman.krida.congklak.GlbWriter
import com.ardeman.krida.core.games.PerangKapalBoard
import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.PerangKapalShip
import com.ardeman.krida.core.games.PerangKapalShot
import com.ardeman.krida.core.games.PerangKapalState
import com.ardeman.krida.ui.VoxelStage
import com.google.android.filament.Camera
import java.nio.ByteBuffer
import kotlin.math.hypot

object PerangKapalGlb {
    const val OCEAN = "ocean"
    const val RADAR = "radar"
    fun hull(seat: Int, i: Int) = "hull${seat}_$i"
    fun hitPeg(i: Int) = "hit$i"
    fun missPeg(i: Int) = "miss$i"
    const val RING = "ring"
    const val HULLS = 15
    const val PEGS = 50

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node(OCEAN, glb.mesh(PerangKapalBoard.gridMesh(0f)))
        glb.node(RADAR, glb.mesh(PerangKapalBoard.gridMesh(PerangKapalBoard.RADAR_OFFSET_X)))
        val cell = PerangKapalBoard.shipMesh(PerangKapalShip(4, listOf(0)), 0f)
        val hullMesh = glb.mesh(cell)
        for (seat in 0..1) for (i in 0 until HULLS) glb.node(hull(seat, i), hullMesh)
        val hit = glb.mesh(PerangKapalBoard.pegMesh(0, hit = true, 0f))
        val miss = glb.mesh(PerangKapalBoard.pegMesh(0, hit = false, 0f))
        for (i in 0 until PEGS) {
            glb.node(hitPeg(i), hit)
            glb.node(missPeg(i), miss)
        }
        glb.node(RING, glb.mesh(PerangKapalBoard.ringMesh(0, 0f)))
        return glb.build()
    }
}

/** Filament perang kapal board: ocean (left) and radar (right). Main thread only. */
class PerangKapalRenderer(textureView: TextureView) : VoxelStage(textureView, PerangKapalGlb.build()) {

    private val radar = entity(PerangKapalGlb.RADAR)
    private val myHull = IntArray(PerangKapalGlb.HULLS) { entity(PerangKapalGlb.hull(0, it)) }
    private val foeHull = IntArray(PerangKapalGlb.HULLS) { entity(PerangKapalGlb.hull(1, it)) }
    private val hitPegs = IntArray(PerangKapalGlb.PEGS) { entity(PerangKapalGlb.hitPeg(it)) }
    private val missPegs = IntArray(PerangKapalGlb.PEGS) { entity(PerangKapalGlb.missPeg(it)) }
    private val ring = entity(PerangKapalGlb.RING)

    private var battle = false
    private var room: FloatArray? = null
    private var obstacles: List<FloatArray> = emptyList()
    private val base = PerangKapalBoard.cellCenter(0, 0f)

    init {
        (myHull + foeHull + hitPegs + missPegs).forEach { show(it, false) }
        show(ring, false)
        show(radar, false)
    }

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

    fun applyState(state: PerangKapalState, mySeat: Int) {
        val foe = 1 - mySeat
        showBattle(!state.placing)
        placeHulls(myHull, state.fleets.getOrElse(mySeat) { emptyList() }, 0f)
        placeHulls(
            foeHull,
            state.fleets.getOrElse(foe) { emptyList() },
            if (battle) PerangKapalBoard.RADAR_OFFSET_X else 0f,
        )
        placeShots(state.shots.getOrElse(foe) { emptyList() }, 0f, startHit = 0, startMiss = 0)
        placeShots(
            state.shots.getOrElse(mySeat) { emptyList() },
            PerangKapalBoard.RADAR_OFFSET_X,
            startHit = PerangKapalGlb.PEGS / 2,
            startMiss = PerangKapalGlb.PEGS / 2,
        )
        invalidate()
    }

    fun showRing(cell: Int?, onRadar: Boolean) {
        if (cell == null) {
            show(ring, false)
            invalidate()
            return
        }
        val origin = if (onRadar) PerangKapalBoard.RADAR_OFFSET_X else 0f
        val c = PerangKapalBoard.cellCenter(cell, origin)
        setTransform(ring, translation(c[0] - base[0], 0f, c[2] - base[2]))
        show(ring, true)
        invalidate()
    }

    fun pickCell(sx: Float, sy: Float, onRadar: Boolean, reach: Float): Int? {
        if (onRadar && !battle) return null
        val origin = if (onRadar) PerangKapalBoard.RADAR_OFFSET_X else 0f
        var best = -1
        var bestD = reach
        for (cell in 0 until PerangKapalGame.CELLS) {
            val c = PerangKapalBoard.cellCenter(cell, origin)
            val p = project(c[0], PerangKapalBoard.TOP, c[2])
            val d = hypot(p[0] - sx, p[1] - sy)
            if (d < bestD) {
                bestD = d
                best = cell
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun showBattle(on: Boolean) {
        if (battle == on) return
        battle = on
        show(radar, on)
        if (width > 1) onResize()
    }

    private fun placeHulls(entities: IntArray, fleet: List<PerangKapalShip>, originX: Float) {
        entities.forEach { show(it, false) }
        var i = 0
        for (ship in fleet) for (cell in ship.cells) {
            if (i >= entities.size) return
            val c = PerangKapalBoard.cellCenter(cell, originX)
            setTransform(entities[i], translation(c[0] - base[0], 0f, c[2] - base[2]))
            show(entities[i], true)
            i++
        }
    }

    private fun placeShots(shots: List<PerangKapalShot>, originX: Float, startHit: Int, startMiss: Int) {
        val hitEnd = (startHit + PerangKapalGlb.PEGS / 2).coerceAtMost(PerangKapalGlb.PEGS)
        val missEnd = (startMiss + PerangKapalGlb.PEGS / 2).coerceAtMost(PerangKapalGlb.PEGS)
        for (i in startHit until hitEnd) show(hitPegs[i], false)
        for (i in startMiss until missEnd) show(missPegs[i], false)
        var hi = startHit
        var mi = startMiss
        for (shot in shots) {
            val c = PerangKapalBoard.cellCenter(shot.cell, originX)
            val t = translation(c[0] - base[0], 0f, c[2] - base[2])
            if (shot.hit) {
                if (hi >= hitEnd) continue
                setTransform(hitPegs[hi], t)
                show(hitPegs[hi], true)
                hi++
            } else {
                if (mi >= missEnd) continue
                setTransform(missPegs[mi], t)
                show(missPegs[mi], true)
                mi++
            }
        }
    }

    private fun translation(x: Float, y: Float, z: Float) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, y, z, 1f,
    )

    override fun onResize() {
        val eye = PerangKapalBoard.cameraEye
        val target = PerangKapalBoard.cameraTarget
        val lookX = if (battle) PerangKapalBoard.RADAR_OFFSET_X / 2f else 0f
        camera.lookAt(
            (eye[0] + lookX).toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            (target[0] + lookX).toDouble(), target[1].toDouble(), target[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        val fov = if (battle) PerangKapalBoard.CAMERA_VERTICAL_FOV_DEGREES + 8f
        else PerangKapalBoard.CAMERA_VERTICAL_FOV_DEGREES
        camera.setProjection(fov.toDouble(), width.toDouble() / height.coerceAtLeast(1), 0.5, 80.0, Camera.Fov.VERTICAL)
    }
}
