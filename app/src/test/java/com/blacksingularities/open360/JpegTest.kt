package com.blacksingularities.open360

import com.blacksingularities.open360.jpeg.JpegWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.math.sin

class JpegTest {
    private fun image(w: Int, h: Int) = ByteArray(w * h * 4).also { a ->
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            a[i] = (128 + 100 * sin(x * 0.05) * sin(y * 0.031)).toInt().toByte()
            a[i + 1] = ((x * 255) / w).toByte()
            a[i + 2] = (((x xor y) and 0x3F) * 3 + 40).toByte()
            a[i + 3] = -1
        }
    }

    private fun encode(w: Int, h: Int, rgba: ByteArray, strip: Int, app1: ByteArray? = null): ByteArray {
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

    @Test
    fun decodesWithGoodQuality() {
        for ((w, h) in listOf(333 to 197, 640 to 320, 17 to 9)) {
            val src = image(w, h)
            val jpg = encode(w, h, src, 64, "http://ns.adobe.com/xap/1.0/\u0000<x/>".toByteArray())
            val img = ImageIO.read(ByteArrayInputStream(jpg))
            assertEquals(w, img.width)
            assertEquals(h, img.height)
            var se = 0.0
            for (y in 0 until h) for (x in 0 until w) {
                val p = img.getRGB(x, y)
                val i = (y * w + x) * 4
                for ((c, sh) in listOf(0 to 16, 1 to 8, 2 to 0)) {
                    val d = ((p shr sh) and 0xFF) - (src[i + c].toInt() and 0xFF)
                    se += d * d
                }
            }
            val psnr = 10 * log10(255.0 * 255.0 / (se / (w * h * 3)))
            println("JPEG ${w}x$h: ${jpg.size} B, PSNR ${"%.1f".format(psnr)} dB")
            assertTrue("PSNR $psnr", psnr > 30)
        }
    }

    @Test
    fun largeStripsAreFast() {
        val w = 4096; val h = 2048
        val src = image(w, 128)
        val out = ByteArrayOutputStream()
        val t0 = System.nanoTime()
        JpegWriter(out, w, h, 94).use { j ->
            repeat(h / 128) { j.writeRows(src, w * 4, 128) }
            j.finish()
        }
        val ms = (System.nanoTime() - t0) / 1e6
        println("JPEG 8 Mpx: ${"%.0f".format(ms)} ms, ${out.size() / 1024} KB")
        assertTrue(ImageIO.read(ByteArrayInputStream(out.toByteArray())).width == w)
    }
}
