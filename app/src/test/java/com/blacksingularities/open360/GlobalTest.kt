package com.blacksingularities.open360

import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3
import com.blacksingularities.open360.stitch.FrameGeom
import com.blacksingularities.open360.stitch.Gamma
import com.blacksingularities.open360.stitch.GlobalAligner
import com.blacksingularities.open360.stitch.GrayFrame
import com.blacksingularities.open360.stitch.Keyframe
import com.blacksingularities.open360.stitch.KeyframeStore
import com.blacksingularities.open360.stitch.Lens
import com.blacksingularities.open360.stitch.LensEstimator
import com.blacksingularities.open360.stitch.Seams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan
import kotlin.math.floor

/** Globalne dopasowanie, szwy i model obiektywu na syntetycznej scenie. */
class GlobalTest {
    private val tanX = 0.55f
    private val tanY = 0.73f

    private fun world(d: Vec3): Float {
        var s = 0f
        var amp = 0.5f
        var f = 3f
        repeat(6) {
            s += amp * noise(d.x * f + 17.3f, d.y * f - 4.1f, d.z * f + 9.7f)
            amp *= 0.6f
            f *= 2.2f
        }
        return (0.5f + 0.9f * (s - 0.6f)).coerceIn(0.03f, 0.97f)
    }

    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    private fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        fun s(t: Float) = t * t * (3 - 2 * t)
        val tx = s(x - xi); val ty = s(y - yi); val tz = s(z - zi)
        fun l(a: Float, b: Float, t: Float) = a + (b - a) * t
        val c00 = l(hash(xi, yi, zi), hash(xi + 1, yi, zi), tx)
        val c10 = l(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), tx)
        val c01 = l(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), tx)
        val c11 = l(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), tx)
        return l(l(c00, c10, ty), l(c01, c11, ty), tz)
    }

    private fun render(pose: Mat3, geom: FrameGeom, w: Int, gain: Float = 1f): GrayFrame {
        val h = (w * geom.tanY / geom.tanX).toInt()
        val f = GrayFrame(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
            val ray = geom.rayAt((2 * u - 1) * geom.tanX, (1 - 2 * v) * geom.tanY)
            f.px[y * w + x] = Gamma.gam((Gamma.lin(world(pose.transform(ray).normalized())) * gain).coerceAtMost(1f))
        }
        f.computeStats()
        return f
    }

    private fun pose(yaw: Float, pitch: Float, roll: Float = 0f) = Mat3.view(yaw * DEG, pitch * DEG, roll * DEG)
    private fun angleDeg(a: Mat3, b: Mat3) = (a * b.transposed()).log().length() / DEG

    private fun keyframe(id: Int, live: Mat3, frame: GrayFrame) = Keyframe(
        id, live, 0, 0f, Vec3(0f, 0f, 0f), 1f,
        ByteArray(frame.px.size) { (frame.px[it] * 255f + 0.5f).toInt().toByte() }, frame.w, frame.h,
        File("kf_$id.jpg"), 4, 3,
    )

    @Test
    fun lensDistortionRoundTrip() {
        val g = FrameGeom(tanX, tanY, k1 = 0.08f)
        val out = FloatArray(2)
        for ((x, y) in listOf(0.1f to 0.2f, 0.5f to -0.7f, -0.55f to 0.73f)) {
            val r = g.rayAt(x, y)
            assertTrue(g.project(r.x, r.y, r.z, out))
            assertEquals(0.5f + 0.5f * x / tanX, out[0], 2e-4f)
            assertEquals(0.5f - 0.5f * y / tanY, out[1], 2e-4f)
        }
    }

    @Test
    fun bundleAdjustmentFixesPosesAndLens() {
        // Prawdziwy obiektyw: kąt widzenia o 4% szerszy i beczka k1 = 0,06; pozy z czujnika
        // mylą się o ~1,5° w losowych kierunkach.
        val trueLens = Lens(tanX * 1.04f, tanY * 1.04f, 0.06f)
        val rnd = java.util.Random(5)
        val truth = ArrayList<Mat3>()
        for (pitch in listOf(-22f, 18f)) for (yaw in -60..60 step 24) truth += pose(yaw + pitch * 0.3f, pitch, rnd.nextFloat() * 4f - 2f)
        val frames = truth.mapIndexed { i, p ->
            val err = Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalized() * (1.5f * DEG)
            val live = Mat3.exp(err) * p
            val g = FrameGeom(trueLens.tanX, trueLens.tanY, k1 = trueLens.k1)
            keyframe(i, live, render(p, g, (2f * atan(trueLens.tanX) / (0.1f * DEG)).toInt(), gain = if (i == 3) 0.75f else 1f))
        }
        val before = relErr(frames.map { it.pose }, truth)
        val res = GlobalAligner.optimize(frames, Lens(tanX, tanY, 0f), 0f)!!
        val after = relErr(res.poses, truth)
        println("BA: pary=${res.pairs} punkty=${res.matches} rms ${res.rmsBeforeDeg}° → ${res.rmsDeg}°, błąd względny poz $before° → $after°, skala=${res.lens.tanX / tanX} k1=${res.lens.k1} wzmocnienie[3]=${res.gains[3]}")
        assertTrue("pozy $after", after < 0.08f)
        assertEquals(1.04f, res.lens.tanX / tanX, 0.01f)
        assertEquals(0.06f, res.lens.k1, 0.025f)
        assertTrue(res.rmsDeg < 0.05f)
        // Klatka nr 3 była ciemniejsza (0,75) – jej wzmocnienie ok. 1/0,75 względem reszty.
        val others = res.gains.filterIndexed { i, _ -> i != 3 }.average().toFloat()
        assertEquals(1f / 0.75f, res.gains[3] / others, 0.12f)

        // Szwy i wagi: każdy pokryty punkt ma wagi sumujące się do ~1.
        val plan = Seams.build(frames, res, 0f)
        assertTrue(plan.frames.size >= frames.size - 1)
        val sum = FloatArray(plan.lw * plan.lh)
        for (pf in plan.frames) {
            val h = pf.box.y1 - pf.box.y0 + 1
            for (yy in 0 until h) for (xx in 0 until pf.box.width) {
                sum[(pf.box.y0 + yy) * plan.lw + (pf.box.x0 + xx) % plan.lw] += (pf.weight[yy * pf.box.width + xx].toInt() and 0xFF) / 255f
            }
        }
        var bad = 0; var cov = 0
        for (i in sum.indices) if (plan.covered[i]) {
            cov++
            if (sum[i] !in 0.9f..1.1f) bad++
        }
        println("szwy: pokryte=$cov poza normą=$bad")
        assertTrue(cov > 1000)
        assertTrue(bad < cov * 0.03f)
    }

    /** Średni błąd obrotów względnych między kolejnymi klatkami (niezależny od obrotu całości). */
    private fun relErr(est: List<Mat3>, truth: List<Mat3>): Float {
        var s = 0f; var n = 0
        for (i in est.indices) for (j in i + 1 until est.size) {
            val e = est[i].transposed() * est[j]
            val t = truth[i].transposed() * truth[j]
            s += angleDeg(e, t); n++
        }
        return s / n
    }

    @Test
    fun seamAvoidsMovingObject() {
        // Dwie klatki obok siebie; w drugiej w zakładce pojawia się jasny „autobus”.
        val g = FrameGeom(tanX, tanY)
        val p0 = pose(0f, 0f); val p1 = pose(30f, 0f)
        val w = (2f * atan(tanX) / (0.1f * DEG)).toInt()
        val f0 = render(p0, g, w)
        val f1 = render(p1, g, w)
        // Obiekt w kierunku yaw 16°, pitch 0 (bliżej środka klatki 1 niż szew środkowy 15°).
        val obj = pose(16f, 0f).transform(Vec3(0f, 0f, -1f))
        for (y in 0 until f1.h) for (x in 0 until f1.w) {
            val ray = p1.transform(g.rayAt((2 * (x + 0.5f) / f1.w - 1) * tanX, (1 - 2 * (y + 0.5f) / f1.h) * tanY).normalized())
            if (kotlin.math.acos(ray.dot(obj).coerceIn(-1f, 1f)) < 4f * DEG) f1.px[y * f1.w + x] = 0.98f
        }
        val frames = listOf(keyframe(0, p0, f0), keyframe(1, p1, f1))
        val res = com.blacksingularities.open360.stitch.GlobalResult(listOf(p0, p1), Lens(tanX, tanY, 0f), floatArrayOf(1f, 1f), 0f, 0f, 0, 1, 0f)
        val plan = Seams.build(frames, res, 0f)
        fun weightOf(k: Int, lx: Int, ly: Int): Float {
            val pf = plan.frames.firstOrNull { it.kf.id == k } ?: return 0f
            val dx = ((lx - pf.box.x0) % plan.lw + plan.lw) % plan.lw
            if (dx >= pf.box.width || ly < pf.box.y0 || ly > pf.box.y1) return 0f
            return (pf.weight[(ly - pf.box.y0) * pf.box.width + dx].toInt() and 0xFF) / 255f
        }
        // Szew nie może przeciąć obiektu: cały obiekt pochodzi z jednej klatki (dowolnej).
        var from0 = 0; var from1 = 0
        for (ly in 0 until plan.lh) for (lx in 0 until plan.lw) {
            val d = Vec3.fromLonLat(((lx + 0.5f) / plan.lw - 0.5f) * 2f * Math.PI.toFloat(), (0.5f - (ly + 0.5f) / plan.lh) * Math.PI.toFloat())
            if (kotlin.math.acos(d.dot(obj).coerceIn(-1f, 1f)) > 4.5f * DEG) continue
            val w0 = weightOf(0, lx, ly)
            if (w0 > 0.5f) from0++ else from1++
        }
        println("obiekt: komórek z klatki 0 = $from0, z klatki 1 = $from1")
        assertTrue(minOf(from0, from1) <= (from0 + from1) / 20)
    }

    @Test
    fun keyframeStoreKeepsCoveringFrames() {
        val store = KeyframeStore(null)
        val g = FrameGeom(tanX, tanY)
        val gf = GrayFrame(8, 8)
        var kept = 0
        // Malowanie co 2° w poziomie: zachowane klatki co ~15–20°, nie każda.
        for (yaw in 0..120 step 2) {
            val p = pose(yaw.toFloat(), 0f)
            val d = store.decide(p, g, 1f)
            if (store.add(d, p, g, 1f, gf, null, 4, 3, null) != null) kept++
        }
        println("zachowane klatki: $kept z 61")
        assertTrue(kept in 4..12)
        // Lepsza klatka w prawie tej samej pozie zastępuje gorszą.
        val p = pose(0.5f, 0f)
        assertEquals(KeyframeStore.Decision.SKIP, store.decide(p, g, 1f))
    }

    @Test
    fun lensEstimatorConverges() {
        val est = LensEstimator()
        repeat(40) { est.add(1f + (1.05f - est.fovScale) / est.fovScale, 0.07f - est.k1) }
        assertEquals(1.05f, est.fovScale, 0.01f)
        assertEquals(0.07f, est.k1, 0.01f)
    }
}
