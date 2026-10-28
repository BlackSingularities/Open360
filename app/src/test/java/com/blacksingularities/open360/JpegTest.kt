package com.blacksingularities.open360

import com.blacksingularities.open360.jpeg.JpegWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Struktura pliku z kodera JPEG (dekodowanie sprawdzamy poza testami Androida). */
class JpegTest {
    private fun encode(w: Int, h: Int, strip: Int, app1: ByteArray? = null): ByteArray {
        val rgba = ByteArray(w * h * 4) { ((it * 31) xor (it shr 7)).toByte() }
        val out = ByteArrayOutputStream()
        JpegWriter(out, w, h, 94, app1).use { j ->
            var y = 0
            while (y < h) {
                val rows = minOf(strip, h - y)
                j.writeRows(rgba.copyOfRange(y * w * 4, (y + rows) * w * 4), w * 4, rows)
                y += rows
            }
            j.finish()
        }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    @Test
    fun markersAndSize() {
        val w = 333; val h = 197
        val jpg = encode(w, h, 64, "http://ns.adobe.com/xap/1.0/\u0000<x/>".toByteArray())
        assertEquals(0xFFD8, u16(jpg, 0))
        assertEquals(0xFFD9, u16(jpg, jpg.size - 2))
        // Przejście po segmentach nagłówka do SOS.
        var i = 2
        val seen = HashSet<Int>()
        while (true) {
            val m = u16(jpg, i)
            seen += m
            if (m == 0xFFC0) {
                assertEquals(h, u16(jpg, i + 5))
                assertEquals(w, u16(jpg, i + 7))
            }
            if (m == 0xFFDA) break
            i += 2 + u16(jpg, i + 2)
        }
        assertTrue(seen.containsAll(listOf(0xFFE0, 0xFFE1, 0xFFDB, 0xFFC0, 0xFFC4, 0xFFDD, 0xFFDA)))
        // Jeden znacznik restartu między każdymi dwoma rzędami bloków 16×16.
        var rst = 0
        for (k in i until jpg.size - 1) {
            if (jpg[k] == 0xFF.toByte() && (jpg[k + 1].toInt() and 0xFF) in 0xD0..0xD7) rst++
        }
        assertEquals((h + 15) / 16 - 1, rst)
    }

    @Test
    fun stripSizeDoesNotChangeOutput() {
        assertArrayEquals(encode(200, 150, 16), encode(200, 150, 128))
    }
}
