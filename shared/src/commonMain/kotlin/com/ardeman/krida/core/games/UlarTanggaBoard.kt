package com.ardeman.krida.core.games

import com.ardeman.krida.core.voxel.VoxelMesh
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ular tangga board as both platforms draw it, in world units (y up, the camera on +z).
 * Ten rows of ten tiles; square 1 is bottom-left, nearest the camera, and rows run
 * boustrophedon up to 100 at the far left. Pieces wait on a start pad left of square 1
 * (square 0). Pure geometry, so the Android (Filament) and iOS (RealityKit) boards match.
 */
object UlarTanggaBoard {
    const val PIECES = 4

    /** Camera, shared so both platforms frame the board the same way; the board fills the height. */
    val cameraEye = floatArrayOf(-0.8f, 33f, 15f)
    val cameraTarget = floatArrayOf(-0.8f, 0f, 1.2f)
    const val CAMERA_VERTICAL_FOV_DEGREES = 38f

    /** Background behind the board, sRGB (the same cream as congklak). */
    val background = floatArrayOf(0.96f, 0.93f, 0.86f)

    private const val TILE = 2f
    private const val HALF = TILE * UlarTanggaGame.SIZE / 2
    private const val RIM = 0.6f
    private const val FRAME_TOP = 0.5f
    private const val GAP = 0.06f

    /** Height of the tile tops; pieces stand on it. */
    const val TOP = 0.7f

    private val PAD_CENTER = floatArrayOf(-HALF - RIM - 1.7f, HALF - TILE / 2)
    private const val PAD_HALF = 1.3f

    private const val PIECE_SCALE = 1.25f
    private const val DIGIT_PIXEL = 0.14f
    private const val DIGIT_RISE = 0.025f

    private val WOOD = floatArrayOf(0.64f, 0.42f, 0.24f)
    private val INK = floatArrayOf(0.36f, 0.25f, 0.18f)
    private val CREAM = floatArrayOf(0.99f, 0.95f, 0.85f)
    private val TILE_COLORS = arrayOf(
        floatArrayOf(0.98f, 0.80f, 0.62f), // peach
        floatArrayOf(0.74f, 0.88f, 0.66f), // green
        floatArrayOf(0.70f, 0.84f, 0.94f), // blue
        floatArrayOf(0.99f, 0.86f, 0.48f), // yellow
    )
    private val GOLD = floatArrayOf(0.95f, 0.72f, 0.29f)
    private val RAIL = floatArrayOf(0.78f, 0.50f, 0.26f)
    private val RUNG = floatArrayOf(0.93f, 0.72f, 0.42f)
    private val SNAKE_COLORS = arrayOf(
        floatArrayOf(0.30f, 0.66f, 0.30f) to floatArrayOf(0.96f, 0.84f, 0.30f),
        floatArrayOf(0.62f, 0.36f, 0.74f) to floatArrayOf(0.98f, 0.70f, 0.80f),
        floatArrayOf(0.90f, 0.46f, 0.20f) to floatArrayOf(0.40f, 0.22f, 0.14f),
    )
    private val WHITE = floatArrayOf(1f, 1f, 1f)
    private val BLACK = floatArrayOf(0.1f, 0.08f, 0.07f)
    private val TONGUE = floatArrayOf(0.86f, 0.16f, 0.18f)
    /** The die follows the brand die (docs/brand/die.svg): Krem body, Kayu gelap pips, and the four colours on the 4. */
    private val DIE_WHITE = floatArrayOf(1f, 0.969f, 0.925f)
    /** Milder than the default, since the die turns and its baked shading turns with it. */
    private val DIE_SHADE = floatArrayOf(1f, 0.9f, 0.94f)
    private val PIP_WOOD = floatArrayOf(0.561f, 0.333f, 0.165f)
    /** The 4's pips, by spot in [DIE_PIPS]: red and green on one diagonal, blue and yellow on the other. */
    private val PIP_FOUR = arrayOf(
        floatArrayOf(0.839f, 0.271f, 0.239f),
        floatArrayOf(0.184f, 0.435f, 0.820f),
        floatArrayOf(0.941f, 0.706f, 0.161f),
        floatArrayOf(0.243f, 0.604f, 0.310f),
    )
    private val SHADOW = floatArrayOf(0.85f, 0.81f, 0.73f)

    /** Piece colors by seat, sRGB: red, blue, green, yellow. The UI uses them too. */
    private val SEAT_COLORS = arrayOf(
        floatArrayOf(0.90f, 0.27f, 0.24f),
        floatArrayOf(0.24f, 0.50f, 0.90f),
        floatArrayOf(0.26f, 0.70f, 0.34f),
        floatArrayOf(0.98f, 0.78f, 0.18f),
    )

    fun seatColor(seat: Int): FloatArray = SEAT_COLORS[seat % SEAT_COLORS.size]

    /**
     * Corners of a box around everything drawn (frame, start pad, a pawn's height): x, y, z
     * each. Platforms fit the camera to them when the HUD leaves less room than the full height.
     */
    fun bounds(): List<FloatArray> {
        val e = HALF + RIM
        val x0 = PAD_CENTER[0] - PAD_HALF - 0.2f
        return listOf(0f, TOP + 1.3f * PIECE_SCALE).flatMap { y ->
            listOf(floatArrayOf(x0, y, -e), floatArrayOf(e, y, -e), floatArrayOf(x0, y, e), floatArrayOf(e, y, e))
        }
    }

    /** Center of [square]'s tile top (1..100), or of the start pad for 0: x, z. */
    private fun center(square: Int): FloatArray =
        if (square == 0) PAD_CENTER
        else floatArrayOf(
            (UlarTanggaGame.column(square) - 4.5f) * TILE,
            (4.5f - UlarTanggaGame.row(square)) * TILE,
        )

    /** Point on the tile top at the middle of [square], for labels: x, y, z. */
    fun squareAnchor(square: Int): FloatArray = center(square).let { floatArrayOf(it[0], TOP, it[1]) }

    /**
     * Where [seat]'s piece stands on [square]: x, y, z. Pieces on a shared square (and always
     * on the start pad) step aside into a 2×2 grid so none hides another.
     */
    fun pieceSpot(square: Int, seat: Int, shared: Boolean): FloatArray {
        val c = center(square)
        if (!shared && square != 0) return floatArrayOf(c[0], TOP, c[1])
        val d = if (square == 0) 0.62f else 0.5f
        val dx = if (seat % 2 == 0) -d else d
        val dz = if (seat < 2) d else -d
        return floatArrayOf(c[0] + dx, TOP, c[1] + dz)
    }

    /** Waypoints a piece follows down the snake whose head is on [head], head to tail: x, y, z each. */
    fun snakePath(head: Int): List<FloatArray> {
        val tail = requireNotNull(UlarTanggaGame.SNAKES[head]) { "no snake at $head" }
        val curve = SnakeCurve(center(head), center(tail))
        val n = max(4, (curve.length / 0.5f).roundToInt())
        return List(n + 1) { i -> curve.at(i.toFloat() / n).let { floatArrayOf(it[0], TOP, it[1]) } }
    }

    /** Waypoints a piece follows up the ladder whose foot is on [foot]: x, y, z each. */
    fun ladderPath(foot: Int): List<FloatArray> {
        val top = requireNotNull(UlarTanggaGame.LADDERS[foot]) { "no ladder at $foot" }
        return listOf(squareAnchor(foot), squareAnchor(top))
    }

    /** A voxel pawn in [seat]'s color, standing on the origin. */
    fun pieceMesh(seat: Int): VoxelMesh {
        val c = seatColor(seat)
        val dark = floatArrayOf(c[0] * 0.75f, c[1] * 0.75f, c[2] * 0.75f)
        val k = PIECE_SCALE
        return VoxelMesh().apply {
            box(floatArrayOf(-0.38f * k, 0f, -0.38f * k), floatArrayOf(0.38f * k, 0.2f * k, 0.38f * k), dark, bottom = true)
            box(floatArrayOf(-0.26f * k, 0.2f * k, -0.26f * k), floatArrayOf(0.26f * k, 0.78f * k, 0.26f * k), c)
            box(floatArrayOf(-0.32f * k, 0.78f * k, -0.32f * k), floatArrayOf(0.32f * k, 1.3f * k, 0.32f * k), c)
            // A white glint on the head's front corner, so the pawn reads as round.
            box(floatArrayOf(-0.24f * k, 1.12f * k, 0.26f * k), floatArrayOf(-0.1f * k, 1.24f * k, 0.34f * k), WHITE)
        }
    }

    /** Edge of the die, in world units. */
    const val DIE = 1.8f

    /**
     * Where a thrown die may come to rest on the board: the range its center keeps to, min x,
     * min z, max x, max z, a tile in from the frame. It rests with its center at [DIE_REST_Y].
     */
    val dieLanding: FloatArray = floatArrayOf(-HALF + TILE, -HALF + TILE, HALF - TILE, HALF - TILE)

    /** Height of a resting die's center on the tiles. */
    const val DIE_REST_Y = TOP + DIE / 2

    /**
     * The face normal of each value, by value - 1: 1 up, 6 down, 2 and 5 front and back, 3 and
     * 4 right and left, so opposite faces add up to seven.
     */
    internal val DIE_FACES = arrayOf(
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(-1f, 0f, 0f),
        floatArrayOf(0f, 0f, -1f),
        floatArrayOf(0f, -1f, 0f),
    )

    /**
     * The die's rotation (a unit quaternion x, y, z, w) that turns [value]'s face up. Platforms
     * end a roll on it, after any turn about the vertical axis.
     */
    fun dieRest(value: Int): FloatArray {
        val h = sqrt(0.5f)
        return when (value) {
            1 -> floatArrayOf(0f, 0f, 0f, 1f)
            2 -> floatArrayOf(-h, 0f, 0f, h) // -90° about x: front to top
            3 -> floatArrayOf(0f, 0f, h, h) // +90° about z: right to top
            4 -> floatArrayOf(0f, 0f, -h, h)
            5 -> floatArrayOf(h, 0f, 0f, h)
            6 -> floatArrayOf(1f, 0f, 0f, 0f)
            else -> throw IllegalArgumentException("no face $value")
        }
    }

    /** A white voxel die centered on the origin; the one and the four have red pips, as a dadu. */
    fun dieMesh(): VoxelMesh {
        val mesh = VoxelMesh()
        val half = DIE / 2
        mesh.box(floatArrayOf(-half, -half, -half), floatArrayOf(half, half, half), DIE_WHITE, DIE_SHADE, bottom = true)
        val pip = DIE * 0.16f
        val spread = DIE * 0.27f
        DIE_FACES.forEachIndexed { i, n ->
            val value = i + 1
            // Two axes across the face, with a × b = -n so the quad winds outward like box().
            val a = if (n[0] != 0f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(1f, 0f, 0f)
            val b = cross(a, n)
            // As on the brand die: a big single pip, and bigger pips on the 4.
            val size = when (value) { 1 -> pip * 2.1f; 4 -> pip * 1.3f; else -> pip }
            DIE_PIPS[i].forEachIndexed { k, (u, v) ->
                val rgb = if (value == 4) PIP_FOUR[k] else PIP_WOOD
                val c = FloatArray(3) { n[it] * (half + 0.01f) + a[it] * u * spread + b[it] * v * spread }
                val s = size / 2
                fun corner(du: Float, dv: Float) = FloatArray(3) { c[it] + a[it] * du + b[it] * dv }
                mesh.quad(arrayOf(corner(-s, -s), corner(-s, s), corner(s, s), corner(s, -s)), rgb)
            }
        }
        return mesh
    }

    /** The die's shadow: a flat square on the floor, a little darker than the background. */
    fun dieShadowMesh(): VoxelMesh {
        val s = DIE * 0.55f
        val y = 0.01f
        return VoxelMesh().apply {
            quad(arrayOf(VoxelMesh.p(-s, y, -s), VoxelMesh.p(-s, y, s), VoxelMesh.p(s, y, s), VoxelMesh.p(s, y, -s)), SHADOW)
        }
    }

    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** Pip spots per face, by value - 1, in face units (-1..1). */
    private val DIE_PIPS: List<List<Pair<Float, Float>>> = listOf(
        listOf(0f to 0f),
        listOf(-1f to -1f, 1f to 1f),
        listOf(-1f to -1f, 0f to 0f, 1f to 1f),
        listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f),
        listOf(-1f to -1f, 1f to -1f, 0f to 0f, -1f to 1f, 1f to 1f),
        listOf(-1f to -1f, 1f to -1f, -1f to 0f, 1f to 0f, -1f to 1f, 1f to 1f),
    )

    /** Frame, tiles, numbers, start pad, ladders and snakes. */
    fun boardMesh(): VoxelMesh {
        val mesh = VoxelMesh()
        val e = HALF + RIM
        mesh.box(floatArrayOf(-e, 0f, -e), floatArrayOf(e, FRAME_TOP, e), WOOD)
        // The start pad, joined to the frame by a short bridge.
        val p = PAD_CENTER
        mesh.box(floatArrayOf(p[0] - PAD_HALF - 0.2f, 0f, p[1] - PAD_HALF - 0.2f), floatArrayOf(p[0] + PAD_HALF + 0.2f, FRAME_TOP, p[1] + PAD_HALF + 0.2f), WOOD)
        mesh.box(floatArrayOf(p[0] + PAD_HALF, 0f, p[1] - 0.5f), floatArrayOf(-e, FRAME_TOP, p[1] + 0.5f), WOOD)
        mesh.box(floatArrayOf(p[0] - PAD_HALF, FRAME_TOP, p[1] - PAD_HALF), floatArrayOf(p[0] + PAD_HALF, TOP, p[1] + PAD_HALF), CREAM)

        for (square in 1..UlarTanggaGame.LAST) {
            val c = center(square)
            val h = TILE / 2 - GAP / 2
            val col = UlarTanggaGame.column(square)
            val row = UlarTanggaGame.row(square)
            val rgb = when {
                square == UlarTanggaGame.LAST -> GOLD
                (col + row) % 2 == 0 -> CREAM
                else -> TILE_COLORS[(col / 2 + row) % TILE_COLORS.size]
            }
            mesh.box(floatArrayOf(c[0] - h, FRAME_TOP, c[1] - h), floatArrayOf(c[0] + h, TOP, c[1] + h), rgb)
            number(mesh, square, c[0] - h + 0.16f, c[1] - h + 0.16f)
        }

        UlarTanggaGame.LADDERS.forEach { (foot, top) -> ladder(mesh, center(foot), center(top)) }
        UlarTanggaGame.SNAKES.keys.sorted().forEachIndexed { i, head ->
            snake(mesh, SnakeCurve(center(head), center(UlarTanggaGame.SNAKES.getValue(head))), SNAKE_COLORS[i % SNAKE_COLORS.size])
        }
        return mesh
    }

    /** [n] in 3×5 voxel-pixel digits, its top-left corner at ([x], [z]). */
    private fun number(mesh: VoxelMesh, n: Int, x: Float, z: Float) {
        val y = TOP + DIGIT_RISE
        val px = DIGIT_PIXEL
        n.toString().forEachIndexed { i, ch ->
            val rows = DIGITS[ch - '0']
            val x0 = x + i * 4 * px
            rows.forEachIndexed { r, bits ->
                for (b in 0 until 3) {
                    if (bits and (4 shr b) == 0) continue
                    val a = x0 + b * px
                    val c = z + r * px
                    mesh.quad(arrayOf(VoxelMesh.p(a, y, c), VoxelMesh.p(a, y, c + px), VoxelMesh.p(a + px, y, c + px), VoxelMesh.p(a + px, y, c)), INK)
                }
            }
        }
    }

    private fun ladder(mesh: VoxelMesh, from: FloatArray, to: FloatArray) {
        val dx = to[0] - from[0]
        val dz = to[1] - from[1]
        val len = sqrt(dx * dx + dz * dz)
        val ux = dx / len
        val uz = dz / len
        val nx = -uz
        val nz = ux
        val w = 0.44f
        fun cube(x: Float, z: Float, s: Float, h: Float, rgb: FloatArray) =
            mesh.box(floatArrayOf(x - s / 2, TOP, z - s / 2), floatArrayOf(x + s / 2, TOP + h, z + s / 2), rgb)
        val start = -0.4f
        val end = len + 0.4f
        var s = start
        while (s <= end) {
            val x = from[0] + ux * s
            val z = from[1] + uz * s
            cube(x + nx * w, z + nz * w, 0.2f, 0.26f, RAIL)
            cube(x - nx * w, z - nz * w, 0.2f, 0.26f, RAIL)
            s += 0.16f
        }
        var r = start + 0.3f
        while (r <= end - 0.2f) {
            var k = -w + 0.12f
            while (k <= w - 0.12f) {
                cube(from[0] + ux * r + nx * k, from[1] + uz * r + nz * k, 0.16f, 0.18f, RUNG)
                k += 0.12f
            }
            r += 0.6f
        }
    }

    private fun snake(mesh: VoxelMesh, curve: SnakeCurve, colors: Pair<FloatArray, FloatArray>) {
        val (body, stripe) = colors
        val n = max(8, (curve.length / 0.15f).roundToInt())
        // From the tail up, so the head's bigger cubes are drawn over the neck.
        for (i in n downTo 1) {
            val t = i.toFloat() / n
            val c = curve.at(t)
            val size = 0.62f - 0.42f * t
            val rgb = if (((t * curve.length) / 0.7f).toInt() % 2 == 0) body else stripe
            val q = 1f + ((i * 7) % 3 - 1) * 0.03f
            mesh.box(
                floatArrayOf(c[0] - size / 2, TOP, c[1] - size / 2),
                floatArrayOf(c[0] + size / 2, TOP + size * 0.8f, c[1] + size / 2),
                floatArrayOf(rgb[0] * q, rgb[1] * q, rgb[2] * q),
            )
        }
        // The head looks away from the body.
        val h = curve.at(0f)
        val back = curve.at(0.06f)
        var fx = h[0] - back[0]
        var fz = h[1] - back[1]
        val fl = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-4f)
        fx /= fl; fz /= fl
        val sx = -fz
        val sz = fx
        val hs = 0.46f
        val hx = h[0] + fx * 0.15f
        val hz = h[1] + fz * 0.15f
        mesh.box(floatArrayOf(hx - hs, TOP, hz - hs), floatArrayOf(hx + hs, TOP + 0.56f, hz + hs), body)
        for (side in listOf(-1f, 1f)) {
            val ex = hx + fx * 0.18f + sx * side * 0.24f
            val ez = hz + fz * 0.18f + sz * side * 0.24f
            mesh.box(floatArrayOf(ex - 0.12f, TOP + 0.56f, ez - 0.12f), floatArrayOf(ex + 0.12f, TOP + 0.66f, ez + 0.12f), WHITE)
            mesh.box(floatArrayOf(ex - 0.06f + fx * 0.04f, TOP + 0.66f, ez - 0.06f + fz * 0.04f), floatArrayOf(ex + 0.06f + fx * 0.04f, TOP + 0.7f, ez + 0.06f + fz * 0.04f), BLACK)
        }
        for (k in 0..2) {
            val tx = hx + fx * (hs + 0.08f + k * 0.12f) + if (k == 2) sx * 0.08f else 0f
            val tz = hz + fz * (hs + 0.08f + k * 0.12f) + if (k == 2) sz * 0.08f else 0f
            mesh.box(floatArrayOf(tx - 0.05f, TOP, tz - 0.05f), floatArrayOf(tx + 0.05f, TOP + 0.08f, tz + 0.05f), TONGUE)
            if (k == 2) {
                val ox = tx - sx * 0.16f
                val oz = tz - sz * 0.16f
                mesh.box(floatArrayOf(ox - 0.05f, TOP, oz - 0.05f), floatArrayOf(ox + 0.05f, TOP + 0.08f, oz + 0.05f), TONGUE)
            }
        }
    }

    /** A snake's body line: a straight run from head to tail with a sine wiggle that is still at both ends. */
    private class SnakeCurve(private val head: FloatArray, private val tail: FloatArray) {
        private val dx = tail[0] - head[0]
        private val dz = tail[1] - head[1]
        private val straight = sqrt(dx * dx + dz * dz)
        private val nx = -dz / straight
        private val nz = dx / straight
        private val waves = max(1, (straight / 4.5f).roundToInt())

        /** Rough arc length, enough for spacing cubes and waypoints. */
        val length = straight * 1.15f

        /** x, z at [t] (0 = head, 1 = tail). */
        fun at(t: Float): FloatArray {
            val wiggle = 0.75f * sin(t * waves * 2 * PI.toFloat()) * sin(t * PI.toFloat())
            return floatArrayOf(head[0] + dx * t + nx * wiggle, head[1] + dz * t + nz * wiggle)
        }
    }

    /** 3×5 digits, one row per entry from the top, bit 4 = left pixel. */
    private val DIGITS = arrayOf(
        intArrayOf(7, 5, 5, 5, 7),
        intArrayOf(2, 6, 2, 2, 7),
        intArrayOf(7, 1, 7, 4, 7),
        intArrayOf(7, 1, 3, 1, 7),
        intArrayOf(5, 5, 7, 1, 1),
        intArrayOf(7, 4, 7, 1, 7),
        intArrayOf(7, 4, 7, 5, 7),
        intArrayOf(7, 1, 1, 2, 2),
        intArrayOf(7, 5, 7, 5, 7),
        intArrayOf(7, 5, 7, 1, 7),
    )
}
