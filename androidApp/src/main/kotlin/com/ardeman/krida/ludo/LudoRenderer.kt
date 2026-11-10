package com.ardeman.krida.ludo

import android.view.TextureView
import com.ardeman.krida.congklak.GlbWriter
import com.ardeman.krida.core.games.LudoBoard
import com.ardeman.krida.core.games.LudoGame
import com.ardeman.krida.core.games.UlarTanggaBoard
import com.ardeman.krida.core.games.UlarTanggaDiceAnimation
import com.ardeman.krida.ui.VoxelStage
import com.google.android.filament.Camera
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.tan

/** Board plus four tokens per colour and a die; tokens are [pieceName]. */
object LudoGlb {
    fun pieceName(color: Int, pawn: Int) = "piece${color}_$pawn"
    const val DIE = "die"
    const val DIE_SHADOW = "dieShadow"
    const val TOKENS = LudoGame.COLORS * LudoGame.PAWNS

    fun build(): ByteBuffer {
        val glb = GlbWriter()
        glb.node("board", glb.mesh(LudoBoard.boardMesh()))
        for (color in 0 until LudoGame.COLORS) for (pawn in 0 until LudoGame.PAWNS) {
            glb.node(pieceName(color, pawn), glb.mesh(LudoBoard.pieceMesh(color)))
        }
        // Same dadu as ular tangga so both games match the brand die.
        glb.node(DIE_SHADOW, glb.mesh(UlarTanggaBoard.dieShadowMesh()))
        glb.node(DIE, glb.mesh(UlarTanggaBoard.dieMesh()))
        return glb.build()
    }
}

/**
 * Draws the voxel Ludo board with Filament. Tokens hop along paths given to [move]. The die
 * waits on screen at [dieSpot]; [throwDie] throws it onto the board. Call on the main thread.
 */
class LudoRenderer(textureView: TextureView) : VoxelStage(textureView, LudoGlb.build()) {

    private class Motion(val path: List<FloatArray>, val seconds: Float, val hop: Float) {
        var elapsed = 0f
        val lengths = FloatArray(path.size).also { l ->
            for (i in 1 until path.size) l[i] = l[i - 1] + dist(path[i - 1], path[i])
        }
    }

    fun fitBoard(room: FloatArray, obstacles: List<FloatArray>) {
        if (room.contentEquals(this.room) && obstacles.size == this.obstacles.size &&
            obstacles.indices.all { obstacles[it].contentEquals(this.obstacles[it]) }) return
        this.room = room
        this.obstacles = obstacles
        if (width > 1) { onResize(); invalidate() }
    }

    private var room: FloatArray? = null
    /** Instant menu framing; null while playing (see [watch]). */
    private var focus: List<FloatArray>? = null
    /** Gameplay framing that eases in [step]; null = whole board. */
    private var watchPoints: List<FloatArray>? = null
    private var targetFov = LudoBoard.CAMERA_VERTICAL_FOV_DEGREES
    private var targetShiftX = 0.0
    private var targetShiftY = 0.0
    private var camSettled = true

    /** Menu preview: snap the camera onto [points] (no easing). */
    fun frame(points: List<FloatArray>) {
        focus = points
        watchPoints = null
        if (width > 1) { onResize(); invalidate() }
    }

    /**
     * Gameplay: ease the camera onto [points] (token path, die landing), or back to the whole
     * board when null. Call before a move and again with null when it has settled.
     */
    fun watch(points: List<FloatArray>?) {
        focus = null
        watchPoints = points?.let { padded(it) }
        if (width > 1) {
            val view = computeView(watchPoints)
            targetFov = view[0]
            targetShiftX = view[1].toDouble()
            targetShiftY = view[2].toDouble()
            camSettled = false
            invalidate()
        }
    }

    private var obstacles: List<FloatArray> = emptyList()

    private val entities = Array(LudoGame.COLORS) { color ->
        IntArray(LudoGame.PAWNS) { pawn -> entity(LudoGlb.pieceName(color, pawn)) }
    }
    private val position = Array(LudoGlb.TOKENS) { FloatArray(3) }
    private val motions = arrayOfNulls<Motion>(LudoGlb.TOKENS)
    private var seatColors: List<Int> = emptyList()

    private fun index(seat: Int, pawn: Int) = seat * LudoGame.PAWNS + pawn

    /** Shows tokens for [colors.size] seats; hides the rest of each colour's spare tokens. */
    fun setSeats(colors: List<Int>) {
        if (colors == seatColors) return
        for (color in 0 until LudoGame.COLORS) {
            val seat = colors.indexOf(color)
            for (pawn in 0 until LudoGame.PAWNS) {
                show(entities[color][pawn], seat >= 0)
            }
        }
        seatColors = colors
        invalidate()
    }

    fun place(seat: Int, pawn: Int, xyz: FloatArray) {
        val i = index(seat, pawn)
        motions[i] = null
        xyz.copyInto(position[i])
        apply(seat, pawn)
        invalidate()
    }

    /** Screen pixel of [seat]'s [pawn] (origin top-left of the view). */
    fun tokenOnScreen(seat: Int, pawn: Int): FloatArray {
        val p = position[index(seat, pawn)]
        return project(p[0], p[1], p[2])
    }

    fun move(seat: Int, pawn: Int, path: List<FloatArray>, seconds: Float, hop: Float = 0f) {
        val i = index(seat, pawn)
        motions[i] = Motion(listOf(position[i].copyOf()) + path, seconds.coerceAtLeast(0.01f), hop)
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

    private val die = entity(LudoGlb.DIE)
    private val dieShadow = entity(LudoGlb.DIE_SHADOW)
    private val restY = LudoBoard.TOP + UlarTanggaBoard.DIE / 2
    private var dieMode = DieMode.Home
    private var dieValue = 6
    /** Where the die waits on screen, in world space; set from [dieSpot]. */
    private val home = floatArrayOf(0f, 20f, 8f)
    private val shown = home.copyOf()
    private var idleOrigin: FloatArray? = null
    private var dieRot = homeRotation(dieValue)
    private var flight: Flight? = null
    private var pressed = false
    private var shaking = false
    private var heldX = 0f
    private var heldY = 0f
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
    private var fovDegrees = LudoBoard.CAMERA_VERTICAL_FOV_DEGREES
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
        if (!held) { heldX = 0f; heldY = 0f }
        invalidate()
    }

    /** Follows the finger in screen pixels, keeping the die inside the viewport. */
    fun moveHeldDie(x: Float, y: Float) {
        val spot = dieSpot ?: return
        if (!pressed || dieMode != DieMode.Home) return
        heldX = x.coerceIn(-spot[0] + spot[2], maxOf(-spot[0] + spot[2], width - spot[0] - spot[2]))
        heldY = y.coerceIn(-spot[1] + spot[2], maxOf(-spot[1] + spot[2], height - spot[1] - spot[2]))
        applyDie()
        invalidate()
    }

    /**
     * Throws the die from the screen onto the board so it lands showing [value]; returns how
     * long until it rests, in seconds. A [Toss.Long] flies farther, tumbles and bounces more.
     * It stays on the board until [recallDie].
     * [seat] is the throwing player's seat, used to seed deterministic animation so all clients
     * see the same trajectory and rotation.
     */
    fun throwDie(value: Int, toss: Toss, origin: FloatArray? = null, seat: Int = 0): Float {
        pressed = false
        shaking = false
        // Leave from the pose on screen, mid-shake or not.
        drawnPos.copyInto(shown)
        heldX = 0f; heldY = 0f
        dieRot = drawnRot
        // Opponents throw from their player tile, at the same depth as the held die.
        if (origin != null) diePosition(origin).copyInto(shown)
        pressAmt = 0f; shakeAmt = 0f; rockAmt = 0f; spinAngle = 0f
        dieValue = value
        val long = toss == Toss.Long

        // Compute deterministic landing position and rotation from seat and roll value.
        // This ensures all clients show the same animation without protocol changes.
        val animation = UlarTanggaDiceAnimation.compute(seat, value, long)
        val tx = animation.landX
        val tz = animation.landZ
        val yaw = animation.spinYaw

        val dx = tx - shown[0]
        val dz = tz - shown[2]
        val len = hypot(dx, dz).coerceAtLeast(0.01f)
        val heights = if (long) floatArrayOf(2.2f, 0.9f, 0.3f) else floatArrayOf(1.2f, 0.4f)
        val weights = heights.map { sqrt(it) }
        val shares = weights.map { it / weights.sum() }.toFloatArray()
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

    private fun diePosition(origin: FloatArray?): FloatArray {
        val spot = dieSpot
        if (origin == null || spot == null) return home.copyOf()
        val eye = LudoBoard.cameraEye
        val at = LudoBoard.cameraTarget
        val fwd = norm(FloatArray(3) { at[it] - eye[it] })
        val right = norm(cross(fwd, floatArrayOf(0f, 1f, 0f)))
        val up = cross(right, fwd)
        val units = UlarTanggaBoard.DIE / spot[2]
        return FloatArray(3) { home[it] + (right[it] * (origin[0] - spot[0]) - up[it] * (origin[1] - spot[1])) * units }
    }

    /** Returns to the next player's box, or the local control when [origin] is null. */
    fun recallDie(origin: FloatArray? = null) {
        idleOrigin = origin
        if (dieMode == DieMode.Home) {
            diePosition(idleOrigin).copyInto(shown)
            applyDie()
            invalidate()
        }
        if (dieMode != DieMode.Landed) return
        flight = Flight(
            from = shown.copyOf(), to = diePosition(idleOrigin), q0 = dieRot, q1 = homeRotation(dieValue),
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
        diePosition(idleOrigin).copyInto(shown)
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
            dieSpot?.let { spot ->
                val eye = LudoBoard.cameraEye
                val at = LudoBoard.cameraTarget
                val fwd = norm(FloatArray(3) { at[it] - eye[it] })
                val right = norm(cross(fwd, floatArrayOf(0f, 1f, 0f)))
                val up = cross(right, fwd)
                val units = UlarTanggaBoard.DIE / spot[2]
                for (i in 0..2) pos[i] += (right[i] * heldX - up[i] * heldY) * units
            }
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
        setTransform(dieShadow, floatArrayOf(sc, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, sc, 0f, shown[0], LudoBoard.TOP, shown[2], 1f))
    }

    /** Puts [home] on the line of sight through [dieSpot], at the depth that gives the die its size. */
    private fun updateHome() {
        val spot = dieSpot ?: return
        if (width <= 1) return
        val eye = LudoBoard.cameraEye
        val at = LudoBoard.cameraTarget
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
            diePosition(idleOrigin).copyInto(shown)
            applyDie()
        }
        show(die, true)
        invalidate()
    }

    override fun step(dt: Float): Boolean {
        var moving = stepDie(dt) || stepCamera(dt)
        for (i in motions.indices) {
            val m = motions[i] ?: continue
            m.elapsed += dt
            val t = (m.elapsed / m.seconds).coerceAtMost(1f)
            val total = m.lengths.last()
            val at = t * total
            var j = 1
            while (j < m.path.lastIndex && m.lengths[j] < at) j++
            val a = m.path[j - 1]
            val b = m.path[j]
            val span = m.lengths[j] - m.lengths[j - 1]
            val u = if (span <= 0f) 1f else ((at - m.lengths[j - 1]) / span).coerceIn(0f, 1f)
            val p = position[i]
            for (k in 0..2) p[k] = a[k] + (b[k] - a[k]) * u
            p[1] += m.hop * 4 * t * (1 - t)
            apply(i / LudoGame.PAWNS, i % LudoGame.PAWNS)
            if (t >= 1f) motions[i] = null else moving = true
        }
        return moving || motions.any { it != null } || !camSettled
    }

    /** Ease FOV and shift toward [targetFov] / [targetShiftX]/[targetShiftY]. */
    private fun stepCamera(dt: Float): Boolean {
        if (camSettled || width <= 1) return false
        val k = 1f - exp(-dt / CAMERA_TAU)
        fovDegrees += (targetFov - fovDegrees) * k
        shiftX += (targetShiftX - shiftX) * k
        shiftY += (targetShiftY - shiftY) * k
        camera.setProjection(fovDegrees.toDouble(), width.toDouble() / height, 0.5, 120.0, Camera.Fov.VERTICAL)
        camera.setShift(shiftX / 2, shiftY / 2)
        updateHome()
        val done = abs(targetFov - fovDegrees) < 0.05f &&
            abs(targetShiftX - shiftX) < 0.002 && abs(targetShiftY - shiftY) < 0.002
        if (done) {
            fovDegrees = targetFov
            shiftX = targetShiftX
            shiftY = targetShiftY
            camera.setProjection(fovDegrees.toDouble(), width.toDouble() / height, 0.5, 120.0, Camera.Fov.VERTICAL)
            camera.setShift(shiftX / 2, shiftY / 2)
            camSettled = true
        }
        return !done
    }

    private fun apply(seat: Int, pawn: Int) {
        if (seat !in seatColors.indices) return
        val color = seatColors[seat]
        val p = position[index(seat, pawn)]
        setTransform(
            entities[color][pawn],
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, p[0], p[1], p[2], 1f),
        )
    }

    override fun onResize() {
        val eye = LudoBoard.cameraEye
        val at = LudoBoard.cameraTarget
        camera.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            at[0].toDouble(), at[1].toDouble(), at[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        camera.setShift(0.0, 0.0)
        val base = LudoBoard.CAMERA_VERTICAL_FOV_DEGREES
        camera.setProjection(base.toDouble(), width.toDouble() / height, 0.5, 120.0, Camera.Fov.VERTICAL)
        // Menu [frame] snaps; gameplay [watch] keeps easing toward its target.
        val points = focus ?: watchPoints
        val view = computeView(points)
        if (focus != null || camSettled) {
            fovDegrees = view[0]
            shiftX = view[1].toDouble()
            shiftY = view[2].toDouble()
            targetFov = fovDegrees
            targetShiftX = shiftX
            targetShiftY = shiftY
            camSettled = true
        } else {
            targetFov = view[0]
            targetShiftX = view[1].toDouble()
            targetShiftY = view[2].toDouble()
        }
        camera.setProjection(fovDegrees.toDouble(), width.toDouble() / height, 0.5, 120.0, Camera.Fov.VERTICAL)
        // shiftX/shiftY are in NDC (2 across the view); Filament's shift is in whole views.
        camera.setShift(shiftX / 2, shiftY / 2)
        updateHome()
    }

    /**
     * FOV and screen shift (NDC) that frames [points], or the whole board when null.
     * Returns floatArrayOf(fov, shiftX, shiftY).
     */
    private fun computeView(points: List<FloatArray>?): FloatArray {
        val base = LudoBoard.CAMERA_VERTICAL_FOV_DEGREES
        val cx = width / 2f
        val cy = height / 2f
        val hulls = LudoBoard.bounds().map { part -> convexHull(part.map { (x, y, z) -> project(x, y, z) }) }
        val room = this.room ?: floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        val tanBase = tan(base * PI.toFloat() / 360f)
        fun scaleFor(fov: Float) = tanBase / tan(fov * PI.toFloat() / 360f)
        fun place(k: Float): FloatArray? {
            val shapes = hulls.map { hull -> hull.map { floatArrayOf(cx + (it[0] - cx) * k, cy + (it[1] - cy) * k) } }
            val all = shapes.flatten()
            val x0 = all.minOf { it[0] }; val x1 = all.maxOf { it[0] }
            val y0 = all.minOf { it[1] }; val y1 = all.maxOf { it[1] }
            val loX = room[0] - x0; val hiX = room[2] - x1
            val loY = room[1] - y0; val hiY = room[3] - y1
            if (loX > hiX || loY > hiY) return null
            val midX = (loX + hiX) / 2; val midY = (loY + hiY) / 2
            var best: FloatArray? = null
            var bestDistance = Float.MAX_VALUE
            for (i in 0..FIT_STEPS) for (j in 0..FIT_STEPS) {
                val dx = loX + (hiX - loX) * i / FIT_STEPS
                val dy = loY + (hiY - loY) * j / FIT_STEPS
                val distance = (dx - midX) * (dx - midX) + (dy - midY) * (dy - midY)
                if (distance >= bestDistance) continue
                if (obstacles.any { box -> shapes.any { overlaps(it, dx, dy, box) } }) continue
                best = floatArrayOf(dx, dy)
                bestDistance = distance
            }
            return best
        }
        val hi: Float
        val dx: Float
        val dy: Float
        if (points != null && points.isNotEmpty()) {
            val shot = points.map { (x, y, z) -> project(x, y, z) }
            val x0 = shot.minOf { it[0] }; val x1 = shot.maxOf { it[0] }
            val y0 = shot.minOf { it[1] }; val y1 = shot.maxOf { it[1] }
            val spanX = (x1 - x0).coerceAtLeast(1f)
            val spanY = (y1 - y0).coerceAtLeast(1f)
            val k = FRAME_FILL * min(width / spanX, height / spanY)
            hi = (atan(tanBase / k) * 360f / PI.toFloat()).coerceIn(MIN_FOV, MAX_FOV)
            dx = cx - (cx + ((x0 + x1) / 2 - cx) * k)
            dy = cy - (cy + ((y0 + y1) / 2 - cy) * k)
        } else {
            var lo = MIN_FOV
            var top = MAX_FOV
            repeat(20) {
                val mid = (lo + top) / 2
                if (place(scaleFor(mid)) != null) top = mid else lo = mid
            }
            hi = top
            val best = place(scaleFor(hi)) ?: floatArrayOf(0f, 0f)
            dx = best[0]
            dy = best[1]
        }
        return floatArrayOf(hi, dx * 2f / width, -dy * 2f / height)
    }

    /** Corners around [points] so a short hop still has air to breathe. */
    private fun padded(points: List<FloatArray>): List<FloatArray> {
        if (points.isEmpty()) return points
        val pad = LudoBoard.CELL * 2.2f
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

    /** Convex outline of [points], counter-clockwise on screen (monotone chain). */
    private fun convexHull(points: List<FloatArray>): List<FloatArray> {
        val sorted = points.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun turn(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = mutableListOf<FloatArray>()
        for (p in sorted) {
            while (lower.size >= 2 && turn(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
            lower += p
        }
        val upper = mutableListOf<FloatArray>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && turn(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** Whether convex [shape], moved by [dx], [dy], overlaps [box] (left, top, right, bottom). */
    private fun overlaps(shape: List<FloatArray>, dx: Float, dy: Float, box: FloatArray): Boolean {
        if (shape.maxOf { it[0] } + dx <= box[0] || shape.minOf { it[0] } + dx >= box[2]) return false
        if (shape.maxOf { it[1] } + dy <= box[1] || shape.minOf { it[1] } + dy >= box[3]) return false
        val corners = listOf(box[0] to box[1], box[2] to box[1], box[2] to box[3], box[0] to box[3])
        for (i in shape.indices) {
            val a = shape[i]
            val b = shape[(i + 1) % shape.size]
            val nx = b[1] - a[1]
            val ny = a[0] - b[0]
            var sMin = Float.MAX_VALUE; var sMax = -Float.MAX_VALUE
            for (p in shape) { val d = (p[0] + dx) * nx + (p[1] + dy) * ny; sMin = minOf(sMin, d); sMax = maxOf(sMax, d) }
            var bMin = Float.MAX_VALUE; var bMax = -Float.MAX_VALUE
            for ((x, y) in corners) { val d = x * nx + y * ny; bMin = minOf(bMin, d); bMax = maxOf(bMax, d) }
            if (sMax <= bMin || bMax <= sMin) return false
        }
        return true
    }

    private companion object {
        const val MIN_FOV = 5f
        const val MAX_FOV = 90f
        /** Places tried along each axis when fitting the board. */
        const val FIT_STEPS = 24
        /** Share of the view the framed points take, leaving room for what sits around them. */
        const val FRAME_FILL = 0.72f
        /** How quickly the camera eases onto a [watch] target (seconds). */
        const val CAMERA_TAU = 0.28f

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
