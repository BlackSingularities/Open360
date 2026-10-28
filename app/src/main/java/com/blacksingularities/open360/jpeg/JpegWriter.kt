package com.blacksingularities.open360.jpeg

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Strumieniowy koder JPEG (baseline, YCbCr 4:2:0, standardowe tablice Huffmana). Obraz
 * przychodzi pasami RGBA, więc nawet panorama 16K (134 Mpx) nie musi istnieć w pamięci
 * w całości. Każdy rząd bloków 16×16 jest osobnym odcinkiem restartu (DRI), dzięki czemu
 * rzędy koduje się równolegle na wszystkich rdzeniach, a wynik jest zwykłym plikiem JPEG.
 */
class JpegWriter(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    quality: Int,
    /** Gotowy ładunek segmentu APP1 (np. XMP z nagłówkiem przestrzeni nazw) albo null. */
    app1: ByteArray? = null,
    threads: Int = Runtime.getRuntime().availableProcessors(),
) : Closeable {
    private val mcusPerRow = (width + 15) / 16
    private val mcuRows = (height + 15) / 16
    private val pool: ExecutorService = Executors.newFixedThreadPool(max(1, min(threads, 8)))
    private var rowsDone = 0
    private var segmentsDone = 0

    private val qLum = scaled(LUM_Q, quality)
    private val qChr = scaled(CHR_Q, quality)
    private val divLum = divisors(qLum)
    private val divChr = divisors(qChr)

    /** Ile wierszy obrazu już przyjęto (0..height). */
    val progress: Float get() = rowsDone.toFloat() / height

    init {
        require(width in 1..65535 && height in 1..65535)
        writeHeaders(app1)
    }

    /**
     * Dopisuje [rows] kolejnych wierszy RGBA z [rgba] (wiersz co [stride] bajtów). Liczba
     * wierszy musi być wielokrotnością 16 – poza ostatnim pasem obrazu.
     */
    fun writeRows(rgba: ByteArray, stride: Int, rows: Int) {
        require(rows > 0 && rowsDone + rows <= height)
        val last = rowsDone + rows == height
        require(last || rows % 16 == 0) { "pas musi mieć wielokrotność 16 wierszy" }
        val bands = (rows + 15) / 16
        val tasks = ArrayList<Future<ByteArray>>(bands)
        for (b in 0 until bands) {
            val y0 = b * 16
            tasks += pool.submit(Callable { encodeBand(rgba, stride, y0, rows) })
        }
        for (t in tasks) {
            val seg = t.get()
            out.write(seg)
            segmentsDone++
            // Znacznik restartu między odcinkami (po ostatnim – koniec obrazu).
            if (segmentsDone < mcuRows) {
                out.write(0xFF)
                out.write(0xD0 + ((segmentsDone - 1) and 7))
            }
        }
        rowsDone += rows
    }

    /** Zamyka plik (EOI). Wymaga wcześniejszego przekazania wszystkich wierszy. */
    fun finish() {
        check(rowsDone == height) { "brakuje ${height - rowsDone} wierszy" }
        out.write(0xFF)
        out.write(0xD9)
        out.flush()
    }

    override fun close() {
        pool.shutdownNow()
    }

    // ── Nagłówki ────────────────────────────────────────────────────────────

    private fun marker(code: Int, payload: ByteArray) {
        out.write(0xFF); out.write(code)
        val len = payload.size + 2
        out.write(len shr 8); out.write(len and 0xFF)
        out.write(payload)
    }

    private fun writeHeaders(app1: ByteArray?) {
        out.write(0xFF); out.write(0xD8)
        marker(0xE0, byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        if (app1 != null && app1.size + 2 <= 65535) marker(0xE1, app1)
        val dqt = ByteArray(130)
        dqt[0] = 0
        for (k in 0 until 64) dqt[1 + k] = qLum[ZIGZAG[k]].toByte()
        dqt[65] = 1
        for (k in 0 until 64) dqt[66 + k] = qChr[ZIGZAG[k]].toByte()
        marker(0xDB, dqt)
        marker(
            0xC0,
            byteArrayOf(
                8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 3,
                1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
            ),
        )
        val dht = java.io.ByteArrayOutputStream()
        for ((cls, t) in listOf(0x00 to DC_LUM, 0x10 to AC_LUM, 0x01 to DC_CHR, 0x11 to AC_CHR)) {
            dht.write(cls)
            dht.write(t.bits, 0, 16)
            dht.write(t.vals, 0, t.vals.size)
        }
        marker(0xC4, dht.toByteArray())
        marker(0xDD, byteArrayOf((mcusPerRow shr 8).toByte(), mcusPerRow.toByte()))
        marker(0xDA, byteArrayOf(3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0))
    }

    // ── Kodowanie rzędu bloków ──────────────────────────────────────────────

    private class Bits(capacity: Int) {
        var buf = ByteArray(capacity)
        var n = 0
        private var acc = 0L
        private var cnt = 0

        fun put(code: Int, size: Int) {
            acc = (acc shl size) or (code.toLong() and ((1L shl size) - 1))
            cnt += size
            while (cnt >= 8) {
                val b = ((acc shr (cnt - 8)) and 0xFF).toInt()
                byte(b)
                if (b == 0xFF) byte(0)
                cnt -= 8
            }
        }

        private fun byte(b: Int) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n++] = b.toByte()
        }

        fun flush(): ByteArray {
            if (cnt > 0) put((1 shl (8 - cnt)) - 1, 8 - cnt)
            return buf.copyOf(n)
        }
    }

    private fun encodeBand(rgba: ByteArray, stride: Int, y0: Int, rows: Int): ByteArray {
        val bits = Bits(width * 6 + 1024)
        val yb = FloatArray(256)
        val cb = FloatArray(64)
        val cr = FloatArray(64)
        val blk = FloatArray(64)
        val q = IntArray(64)
        val pred = IntArray(3)
        val maxRow = rows - 1
        val maxCol = width - 1
        for (mx in 0 until mcusPerRow) {
            cb.fill(0f); cr.fill(0f)
            for (yy in 0 until 16) {
                val row = min(y0 + yy, maxRow) * stride
                for (xx in 0 until 16) {
                    val i = row + min(mx * 16 + xx, maxCol) * 4
                    val r = (rgba[i].toInt() and 0xFF).toFloat()
                    val g = (rgba[i + 1].toInt() and 0xFF).toFloat()
                    val b = (rgba[i + 2].toInt() and 0xFF).toFloat()
                    yb[yy * 16 + xx] = 0.299f * r + 0.587f * g + 0.114f * b - 128f
                    val c = (yy shr 1) * 8 + (xx shr 1)
                    cb[c] += -0.168736f * r - 0.331264f * g + 0.5f * b
                    cr[c] += 0.5f * r - 0.418688f * g - 0.081312f * b
                }
            }
            for (by in 0..1) for (bx in 0..1) {
                for (k in 0 until 64) blk[k] = yb[(by * 8 + (k shr 3)) * 16 + bx * 8 + (k and 7)]
                fdct(blk)
                quantize(blk, divLum, q)
                pred[0] = block(bits, q, pred[0], DC_LUM, AC_LUM)
            }
            for (k in 0 until 64) blk[k] = cb[k] * 0.25f
            fdct(blk); quantize(blk, divChr, q)
            pred[1] = block(bits, q, pred[1], DC_CHR, AC_CHR)
            for (k in 0 until 64) blk[k] = cr[k] * 0.25f
            fdct(blk); quantize(blk, divChr, q)
            pred[2] = block(bits, q, pred[2], DC_CHR, AC_CHR)
        }
        return bits.flush()
    }

    private fun quantize(d: FloatArray, div: FloatArray, q: IntArray) {
        for (k in 0 until 64) q[k] = (d[k] * div[k]).roundToInt()
    }

    private fun block(bits: Bits, q: IntArray, prev: Int, dc: Huff, ac: Huff): Int {
        val dcv = q[0]
        emit(bits, dcv - prev, dc, 0)
        var run = 0
        for (k in 1 until 64) {
            val v = q[ZIGZAG[k]]
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                bits.put(ac.code[0xF0], ac.size[0xF0])
                run -= 16
            }
            emit(bits, v, ac, run shl 4)
            run = 0
        }
        if (run > 0) bits.put(ac.code[0], ac.size[0])
        return dcv
    }

    /** Kategoria wartości + jej bity; [high] – starsze 4 bity symbolu (długość serii zer). */
    private fun emit(bits: Bits, v: Int, t: Huff, high: Int) {
        var a = if (v < 0) -v else v
        var nb = 0
        while (a != 0) { nb++; a = a shr 1 }
        val sym = high or nb
        bits.put(t.code[sym], t.size[sym])
        if (nb > 0) bits.put(if (v < 0) v - 1 else v, nb)
    }

    /** Szybka DCT zmiennoprzecinkowa (Arai–Agui–Nakajima, jak jfdctflt w libjpeg). */
    private fun fdct(d: FloatArray) {
        for (p in 0 until 2) {
            for (i in 0 until 8) {
                val o = if (p == 0) i * 8 else i
                val s = if (p == 0) 1 else 8
                val d0 = d[o]; val d1 = d[o + s]; val d2 = d[o + 2 * s]; val d3 = d[o + 3 * s]
                val d4 = d[o + 4 * s]; val d5 = d[o + 5 * s]; val d6 = d[o + 6 * s]; val d7 = d[o + 7 * s]
                val tmp0 = d0 + d7; val tmp7 = d0 - d7
                val tmp1 = d1 + d6; val tmp6 = d1 - d6
                val tmp2 = d2 + d5; val tmp5 = d2 - d5
                val tmp3 = d3 + d4; val tmp4 = d3 - d4
                var tmp10 = tmp0 + tmp3; val tmp13 = tmp0 - tmp3
                var tmp11 = tmp1 + tmp2; var tmp12 = tmp1 - tmp2
                d[o] = tmp10 + tmp11
                d[o + 4 * s] = tmp10 - tmp11
                val z1 = (tmp12 + tmp13) * 0.707106781f
                d[o + 2 * s] = tmp13 + z1
                d[o + 6 * s] = tmp13 - z1
                tmp10 = tmp4 + tmp5
                tmp11 = tmp5 + tmp6
                tmp12 = tmp6 + tmp7
                val z5 = (tmp10 - tmp12) * 0.382683433f
                val z2 = 0.541196100f * tmp10 + z5
                val z4 = 1.306562965f * tmp12 + z5
                val z3 = tmp11 * 0.707106781f
                val z11 = tmp7 + z3
                val z13 = tmp7 - z3
                d[o + 5 * s] = z13 + z2
                d[o + 3 * s] = z13 - z2
                d[o + s] = z11 + z4
                d[o + 7 * s] = z11 - z4
            }
        }
    }

    private class Huff(val bits: ByteArray, val vals: ByteArray) {
        val code = IntArray(256)
        val size = IntArray(256)

        init {
            var c = 0
            var k = 0
            for (len in 1..16) {
                repeat(bits[len - 1].toInt()) {
                    val sym = vals[k++].toInt() and 0xFF
                    code[sym] = c
                    size[sym] = len
                    c++
                }
                c = c shl 1
            }
        }
    }

    companion object {
        private val ZIGZAG = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
        )
        private val LUM_Q = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
        )
        private val CHR_Q = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
        )
        private val AAN = floatArrayOf(1f, 1.387039845f, 1.306562965f, 1.175875602f, 1f, 0.785694958f, 0.541196100f, 0.275899379f)

        private fun scaled(base: IntArray, quality: Int): IntArray {
            val q = quality.coerceIn(1, 100)
            val s = if (q < 50) 5000 / q else 200 - 2 * q
            return IntArray(64) { ((base[it] * s + 50) / 100).coerceIn(1, 255) }
        }

        private fun divisors(q: IntArray) = FloatArray(64) { 1f / (q[it] * AAN[it shr 3] * AAN[it and 7] * 8f) }

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        private val DC_LUM = Huff(bytes(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0), bytes(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))
        private val DC_CHR = Huff(bytes(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0), bytes(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))
        private val AC_LUM = Huff(
            bytes(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d),
            bytes(
                0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
                0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
                0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
                0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
                0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
                0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
                0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
                0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
                0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
                0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
                0xf9, 0xfa,
            ),
        )
        private val AC_CHR = Huff(
            bytes(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77),
            bytes(
                0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
                0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
                0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
                0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
                0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
                0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
                0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
                0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
                0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
                0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
                0xf9, 0xfa,
            ),
        )
    }
}
