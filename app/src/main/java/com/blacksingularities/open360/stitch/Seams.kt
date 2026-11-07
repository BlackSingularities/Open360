package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.PI_F
import com.blacksingularities.open360.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Klatka gotowa do ponownego złożenia: geometria po optymalizacji i waga na siatce etykiet. */
class PlanFrame(
    val kf: Keyframe,
    val pose: Mat3,
    val geom: FrameGeom,
    /** Wzmocnienie (światło liniowe). */
    val gain: Float,
    /** Zakres na siatce etykiet i waga (0..255) w jego obrębie, wiersz po wierszu. */
    val box: LonLatBox,
    val weight: ByteArray,
)

/** Plan złożenia panoramy przy zapisie. */
class RenderPlan(
    val frames: List<PlanFrame>,
    /** Rozmiar siatki etykiet (równoprostokątnej). */
    val lw: Int,
    val lh: Int,
    val vignette: Float,
    /** Które komórki siatki mają treść – do kadrowania zapisu. */
    val covered: BooleanArray,
    val result: GlobalResult,
) {
    /** Prostokąt treści na płótnie [cw]×[ch] (jak GrayCanvas.contentBounds). */
    fun contentBounds(cw: Int, ch: Int): IntArray? {
        val g = GrayCanvas(lw, lh)
        for (i in covered.indices) if (covered[i]) g.weight[i] = 1
        return g.contentBounds(cw, ch)
    }
}

/**
 * Szwy jak w programach do panoram: każdy punkt siatki (0,5°) dostaje jedną klatkę tak, by
 * cięcia biegły tam, gdzie sąsiednie klatki się zgadzają (z dala od ruchomych obiektów
 * i paralaksy), a przy remisie wygrywa środek kadru. Minimalizacja energii
 *   Σ D_p(l_p) + Σ_{p~q} V(l_p, l_q),  V = |I_a − I_b|(p) + |I_a − I_b|(q) + ε dla a ≠ b
 * metodą alpha-expansion (Boykov, Veksler, Zabih) – V jest metryką, więc każdy krok to
 * jedno cięcie grafu (max-flow). Potem waga każdej klatki to rozmyta maska jej etykiet,
 * znormalizowana – wąskie przenikanie wzdłuż szwu.
 */
object Seams {
    const val LW = 720
    const val LH = 360
    private const val DATA = 0.05f
    private const val EPS = 0.004f
    private const val PEN = 0.5f
    private const val INF = 1e4f
    @JvmField var DEBUG = false

    /** Dane klatki na jej zakresie siatki: jasność (NaN = poza kadrem) i centralność. */
    private class Footprint(val box: LonLatBox, val inten: FloatArray, val cent: FloatArray) {
        val w = box.width
        val h = box.y1 - box.y0 + 1
        /** Indeks lokalny piksela siatki (x, y) albo −1. */
        fun local(x: Int, y: Int): Int {
            if (y < box.y0 || y > box.y1) return -1
            val dx = ((x - box.x0) % LW + LW) % LW
            if (dx >= w) return -1
            return (y - box.y0) * w + dx
        }
        fun i(x: Int, y: Int): Float { val k = local(x, y); return if (k < 0) Float.NaN else inten[k] }
        fun c(x: Int, y: Int): Float { val k = local(x, y); return if (k < 0) -1f else cent[k] }
    }

    private val rowSin = FloatArray(LH) { sin((0.5f - (it + 0.5f) / LH) * PI_F) }
    private val rowCos = FloatArray(LH) { cos((0.5f - (it + 0.5f) / LH) * PI_F) }
    private val colSin = FloatArray(LW) { sin(((it + 0.5f) / LW - 0.5f) * 2f * PI_F) }
    private val colCos = FloatArray(LW) { cos(((it + 0.5f) / LW - 0.5f) * 2f * PI_F) }

    private fun footprint(kf: Keyframe, pose: Mat3, geom: FrameGeom, gain: Float, vignette: Float): Footprint {
        val border = ArrayList<Vec3>(32)
        for (i in 0..7) {
            val t = i / 7f * 2f - 1f
            border += pose.transform(geom.rayAt(t * geom.tanX, geom.tanY).normalized())
            border += pose.transform(geom.rayAt(t * geom.tanX, -geom.tanY).normalized())
            border += pose.transform(geom.rayAt(geom.tanX, t * geom.tanY).normalized())
            border += pose.transform(geom.rayAt(-geom.tanX, t * geom.tanY).normalized())
        }
        val fwd = pose.transform(Vec3(0f, 0f, -1f))
        val panoToCam = pose.transposed()
        val pole = run {
            var p = false
            for (z in floatArrayOf(1f, -1f)) {
                val d = panoToCam.transform(Vec3(0f, 0f, z))
                val uv = FloatArray(2)
                if (geom.project(d.x, d.y, d.z, uv) && uv[0] in -0.05f..1.05f && uv[1] in -0.05f..1.05f) p = true
            }
            p
        }
        val box = LonLatBox.of(border, fwd, LW, LH, pole)
        val h = box.y1 - box.y0 + 1
        val inten = FloatArray(box.width * h) { Float.NaN }
        val cent = FloatArray(box.width * h) { -1f }
        val m = panoToCam.m
        val uv = FloatArray(2)
        // Margines: pół piksela siatki w ułamku kadru – waga nie może sięgać poza obraz.
        val mu = 0.5f * (2f * PI_F / LW) / (2f * kotlin.math.atan(geom.tanX)) + 0.002f
        val mv = 0.5f * (2f * PI_F / LW) / (2f * kotlin.math.atan(geom.tanY)) + 0.002f
        for (yy in 0 until h) {
            val py = box.y0 + yy
            val cl = rowCos[py]; val sl = rowSin[py]
            for (k in 0 until box.width) {
                val px = (box.x0 + k) % LW
                val dx = cl * colSin[px]; val dy = cl * colCos[px]
                val cx = m[0] * dx + m[1] * dy + m[2] * sl
                val cy = m[3] * dx + m[4] * dy + m[5] * sl
                val cz = m[6] * dx + m[7] * dy + m[8] * sl
                if (!geom.project(cx, cy, cz, uv)) continue
                val u = uv[0]; val v = uv[1]
                if (u <= mu || u >= 1f - mu || v <= mv || v >= 1f - mv) continue
                val raw = GlobalAligner.pixel(kf, u, v)
                val lin = Gamma.lin(raw) * gain * (1f + vignette * Photometry.radius2(u, v, geom))
                inten[yy * box.width + k] = Gamma.gam(lin.coerceIn(0f, 1f))
                val x = 2f * u - 1f; val y = 2f * v - 1f
                cent[yy * box.width + k] = (1f - x * x) * (1f - y * y) * kf.quality
            }
        }
        return Footprint(box, inten, cent)
    }

    fun build(frames: List<Keyframe>, res: GlobalResult, vignette: Float, budgetMs: Long = 6000, progress: (Float) -> Unit = {}): RenderPlan {
        val n = frames.size
        val geoms = frames.map { res.lens.geom(it) }
        val fps = arrayOfNulls<Footprint>(n)
        Parallel.forRange(0, n) { a, b ->
            for (k in a until b) fps[k] = footprint(frames[k], res.poses[k], geoms[k], res.gains[k], vignette)
        }
        val fp = fps.map { it!! }
        progress(0.15f)

        // Start: najbardziej centralna klatka.
        val label = IntArray(LW * LH) { -1 }
        val best = FloatArray(LW * LH) { -1f }
        for ((k, f) in fp.withIndex()) {
            for (yy in 0 until f.h) for (xx in 0 until f.w) {
                val c = f.cent[yy * f.w + xx]
                if (c < 0f) continue
                val p = (f.box.y0 + yy) * LW + (f.box.x0 + xx) % LW
                if (c > best[p]) { best[p] = c; label[p] = k }
            }
        }
        progress(0.25f)

        // Alpha-expansion, kolejność: klatki od największej treści.
        val t0 = System.currentTimeMillis()
        val order = (0 until n).sortedByDescending { k -> fp[k].cent.count { it >= 0f } }
        cycles@ for (cycle in 0 until 2) {
            var changed = false
            for ((idx, a) in order.withIndex()) {
                if (System.currentTimeMillis() - t0 > budgetMs) break@cycles
                if (expand(a, fp, label)) changed = true
                progress(0.25f + 0.6f * ((cycle * n + idx + 1).toFloat() / (2 * n)))
            }
            if (!changed) break
        }

        // Wagi: rozmyta maska etykiet × ważność piksela, normalizowana sumą wszystkich klatek.
        val sum = FloatArray(LW * LH)
        val masks = arrayOfNulls<FloatArray>(n)
        for ((k, f) in fp.withIndex()) {
            val mk = FloatArray(f.w * f.h)
            for (yy in 0 until f.h) for (xx in 0 until f.w) {
                val p = (f.box.y0 + yy) * LW + (f.box.x0 + xx) % LW
                if (label[p] == k) mk[yy * f.w + xx] = 1f
            }
            blur(mk, f.w, f.h)
            for (q in mk.indices) if (f.inten[q].isNaN()) mk[q] = 0f
            for (yy in 0 until f.h) for (xx in 0 until f.w) {
                val v = mk[yy * f.w + xx]
                if (v > 0f) sum[(f.box.y0 + yy) * LW + (f.box.x0 + xx) % LW] += v
            }
            masks[k] = mk
        }
        val out = ArrayList<PlanFrame>(n)
        for ((k, f) in fp.withIndex()) {
            val mk = masks[k]!!
            val bytes = ByteArray(mk.size)
            var any = false
            for (yy in 0 until f.h) for (xx in 0 until f.w) {
                val q = yy * f.w + xx
                val s = sum[(f.box.y0 + yy) * LW + (f.box.x0 + xx) % LW]
                val w = if (s > 1e-6f) mk[q] / s else 0f
                val b = (w * 255f + 0.5f).toInt().coerceIn(0, 255)
                if (b > 0) any = true
                bytes[q] = b.toByte()
            }
            if (any) out += PlanFrame(frames[k], res.poses[k], geoms[k], res.gains[k], f.box, bytes)
        }
        val covered = BooleanArray(LW * LH) { label[it] >= 0 }
        progress(1f)
        return RenderPlan(out, LW, LH, vignette, covered, res)
    }

    /** Rozmycie gaussowskie σ ≈ 1 piksel siatki (jądro 1-4-6-4-1), z zawijaniem tylko w obrębie zakresu. */
    private fun blur(a: FloatArray, w: Int, h: Int) {
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val tmp = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var ws = 0f
            for (d in -2..2) {
                val xx = x + d
                if (xx < 0 || xx >= w) continue
                s += a[y * w + xx] * k[d + 2]; ws += k[d + 2]
            }
            tmp[y * w + x] = s / ws
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var ws = 0f
            for (d in -2..2) {
                val yy = y + d
                if (yy < 0 || yy >= h) continue
                s += tmp[yy * w + x] * k[d + 2]; ws += k[d + 2]
            }
            a[y * w + x] = s / ws
        }
    }

    private fun dataCost(f: Footprint, x: Int, y: Int): Float {
        val c = f.c(x, y)
        return if (c < 0f) INF else DATA * (1f - c)
    }

    private fun diff(fa: Footprint, fb: Footprint, x: Int, y: Int): Float {
        val a = fa.i(x, y); val b = fb.i(x, y)
        return if (a.isNaN() || b.isNaN()) PEN else abs(a - b)
    }

    /** Koszt szwu między sąsiadami p=(x1,y1) i q=(x2,y2) przy etykietach la i lb. */
    private fun v(fp: List<Footprint>, la: Int, lb: Int, x1: Int, y1: Int, x2: Int, y2: Int): Float {
        if (la == lb) return 0f
        if (la < 0 || lb < 0) return PEN
        val fa = fp[la]; val fb = fp[lb]
        return diff(fa, fb, x1, y1) + diff(fa, fb, x2, y2) + EPS
    }

    /** Jeden krok alpha-expansion dla etykiety [a]. Zwraca, czy coś się zmieniło. */
    private fun expand(a: Int, fp: List<Footprint>, label: IntArray): Boolean {
        val fa = fp[a]
        // Węzły: piksele, gdzie [a] ma dane.
        val idx = IntArray(fa.w * fa.h) { -1 }
        val gx = IntArray(fa.w * fa.h); val gy = IntArray(fa.w * fa.h)
        var nn = 0
        for (yy in 0 until fa.h) for (xx in 0 until fa.w) {
            if (fa.cent[yy * fa.w + xx] < 0f) continue
            idx[yy * fa.w + xx] = nn
            gx[nn] = (fa.box.x0 + xx) % LW; gy[nn] = fa.box.y0 + yy
            nn++
        }
        if (nn == 0) return false
        val g = Flow(nn + 2, nn * 6)
        val s = nn; val t = nn + 1
        val unarySrc = FloatArray(nn) // koszt x=1 (przejście na a)
        val unarySnk = FloatArray(nn) // koszt x=0 (bez zmiany)
        for (p in 0 until nn) {
            val x = gx[p]; val y = gy[p]
            val lp = label[y * LW + x]
            unarySnk[p] += if (lp < 0) INF else dataCost(fp[lp], x, y)
            unarySrc[p] += dataCost(fa, x, y)
        }
        // Sąsiedztwo 4-spójne (poziomo z zawijaniem).
        fun neighbour(p: Int, x2: Int, y2: Int) {
            if (y2 < 0 || y2 >= LH) return
            val x1 = gx[p]; val y1 = gy[p]
            val lp = label[y1 * LW + x1]
            val lq = label[y2 * LW + x2]
            val q = fa.local(x2, y2).let { if (it >= 0) idx[it] else -1 }
            if (q < 0) {
                // Sąsiad poza zasięgiem [a] – jego etykieta się nie zmieni.
                unarySnk[p] += v(fp, lp, lq, x1, y1, x2, y2)
                unarySrc[p] += v(fp, a, lq, x1, y1, x2, y2)
                return
            }
            if (q < p) return
            val e00 = v(fp, lp, lq, x1, y1, x2, y2)
            val e01 = v(fp, lp, a, x1, y1, x2, y2)
            val e10 = v(fp, a, lq, x1, y1, x2, y2)
            // E = e00 + (e10 − e00)·x_p + (e11 − e10)·x_q + (e01 + e10 − e00 − e11)·(1 − x_p)·x_q, e11 = 0
            addLinear(unarySrc, unarySnk, p, e10 - e00)
            addLinear(unarySrc, unarySnk, q, -e10)
            val w = (e01 + e10 - e00).coerceAtLeast(0f)
            if (w > 0f) g.add(p, q, w, 0f)
        }
        for (p in 0 until nn) {
            val x = gx[p]; val y = gy[p]
            neighbour(p, (x + 1) % LW, y)
            neighbour(p, (x - 1 + LW) % LW, y)
            neighbour(p, x, y + 1)
            neighbour(p, x, y - 1)
        }
        for (p in 0 until nn) {
            val m = min(unarySrc[p], unarySnk[p])
            val cs = unarySrc[p] - m; val ct = unarySnk[p] - m
            if (cs > 0f) g.add(s, p, cs, 0f)
            if (ct > 0f) g.add(p, t, ct, 0f)
        }
        val flow = g.maxflow(s, t)
        val reach = g.reachable(s)
        if (DEBUG) println("expand a=$a nodes=$nn flow=$flow toA=${(0 until nn).count { !reach[it] }} srcSum=${unarySrc.sum()} snkSum=${unarySnk.sum()}")
        var changed = false
        for (p in 0 until nn) {
            // Strona ujścia (nieosiągalna ze źródła) = x_p = 1 → etykieta a.
            if (!reach[p]) {
                val i = gy[p] * LW + gx[p]
                if (label[i] != a) {
                    label[i] = a
                    changed = true
                }
            }
        }
        return changed
    }

    /** Dodaje v·x_p do energii: x_p = 1 kosztuje v (krawędź ze źródła), ujemne – x_p = 0 kosztuje −v. */
    private fun addLinear(src: FloatArray, snk: FloatArray, p: Int, v: Float) {
        if (v >= 0f) src[p] += v else snk[p] += -v
    }

    /** Max-flow Dinica na listach krawędzi (pojemności zmiennoprzecinkowe). */
    class Flow(private val n: Int, capacityHint: Int) {
        private var to = IntArray(capacityHint * 2)
        private var cap = FloatArray(capacityHint * 2)
        private var next = IntArray(capacityHint * 2)
        private val head = IntArray(n) { -1 }
        private var m = 0
        private val level = IntArray(n)
        private val it = IntArray(n)

        fun add(u: Int, v: Int, c: Float, rc: Float) {
            ensure(m + 2)
            to[m] = v; cap[m] = c; next[m] = head[u]; head[u] = m; m++
            to[m] = u; cap[m] = rc; next[m] = head[v]; head[v] = m; m++
        }

        private fun ensure(k: Int) {
            if (k <= to.size) return
            val ns = max(k, to.size * 2)
            to = to.copyOf(ns); cap = cap.copyOf(ns); next = next.copyOf(ns)
        }

        private fun bfs(s: Int, t: Int): Boolean {
            level.fill(-1)
            val q = IntArray(n)
            var qh = 0; var qt = 0
            q[qt++] = s
            level[s] = 0
            while (qh < qt) {
                val u = q[qh++]
                var e = head[u]
                while (e >= 0) {
                    if (cap[e] > 1e-7f && level[to[e]] < 0) {
                        level[to[e]] = level[u] + 1
                        q[qt++] = to[e]
                    }
                    e = next[e]
                }
            }
            return level[t] >= 0
        }

        /** Iteracyjne DFS (bez rekurencji – grafy mają dziesiątki tysięcy węzłów). */
        private fun dfs(s: Int, t: Int): Float {
            var total = 0f
            val stackE = IntArray(n)
            while (true) {
                // Szukamy ścieżki powiększającej w grafie poziomów.
                var depth = 0
                var u = s
                var found = false
                while (true) {
                    if (u == t) { found = true; break }
                    var advanced = false
                    while (it[u] >= 0) {
                        val e = it[u]
                        val v = to[e]
                        if (cap[e] > 1e-7f && level[v] == level[u] + 1) {
                            stackE[depth++] = e
                            u = v
                            advanced = true
                            break
                        }
                        it[u] = next[e]
                    }
                    if (advanced) continue
                    // Ślepy zaułek – cofamy się.
                    level[u] = -1
                    if (depth == 0) break
                    val e = stackE[--depth]
                    u = to[e xor 1]
                    it[u] = next[it[u]]
                }
                if (!found) return total
                var f = Float.MAX_VALUE
                for (k in 0 until depth) f = min(f, cap[stackE[k]])
                for (k in 0 until depth) {
                    val e = stackE[k]
                    cap[e] -= f
                    cap[e xor 1] += f
                }
                total += f
            }
        }

        fun maxflow(s: Int, t: Int): Float {
            var flow = 0f
            var rounds = 0
            while (bfs(s, t) && rounds < 10000) {
                for (u in 0 until n) it[u] = head[u]
                flow += dfs(s, t)
                rounds++
            }
            return flow
        }

        /** Węzły osiągalne ze źródła w grafie rezydualnym (strona źródła cięcia). */
        fun reachable(s: Int): BooleanArray {
            val seen = BooleanArray(n)
            val q = IntArray(n)
            var qh = 0; var qt = 0
            q[qt++] = s; seen[s] = true
            while (qh < qt) {
                val u = q[qh++]
                var e = head[u]
                while (e >= 0) {
                    if (cap[e] > 1e-7f && !seen[to[e]]) {
                        seen[to[e]] = true
                        q[qt++] = to[e]
                    }
                    e = next[e]
                }
            }
            return seen
        }
    }
}
