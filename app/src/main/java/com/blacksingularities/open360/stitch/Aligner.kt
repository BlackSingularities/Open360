package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.FastMath
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Punkty klatki użyte do dopasowania: współrzędne w kadrze, tangensowe i jasność. */
class SampleSet(capacity: Int) {
    var n = 0
        private set
    val u = FloatArray(capacity)
    val v = FloatArray(capacity)
    val f = FloatArray(capacity)

    fun add(uu: Float, vv: Float, ff: Float) {
        if (n >= u.size) return
        u[n] = uu; v[n] = vv; f[n] = ff
        n++
    }

    companion object {
        /** Równomierna siatka co [step] pikseli. */
        fun dense(frame: GrayFrame, step: Int): SampleSet {
            val nx = (frame.w - 4 + step - 1) / step
            val ny = (frame.h - 4 + step - 1) / step
            val s = SampleSet(nx * ny)
            var y = 2
            while (y < frame.h - 2) {
                var x = 2
                while (x < frame.w - 2) {
                    s.add((x + 0.5f) / frame.w, (y + 0.5f) / frame.h, frame.px[y * frame.w + x])
                    x += step
                }
                y += step
            }
            return s
        }

        /**
         * Punkty o największym gradiencie – po jednym na komórkę [cell]×[cell], żeby pokryć cały
         * kadr. Gładkie fragmenty (niebo, ściana) nic nie wnoszą do dopasowania.
         */
        fun textured(frame: GrayFrame, cell: Int, maxN: Int): SampleSet {
            val w = frame.w; val h = frame.h; val p = frame.px
            var c = cell
            while (((w - 6) / c) * ((h - 6) / c) > maxN * 2) c++
            val cx = (w - 6) / c; val cy = (h - 6) / c
            val mags = FloatArray(cx * cy)
            val best = IntArray(cx * cy)
            var sum = 0.0
            for (j in 0 until cy) for (i in 0 until cx) {
                var bm = -1f; var bi = 0
                for (y in 3 + j * c until 3 + (j + 1) * c) {
                    val r = y * w
                    for (x in 3 + i * c until 3 + (i + 1) * c) {
                        val gx = p[r + x + 1] - p[r + x - 1]
                        val gy = p[r + x + w] - p[r + x - w]
                        val m = gx * gx + gy * gy
                        if (m > bm) { bm = m; bi = r + x }
                    }
                }
                mags[j * cx + i] = bm
                best[j * cx + i] = bi
                sum += sqrt(bm)
            }
            val thr = (0.35 * sum / max(1, mags.size)).toFloat().let { it * it }.coerceAtLeast(1e-6f)
            val order = mags.indices.filter { mags[it] > thr }.sortedByDescending { mags[it] }
            val s = SampleSet(min(maxN, order.size))
            for (k in 0 until s.u.size) {
                val i = best[order[k]]
                val x = i % w; val y = i / w
                s.add((x + 0.5f) / w, (y + 0.5f) / h, p[i])
            }
            return s
        }
    }
}

/** Wynik dopasowania klatki do panoramy. */
class AlignResult(
    val status: Status,
    /** Poprawiona poza (urządzenie → panorama) albo poza wejściowa. */
    val devToPano: Mat3,
    val ncc: Float,
    /** Udział próbek trafiających w uchwyconą treść. */
    val overlap: Float,
    val valid: Int,
    /** Poprawka kąta widzenia z tej klatki (mnożnik tangensów). */
    val fovScale: Float = 1f,
    /** Czy obrót był dobrze wyznaczony we wszystkich osiach (tekstura w różnych kierunkach). */
    val conditioned: Boolean = true,
) {
    enum class Status { OK, WEAK, MISMATCH, NO_OVERLAP }
}

/** Statystyki fotometryczne zakładki: wzmocnienie i dane do oszacowania winietowania. */
class PhotoStats {
    var n = 0
    var gain = 0f
    // Regresja z = c/f względem x = r² (liniowo w świetle liniowym): z ≈ g + g·k·x.
    var sw = 0.0; var sx = 0.0; var sz = 0.0; var sxx = 0.0; var sxz = 0.0
}

/**
 * Dopasowanie obrazem. Dwa etapy:
 *  1. zgrubnie – przeszukanie siatki obrotów (yaw/pitch ±4°, przechył ±3°) miarą NCC na
 *     panoramie niskiej rozdzielczości (odporne na duże błędy czujnika),
 *  2. dokładnie – Gauss-Newton (Lucas-Kanade na sferze) na punktach o silnym gradiencie:
 *     trzy kąty obrotu, skala ogniskowej i liniowa fotometria (a·f + b), z wagami Hubera
 *     odpornymi na ruchome obiekty i paralaksę. Daje dokładność ułamka piksela.
 */
object Aligner {
    private const val MIN_OVERLAP = 0.15f

    // ── Etap 1: przeszukanie siatki ─────────────────────────────────────────

    private class Rays(val x: FloatArray, val y: FloatArray, val z: FloatArray)

    private fun rays(s: SampleSet, geom: FrameGeom, scale: Float = 1f): Rays {
        val rx = FloatArray(s.n); val ry = FloatArray(s.n); val rz = FloatArray(s.n)
        for (i in 0 until s.n) {
            val d = geom.rayAt((2f * s.u[i] - 1f) * geom.tanX * scale, (1f - 2f * s.v[i]) * geom.tanY * scale)
            rx[i] = d.x; ry[i] = d.y; rz[i] = d.z
        }
        return Rays(rx, ry, rz)
    }

    /** (1 − NCC, udział próbek w zakładce). [stride] – co która próbka. */
    private fun nccScore(canvas: GrayCanvas, rot: Mat3, s: SampleSet, r: Rays, stride: Int): FloatArray {
        var n = 0
        var sf = 0.0; var sc = 0.0; var sff = 0.0; var scc = 0.0; var sfc = 0.0
        val m = rot.m
        var total = 0
        var i = 0
        while (i < s.n) {
            total++
            val x = r.x[i]; val y = r.y[i]; val z = r.z[i]
            val c = canvas.sample(
                Vec3(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z),
            )
            if (!c.isNaN()) {
                val f = s.f[i].toDouble()
                n++
                sf += f; sc += c; sff += f * f; scc += c * c; sfc += f * c
            }
            i += stride
        }
        if (n < 20) return floatArrayOf(2f, n.toFloat() / max(1, total))
        val mf = sf / n; val mc = sc / n
        val vf = sff / n - mf * mf
        val vc = scc / n - mc * mc
        val cov = sfc / n - mf * mc
        val ncc = if (vf > 1e-6 && vc > 1e-6) cov / sqrt(vf * vc) else 0.0
        return floatArrayOf((1 - ncc).toFloat(), n.toFloat() / total)
    }

    /**
     * Zgrubne dopasowanie: najlepszy obrót z siatki (1° → 0,5° → 0,25°) – zwraca pozę
     * wraz z oceną. Zakres wystarcza na typowy dryf żyroskopu i opóźnienia czujnika.
     */
    fun coarse(canvas: GrayCanvas, s: SampleSet, geom: FrameGeom, devToPano: Mat3): AlignResult {
        val r = rays(s, geom)
        val base = nccScore(canvas, devToPano, s, r, 1)
        if (base[1] < MIN_OVERLAP) return AlignResult(AlignResult.Status.NO_OVERLAP, devToPano, 0f, base[1], 0)
        val forward = devToPano.transform(Vec3(0f, 0f, -1f))
        val right = forward.cross(Vec3(0f, 0f, 1f)).let { if (it.length() < 1e-3f) Vec3(1f, 0f, 0f) else it.normalized() }

        fun delta(yaw: Float, pitch: Float, roll: Float): Mat3 =
            Mat3.axisAngle(forward, roll * DEG) * Mat3.rotZ(yaw * DEG) * Mat3.axisAngle(right, pitch * DEG)

        var bY = 0f; var bP = 0f; var bR = 0f
        var best = nccScore(canvas, devToPano, s, r, 4)
        // Próby w danym etapie liczone równolegle, wybór najlepszej po kolei (powtarzalnie).
        fun stage(cands: List<FloatArray>, stride: Int) {
            val scores = arrayOfNulls<FloatArray>(cands.size)
            Parallel.forRange(0, cands.size) { a, b ->
                for (k in a until b) {
                    val c = cands[k]
                    scores[k] = nccScore(canvas, delta(c[0], c[1], c[2]) * devToPano, s, r, stride)
                }
            }
            for (k in cands.indices) {
                val sc = scores[k]!!
                if (sc[1] >= MIN_OVERLAP && sc[0] < best[0]) {
                    best = sc; bY = cands[k][0]; bP = cands[k][1]; bR = cands[k][2]
                }
            }
        }
        stage(buildList { for (y in -4..4) for (p in -4..4) if (y != 0 || p != 0) add(floatArrayOf(y.toFloat(), p.toFloat(), 0f)) }, 4)
        run {
            val y0 = bY; val p0 = bP
            stage(buildList { for (rr in -3..3) if (rr != 0) add(floatArrayOf(y0, p0, rr.toFloat())) }, 4)
        }
        best = nccScore(canvas, delta(bY, bP, bR) * devToPano, s, r, 1)
        run {
            val y0 = bY; val p0 = bP; val r0 = bR
            stage(
                buildList {
                    for (dy in -1..1) for (dp in -1..1) for (dr in -1..1) {
                        if (dy != 0 || dp != 0 || dr != 0) add(floatArrayOf(y0 + dy * 0.5f, p0 + dp * 0.5f, r0 + dr * 0.5f))
                    }
                },
                1,
            )
        }
        run {
            val y0 = bY; val p0 = bP; val r0 = bR
            stage(buildList { for (dy in -1..1) for (dp in -1..1) if (dy != 0 || dp != 0) add(floatArrayOf(y0 + dy * 0.25f, p0 + dp * 0.25f, r0)) }, 1)
        }
        val ncc = 1f - best[0]
        val rot = delta(bY, bP, bR) * devToPano
        return when {
            ncc < 0.35f && best[1] > 0.4f -> AlignResult(AlignResult.Status.MISMATCH, devToPano, ncc, best[1], 0)
            ncc < 0.6f -> AlignResult(AlignResult.Status.WEAK, devToPano, ncc, best[1], 0)
            else -> AlignResult(AlignResult.Status.OK, rot, ncc, best[1], 0)
        }
    }

    // ── Etap 2: Gauss-Newton ────────────────────────────────────────────────

    /**
     * Dokładne dopasowanie od pozy [init]. [estimateFov] – czy szacować skalę ogniskowej
     * (silnie regularyzowaną; właściwą kalibrację uśrednia silnik po wielu klatkach).
     */
    fun refine(
        canvas: GrayCanvas,
        s: SampleSet,
        geom: FrameGeom,
        init: Mat3,
        estimateFov: Boolean = false,
        iterations: Int = 10,
    ): AlignResult {
        val n = s.n
        if (n < 40) return AlignResult(AlignResult.Status.NO_OVERLAP, init, 0f, 0f, 0)
        val inv = 1f / canvas.radPerPx
        var rot = init
        var scale = 1f
        var a = 1.0; var b = 0.0
        var sigma = 0.08f
        val res = FloatArray(n)
        val H = DoubleArray(36)
        val g = DoubleArray(6)
        val J = DoubleArray(6)
        var valid = 0
        var hww = DoubleArray(9)
        for (it in 0 until iterations) {
            H.fill(0.0); g.fill(0.0)
            valid = 0
            var cnt = 0
            val m = rot.m
            for (i in 0 until n) {
                val tx = (2f * s.u[i] - 1f) * geom.tanX * scale
                val ty = (1f - 2f * s.v[i]) * geom.tanY * scale
                val p = geom.rayAt(tx, ty)
                val dx = m[0] * p.x + m[1] * p.y + m[2] * p.z
                val dy = m[3] * p.x + m[4] * p.y + m[5] * p.z
                val dz = m[6] * p.x + m[7] * p.y + m[8] * p.z
                val cl = sqrt(dx * dx + dy * dy)
                if (cl < 0.1f) continue
                val lon = FastMath.atan2(dx, dy)
                val lat = FastMath.asin(dz)
                val fx = canvas.px(lon); val fy = canvas.py(lat)
                val c = canvas.sampleAt(fx, fy)
                if (c.isNaN()) continue
                val cxp = canvas.sampleAt(fx + 1f, fy); val cxm = canvas.sampleAt(fx - 1f, fy)
                val cyp = canvas.sampleAt(fx, fy + 1f); val cym = canvas.sampleAt(fx, fy - 1f)
                if (cxp.isNaN() || cxm.isNaN() || cyp.isNaN() || cym.isNaN()) continue
                valid++
                // Gradient na sferze: wzdłuż równoleżnika (E) i południka (N).
                val gE = (cxp - cxm) * 0.5f * inv / cl
                val gN = -(cyp - cym) * 0.5f * inv
                val sinL = dx / cl; val cosL = dy / cl
                val gx = gE * cosL - gN * dz * sinL
                val gy = -gE * sinL - gN * dz * cosL
                val gz = gN * cl
                val r = (c - (a * s.f[i] + b)).toFloat()
                res[cnt++] = abs(r)
                // ∂I/∂ω = d × G
                J[0] = (dy * gz - dz * gy).toDouble()
                J[1] = (dz * gx - dx * gz).toDouble()
                J[2] = (dx * gy - dy * gx).toDouble()
                if (estimateFov) {
                    val nn = 1f / (tx * tx + ty * ty + 1f)
                    val q = nn * sqrt(nn)
                    val px = tx * q; val py = ty * q; val pz = (tx * tx + ty * ty) * q
                    val ex = m[0] * px + m[1] * py + m[2] * pz
                    val ey = m[3] * px + m[4] * py + m[5] * pz
                    val ez = m[6] * px + m[7] * py + m[8] * pz
                    J[3] = (gx * ex + gy * ey + gz * ez).toDouble()
                } else {
                    J[3] = 0.0
                }
                J[4] = -s.f[i].toDouble()
                J[5] = -1.0
                val k = 1.5f * sigma
                val wgt = if (abs(r) <= k) 1.0 else (k / abs(r)).toDouble()
                for (p1 in 0 until 6) {
                    val jw = J[p1] * wgt
                    g[p1] += jw * r
                    for (p2 in p1 until 6) H[p1 * 6 + p2] += jw * J[p2]
                }
            }
            if (valid < 30 || valid < n * MIN_OVERLAP) {
                return AlignResult(AlignResult.Status.NO_OVERLAP, init, 0f, valid.toFloat() / n, valid)
            }
            for (p1 in 0 until 6) for (p2 in 0 until p1) H[p1 * 6 + p2] = H[p2 * 6 + p1]
            hww = DoubleArray(9) { H[(it / 3) * 6 + it % 3] / valid }
            // Regularyzacja: skala tylko przy wyraźnych danych, przesunięcia jasności lekko.
            if (estimateFov) H[3 * 6 + 3] += 4.0 * H[3 * 6 + 3] + 1e-6 * valid else H[3 * 6 + 3] = 1.0
            for (p1 in 0 until 3) H[p1 * 6 + p1] += 1e-9 * valid + 1e-4 * H[p1 * 6 + p1]
            H[4 * 6 + 4] += 1e-3 * valid
            H[5 * 6 + 5] += 1e-3 * valid
            val delta = solve(H, g.map { -it }.toDoubleArray(), 6) ?: break
            val w = Vec3(delta[0].toFloat(), delta[1].toFloat(), delta[2].toFloat())
            // Krok ograniczony do 1° – Gauss-Newton daleko od minimum bywa zbyt śmiały.
            val wl = w.length()
            val wStep = if (wl > DEG) w * (DEG / wl) else w
            rot = (Mat3.exp(wStep) * rot).orthonormalized()
            if (estimateFov) scale = (scale * (1f + delta[3].toFloat().coerceIn(-0.01f, 0.01f))).coerceIn(0.9f, 1.1f)
            a += delta[4]
            b += delta[5]
            sigma = (1.4826f * median(res, cnt)).coerceIn(0.004f, 0.15f)
            if (wl < 3e-6f && abs(delta[3]) < 1e-5) break
        }

        // Ocena końcowa: NCC na tych samych punktach.
        var cnt = 0
        var sf = 0.0; var sc = 0.0; var sff = 0.0; var scc = 0.0; var sfc = 0.0
        val m = rot.m
        for (i in 0 until n) {
            val p = geom.rayAt((2f * s.u[i] - 1f) * geom.tanX * scale, (1f - 2f * s.v[i]) * geom.tanY * scale)
            val c = canvas.sample(
                Vec3(
                    m[0] * p.x + m[1] * p.y + m[2] * p.z,
                    m[3] * p.x + m[4] * p.y + m[5] * p.z,
                    m[6] * p.x + m[7] * p.y + m[8] * p.z,
                ),
            )
            if (c.isNaN()) continue
            val f = s.f[i].toDouble()
            cnt++
            sf += f; sc += c; sff += f * f; scc += c * c; sfc += f * c
        }
        val overlap = cnt.toFloat() / n
        if (cnt < 30) return AlignResult(AlignResult.Status.NO_OVERLAP, init, 0f, overlap, cnt)
        val mf = sf / cnt; val mc = sc / cnt
        val vf = sff / cnt - mf * mf; val vc = scc / cnt - mc * mc
        val ncc = (if (vf > 1e-7 && vc > 1e-7) (sfc / cnt - mf * mc) / sqrt(vf * vc) else 0.0).toFloat()
        val eig = eigenSym3(hww)
        val conditioned = eig[0] > 0.02 * eig[2] && eig[2] > 1e-3
        // Odsuwamy się od wyniku, gdy obrót uciekł daleko od startu – to raczej fałszywe minimum.
        val moved = (rot * init.transposed()).log().length()
        val status = when {
            ncc < 0.45f && overlap > 0.35f -> AlignResult.Status.MISMATCH
            ncc < 0.6f || moved > 3f * DEG -> AlignResult.Status.WEAK
            !conditioned -> AlignResult.Status.WEAK
            else -> AlignResult.Status.OK
        }
        val out = if (status == AlignResult.Status.OK) rot else init
        return AlignResult(status, out, ncc, overlap, cnt, if (status == AlignResult.Status.OK) scale else 1f, conditioned)
    }

    /**
     * Statystyki fotometryczne w pozie [rot]: wzmocnienie (liniowo, bez prześwietleń i cieni)
     * oraz sumy do regresji winietowania. [vignette] – bieżąca korekta winietowania.
     */
    fun photometry(canvas: GrayCanvas, s: SampleSet, geom: FrameGeom, rot: Mat3, vignette: Float): PhotoStats {
        val st = PhotoStats()
        var sfc = 0.0; var sff = 0.0
        val m = rot.m
        for (i in 0 until s.n) {
            val f = s.f[i]
            if (f < 0.06f || f > 0.94f) continue
            val p = geom.rayAt((2f * s.u[i] - 1f) * geom.tanX, (1f - 2f * s.v[i]) * geom.tanY)
            val c = canvas.sample(
                Vec3(
                    m[0] * p.x + m[1] * p.y + m[2] * p.z,
                    m[3] * p.x + m[4] * p.y + m[5] * p.z,
                    m[6] * p.x + m[7] * p.y + m[8] * p.z,
                ),
            )
            if (c.isNaN() || c < 0.06f || c > 0.94f) continue
            val r2 = Photometry.radius2(s.u[i], s.v[i], geom)
            val fl = Gamma.lin(f).toDouble()
            val cl = Gamma.lin(c).toDouble()
            val fc = fl * (1 + vignette * r2)
            sfc += fc * cl; sff += fc * fc
            val z = cl / fl
            if (z > 0.2 && z < 5.0) {
                val w = fl
                st.sw += w; st.sx += w * r2; st.sz += w * z; st.sxx += w * r2 * r2; st.sxz += w * r2 * z
            }
            st.n++
        }
        st.gain = if (sff > 1e-6 && st.n >= 40) (sfc / sff).toFloat() else 0f
        return st
    }

    // ── Algebra ─────────────────────────────────────────────────────────────

    private fun median(v: FloatArray, n: Int): Float {
        if (n == 0) return 0.05f
        val c = v.copyOf(n)
        c.sort()
        return c[n / 2]
    }

    /** Eliminacja Gaussa z wyborem elementu głównego; null, gdy układ osobliwy. */
    fun solve(a0: DoubleArray, b0: DoubleArray, n: Int): DoubleArray? {
        val a = a0.copyOf()
        val b = b0.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[piv * n + col])) piv = r
            if (abs(a[piv * n + col]) < 1e-15) return null
            if (piv != col) {
                for (k in 0 until n) { val t = a[col * n + k]; a[col * n + k] = a[piv * n + k]; a[piv * n + k] = t }
                val t = b[col]; b[col] = b[piv]; b[piv] = t
            }
            for (r in col + 1 until n) {
                val f = a[r * n + col] / a[col * n + col]
                if (f == 0.0) continue
                for (k in col until n) a[r * n + k] -= f * a[col * n + k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r * n + k] * x[k]
            x[r] = s / a[r * n + r]
        }
        return x
    }

    /** Wartości własne symetrycznej macierzy 3×3 (metoda Jacobiego), rosnąco. */
    fun eigenSym3(m0: DoubleArray): DoubleArray {
        val a = m0.copyOf()
        repeat(12) {
            for ((p, q) in arrayOf(0 to 1, 0 to 2, 1 to 2)) {
                val apq = a[p * 3 + q]
                if (abs(apq) < 1e-18) continue
                val app = a[p * 3 + p]; val aqq = a[q * 3 + q]
                val th = 0.5 * kotlin.math.atan2(2 * apq, aqq - app)
                val c = kotlin.math.cos(th); val s = kotlin.math.sin(th)
                for (k in 0 until 3) {
                    val akp = a[k * 3 + p]; val akq = a[k * 3 + q]
                    a[k * 3 + p] = c * akp - s * akq
                    a[k * 3 + q] = s * akp + c * akq
                }
                for (k in 0 until 3) {
                    val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]
                    a[p * 3 + k] = c * apk - s * aqk
                    a[q * 3 + k] = s * apk + c * aqk
                }
            }
        }
        return doubleArrayOf(a[0], a[4], a[8]).sortedArray()
    }
}
