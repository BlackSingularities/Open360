package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Wspólny obiektyw wszystkich klatek: połówkowe tangensy kąta widzenia i dystorsja k1. */
data class Lens(val tanX: Float, val tanY: Float, val k1: Float) {
    fun geom(kf: Keyframe) = FrameGeom(tanX, tanY, kf.rot, kf.skew, kf.omega, k1)
    fun scaled(s: Float, k: Float) = Lens(tanX * s, tanY * s, k)
}

/** Para punktów odpowiadających sobie w klatkach [i] i [j] (współrzędne 0..1, v w dół). */
class Match(
    val i: Int, val j: Int,
    val ui: Float, val vi: Float,
    val uj: Float, val vj: Float,
    /** Średnia jasność (gamma) otoczenia punktu w obu klatkach – do wyrównania ekspozycji. */
    val mi: Float, val mj: Float,
)

/** Wynik globalnego dopasowania. */
class GlobalResult(
    /** Urządzenie → panorama dla każdej klatki. */
    val poses: List<Mat3>,
    val lens: Lens,
    /** Wzmocnienie (światło liniowe) każdej klatki. */
    val gains: FloatArray,
    /** Błąd dopasowania po optymalizacji (RMS, stopnie) i przed nią. */
    val rmsDeg: Float,
    val rmsBeforeDeg: Float,
    val matches: Int,
    val pairs: Int,
    /** Udział punktów odrzuconych jako niezgodne z obrotem (paralaksa, ruch w kadrze). */
    val outlierFraction: Float,
)

/**
 * Globalne dopasowanie wszystkich zachowanych klatek naraz (jak Hugin czy PTGui, ale bez
 * udziału użytkownika):
 *  1. w każdej parze nachodzących klatek punkty o silnej teksturze (Shi-Tomasi) są śledzone
 *     piramidalnym Lucasem-Kanade od miejsca przewidzianego przez pozy na żywo, z testem
 *     w obie strony i dopasowaniem obrotu metodą RANSAC (ruch i paralaksa odpadają),
 *  2. bundle adjustment (Levenberg-Marquardt): obroty wszystkich klatek, wspólna skala kąta
 *     widzenia i dystorsja k1 minimalizują rozbieżność promieni w odpowiadających punktach,
 *     ze słabym przyciąganiem do poz z czujnika (trzyma poziom horyzontu),
 *  3. wzmocnienia klatek z jasności tych samych punktów (kompensacja jak u Browna i Lowe'a).
 * Pomiar nie zależy od założonej ogniskowej, więc obiektyw da się z niego wyznaczyć.
 */
object GlobalAligner {
    private const val LEVELS = 4
    private const val WIN = 6
    private const val MAX_PER_PAIR = 70
    private const val CELL = 16
    @JvmField var DEBUG = false

    // ── Piramida i cechy ────────────────────────────────────────────────────

    /**
     * Piramida obrazu w bajtach (poziom 0 to obraz zachowanej klatki bez kopiowania) – przy
     * ~150 klatkach piramidy zmiennoprzecinkowe zajęłyby kilkaset MB.
     */
    class Pyramid(kf: Keyframe) {
        val w = IntArray(LEVELS)
        val h = IntArray(LEVELS)
        val lv = arrayOfNulls<ByteArray>(LEVELS)

        init {
            w[0] = kf.gw; h[0] = kf.gh
            lv[0] = kf.gray
            for (l in 1 until LEVELS) {
                val pw = w[l - 1]; val ph = h[l - 1]
                val nw = max(1, pw / 2); val nh = max(1, ph / 2)
                val src = lv[l - 1]!!
                val dst = ByteArray(nw * nh)
                for (y in 0 until nh) for (x in 0 until nw) {
                    val x0 = min(pw - 1, 2 * x); val x1 = min(pw - 1, 2 * x + 1)
                    val y0 = min(ph - 1, 2 * y); val y1 = min(ph - 1, 2 * y + 1)
                    val s = (src[y0 * pw + x0].toInt() and 0xFF) + (src[y0 * pw + x1].toInt() and 0xFF) +
                        (src[y1 * pw + x0].toInt() and 0xFF) + (src[y1 * pw + x1].toInt() and 0xFF)
                    dst[y * nw + x] = ((s + 2) / 4).toByte()
                }
                w[l] = nw; h[l] = nh; lv[l] = dst
            }
        }

        /** Piksel poziomu [l] (0..1). */
        fun px(l: Int, i: Int): Float = (lv[l]!![i].toInt() and 0xFF) * (1f / 255f)

        /** Dwuliniowo w pikselach poziomu [l] (środek piksela = całkowita); NaN poza obrazem. */
        fun at(l: Int, x: Float, y: Float): Float {
            val ww = w[l]; val hh = h[l]
            if (x < 0f || y < 0f || x > ww - 1.001f || y > hh - 1.001f) return Float.NaN
            val x0 = x.toInt(); val y0 = y.toInt()
            val tx = x - x0; val ty = y - y0
            val i = y0 * ww + x0
            val p00 = px(l, i); val p01 = px(l, i + 1)
            val p10 = px(l, i + ww); val p11 = px(l, i + ww + 1)
            val a = p00 + (p01 - p00) * tx
            val b = p10 + (p11 - p10) * tx
            return a + (b - a) * ty
        }
    }

    /** Punkty o silnej teksturze w dwóch kierunkach – najlepszy w każdej komórce [CELL]². */
    fun features(p: Pyramid): FloatArray {
        val w = p.w[0]; val h = p.h[0]
        val img = FloatArray(w * h) { p.px(0, it) }
        val m = WIN + 2
        val out = ArrayList<FloatArray>()
        val scores = ArrayList<Float>()
        var cy = m
        while (cy + CELL <= h - m) {
            var cx = m
            while (cx + CELL <= w - m) {
                var best = 0f; var bx = 0; var by = 0
                var y = cy
                while (y < cy + CELL) {
                    var x = cx
                    while (x < cx + CELL) {
                        var sxx = 0f; var syy = 0f; var sxy = 0f
                        for (dy in -3..3) for (dx in -3..3) {
                            val i = (y + dy) * w + x + dx
                            val gx = img[i + 1] - img[i - 1]
                            val gy = img[i + w] - img[i - w]
                            sxx += gx * gx; syy += gy * gy; sxy += gx * gy
                        }
                        val tr = 0.5f * (sxx + syy)
                        val e = tr - sqrt(max(0f, 0.25f * (sxx - syy) * (sxx - syy) + sxy * sxy))
                        if (e > best) { best = e; bx = x; by = y }
                        x += 2
                    }
                    y += 2
                }
                if (best > 2e-3f) {
                    out += floatArrayOf(bx.toFloat(), by.toFloat(), best)
                    scores += best
                }
                cx += CELL
            }
            cy += CELL
        }
        // Najsilniejsze pierwsze.
        val order = out.indices.sortedByDescending { out[it][2] }
        val r = FloatArray(order.size * 2)
        for ((k, idx) in order.withIndex()) {
            r[2 * k] = out[idx][0]; r[2 * k + 1] = out[idx][1]
        }
        return r
    }

    // ── Śledzenie (piramidalny Lucas-Kanade, odwrotna kompozycja) ───────────

    private class Patch {
        val n = (2 * WIN + 1) * (2 * WIN + 1)
        val t = FloatArray(n)
        val gx = FloatArray(n)
        val gy = FloatArray(n)
    }

    /**
     * Śledzi punkt ([x], [y] – piksele poziomu 0 obrazu [a]) w obrazie [b], startując od
     * ([gx], [gy]). Zwraca położenie w [b] i NCC albo null.
     */
    fun track(a: Pyramid, x: Float, y: Float, b: Pyramid, gx: Float, gy: Float, out: FloatArray): Boolean {
        val patch = Patch()
        var dx = 0f; var dy = 0f
        for (l in LEVELS - 1 downTo 0) {
            val s = 1f / (1 shl l)
            val px = (x + 0.5f) * s - 0.5f
            val py = (y + 0.5f) * s - 0.5f
            if (l == LEVELS - 1) {
                dx = (gx - x) * s; dy = (gy - y) * s
            } else {
                dx *= 2f; dy *= 2f
            }
            // Szablon i jego gradient.
            var k = 0
            var mt = 0f
            var ok = true
            for (j in -WIN..WIN) for (i in -WIN..WIN) {
                val v = a.at(l, px + i, py + j)
                val vx = a.at(l, px + i + 1, py + j) - a.at(l, px + i - 1, py + j)
                val vy = a.at(l, px + i, py + j + 1) - a.at(l, px + i, py + j - 1)
                if (v.isNaN() || vx.isNaN() || vy.isNaN()) { ok = false; break }
                patch.t[k] = v; patch.gx[k] = 0.5f * vx; patch.gy[k] = 0.5f * vy
                mt += v
                k++
            }
            if (!ok) {
                if (l == 0) return false
                continue
            }
            mt /= patch.n
            var hxx = 0f; var hyy = 0f; var hxy = 0f
            for (q in 0 until patch.n) {
                hxx += patch.gx[q] * patch.gx[q]; hyy += patch.gy[q] * patch.gy[q]; hxy += patch.gx[q] * patch.gy[q]
            }
            val det = hxx * hyy - hxy * hxy
            if (det < 1e-10f) return false
            for (it in 0 until 15) {
                var bx = 0f; var by = 0f; var mj = 0f
                val vals = FloatArray(patch.n)
                k = 0
                var inside = true
                for (j in -WIN..WIN) {
                    for (i in -WIN..WIN) {
                        val v = b.at(l, px + dx + i, py + dy + j)
                        if (v.isNaN()) { inside = false; break }
                        vals[k] = v; mj += v
                        k++
                    }
                    if (!inside) break
                }
                if (!inside) return false
                mj /= patch.n
                for (q in 0 until patch.n) {
                    val e = (vals[q] - mj) - (patch.t[q] - mt)
                    bx += patch.gx[q] * e; by += patch.gy[q] * e
                }
                val sx = (hyy * bx - hxy * by) / det
                val sy = (hxx * by - hxy * bx) / det
                dx -= sx; dy -= sy
                if (sx * sx + sy * sy < 1e-4f) break
            }
        }
        // NCC w punkcie końcowym (poziom 0).
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        val fx = x + dx; val fy = y + dy
        for (j in -WIN..WIN) for (i in -WIN..WIN) {
            val va = a.at(0, x + i, y + j)
            val vb = b.at(0, fx + i, fy + j)
            if (va.isNaN() || vb.isNaN()) return false
            sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
        }
        val n = ((2 * WIN + 1) * (2 * WIN + 1)).toDouble()
        val va = saa / n - (sa / n) * (sa / n)
        val vb = sbb / n - (sb / n) * (sb / n)
        val ncc = if (va > 1e-8 && vb > 1e-8) (sab / n - (sa / n) * (sb / n)) / sqrt(va * vb) else 0.0
        out[0] = fx; out[1] = fy; out[2] = ncc.toFloat(); out[3] = (sa / n).toFloat(); out[4] = (sb / n).toFloat()
        return true
    }

    // ── Pary klatek ─────────────────────────────────────────────────────────

    private fun forward(m: Mat3) = m.transform(Vec3(0f, 0f, -1f))

    /** Współrzędne obrazu (0..1) promienia [ray] widzianego przez klatkę o geometrii [g] i pozie [pose]. */
    private fun projectTo(g: FrameGeom, pose: Mat3, world: Vec3, out: FloatArray): Boolean {
        val c = pose.transformT(world)
        return g.project(c.x, c.y, c.z, out)
    }

    private fun ray(g: FrameGeom, u: Float, v: Float): Vec3 = g.rayAt((2f * u - 1f) * g.tanX, (1f - 2f * v) * g.tanY).normalized()

    /** Pary klatek, które mogą się nakładać. */
    fun candidatePairs(frames: List<Keyframe>, lens: Lens): List<IntArray> {
        val fovX = 2f * atan(lens.tanX)
        val fovY = 2f * atan(lens.tanY)
        val lim = 0.5f * (fovX + fovY) * 0.92f
        val out = ArrayList<IntArray>()
        val fw = frames.map { forward(it.pose) }
        for (i in frames.indices) for (j in i + 1 until frames.size) {
            val a = kotlin.math.acos(fw[i].dot(fw[j]).coerceIn(-1f, 1f))
            if (a < lim) out += intArrayOf(i, j)
        }
        return out
    }

    /** Dopasowanie pary: śledzenie w obie strony, RANSAC obrotu, punkty zgodne. */
    fun matchPair(
        i: Int, j: Int, frames: List<Keyframe>, pyr: List<Pyramid>, feats: List<FloatArray>, lens: Lens,
        tracked: java.util.concurrent.atomic.AtomicInteger? = null,
    ): List<Match> {
        val a = frames[i]; val b = frames[j]
        val ga = lens.geom(a); val gb = lens.geom(b)
        val pa = pyr[i]; val pb = pyr[j]
        val f = feats[i]
        val uv = FloatArray(2)
        val res = FloatArray(5)
        val back = FloatArray(5)
        val margin = (WIN + 3).toFloat()
        val raw = ArrayList<Match>()
        var tried = 0
        var k = 0
        while (k < f.size / 2 && tried < MAX_PER_PAIR * 2 && raw.size < MAX_PER_PAIR) {
            val x = f[2 * k]; val y = f[2 * k + 1]
            k++
            val u = (x + 0.5f) / a.gw; val v = (y + 0.5f) / a.gh
            val world = a.pose.transform(ray(ga, u, v))
            if (!projectTo(gb, b.pose, world, uv)) continue
            val bx = uv[0] * b.gw - 0.5f; val by = uv[1] * b.gh - 0.5f
            if (bx < margin || by < margin || bx > b.gw - 1 - margin || by > b.gh - 1 - margin) continue
            tried++
            if (!track(pa, x, y, pb, bx, by, res)) continue
            if (res[2] < 0.75f) continue
            if (!track(pb, res[0], res[1], pa, x, y, back)) continue
            val ex = back[0] - x; val ey = back[1] - y
            if (ex * ex + ey * ey > 0.6f * 0.6f) continue
            raw += Match(i, j, u, v, (res[0] + 0.5f) / b.gw, (res[1] + 0.5f) / b.gh, res[3], res[4])
        }
        tracked?.addAndGet(raw.size)
        if (raw.size < 8) return emptyList()
        return ransac(raw, frames, lens)
    }

    /** RANSAC: poprawka obrotu względnego z dwóch punktów, próg 0,3°; zwraca punkty zgodne. */
    private fun ransac(ms: List<Match>, frames: List<Keyframe>, lens: Lens): List<Match> {
        val n = ms.size
        val A = arrayOfNulls<Vec3>(n); val B = arrayOfNulls<Vec3>(n)
        for ((k, m) in ms.withIndex()) {
            val fa = frames[m.i]; val fb = frames[m.j]
            A[k] = fa.pose.transform(ray(lens.geom(fa), m.ui, m.vi))
            B[k] = fb.pose.transform(ray(lens.geom(fb), m.uj, m.vj))
        }
        val thr = 0.3f * DEG
        var bestIn = BooleanArray(n)
        var bestCount = -1
        val rnd = java.util.Random(n * 7919L + ms[0].i * 31L + ms[0].j)
        fun fit(idx: List<Int>): Vec3? {
            // ω minimalizujące Σ |A + ω×A − B|² (małe kąty), dwie iteracje.
            var w = Vec3(0f, 0f, 0f)
            repeat(2) {
                val H = DoubleArray(9); val g = DoubleArray(3)
                val R = Mat3.exp(w)
                for (q in idx) {
                    val a = R.transform(A[q]!!); val bb = B[q]!!
                    val r = a - bb
                    // d(ω×a)/dω = −[a]×
                    val jx = doubleArrayOf(0.0, a.z.toDouble(), -a.y.toDouble())
                    val jy = doubleArrayOf(-a.z.toDouble(), 0.0, a.x.toDouble())
                    val jz = doubleArrayOf(a.y.toDouble(), -a.x.toDouble(), 0.0)
                    val rows = arrayOf(jx, jy, jz)
                    val rv = doubleArrayOf(r.x.toDouble(), r.y.toDouble(), r.z.toDouble())
                    for (row in 0..2) for (p1 in 0..2) {
                        g[p1] += rows[row][p1] * rv[row]
                        for (p2 in 0..2) H[p1 * 3 + p2] += rows[row][p1] * rows[row][p2]
                    }
                }
                for (d in 0..2) H[d * 4] += 1e-9
                val dlt = Aligner.solve(H, doubleArrayOf(-g[0], -g[1], -g[2]), 3) ?: return null
                w = (Mat3.exp(Vec3(dlt[0].toFloat(), dlt[1].toFloat(), dlt[2].toFloat())) * R).log()
            }
            return w
        }
        fun inliers(w: Vec3): BooleanArray {
            val R = Mat3.exp(w)
            return BooleanArray(n) { (R.transform(A[it]!!) - B[it]!!).length() < thr }
        }
        repeat(min(60, n * n)) {
            val p = rnd.nextInt(n)
            var q = rnd.nextInt(n)
            if (q == p) q = (q + 1) % n
            val w = fit(listOf(p, q)) ?: return@repeat
            if (w.length() > 8f * DEG) return@repeat
            val inl = inliers(w)
            val c = inl.count { it }
            if (c > bestCount) { bestCount = c; bestIn = inl }
        }
        if (bestCount < 8) return emptyList()
        val w = fit(bestIn.indices.filter { bestIn[it] }) ?: return emptyList()
        val inl = inliers(w)
        val out = ms.filterIndexed { k, _ -> inl[k] }
        return if (out.size >= 8) out else emptyList()
    }

    // ── Całość ──────────────────────────────────────────────────────────────

    /**
     * Pełne dopasowanie. [lens0] – obiektyw z kalibracji na żywo, [vignette] – winietowanie
     * (do kompensacji jasności), [progress] – postęp 0..1.
     */
    fun optimize(frames: List<Keyframe>, lens0: Lens, vignette: Float, progress: (Float) -> Unit = {}): GlobalResult? {
        val n = frames.size
        if (n < 2) return null
        val pyr = ArrayList<Pyramid>(n)
        val feats = arrayOfNulls<FloatArray>(n)
        for (kf in frames) pyr += Pyramid(kf)
        Parallel.forRange(0, n) { a, b -> for (k in a until b) feats[k] = features(pyr[k]) }
        progress(0.1f)
        val pairs = candidatePairs(frames, lens0)
        val results = arrayOfNulls<List<Match>>(pairs.size)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val tracked = java.util.concurrent.atomic.AtomicInteger()
        val featList = feats.map { it!! }
        Parallel.forRange(0, pairs.size) { a, b ->
            for (p in a until b) {
                results[p] = runCatching { matchPair(pairs[p][0], pairs[p][1], frames, pyr, featList, lens0, tracked) }.getOrDefault(emptyList())
                val d = done.incrementAndGet()
                if (d % 8 == 0) progress(0.1f + 0.5f * d / pairs.size)
            }
        }
        val matches = results.flatMap { it ?: emptyList() }
        val usedPairs = results.count { !it.isNullOrEmpty() }
        if (matches.size < 30 || usedPairs < 1) return null
        progress(0.6f)
        val ba = bundleAdjust(frames, matches, lens0) { progress(0.6f + 0.35f * it) }
        val gains = solveGains(frames, matches, ba.lens, vignette)
        progress(1f)
        val outlier = (1f - matches.size.toFloat() / max(1, tracked.get())).coerceIn(0f, 1f)
        return GlobalResult(ba.poses, ba.lens, gains, ba.rms / DEG, ba.rms0 / DEG, matches.size, usedPairs, outlier)
    }

    class BaResult(val poses: List<Mat3>, val lens: Lens, val rms: Float, val rms0: Float, val rejected: Int)

    /**
     * Levenberg-Marquardt: x = [obroty klatek (3N), ln skali kąta widzenia, k1].
     * Reszta punktu: R_i·a(u_i) − R_j·b(u_j) (wektor ≈ błąd kątowy), wagi Hubera.
     */
    fun bundleAdjust(frames: List<Keyframe>, ms: List<Match>, lens0: Lens, progress: (Float) -> Unit = {}): BaResult {
        val n = frames.size
        val M = 3 * n + 2
        val live = frames.map { it.pose }
        var poses = live.toMutableList()
        var lnS = 0.0
        var k1 = lens0.k1.toDouble()
        val sigmaPrior = 4.0 * DEG
        // Skala ciasno: przy paralaksie (obrót z telefonem w ręce) pętla 360° „wydłuża się” i wolna skala
        // udawałaby mniejszy kąt widzenia. Kształt obrazu (k1) paralaksa tak nie udaje.
        val sigmaS = 0.03
        val sigmaK = 0.12
        val huber = 0.15 * DEG
        // Szum pomiaru punktu (≈ pół piksela obrazu dopasowania) – waży dane względem przyciągania do czujnika.
        val sm = 0.05 * DEG
        val iw = 1.0 / (sm * sm)

        fun lensOf(ls: Double, kk: Double) = lens0.scaled(exp(ls).toFloat(), kk.toFloat())

        fun residualCost(ps: List<Mat3>, ls: Double, kk: Double): Pair<Double, Double> {
            val lens = lensOf(ls, kk)
            var cost = 0.0; var sq = 0.0
            for (m in ms) {
                val a = ps[m.i].transform(ray(lens.geom(frames[m.i]), m.ui, m.vi))
                val b = ps[m.j].transform(ray(lens.geom(frames[m.j]), m.uj, m.vj))
                val r = (a - b).length().toDouble()
                sq += r * r
                cost += iw * (if (r <= huber) r * r else 2 * huber * r - huber * huber)
            }
            for (k in 0 until n) {
                val d = (ps[k] * live[k].transposed()).log().length().toDouble()
                cost += d * d / (sigmaPrior * sigmaPrior)
            }
            cost += ls * ls / (sigmaS * sigmaS) + (kk - lens0.k1) * (kk - lens0.k1) / (sigmaK * sigmaK)
            return Pair(cost, sqrt(sq / max(1, ms.size)))
        }

        val (c0, rms0) = residualCost(poses, lnS, k1)
        var cost = c0
        var lambda = 1e-3
        val H = DoubleArray(M * M)
        val g = DoubleArray(M)
        val hS = 1e-3; val hK = 1e-3
        for (iter in 0 until 12) {
            H.fill(0.0); g.fill(0.0)
            val lens = lensOf(lnS, k1)
            val lensS = lensOf(lnS + hS, k1)
            val lensK = lensOf(lnS, k1 + hK)
            val Ja = DoubleArray(9); val Jb = DoubleArray(9); val Ji = DoubleArray(6)
            for (m in ms) {
                val fi = frames[m.i]; val fj = frames[m.j]
                val ra = ray(lens.geom(fi), m.ui, m.vi); val rb = ray(lens.geom(fj), m.uj, m.vj)
                val A = poses[m.i].transform(ra); val B = poses[m.j].transform(rb)
                val r = A - B
                val rl = r.length()
                val w = iw * (if (rl <= huber) 1.0 else huber / rl)
                val aS = poses[m.i].transform(ray(lensS.geom(fi), m.ui, m.vi)); val bS = poses[m.j].transform(ray(lensS.geom(fj), m.uj, m.vj))
                val aK = poses[m.i].transform(ray(lensK.geom(fi), m.ui, m.vi)); val bK = poses[m.j].transform(ray(lensK.geom(fj), m.uj, m.vj))
                // d(exp(δ)A)/dδ = −[A]×, d(−exp(δ)B)/dδ = [B]×  (wiersze: składowe reszty)
                skewNeg(A, Ja); skewPos(B, Jb)
                Ji[0] = ((aS.x - A.x) - (bS.x - B.x)) / hS; Ji[1] = ((aK.x - A.x) - (bK.x - B.x)) / hK
                Ji[2] = ((aS.y - A.y) - (bS.y - B.y)) / hS; Ji[3] = ((aK.y - A.y) - (bK.y - B.y)) / hK
                Ji[4] = ((aS.z - A.z) - (bS.z - B.z)) / hS; Ji[5] = ((aK.z - A.z) - (bK.z - B.z)) / hK
                val rv = doubleArrayOf(r.x.toDouble(), r.y.toDouble(), r.z.toDouble())
                val oi = 3 * m.i; val oj = 3 * m.j; val oc = 3 * n
                for (row in 0..2) {
                    val rr = rv[row] * w
                    for (p in 0..2) {
                        val a = Ja[row * 3 + p]; val b = Jb[row * 3 + p]
                        g[oi + p] += a * rr; g[oj + p] += b * rr
                        for (q in 0..2) {
                            val aq = Ja[row * 3 + q] * w; val bq = Jb[row * 3 + q] * w
                            H[(oi + p) * M + oi + q] += a * aq
                            H[(oj + p) * M + oj + q] += b * bq
                            H[(oi + p) * M + oj + q] += a * bq
                            H[(oj + p) * M + oi + q] += b * aq
                        }
                        for (c in 0..1) {
                            val jc = Ji[row * 2 + c] * w
                            H[(oi + p) * M + oc + c] += a * jc; H[(oc + c) * M + oi + p] += a * jc
                            H[(oj + p) * M + oc + c] += b * jc; H[(oc + c) * M + oj + p] += b * jc
                        }
                    }
                    for (c in 0..1) {
                        g[oc + c] += Ji[row * 2 + c] * rr
                        for (d in 0..1) H[(oc + c) * M + oc + d] += Ji[row * 2 + c] * Ji[row * 2 + d] * w
                    }
                }
            }
            // Przyciąganie do poz z czujnika i do kalibracji na żywo.
            for (k in 0 until n) {
                val d = (poses[k] * live[k].transposed()).log()
                val ip = 1.0 / (sigmaPrior * sigmaPrior)
                for (p in 0..2) H[(3 * k + p) * M + 3 * k + p] += ip
                g[3 * k] += d.x * ip; g[3 * k + 1] += d.y * ip; g[3 * k + 2] += d.z * ip
            }
            H[(3 * n) * M + 3 * n] += 1.0 / (sigmaS * sigmaS); g[3 * n] += lnS / (sigmaS * sigmaS)
            H[(3 * n + 1) * M + 3 * n + 1] += 1.0 / (sigmaK * sigmaK); g[3 * n + 1] += (k1 - lens0.k1) / (sigmaK * sigmaK)

            var improved = false
            for (attempt in 0 until 6) {
                val A = H.copyOf()
                for (d in 0 until M) A[d * M + d] *= 1.0 + lambda
                val step = cholSolve(A, DoubleArray(M) { -g[it] }, M)
                if (step == null) { lambda *= 10; continue }
                val np = MutableList(n) { k ->
                    val w = Vec3(step[3 * k].toFloat(), step[3 * k + 1].toFloat(), step[3 * k + 2].toFloat())
                    (Mat3.exp(w) * poses[k]).orthonormalized()
                }
                val nls = (lnS + step[3 * n]).coerceIn(-0.2, 0.2)
                val nk = (k1 + step[3 * n + 1]).coerceIn(-0.35, 0.35)
                val (nc, _) = residualCost(np, nls, nk)
                if (DEBUG) println("BA it=$iter att=$attempt lambda=$lambda cost=$cost -> $nc |step|=${(0 until 3*n).sumOf { step[it]*step[it] }.let{ sqrt(it) } / DEG}")
                if (nc < cost) {
                    poses = np; lnS = nls; k1 = nk
                    val rel = (cost - nc) / cost
                    cost = nc
                    lambda = max(1e-7, lambda / 4)
                    improved = true
                    if (rel < 1e-5) return finish(frames, ms, poses, lensOf(lnS, k1), rms0)
                    break
                }
                lambda *= 8
            }
            progress((iter + 1) / 12f)
            if (!improved) break
        }
        return finish(frames, ms, poses, lensOf(lnS, k1), rms0)
    }

    private fun finish(frames: List<Keyframe>, ms: List<Match>, poses: List<Mat3>, lens: Lens, rms0: Double): BaResult {
        var sq = 0.0
        var bad = 0
        for (m in ms) {
            val a = poses[m.i].transform(ray(lens.geom(frames[m.i]), m.ui, m.vi))
            val b = poses[m.j].transform(ray(lens.geom(frames[m.j]), m.uj, m.vj))
            val r = (a - b).length().toDouble()
            sq += r * r
            if (r > 0.5 * DEG) bad++
        }
        return BaResult(poses, lens, sqrt(sq / max(1, ms.size)).toFloat(), rms0.toFloat(), bad)
    }

    private fun skewNeg(a: Vec3, out: DoubleArray) {
        // −[a]× : wiersze składowych (ω × a = −a × ω).
        out[0] = 0.0; out[1] = a.z.toDouble(); out[2] = -a.y.toDouble()
        out[3] = -a.z.toDouble(); out[4] = 0.0; out[5] = a.x.toDouble()
        out[6] = a.y.toDouble(); out[7] = -a.x.toDouble(); out[8] = 0.0
    }

    private fun skewPos(a: Vec3, out: DoubleArray) {
        skewNeg(a, out)
        for (i in 0 until 9) out[i] = -out[i]
    }

    /** Rozkład Cholesky'ego (macierz dodatnio określona) i rozwiązanie; null przy niepowodzeniu. */
    fun cholSolve(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
        val l = a
        for (j in 0 until n) {
            var s = l[j * n + j]
            for (k in 0 until j) s -= l[j * n + k] * l[j * n + k]
            if (s <= 1e-18) return null
            val d = sqrt(s)
            l[j * n + j] = d
            for (i in j + 1 until n) {
                var t = l[i * n + j]
                val ri = i * n; val rj = j * n
                for (k in 0 until j) t -= l[ri + k] * l[rj + k]
                l[ri + j] = t / d
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= l[i * n + k] * y[k]
            y[i] = s / l[i * n + i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k * n + i] * x[k]
            x[i] = s / l[i * n + i]
        }
        return x
    }

    /**
     * Wzmocnienia klatek (światło liniowe): Σ n_ij·(g_i·Ī_ij − g_j·Ī_ji)²/σN² + Σ N_k·(1 − g_k)²/σg²,
     * Ī – średnia jasność wspólnych punktów po korekcie winietowania.
     */
    fun solveGains(frames: List<Keyframe>, ms: List<Match>, lens: Lens, vignette: Float): FloatArray {
        val n = frames.size
        val sI = HashMap<Long, DoubleArray>()
        for (m in ms) {
            val key = m.i.toLong() * 100000 + m.j
            val acc = sI.getOrPut(key) { DoubleArray(3) }
            val gi = lens.geom(frames[m.i]); val gj = lens.geom(frames[m.j])
            acc[0] += Gamma.lin(m.mi) * (1 + vignette * Photometry.radius2(m.ui, m.vi, gi))
            acc[1] += Gamma.lin(m.mj) * (1 + vignette * Photometry.radius2(m.uj, m.vj, gj))
            acc[2] += 1.0
        }
        val sigmaN = 0.01
        val sigmaG = 0.4
        val A = DoubleArray(n * n)
        val b = DoubleArray(n)
        val cnt = DoubleArray(n)
        for ((key, acc) in sI) {
            val i = (key / 100000).toInt(); val j = (key % 100000).toInt()
            val c = acc[2]
            val ii = acc[0] / c; val jj = acc[1] / c
            val w = c / (sigmaN * sigmaN)
            A[i * n + i] += w * ii * ii; A[j * n + j] += w * jj * jj
            A[i * n + j] -= w * ii * jj; A[j * n + i] -= w * ii * jj
            cnt[i] += c; cnt[j] += c
        }
        for (k in 0 until n) {
            val w = max(1.0, cnt[k]) / (sigmaG * sigmaG)
            A[k * n + k] += w
            b[k] += w
        }
        val x = cholSolve(A, b, n) ?: return FloatArray(n) { 1f }
        return FloatArray(n) { x[it].toFloat().coerceIn(0.4f, 2.5f) }
    }

    /** Pomocnicze: piksel szarego obrazu klatki z kolorem (u, v) – do testów. */
    fun pixel(kf: Keyframe, u: Float, v: Float): Float {
        val x = (u * kf.gw - 0.5f).coerceIn(0f, kf.gw - 1.001f)
        val y = (v * kf.gh - 0.5f).coerceIn(0f, kf.gh - 1.001f)
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val tx = x - x0; val ty = y - y0
        val a = kf.grayAt(x0, y0) + (kf.grayAt(x0 + 1, y0) - kf.grayAt(x0, y0)) * tx
        val c = kf.grayAt(x0, y0 + 1) + (kf.grayAt(x0 + 1, y0 + 1) - kf.grayAt(x0, y0 + 1)) * tx
        return a + (c - a) * ty
    }
}
