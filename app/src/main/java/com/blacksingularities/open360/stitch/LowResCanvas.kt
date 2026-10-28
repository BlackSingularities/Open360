package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.data.BlendMode
import com.blacksingularities.open360.data.CaptureRange
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.PI_F
import com.blacksingularities.open360.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Szara, niska rozdzielczość panoramy (0,25°/px) prowadzona równolegle z płótnem na GPU.
 * Służy do korekty pozy obrazem (dopasowanie klatki do tego, co już zszyte – idea z OpenPano,
 * ale zamiast cech SIFT szukamy obrotu minimalizującego różnicę jasności), do kompensacji
 * ekspozycji i do liczenia pokrycia.
 */
class LowResCanvas {
    val w = 1440
    val h = 720
    val gray = FloatArray(w * h)
    val weight = FloatArray(w * h)

    fun clear() {
        gray.fill(0f)
        weight.fill(0f)
    }

    fun copyFrom(o: LowResCanvas) {
        o.gray.copyInto(gray)
        o.weight.copyInto(weight)
    }

    /** Dwuliniowe próbkowanie w kierunku [d]; NaN, gdy brak danych. */
    fun sample(d: Vec3, minWeight: Float = 0.25f): Float {
        val lon = kotlin.math.atan2(d.x, d.y)
        val lat = kotlin.math.asin(d.z.coerceIn(-1f, 1f))
        val fx = (lon / (2 * PI_F) + 0.5f) * w - 0.5f
        val fy = (0.5f - lat / PI_F) * h - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt().coerceIn(0, h - 2)
        val tx = fx - x0
        val ty = (fy - y0).coerceIn(0f, 1f)
        val xa = ((x0 % w) + w) % w
        val xb = (xa + 1) % w
        val i00 = y0 * w + xa; val i01 = y0 * w + xb
        val i10 = i00 + w; val i11 = i01 + w
        if (weight[i00] < minWeight || weight[i01] < minWeight || weight[i10] < minWeight || weight[i11] < minWeight) return Float.NaN
        val a = gray[i00] + (gray[i01] - gray[i00]) * tx
        val b = gray[i10] + (gray[i11] - gray[i10]) * tx
        return a + (b - a) * ty
    }

    /**
     * Wpisuje klatkę ([frame] – szary obraz klatki, [panoToCam] – kierunek panoramy → układ kamery)
     * tą samą regułą łączenia co shader na GPU.
     */
    fun splat(frame: GrayFrame, panoToCam: Mat3, tanX: Float, tanY: Float, gain: Float, blend: BlendMode, feather: Float, box: LonLatBox) {
        val f = max(0.02f, feather)
        for (py in box.y0..box.y1) {
            val lat = (0.5f - (py + 0.5f) / h) * PI_F
            val cl = cos(lat); val sl = sin(lat)
            for (k in 0 until box.width) {
                val px = (box.x0 + k) % w
                val lon = ((px + 0.5f) / w - 0.5f) * 2 * PI_F
                val dx = cl * sin(lon); val dy = cl * cos(lon)
                val m = panoToCam.m
                val cx = m[0] * dx + m[1] * dy + m[2] * sl
                val cy = m[3] * dx + m[4] * dy + m[5] * sl
                val cz = m[6] * dx + m[7] * dy + m[8] * sl
                if (cz >= -1e-4f) continue
                val u = 0.5f + 0.5f * (cx / -cz) / tanX
                val v = 0.5f - 0.5f * (cy / -cz) / tanY
                if (u <= 0f || u >= 1f || v <= 0f || v >= 1f) continue
                val c = frame.sample(u, v) * gain
                val e = min(min(u, 1 - u), min(v, 1 - v)) * 2f
                val wNew = (e / f).coerceIn(0f, 1f)
                val i = py * w + px
                val wOld = weight[i]
                when (blend) {
                    BlendMode.FEATHER -> {
                        val tot = wOld + wNew
                        if (tot > 0f) gray[i] = (gray[i] * wOld + c * wNew) / tot
                        weight[i] = min(1f, tot)
                    }
                    BlendMode.BEST -> {
                        val t = ((wNew - wOld) * 6f).coerceIn(0f, 1f)
                        gray[i] += (c - gray[i]) * t
                        weight[i] = max(wOld, wNew)
                    }
                    BlendMode.FILL -> {
                        val t = wNew * (1f - wOld)
                        gray[i] += (c - gray[i]) * (if (wOld < 0.004f) 1f else t)
                        weight[i] = max(wOld, wNew)
                    }
                    BlendMode.OVERWRITE -> {
                        gray[i] += (c - gray[i]) * wNew
                        weight[i] = max(wOld, wNew)
                    }
                }
            }
        }
    }

    /** Pokrycie zadanego zakresu (0..1), ważone polem powierzchni na sferze. */
    fun coverage(range: CaptureRange): Float {
        var tot = 0.0
        var cov = 0.0
        val lonHalf = range.lonSpan / 2f
        for (py in 0 until h step 2) {
            val latDeg = 90f - (py + 0.5f) * 180f / h
            if (latDeg < range.latMin || latDeg > range.latMax) continue
            val a = cos(latDeg * DEG).toDouble()
            for (px in 0 until w step 2) {
                val lonDeg = (px + 0.5f) * 360f / w - 180f
                if (abs(lonDeg) > lonHalf) continue
                tot += a
                if (weight[py * w + px] > 0.05f) cov += a
            }
        }
        return if (tot > 0) (cov / tot).toFloat() else 0f
    }

    /** Prostokąt (w pikselach płótna o szerokości [fullW]) obejmujący uchwyconą treść. */
    fun contentBounds(fullW: Int, fullH: Int): IntArray? {
        var y0 = h; var y1 = -1
        val cols = BooleanArray(w)
        for (py in 0 until h) for (px in 0 until w) {
            if (weight[py * w + px] > 0.02f) {
                if (py < y0) y0 = py
                if (py > y1) y1 = py
                cols[px] = true
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

/** Szary, pomniejszony obraz klatki (u, v ∈ 0..1, v w dół). */
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
}

/** Zakres płótna (w pikselach) zajęty przez klatkę; szerokość może zawijać się przez szew. */
data class LonLatBox(val x0: Int, val width: Int, val y0: Int, val y1: Int) {
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
