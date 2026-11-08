package com.ardeman.krida.ulartangga

import android.view.SurfaceView
import android.view.TextureView
import com.ardeman.krida.congklak.GlbWriter
import com.ardeman.krida.core.games.UlarTanggaBoard
import com.ardeman.krida.ui.VoxelStage
import com.google.android.filament.Camera
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** The ular tangga board and one pawn per seat as one .glb; pawn nodes are named by [pieceName]. */
object UlarTanggaGlb {
    fun pieceName(seat: Int) = "piece$seat"
    const val DIE = "die"
    const val DIE_SHADOW = "dieShadow"

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node("board", glb.mesh(UlarTanggaBoard.boardMesh()))
        repeat(UlarTanggaBoard.PIECES) { glb.node(pieceName(it), glb.mesh(UlarTanggaBoard.pieceMesh(it))) }
        glb.node(DIE_SHADOW, glb.mesh(UlarTanggaBoard.dieShadowMesh()))
        glb.node(DIE, glb.mesh(UlarTanggaBoard.dieMesh()))
        return glb.build()
    }
}

/**
 * Draws the voxel ular tangga board with Filament. Pawns move along paths given to [move]
 * (a hop per square, a slide down a snake, a climb up a ladder). The die
 * waits on screen at [dieSpot]; [throwDie] throws it onto the board to land on a value and
 * [recallDie] brings it back. Frames are
 * only rendered while something moves. Call everything on the main thread.
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

    /** How hard a throw is: a tap tosses the die onto the near side, a hold-and-release throws it far. */
    enum class Toss { Short, Long }

    private enum class DieMode { Home, Thrown, Landed, Returning }

    /** A flight of the die's center from [from] to [to] and its turn from [q0] to [q1]. */
    private class Flight(
        val from: FloatArray,
        val to: FloatArray,
        val q0: FloatArray,
        val q1: FloatArray,
        val axis: FloatArray,
        val turns: Int,
        val seconds: Float,
        /** Arc height of each bounce, and its share of the time. */
        val heights: FloatArray,
        val shares: FloatArray,
    ) {
        var elapsed = 0f
        var landed = 0
    }

    private val die = entity(UlarTanggaGlb.DIE)
    private val dieShadow = entity(UlarTanggaGlb.DIE_SHADOW)
    private val restY = UlarTanggaBoard.DIE_REST_Y
    private var dieMode = DieMode.Home
    private var dieValue = 6
    /** Where the die waits on screen, in world space; set from [dieSpot]. */
    private val home = floatArrayOf(0f, 20f, 8f)
    private val shown = home.copyOf()
    private var dieRot = homeRotation(dieValue)
    private var flight: Flight? = null
    private var pressed = false
    private var shaking = false
    private var shakeTime = 0f
    /** Radians the held die has spun; it settles back to a whole turn when the hold ends. */
    private var spinAngle = 0f
    private var idleTime = 0f
    /** Eased 0..1 amounts of the press, the shake and the idle rocking, so none of them snaps. */
    private var pressAmt = 0f
    private var shakeAmt = 0f
    private var rockAmt = 0f
    /** The die's pose as last drawn (shake included), so a throw starts where the eye sees it. */
    private var drawnRot = dieRot
    private val drawnPos = home.copyOf()
    private var fovDegrees = UlarTanggaBoard.CAMERA_VERTICAL_FOV_DEGREES
    private var shiftX = 0.0
    private var shiftY = 0.0

    /** Called each time a thrown die lands on the board. */
    var onDieBounce: (() -> Unit)? = null

    /**
     * Where the die waits between rolls, on screen: center x, center y and size, in pixels.
     * The die is drawn there, in front of the board, so it reads as part of the HUD.
     */
    var dieSpot: FloatArray? = null
        set(value) {
            if (value != null && field?.contentEquals(value) == true) return
            field = value
            updateHome()
        }

    /** When true and the die waits on screen, it rocks gently to say it can be tapped. */
    var dieReady = false
        set(value) {
            if (field == value) return
            field = value
            idleTime = 0f
            invalidate()
        }

    init {
        // No die until the HUD gives it a spot (the menu's board preview never does).
        show(die, false)
        show(dieShadow, false)
        applyDie()
    }

    /** Presses the die where it waits; [shake] rattles it, as in a hand, until [throwDie]. */
    fun holdDie(held: Boolean, shake: Boolean = false) {
        if (dieMode != DieMode.Home) return
        if (shake && !shaking) { shakeTime = 0f; spinAngle = 0f }
        pressed = held
        shaking = held && shake
        invalidate()
    }

    /**
     * Throws the die from the screen onto the board so it lands showing [value]; returns how
     * long until it rests, in seconds. A [Toss.Long] flies farther, tumbles and bounces more.
     * It stays on the board until [recallDie].
     */
    fun throwDie(value: Int, toss: Toss): Float {
        pressed = false
        shaking = false
        // Leave from the pose on screen, mid-shake or not.
        drawnPos.copyInto(shown)
        dieRot = drawnRot
        pressAmt = 0f; shakeAmt = 0f; rockAmt = 0f; spinAngle = 0f
        dieValue = value
        val area = UlarTanggaBoard.dieLanding
        val long = toss == Toss.Long
        // A tap lands on the near (right) side of the board, a throw carries across it.
        val midX = (area[0] + area[2]) / 2
        val tx = if (long) area[0] + Random.nextFloat() * (midX - area[0]) else midX + Random.nextFloat() * (area[2] - midX)
        val tz = area[1] + Random.nextFloat() * (area[3] - area[1])
        val dx = tx - shown[0]
        val dz = tz - shown[2]
        val len = hypot(dx, dz).coerceAtLeast(0.01f)
        val heights = if (long) floatArrayOf(2.2f, 0.9f, 0.3f) else floatArrayOf(1.2f, 0.4f)
        val weights = heights.map { sqrt(it) }
        val shares = weights.map { it / weights.sum() }.toFloatArray()
        val yaw = (Random.nextInt(4) * 90f + (Random.nextFloat() - 0.5f) * 40f) * PI.toFloat() / 180f
        val seconds = if (long) 1.5f else 1.0f
        flight = Flight(
            from = shown.copyOf(),
            to = floatArrayOf(tx, restY, tz),
            q0 = dieRot,
            q1 = qMul(qAxis(0f, 1f, 0f, yaw), UlarTanggaBoard.dieRest(value)),
            // Rolling forward: about the level axis square to the travel.
            axis = floatArrayOf(dz / len, 0f, -dx / len),
            turns = if (long) 4 else 2,
            seconds = seconds,
            heights = heights,
            shares = shares,
        )
        dieMode = DieMode.Thrown
        show(dieShadow, true)
        invalidate()
        return seconds
    }

    /** Lifts the die off the board back to its place on screen, still showing its value. */
    fun recallDie() {
        if (dieMode != DieMode.Landed) return
        flight = Flight(
            from = shown.copyOf(), to = home.copyOf(), q0 = dieRot, q1 = homeRotation(dieValue),
            axis = floatArrayOf(1f, 0f, 0f), turns = 0, seconds = 0.45f,
            heights = floatArrayOf(0f), shares = floatArrayOf(1f),
        )
        dieMode = DieMode.Returning
        invalidate()
    }

    private fun stepDie(dt: Float): Boolean {
        val f = flight
        if (f != null) {
            f.elapsed += dt
            val k = (f.elapsed / f.seconds).coerceAtMost(1f)
            if (dieMode == DieMode.Returning) {
                val e = 1 - (1 - k) * (1 - k)
                for (i in 0..2) shown[i] = f.from[i] + (f.to[i] - f.from[i]) * e
                dieRot = qSlerp(f.q0, f.q1, e)
            } else {
                // Across, slowing down; down in bounces, the first falling from the screen.
                val glide = 1 - (1 - k) * (1 - k)
                shown[0] = f.from[0] + (f.to[0] - f.from[0]) * glide
                shown[2] = f.from[2] + (f.to[2] - f.from[2]) * glide
                var start = 0f
                for (i in f.shares.indices) {
                    val end = start + f.shares[i]
                    if (k <= end || i == f.shares.lastIndex) {
                        val u = ((k - start) / f.shares[i]).coerceIn(0f, 1f)
                        val base = if (i == 0) f.from[1] + (restY - f.from[1]) * u * u else restY
                        shown[1] = base + 4 * f.heights[i] * u * (1 - u)
                        while (f.landed < i) { f.landed++; onDieBounce?.invoke() }
                        break
                    }
                    start = end
                }
                // Tumble: whole turns about the roll axis, unwinding onto the resting face.
                val settle = minOf(1f, k / 0.9f)
                val angle = -f.turns * 2 * PI.toFloat() * (1 - settle) * (1 - settle)
                dieRot = qMul(qAxis(f.axis[0], f.axis[1], f.axis[2], angle), qSlerp(f.q0, f.q1, settle))
            }
            if (k >= 1f) {
                flight = null
                f.to.copyInto(shown)
                dieRot = f.q1
                if (dieMode == DieMode.Returning) {
                    dieMode = DieMode.Home
                    show(dieShadow, false)
                } else {
                    while (f.landed < f.shares.size) { f.landed++; onDieBounce?.invoke() }
                    dieMode = DieMode.Landed
                }
                idleTime = 0f
            }
            applyDie()
            return true
        }
        if (dieMode != DieMode.Home) return false
        home.copyInto(shown)
        // Ease toward the targets: quick to press, a little slower to build up a shake.
        pressAmt = approach(pressAmt, if (pressed) 1f else 0f, dt, 0.06f)
        shakeAmt = approach(shakeAmt, if (shaking) 1f else 0f, dt, if (shaking) 0.18f else 0.08f)
        rockAmt = approach(rockAmt, if (dieReady && !pressed) 1f else 0f, dt, 0.15f)
        val easing = pressAmt + shakeAmt + rockAmt > 0.002f
        if (shaking || shakeAmt > 0.002f) shakeTime += dt
        val turn = 2 * PI.toFloat()
        if (shaking) {
            // Spin up from about one turn a second to three over the first moment of the hold.
            spinAngle += dt * turn * shakeAmt * (1f + 2f * minOf(1f, shakeTime / 0.8f))
        } else if (spinAngle != 0f) {
            // Let go without a throw: finish the turn it is on and come to rest.
            val rest = kotlin.math.ceil(spinAngle / turn) * turn
            spinAngle = approach(spinAngle, rest, dt, 0.12f)
            if (spinAngle == rest) spinAngle = 0f
        }
        if (dieReady || rockAmt > 0.002f) idleTime += dt
        applyDie()
        return pressed || dieReady || easing || spinAngle != 0f
    }

    /** Exponential ease of [value] toward [target] with time constant [tau] seconds. */
    private fun approach(value: Float, target: Float, dt: Float, tau: Float): Float {
        val v = target + (value - target) * kotlin.math.exp(-dt / tau)
        return if (kotlin.math.abs(v - target) < 0.001f) target else v
    }

    /** Sets the die and its shadow: pressed, shaken or rocking while it waits on screen. */
    private fun applyDie() {
        var q = dieRot
        var scale = 1f
        val pos = shown.copyOf()
        if (dieMode == DieMode.Home) {
            if (spinAngle != 0f) {
                // Spinning in the air before the throw: whole turns about a tilted axis that
                // slowly sweeps round, so every face flashes past.
                val p = shakeTime * 1.3f
                val ax = 0.7f * sin(p)
                val az = 0.7f * kotlin.math.cos(p)
                val ay = 0.6f
                val n = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                q = qMul(qAxis(ax / n, ay / n, az / n, spinAngle), q)
            }
            if (shakeAmt > 0f) {
                // A small toss up and down while it spins.
                val s = shakeTime * 2 * PI.toFloat()
                val bob = UlarTanggaBoard.DIE * 0.12f * shakeAmt
                pos[0] += bob * 0.5f * sin(s * 2.3f)
                pos[1] += bob * kotlin.math.abs(sin(s * 3.1f))
            }
            if (rockAmt > 0f) q = qMul(qAxis(0f, 1f, 0f, rockAmt * 0.18f * sin(idleTime * 2 * PI.toFloat() / 1.6f)), q)
            scale = 1f - 0.1f * pressAmt
        }
        drawnRot = q
        pos.copyInto(drawnPos)
        val m = qMatrix(q)
        for (i in 0..10) if (i % 4 != 3) m[i] *= scale
        m[12] = pos[0]; m[13] = pos[1]; m[14] = pos[2]
        setTransform(die, m)
        // The shadow on the tiles shrinks as the die rises.
        val sc = (1f - (shown[1] - restY) * 0.08f).coerceIn(0.3f, 1f)
        setTransform(dieShadow, floatArrayOf(sc, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, sc, 0f, shown[0], UlarTanggaBoard.TOP, shown[2], 1f))
    }

    /** Puts [home] on the line of sight through [dieSpot], at the depth that gives the die its size. */
    private fun updateHome() {
        val spot = dieSpot ?: return
        if (width <= 1) return
        val eye = UlarTanggaBoard.cameraEye
        val at = UlarTanggaBoard.cameraTarget
        val fwd = norm(FloatArray(3) { at[it] - eye[it] })
        val right = norm(cross(fwd, floatArrayOf(0f, 1f, 0f)))
        val up = cross(right, fwd)
        val tanV = tan(fovDegrees * PI.toFloat() / 360f)
        val tanH = tanV * width / height
        val nx = (spot[0] * 2f / width - 1f - shiftX.toFloat())
        val ny = (1f - spot[1] * 2f / height - shiftY.toFloat())
        val depth = UlarTanggaBoard.DIE * (height / 2f) / (tanV * spot[2])
        for (i in 0..2) home[i] = eye[i] + (fwd[i] + right[i] * nx * tanH + up[i] * ny * tanV) * depth
        if (dieMode == DieMode.Home) {
            home.copyInto(shown)
            applyDie()
        }
        show(die, true)
        invalidate()
    }

    override fun step(dt: Float): Boolean {
        var moving = stepDie(dt)
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
        // Find the narrowest view (largest board) whose board bounds still fit the room the HUD
        // leaves, then shift the image so they sit in the middle of that room.
        val (l, t, r, b) = insets.toList()
        val roomW = (width - l - r).coerceAtLeast(width * 0.3f)
        val roomH = (height - t - b).coerceAtLeast(height * 0.3f)
        val bounds = UlarTanggaBoard.bounds()
        fun fits(fov: Float): Boolean {
            camera.setProjection(fov.toDouble(), width.toDouble() / height, 0.5, 120.0, Camera.Fov.VERTICAL)
            val box = screenBox(bounds)
            return box[2] - box[0] <= roomW && box[3] - box[1] <= roomH
        }
        var lo = 5f
        var hi = 90f
        repeat(20) {
            val mid = (lo + hi) / 2
            if (fits(mid)) hi = mid else lo = mid
        }
        fits(hi)
        val box = screenBox(bounds)
        val dx = (l + roomW / 2) - (box[0] + box[2]) / 2
        val dy = (t + roomH / 2) - (box[1] + box[3]) / 2
        fovDegrees = hi
        shiftX = dx * 2.0 / width
        shiftY = -dy * 2.0 / height
        camera.setShift(shiftX, shiftY)
        updateHome()
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
        /** The die as it waits on screen: [value] up, turned to show two more faces. */
        fun homeRotation(value: Int): FloatArray =
            qMul(qMul(qAxis(1f, 0f, 0f, 0.2f), qAxis(0f, 1f, 0f, 0.6f)), UlarTanggaBoard.dieRest(value))

        fun cross(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        fun norm(v: FloatArray): FloatArray {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }

        /** Quaternions are x, y, z, w. */
        fun qAxis(x: Float, y: Float, z: Float, angle: Float): FloatArray {
            val s = sin(angle / 2)
            return floatArrayOf(x * s, y * s, z * s, cos(angle / 2))
        }

        fun qMul(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
        )

        fun qSlerp(a: FloatArray, b0: FloatArray, t: Float): FloatArray {
            var d = a[0] * b0[0] + a[1] * b0[1] + a[2] * b0[2] + a[3] * b0[3]
            val b = if (d < 0f) { d = -d; FloatArray(4) { -b0[it] } } else b0
            if (d > 0.9995f) {
                val q = FloatArray(4) { a[it] + (b[it] - a[it]) * t }
                val n = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
                return FloatArray(4) { q[it] / n }
            }
            val th = acos(d)
            val sa = sin((1 - t) * th) / sin(th)
            val sb = sin(t * th) / sin(th)
            return FloatArray(4) { a[it] * sa + b[it] * sb }
        }

        /** Column-major 4×4 rotation of unit quaternion [q]. */
        fun qMatrix(q: FloatArray): FloatArray {
            val (x, y, z, w) = q.toList()
            return floatArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f,
                0f, 0f, 0f, 1f,
            )
        }

        fun dist(a: FloatArray, b: FloatArray): Float {
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
