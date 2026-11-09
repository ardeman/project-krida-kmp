package com.ardeman.krida.core

import com.ardeman.krida.core.qr.QrCode
import com.ardeman.krida.core.session.RoomCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrCodeTest {
    @Test
    fun reedSolomonMatchesTheStandardsExample() {
        // "HELLO WORLD" at 1-M, from the worked example in common QR references.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        val ecc = intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23)
        assertContentEquals(ecc, QrCode.reedSolomon(data, 10))
    }

    @Test
    fun roomLinkIsVersion2() {
        val qr = QrCode.encode(RoomCode.joinUrl("K7QM"))
        assertEquals(2, qr.version)
        assertEquals(25, qr.size)
    }

    @Test
    fun finderPatternsAndTimingAreInPlace() {
        val qr = QrCode.encode(RoomCode.joinUrl("ABCD"))
        val n = qr.size
        for ((r0, c0) in listOf(0 to 0, 0 to n - 7, n - 7 to 0)) {
            for (r in 0 until 7) for (c in 0 until 7) {
                val ring = maxOf(kotlin.math.abs(r - 3), kotlin.math.abs(c - 3))
                assertEquals(ring != 2, qr.isDark(r0 + r, c0 + c), "finder at ($r0,$c0) module ($r,$c)")
            }
        }
        for (i in 8 until n - 8) {
            assertEquals(i % 2 == 0, qr.isDark(6, i))
            assertEquals(i % 2 == 0, qr.isDark(i, 6))
        }
        assertTrue(qr.isDark(n - 8, 8), "dark module")
    }

    @Test
    fun bothFormatCopiesAgreeAndNameLevelM() {
        val qr = QrCode.encode("krida://join?room=ZZ99")
        val n = qr.size
        var first = 0
        var second = 0
        // Bit i of each copy, as placed by the standard (bit 0 first).
        val a = (0..5).map { 8 to it } + listOf(8 to 7, 8 to 8, 7 to 8) + (9 until 15).map { (14 - it) to 8 }
        val b = (0 until 8).map { (n - 1 - it) to 8 } + (8 until 15).map { 8 to (n - 15 + it) }
        for (i in 0 until 15) {
            val (ax, ay) = a[i]
            val (bx, by) = b[i]
            if (qr.isDark(ay, ax)) first = first or (1 shl i)
            if (qr.isDark(by, bx)) second = second or (1 shl i)
        }
        assertEquals(first, second)
        val format = first xor 0x5412
        assertEquals(0, format ushr 13, "error correction level M")
    }

    @Test
    fun longerTextPicksALargerVersionAndTooLongFails() {
        assertEquals(6, QrCode.encode("x".repeat(106)).version)
        assertFailsWith<IllegalArgumentException> { QrCode.encode("x".repeat(107)) }
    }
}
