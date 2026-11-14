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
import kotlin.math.sqrt
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
    /** Ocean cell under the finger while placing; null when none. Reuses the gold ring. */
    private var hoverCell: Int? = null
    /** Pixel shift so the look-at lands in the free HUD box (Filament's matrix omits setShift). */
    private var shiftX = 0f
    private var shiftY = 0f

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
        if (last != null) {
            hoverCell = null
            setTransform(ring, at(if (state.lastSeat == mySeat) 1 else 0, last, 0f))
            show(ring, true)
        } else {
            applyHoverRing()
        }
        invalidate()
    }

    /**
     * Gold ring on the ocean cell under the finger while placing, so a mistap next to a ship
     * is obvious (tap a ship cell to turn it). Cleared when [cell] is null or in battle.
     */
    fun setHover(cell: Int?) {
        val next = cell.takeUnless { battle }
        if (hoverCell == next) return
        hoverCell = next
        applyHoverRing()
        invalidate()
    }

    private fun applyHoverRing() {
        val cell = hoverCell
        if (battle || cell == null) {
            show(ring, false)
        } else {
            setTransform(ring, at(0, cell, 0f))
            show(ring, true)
        }
    }

    /** The cell of the radar (in battle) or of the ocean (while placing) under a screen point. */
    fun pickCell(sx: Float, sy: Float, onRadar: Boolean): Int? {
        if (onRadar != battle) return null
        val origin = if (onRadar) PerangKapalBoard.RADAR_OFFSET_X else 0f
        fun center(cell: Int): FloatArray {
            val c = PerangKapalBoard.cellCenter(cell, origin)
            val p = project(c[0], PerangKapalBoard.TOP, c[2])
            // Filament's projection matrix leaves out setShift; add it by hand (see Gaple/Congklak).
            return if (shiftInProjection()) p else floatArrayOf(p[0] + shiftX, p[1] + shiftY)
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

    /** Whether Filament's projection matrix already carries the shift. */
    private fun shiftInProjection(): Boolean {
        val m = camera.getProjectionMatrix(DoubleArray(16))
        return abs(m[8]) > 1e-6 || abs(m[9]) > 1e-6
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
        camera.setShift(0.0, 0.0)
        camera.setProjection(base, aspect, 0.5, 80.0, Camera.Fov.VERTICAL)

        // Same idea as ludo / ular tangga: largest play grid in `room` that misses the HUD
        // boxes. In battle the radar is the play grid (must clear obstacles); the mini ocean
        // only has to stay inside the room (it may sit under the left tiles).
        val room = room ?: focus?.let { floatArrayOf(0f, 0f, width.toFloat(), height.toFloat()) } ?: return
        val cx = width / 2f
        val cy = height / 2f
        val play = focus ?: PerangKapalBoard.bounds(if (battle) PerangKapalBoard.RADAR_OFFSET_X else 0f)
        val playShot = play.map { project(it[0], it[1], it[2]) }
        val roomShot = if (battle && focus == null) {
            playShot + PerangKapalBoard.miniBounds().map { project(it[0], it[1], it[2]) }
        } else {
            playShot
        }
        fun place(k: Float): FloatArray? {
            fun scaled(pts: List<FloatArray>) = pts.map {
                floatArrayOf(cx + (it[0] - cx) * k, cy + (it[1] - cy) * k)
            }
            val roomPts = scaled(roomShot)
            val x0 = roomPts.minOf { it[0] }; val x1 = roomPts.maxOf { it[0] }
            val y0 = roomPts.minOf { it[1] }; val y1 = roomPts.maxOf { it[1] }
            val loX = room[0] - x0; val hiX = room[2] - x1
            val loY = room[1] - y0; val hiY = room[3] - y1
            if (loX > hiX || loY > hiY) return null
            val playPts = scaled(playShot)
            val midX = (loX + hiX) / 2f; val midY = (loY + hiY) / 2f
            var best: FloatArray? = null
            var bestD = Float.MAX_VALUE
            for (i in 0..FIT_STEPS) for (j in 0..FIT_STEPS) {
                val sx = loX + (hiX - loX) * i / FIT_STEPS
                val sy = loY + (hiY - loY) * j / FIT_STEPS
                val d = (sx - midX) * (sx - midX) + (sy - midY) * (sy - midY)
                if (d >= bestD) continue
                val px0 = playPts.minOf { it[0] } + sx; val px1 = playPts.maxOf { it[0] } + sx
                val py0 = playPts.minOf { it[1] } + sy; val py1 = playPts.maxOf { it[1] } + sy
                if (obstacles.any { box -> px0 < box[2] && px1 > box[0] && py0 < box[3] && py1 > box[1] }) continue
                best = floatArrayOf(sx, sy)
                bestD = d
            }
            return best
        }
        var lo = 0.05f
        var hi = 20f
        repeat(20) {
            val mid = sqrt(lo * hi)
            if (place(mid) != null) lo = mid else hi = mid
        }
        val k = lo
        val shift = place(k) ?: floatArrayOf(0f, 0f)
        val fov = Math.toDegrees(2 * atan(tan(Math.toRadians(base / 2)) / k))
        shiftX = shift[0]
        shiftY = shift[1]
        camera.setProjection(fov, aspect, 0.5, 80.0, Camera.Fov.VERTICAL)
        camera.setShift((shiftX / (width / 2f)).toDouble(), (-shiftY / (height / 2f)).toDouble())
    }

    private companion object {
        const val FIT_STEPS = 24
    }
}
