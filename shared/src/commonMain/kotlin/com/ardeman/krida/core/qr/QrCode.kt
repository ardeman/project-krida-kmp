package com.ardeman.krida.core.qr

/**
 * A QR code for short text such as a room link (`krida://join?room=ABCD`, version 2): byte
 * mode, error correction level M, versions 1–6, so up to 106 bytes. Written here so the apps
 * need no QR library; iOS may use CoreImage instead, any valid code scans the same.
 */
class QrCode private constructor(
    /** Modules per side, without the quiet zone (draw 4 light modules around it). */
    val size: Int,
    private val dark: BooleanArray,
) {
    val version: Int get() = (size - 17) / 4

    fun isDark(row: Int, col: Int): Boolean = dark[row * size + col]

    companion object {
        /** Throws IllegalArgumentException when [text] is longer than 106 bytes in UTF-8. */
        fun encode(text: String): QrCode = encodeBytes(text.encodeToByteArray())

        internal fun encodeBytes(data: ByteArray): QrCode {
            val version = (1..VERSIONS.size).firstOrNull { data.size <= capacity(it) }
                ?: throw IllegalArgumentException("Too long for a QR code: ${data.size} bytes")
            val codewords = interleave(version, dataCodewords(version, data))
            val grid = Grid(version)
            grid.drawFunctionPatterns()
            grid.placeData(codewords)
            val mask = (0 until 8).minBy { m ->
                grid.applyMask(m)
                grid.drawFormat(m)
                grid.penalty().also { grid.applyMask(m) } // XOR again undoes it
            }
            grid.applyMask(mask)
            grid.drawFormat(mask)
            return QrCode(grid.size, grid.dark)
        }

        /** Bytes that fit: data codewords minus mode (4 bits), count (8) and terminator. */
        private fun capacity(version: Int): Int = VERSIONS[version - 1].dataCodewords - 2

        private fun dataCodewords(version: Int, data: ByteArray): IntArray {
            val total = VERSIONS[version - 1].dataCodewords
            val bits = BitBuffer()
            bits.append(0b0100, 4) // byte mode
            bits.append(data.size, 8)
            data.forEach { bits.append(it.toInt() and 0xFF, 8) }
            bits.append(0, minOf(4, total * 8 - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            val out = IntArray(total)
            for (i in 0 until bits.size / 8) out[i] = bits.byteAt(i)
            for (i in bits.size / 8 until total) out[i] = if ((i - bits.size / 8) % 2 == 0) 0xEC else 0x11
            return out
        }

        /** Splits into blocks, adds each block's error correction, then interleaves. */
        private fun interleave(version: Int, data: IntArray): IntArray {
            val v = VERSIONS[version - 1]
            val blockLen = data.size / v.blocks
            val blocks = (0 until v.blocks).map { b -> data.copyOfRange(b * blockLen, (b + 1) * blockLen) }
            val ecc = blocks.map { reedSolomon(it, v.eccPerBlock) }
            val out = ArrayList<Int>(v.totalCodewords)
            for (i in 0 until blockLen) blocks.forEach { out += it[i] }
            for (i in 0 until v.eccPerBlock) ecc.forEach { out += it[i] }
            return out.toIntArray()
        }

        /** Error correction codewords: the remainder of data·x^n divided by the generator. */
        internal fun reedSolomon(data: IntArray, n: Int): IntArray {
            var gen = intArrayOf(1)
            for (i in 0 until n) {
                val next = IntArray(gen.size + 1)
                val root = EXP[i]
                for (j in gen.indices) {
                    next[j] = next[j] xor gen[j]
                    next[j + 1] = next[j + 1] xor gfMul(gen[j], root)
                }
                gen = next
            }
            val rem = IntArray(n)
            for (d in data) {
                val factor = d xor rem[0]
                for (j in 0 until n - 1) rem[j] = rem[j + 1]
                rem[n - 1] = 0
                for (j in 0 until n) rem[j] = rem[j] xor gfMul(gen[j + 1], factor)
            }
            return rem
        }

        private fun gfMul(a: Int, b: Int): Int =
            if (a == 0 || b == 0) 0 else EXP[(LOG[a] + LOG[b]) % 255]

        private val EXP = IntArray(256)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
            }
            EXP[255] = EXP[0]
        }

        private class Version(val totalCodewords: Int, val eccPerBlock: Int, val blocks: Int) {
            val dataCodewords: Int get() = totalCodewords - eccPerBlock * blocks
        }

        /** Level M, versions 1–6; every block in these versions has the same length. */
        private val VERSIONS = listOf(
            Version(26, 10, 1),
            Version(44, 16, 1),
            Version(70, 26, 1),
            Version(100, 18, 2),
            Version(134, 24, 2),
            Version(172, 16, 4),
        )
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size: Int get() = bits.size

        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1 == 1
        }

        fun byteAt(i: Int): Int = (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) }
    }

    /** The module grid while it is built; x is the column, y the row. */
    private class Grid(val version: Int) {
        val size = version * 4 + 17
        val dark = BooleanArray(size * size)
        private val function = BooleanArray(size * size)

        private fun set(x: Int, y: Int, on: Boolean) {
            dark[y * size + x] = on
            function[y * size + x] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            if (version >= 2) alignment(size - 7, size - 7)
            drawFormat(0) // reserves the format areas; redrawn once the mask is chosen
        }

        /** 7×7 finder centred on (cx, cy) with its light separator, clipped at the edges. */
        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(x, y, d != 2 && d != 4)
            }
        }

        private fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) {
                set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }

        /** Format bits: level M (00) and the mask, BCH-protected, both copies, plus the dark module. */
        fun drawFormat(mask: Int) {
            val data = mask // level M is 00
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        /** Zigzag up and down two-column strips from the right, skipping the timing column. */
        fun placeData(codewords: IntArray) {
            var i = 0
            val total = codewords.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                val upward = (right + 1) and 2 == 0
                for (vert in 0 until size) {
                    val y = if (upward) size - 1 - vert else vert
                    for (j in 0..1) {
                        val x = right - j
                        if (function[y * size + x] || i >= total) continue
                        dark[y * size + x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
                right -= 2
            }
        }

        /** XORs mask [m] over the data modules; applying it twice restores the grid. */
        fun applyMask(m: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y * size + x]) continue
                val flip = when (m) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip) dark[y * size + x] = !dark[y * size + x]
            }
        }

        private fun at(x: Int, y: Int) = dark[y * size + x]

        /** The standard's four penalty rules; the mask with the lowest score is used. */
        fun penalty(): Int {
            var score = 0
            for (line in 0 until size) for (horizontal in listOf(true, false)) {
                fun get(i: Int) = if (horizontal) at(i, line) else at(line, i)
                var run = 1
                for (i in 1..size) {
                    if (i < size && get(i) == get(i - 1)) {
                        run++
                    } else {
                        if (run >= 5) score += run - 2
                        run = 1
                    }
                }
                for (i in 0..size - 11) {
                    val window = (0 until 11).map { get(i + it) }
                    if (window == FINDER_LIKE_1 || window == FINDER_LIKE_2) score += 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = at(x, y)
                if (c == at(x + 1, y) && c == at(x, y + 1) && c == at(x + 1, y + 1)) score += 3
            }
            val total = size * size
            val darkCount = dark.count { it }
            // 10 points per 5% that the dark share strays from 50%.
            val steps = (kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
            score += maxOf(0, steps) * 10
            return score
        }
    }
}

private val FINDER_LIKE_1 = "10111010000".map { it == '1' }
private val FINDER_LIKE_2 = "00001011101".map { it == '1' }
