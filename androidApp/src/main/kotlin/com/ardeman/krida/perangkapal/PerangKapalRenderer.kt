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
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

object PerangKapalGlb {
    const val OCEAN = "ocean"
    const val RADAR = "radar"
    /** Ship [kind] of grid 0 (your ocean) or 1 (sunk, on the radar), across or up the grid. */
    fun ship(grid: Int, kind: Int, horizontal: Boolean) = "ship${grid}_${kind}_${if (horizontal) "h" else "v"}"
    fun hitPeg(i: Int) = "hit$i"
    fun missPeg(i: Int) = "miss$i"
    const val RING = "ring"
    /** Cells of one fleet (5 + 4 + 3 + 3 + 2). */
    const val HULLS = 17
    /** Per grid: every ship cell can be hit, every other cell missed. */
    const val HITS = HULLS
    const val MISSES = PerangKapalGame.CELLS - HULLS

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node(OCEAN, glb.mesh(PerangKapalBoard.gridMesh(0f)))
        glb.node(RADAR, glb.mesh(PerangKapalBoard.gridMesh(PerangKapalBoard.RADAR_OFFSET_X)))
        for (kind in 0 until PerangKapalGame.KINDS) for (horizontal in listOf(true, false)) {
            val mesh = glb.mesh(PerangKapalBoard.shipMesh(kind, horizontal))
            for (grid in 0..1) glb.node(ship(grid, kind, horizontal), mesh)
        }
        val hit = glb.mesh(PerangKapalBoard.pegMesh(0, hit = true, 0f))
        val miss = glb.mesh(PerangKapalBoard.pegMesh(0, hit = false, 0f))
        for (i in 0 until HITS * 2) glb.node(hitPeg(i), hit)
        for (i in 0 until MISSES * 2) glb.node(missPeg(i), miss)
        glb.node(RING, glb.mesh(PerangKapalBoard.ringMesh(0, 0f)))
        return glb.build()
    }
}

/**
 * Filament perang kapal board: your ocean while placing, then the radar to fire at with your
 * ocean small beside it. Main thread only.
 */
class PerangKapalRenderer(textureView: TextureView) : VoxelStage(textureView, PerangKapalGlb.build()) {

    private val ocean = entity(PerangKapalGlb.OCEAN)
    private val radar = entity(PerangKapalGlb.RADAR)
    /** By grid, then `kind * 2`, plus 1 when the ship runs up the grid. */
    private val ships = Array(2) { grid ->
        IntArray(PerangKapalGame.KINDS * 2) { entity(PerangKapalGlb.ship(grid, it / 2, it % 2 == 0)) }
    }
    private val hitPegs = IntArray(PerangKapalGlb.HITS * 2) { entity(PerangKapalGlb.hitPeg(it)) }
    private val missPegs = IntArray(PerangKapalGlb.MISSES * 2) { entity(PerangKapalGlb.missPeg(it)) }
    private val ring = entity(PerangKapalGlb.RING)

    private var battle = false
    private var room: FloatArray? = null

    /**
     * Points (x, y, z) to fit instead of the whole grid, for the menu preview. The fit is
     * centred on the camera's target, so keep them around the grid's centre.
     */
    var focus: List<FloatArray>? = null
    private var obstacles: List<FloatArray> = emptyList()
    /** Cell 0 of the ocean: the ship, peg and ring meshes are built there and moved from it. */
    private val base = PerangKapalBoard.cellCenter(0, 0f)

    init {
        ships.forEach { grid -> grid.forEach { show(it, false) } }
        (hitPegs + missPegs).forEach { show(it, false) }
        show(ring, false)
        show(radar, false)
    }

    /** Fits the grids between the HUD boxes on the left and on the right of [room]. */
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
     * Shows [state] as [mySeat] sees it: own ships and incoming shots on the ocean; own shots
     * and the opponent's sunk ships on the radar. A gold ring marks the last shot.
     */
    fun applyState(state: PerangKapalState, mySeat: Int) {
        val foe = 1 - mySeat
        showBattle(!state.placing)
        val mine = state.fleets.getOrElse(mySeat) { emptyList() }
        val sunk = if (battle) state.fleets.getOrElse(foe) { emptyList() } else emptyList()
        placeShips(0, mine)
        placeShips(1, sunk)
        placeShots(state.shots.getOrElse(foe) { emptyList() }, PerangKapalGame.occupied(mine), grid = 0)
        placeShots(state.shots.getOrElse(mySeat) { emptyList() }, PerangKapalGame.occupied(sunk), grid = 1)
        val last = state.lastCell.takeIf { battle && state.lastHit != null }
        if (last == null) show(ring, false) else {
            setTransform(ring, at(if (state.lastSeat == mySeat) 1 else 0, last, 0f))
            show(ring, true)
        }
        invalidate()
    }

    /** The cell of the radar (in battle) or of the ocean (while placing) under a screen point. */
    fun pickCell(sx: Float, sy: Float, onRadar: Boolean): Int? {
        if (onRadar != battle) return null
        val origin = if (onRadar) PerangKapalBoard.RADAR_OFFSET_X else 0f
        fun center(cell: Int): FloatArray {
            val c = PerangKapalBoard.cellCenter(cell, origin)
            return project(c[0], PerangKapalBoard.TOP, c[2])
        }
        // Within three quarters of a cell of its centre, so a tap beside the grid is ignored.
        val a = center(0)
        val b = center(1)
        var bestD = hypot(b[0] - a[0], b[1] - a[1]) * 0.75f
        var best = -1
        for (cell in 0 until PerangKapalGame.CELLS) {
            val p = center(cell)
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
        setTransform(ocean, oceanMatrix(0f, 0f, 0f))
        if (width > 1) onResize()
    }

    private fun placeShips(grid: Int, fleet: List<PerangKapalShip>) {
        ships[grid].forEach { show(it, false) }
        for (ship in fleet) {
            val entity = ships[grid].getOrNull(ship.kind * 2 + if (ship.horizontal) 0 else 1) ?: continue
            setTransform(entity, at(grid, ship.bow, 0f))
            show(entity, true)
        }
    }

    /** Pegs for [shots] on grid 0 (ocean) or 1 (radar); a peg on a ship in [decks] stands on it. */
    private fun placeShots(shots: List<PerangKapalShot>, decks: Set<Int>, grid: Int) {
        val startHit = grid * PerangKapalGlb.HITS
        val startMiss = grid * PerangKapalGlb.MISSES
        val hitEnd = startHit + PerangKapalGlb.HITS
        val missEnd = startMiss + PerangKapalGlb.MISSES
        for (i in startHit until hitEnd) show(hitPegs[i], false)
        for (i in startMiss until missEnd) show(missPegs[i], false)
        var hi = startHit
        var mi = startMiss
        for (shot in shots) {
            val t = at(grid, shot.cell, if (shot.cell in decks) PerangKapalBoard.SHIP_H else 0f)
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

    /** Moves a mesh built on ocean cell 0 to [cell] of grid 0 (ocean) or 1 (radar), [lift] up. */
    private fun at(grid: Int, cell: Int, lift: Float): FloatArray {
        val c = PerangKapalBoard.cellCenter(cell, if (grid == 1) PerangKapalBoard.RADAR_OFFSET_X else 0f)
        val x = c[0] - base[0]
        val z = c[2] - base[2]
        return if (grid == 0) oceanMatrix(x, lift, z) else floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            x, lift, z, 1f,
        )
    }

    /** In battle the ocean and everything on it is small and sits left of the radar. */
    private fun oceanMatrix(x: Float, y: Float, z: Float): FloatArray {
        val s = if (battle) PerangKapalBoard.MINI_SCALE else 1f
        val o = PerangKapalBoard.miniOffset
        val ox = if (battle) o[0] else 0f
        val oy = if (battle) o[1] else 0f
        val oz = if (battle) o[2] else 0f
        return floatArrayOf(
            s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, s, 0f,
            ox + s * x, oy + s * y, oz + s * z, 1f,
        )
    }

    override fun onResize() {
        val eye = PerangKapalBoard.cameraEye
        val target = PerangKapalBoard.cameraTarget
        // In battle the camera looks at the radar.
        val lookX = if (battle) PerangKapalBoard.RADAR_OFFSET_X else 0f
        camera.lookAt(
            (eye[0] + lookX).toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            (target[0] + lookX).toDouble(), target[1].toDouble(), target[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val base = PerangKapalBoard.CAMERA_VERTICAL_FOV_DEGREES.toDouble()
        camera.setProjection(base, aspect, 0.5, 80.0, Camera.Fov.VERTICAL)

        // The narrowest field of view that keeps the grid to play on between the HUD columns:
        // the free space is taken evenly around the screen centre, where the camera looks. In
        // battle that grid is the radar; the small ocean may reach under the left column.
        // The menu preview has no HUD: its focus fills the whole view.
        val room = room ?: focus?.let { floatArrayOf(0f, 0f, width.toFloat(), height.toFloat()) } ?: return
        val cx = width / 2f
        val cy = height / 2f
        var left = room[0]
        var right = room[2]
        for (o in obstacles) {
            if ((o[0] + o[2]) / 2 < cx) left = max(left, o[2]) else right = min(right, o[0])
        }
        val halfW = min(cx - left, right - cx)
        val halfH = min(cy - room[1], room[3] - cy)
        if (halfW <= 1f || halfH <= 1f) return
        val corners = focus ?: PerangKapalBoard.bounds(if (battle) PerangKapalBoard.RADAR_OFFSET_X else 0f)
        val shot = corners.map { project(it[0], it[1], it[2]) }
        val needW = shot.maxOf { abs(it[0] - cx) }.coerceAtLeast(1f)
        val needH = shot.maxOf { abs(it[1] - cy) }.coerceAtLeast(1f)
        // Scaling the picture by k around the centre is the same as dividing the tangent by k.
        var k = min(halfW / needW, halfH / needH)
        if (battle) {
            // The small ocean must not leave the room on the left.
            val reach = cx - PerangKapalBoard.miniBounds().minOf { project(it[0], it[1], it[2])[0] }
            if (reach > 0f) k = min(k, (cx - room[0]) / reach)
        }
        val fov = Math.toDegrees(2 * atan(tan(Math.toRadians(base / 2)) / k))
        camera.setProjection(fov, aspect, 0.5, 80.0, Camera.Fov.VERTICAL)
    }
}
