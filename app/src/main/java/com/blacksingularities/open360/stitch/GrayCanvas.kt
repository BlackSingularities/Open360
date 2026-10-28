package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.data.CaptureRange
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.FastMath
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.PI_F
import com.blacksingularities.open360.math.Vec3
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Szara panorama równoprostokątna prowadzona na CPU równolegle z płótnem na GPU – odniesienie
 * do dopasowania klatek obrazem (idea z OpenPano, ale zamiast cech SIFT szukamy obrotu
 * minimalizującego różnicę jasności), do wyrównania ekspozycji, pokrycia i kadrowania.
 * Jasność 16-bitowa, waga 8-bitowa (dokładnie jak kanał alfa na GPU); waga 0 = brak danych.
 */
class GrayCanvas(val w: Int, val h: Int) {
    val gray = ShortArray(w * h)
    val weight = ByteArray(w * h)

    /** Kąt na piksel (rad). */
    val radPerPx = 2f * PI_F / w

    private val rowSin = FloatArray(h) { sin((0.5f - (it + 0.5f) / h) * PI_F) }
    private val rowCos = FloatArray(h) { cos((0.5f - (it + 0.5f) / h) * PI_F) }
    private val colSin = FloatArray(w) { sin(((it + 0.5f) / w - 0.5f) * 2f * PI_F) }
    private val colCos = FloatArray(w) { cos(((it + 0.5f) / w - 0.5f) * 2f * PI_F) }

    fun clear() {
        gray.fill(0)
        weight.fill(0)
    }

    fun copyFrom(o: GrayCanvas) {
        o.gray.copyInto(gray)
        o.weight.copyInto(weight)
    }

    fun value(i: Int): Float = (gray[i].toInt() and 0xFFFF) * (1f / 65535f)
    fun wgt(i: Int): Float = (weight[i].toInt() and 0xFF) * (1f / 255f)
    fun present(i: Int): Boolean = weight[i].toInt() != 0

    /** Współrzędne piksela (ciągłe, środek piksela = całkowita) dla kierunku. */
    fun px(lon: Float): Float = (lon / (2 * PI_F) + 0.5f) * w - 0.5f
    fun py(lat: Float): Float = (0.5f - lat / PI_F) * h - 0.5f

    /** Dwuliniowo w kierunku [d]; NaN, gdy któregoś z czterech sąsiadów brak. */
    fun sample(d: Vec3): Float = sampleAt(px(FastMath.atan2(d.x, d.y)), py(FastMath.asin(d.z)))

    fun sampleAt(fx: Float, fy: Float): Float {
        val x0 = floor(fx).toInt()
        val yf = floor(fy).toInt()
        if (yf < 0 || yf >= h - 1) return Float.NaN
        val tx = fx - x0
        val ty = fy - yf
        val xa = ((x0 % w) + w) % w
        val xb = if (xa + 1 == w) 0 else xa + 1
        val i00 = yf * w + xa; val i01 = yf * w + xb
        val i10 = i00 + w; val i11 = i01 + w
        if (weight[i00].toInt() == 0 || weight[i01].toInt() == 0 || weight[i10].toInt() == 0 || weight[i11].toInt() == 0) return Float.NaN
        val a = value(i00) + (value(i01) - value(i00)) * tx
        val b = value(i10) + (value(i11) - value(i10)) * tx
        return a + (b - a) * ty
    }

    /**
     * Wpisuje klatkę ([panoToCam] – kierunek panoramy → kamera w chwili środka klatki) tą samą
     * regułą co shader zszywania: centralność × [quality], wąski szew, korekta fotometryczna
     * w świetle liniowym i migawka szczelinowa.
     */
    fun splat(frame: GrayFrame, panoToCam: Mat3, geom: FrameGeom, photo: Photometry, quality: Float, box: LonLatBox) {
        val y0 = box.y0.coerceIn(0, h - 1)
        val y1 = box.y1.coerceIn(0, h - 1)
        // Wiersze są niezależne – dzielimy je między rdzenie.
        Parallel.forRange(y0, y1 + 1) { ra, rb -> splatRows(ra, rb, frame, panoToCam.m, geom, photo, quality, box) }
    }

    private fun splatRows(ra: Int, rb: Int, frame: GrayFrame, m: FloatArray, geom: FrameGeom, photo: Photometry, quality: Float, box: LonLatBox) {
        val uv = FloatArray(2)
        for (py in ra until rb) {
            val cl = rowCos[py]; val sl = rowSin[py]
            val row = py * w
            for (k in 0 until box.width) {
                val px = (box.x0 + k) % w
                val dx = cl * colSin[px]; val dy = cl * colCos[px]
                val cx = m[0] * dx + m[1] * dy + m[2] * sl
                val cy = m[3] * dx + m[4] * dy + m[5] * sl
                val cz = m[6] * dx + m[7] * dy + m[8] * sl
                if (!geom.project(cx, cy, cz, uv)) continue
                val u = uv[0]; val v = uv[1]
                if (u <= 0f || u >= 1f || v <= 0f || v >= 1f) continue
                val raw = frame.sample(u, v)
                val c = Gamma.gam(Gamma.lin(raw) * photo.factor(u, v, geom))
                val i = row + px
                val wO = wgt(i)
                val wN = Blend.weight(u, v, quality)
                val old = if (wO > 0f) value(i) else c
                val t = Blend.mix(wN, wO, abs(c - old))
                if (t <= 0f) continue
                val nv = old + (c - old) * t
                val nw = wO + (wN - wO) * t
                gray[i] = (nv.coerceIn(0f, 1f) * 65535f).roundToInt().toShort()
                weight[i] = (nw * 255f).roundToInt().coerceIn(1, 255).toByte()
            }
        }
    }

    /** Pokrycie zadanego zakresu (0..1), ważone polem powierzchni na sferze. */
    fun coverage(range: CaptureRange): Float {
        var tot = 0.0
        var cov = 0.0
        val lonHalf = range.lonSpan / 2f
        val step = max(1, w / 720)
        for (py in 0 until h step step) {
            val latDeg = 90f - (py + 0.5f) * 180f / h
            if (latDeg < range.latMin || latDeg > range.latMax) continue
            val a = rowCos[py].toDouble()
            for (px in 0 until w step step) {
                val lonDeg = (px + 0.5f) * 360f / w - 180f
                if (abs(lonDeg) > lonHalf) continue
                tot += a
                if (weight[py * w + px].toInt() != 0) cov += a
            }
        }
        return if (tot > 0) (cov / tot).toFloat() else 0f
    }

    /**
     * Prostokąt (w pikselach płótna [fullW]×[fullH]) obejmujący uchwyconą treść; x może
     * zawijać się przez szew 180°. Null, gdy pusto.
     */
    fun contentBounds(fullW: Int, fullH: Int): IntArray? {
        var y0 = h; var y1 = -1
        val cols = BooleanArray(w)
        for (py in 0 until h) {
            val row = py * w
            var any = false
            for (px in 0 until w) {
                if (weight[row + px].toInt() != 0) {
                    any = true
                    cols[px] = true
                }
            }
            if (any) {
                if (py < y0) y0 = py
                y1 = py
            }
        }
        if (y1 < 0) return null
        // Największa przerwa w kolumnach (z zawinięciem) wyznacza miejsce cięcia w poziomie.
        var bestGap = 0; var bestEnd = 0
        var run = 0
        for (k in 0 until 2 * w) {
            if (!cols[k % w]) {
                run++
                if (run > bestGap && run <= w) {
                    bestGap = run
                    bestEnd = k
                }
            } else {
                run = 0
            }
        }
        val sx = fullW.toFloat() / w
        val sy = fullH.toFloat() / h
        val top = (y0 * sy).toInt()
        val bottom = min(fullH, ((y1 + 1) * sy).toInt())
        if (bestGap < 4) return intArrayOf(0, top, fullW, bottom - top)
        val start = (bestEnd + 1) % w
        val width = w - bestGap
        return intArrayOf((start * sx).toInt(), top, (width * sx).toInt().coerceAtMost(fullW), bottom - top)
    }
}

/** Szary, pomniejszony obraz klatki w orientacji pionowej (u, v ∈ 0..1, v w dół; gamma). */
class GrayFrame(val w: Int, val h: Int) {
    val px = FloatArray(w * h)
    var mean = 0f
    var contrast = 0f

    fun sample(u: Float, v: Float): Float {
        val fx = (u * w - 0.5f).coerceIn(0f, w - 1.001f)
        val fy = (v * h - 0.5f).coerceIn(0f, h - 1.001f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val tx = fx - x0; val ty = fy - y0
        val i = y0 * w + x0
        val a = px[i] + (px[i + 1] - px[i]) * tx
        val b = px[i + w] + (px[i + w + 1] - px[i + w]) * tx
        return a + (b - a) * ty
    }

    fun computeStats() {
        var s = 0.0; var s2 = 0.0
        for (v in px) { s += v; s2 += v * v }
        val n = px.size
        mean = (s / n).toFloat()
        contrast = sqrt(max(0.0, s2 / n - (s / n) * (s / n))).toFloat()
    }

    /** Rozmycie 3×3 (1-2-1) – filtr antyaliasingowy po pomniejszeniu. */
    fun blur3() {
        val tmp = FloatArray(px.size)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                val l = px[r + max(0, x - 1)]; val c = px[r + x]; val rr = px[r + min(w - 1, x + 1)]
                tmp[r + x] = (l + 2f * c + rr) * 0.25f
            }
        }
        for (y in 0 until h) {
            val up = max(0, y - 1) * w; val r = y * w; val dn = min(h - 1, y + 1) * w
            for (x in 0 until w) px[r + x] = (tmp[up + x] + 2f * tmp[r + x] + tmp[dn + x]) * 0.25f
        }
    }

    companion object {
        /**
         * Obraz pionowy [outW]×[outH] z płaszczyzny jasności (Y) bufora [bw]×[bh] obróconego
         * o [rot]° – cztery próbki w obrębie śladu piksela i rozmycie przy dużym pomniejszeniu.
         */
        fun fromLuma(src: ByteBuffer, rowStride: Int, bw: Int, bh: Int, rot: Int, out: GrayFrame) {
            val ow = out.w; val oh = out.h
            val portrait = rot % 180 != 0
            // Ślad piksela wyjściowego w pikselach bufora.
            val fx = (if (portrait) bw.toFloat() / oh else bw.toFloat() / ow)
            val fy = (if (portrait) bh.toFloat() / ow else bh.toFloat() / oh)
            val ox = fx * 0.25f; val oy = fy * 0.25f
            val maxX = bw - 1; val maxY = bh - 1
            for (y in 0 until oh) {
                val v = (y + 0.5f) / oh
                for (x in 0 until ow) {
                    val u = (x + 0.5f) / ow
                    val bs: Float
                    val bt: Float
                    when (rot) {
                        90 -> { bs = v; bt = 1f - u }
                        180 -> { bs = 1f - u; bt = 1f - v }
                        270 -> { bs = 1f - v; bt = u }
                        else -> { bs = u; bt = v }
                    }
                    val cx = bs * bw; val cy = bt * bh
                    val xa = (cx - ox).toInt().coerceIn(0, maxX); val xb = (cx + ox).toInt().coerceIn(0, maxX)
                    val ya = (cy - oy).toInt().coerceIn(0, maxY) * rowStride; val yb = (cy + oy).toInt().coerceIn(0, maxY) * rowStride
                    val s = (src.get(ya + xa).toInt() and 0xFF) + (src.get(ya + xb).toInt() and 0xFF) +
                        (src.get(yb + xa).toInt() and 0xFF) + (src.get(yb + xb).toInt() and 0xFF)
                    out.px[y * ow + x] = s * (1f / (4f * 255f))
                }
            }
            if (fx > 1.6f) out.blur3()
            out.computeStats()
        }
    }
}

/** Zakres płótna (w pikselach) zajęty przez klatkę; szerokość może zawijać się przez szew. */
data class LonLatBox(val x0: Int, val width: Int, val y0: Int, val y1: Int) {
    /** Ten sam zakres na płótnie szerokości [toW] (zamiast [fromW]). */
    fun scaled(fromW: Int, toW: Int): LonLatBox {
        if (fromW == toW) return this
        val s = toW.toDouble() / fromW
        val nw = if (width >= fromW) toW else min(toW, (width * s).toInt() + 2)
        return LonLatBox((x0 * s).toInt(), nw, (y0 * s).toInt(), min(toW / 2 - 1, ((y1 + 1) * s).toInt()))
    }

    companion object {
        /**
         * Wyznacza zakres na płótnie [cw]×[ch] z kierunków brzegu kadru; przy biegunie
         * albo dużym rozrzucie – pełna szerokość.
         */
        fun of(border: List<Vec3>, forward: Vec3, cw: Int, ch: Int, poleInside: Boolean): LonLatBox {
            var latMin = 90f; var latMax = -90f
            for (d in border) {
                val l = d.lat / DEG
                latMin = min(latMin, l); latMax = max(latMax, l)
            }
            if (poleInside) {
                if (forward.z > 0) latMax = 90f else latMin = -90f
            }
            val y0 = ((0.5f - latMax / 180f) * ch).toInt() - 2
            val y1 = ((0.5f - latMin / 180f) * ch).toInt() + 2
            val fLon = forward.lon / DEG
            var dMin = 0f; var dMax = 0f
            for (d in border) {
                var dl = d.lon / DEG - fLon
                while (dl > 180f) dl -= 360f
                while (dl < -180f) dl += 360f
                dMin = min(dMin, dl); dMax = max(dMax, dl)
            }
            val full = poleInside || dMax - dMin > 300f || latMax > 80f || latMin < -80f
            val yy0 = y0.coerceIn(0, ch - 1)
            val yy1 = y1.coerceIn(0, ch - 1)
            if (full) return LonLatBox(0, cw, yy0, yy1)
            val x0 = (((fLon + dMin) / 360f + 0.5f) * cw).toInt() - 2
            val width = ((dMax - dMin) / 360f * cw).toInt() + 5
            return LonLatBox(((x0 % cw) + cw) % cw, min(width, cw), yy0, yy1)
        }
    }
}
