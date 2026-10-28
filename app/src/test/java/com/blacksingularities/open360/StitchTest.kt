package com.blacksingularities.open360

import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3
import com.blacksingularities.open360.stitch.AlignResult
import com.blacksingularities.open360.stitch.Aligner
import com.blacksingularities.open360.stitch.Blend
import com.blacksingularities.open360.stitch.PoseCorrector
import com.blacksingularities.open360.stitch.FrameGeom
import com.blacksingularities.open360.stitch.Gamma
import com.blacksingularities.open360.stitch.GrayCanvas
import com.blacksingularities.open360.stitch.GrayFrame
import com.blacksingularities.open360.stitch.LonLatBox
import com.blacksingularities.open360.stitch.Photometry
import com.blacksingularities.open360.stitch.SampleSet
import com.blacksingularities.open360.stitch.Sharpness
import com.blacksingularities.open360.stitch.SyncEstimator
import com.blacksingularities.open360.stitch.VignetteEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/** Testy algorytmów zszywania na syntetycznej scenie (szum wartości na sferze). */
class StitchTest {
    private val tanX = 0.55f
    private val tanY = 0.73f

    /** Proceduralna scena: kilka oktaw szumu 3D – szczegóły od ~30° do ~0,4°. */
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

    /** Klatka widziana w pozie [pose] przez kamerę [geom]; [vig] – winietowanie obiektywu. */
    private fun render(pose: Mat3, geom: FrameGeom, w: Int, vig: Float = 0f, gain: Float = 1f): GrayFrame {
        val h = (w * geom.tanY / geom.tanX).toInt()
        val f = GrayFrame(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
            val ray = geom.rayAt((2 * u - 1) * geom.tanX, (1 - 2 * v) * geom.tanY)
            val lin = Gamma.lin(world(pose.transform(ray).normalized()))
            val r2 = Photometry.radius2(u, v, geom)
            f.px[y * w + x] = Gamma.gam(lin * gain / (1 + vig * r2))
        }
        f.computeStats()
        return f
    }

    private fun pose(yawDeg: Float, pitchDeg: Float, rollDeg: Float = 0f) =
        Mat3.view(yawDeg * DEG, pitchDeg * DEG, rollDeg * DEG)

    private fun boxFor(pose: Mat3, geom: FrameGeom, c: GrayCanvas): LonLatBox {
        val border = ArrayList<Vec3>()
        for (i in 0..8) {
            val t = i / 8f * 2 - 1
            border += pose.transform(geom.rayAt(t * geom.tanX, geom.tanY).normalized())
            border += pose.transform(geom.rayAt(t * geom.tanX, -geom.tanY).normalized())
            border += pose.transform(geom.rayAt(geom.tanX, t * geom.tanY).normalized())
            border += pose.transform(geom.rayAt(-geom.tanX, t * geom.tanY).normalized())
        }
        return LonLatBox.of(border, pose.transform(Vec3(0f, 0f, -1f)), c.w, c.h, false)
    }

    private fun splat(c: GrayCanvas, pose: Mat3, geom: FrameGeom, w: Int, photo: Photometry = Photometry(), vig: Float = 0f) {
        c.splat(render(pose, geom, w, vig), pose.transposed(), geom, photo, 1f, boxFor(pose, geom, c))
    }

    private fun angleDeg(a: Mat3, b: Mat3) = (a * b.transposed()).log().length() / DEG

    private fun reference(c: GrayCanvas, geom: FrameGeom, w: Int, photo: Photometry = Photometry(), vig: Float = 0f) {
        for (yaw in listOf(-40f, -20f, 0f, 20f, 40f)) for (pitch in listOf(-25f, 0f, 25f)) splat(c, pose(yaw, pitch), geom, w, photo, vig)
    }

    @Test
    fun rotationLogExpRoundTrip() {
        val v = Vec3(0.1f, -0.2f, 0.05f)
        val back = Mat3.exp(v).log()
        assertEquals(v.x, back.x, 1e-5f); assertEquals(v.y, back.y, 1e-5f); assertEquals(v.z, back.z, 1e-5f)
        val e = Aligner.eigenSym3(doubleArrayOf(2.0, 1.0, 0.0, 1.0, 2.0, 0.0, 0.0, 0.0, 5.0))
        assertEquals(1.0, e[0], 1e-6); assertEquals(3.0, e[1], 1e-6); assertEquals(5.0, e[2], 1e-6)
    }

    @Test
    fun coarseThenFineRecoversPose() {
        val geom = FrameGeom(tanX, tanY)
        val fine = GrayCanvas(3600, 1800)
        val coarse = GrayCanvas(1440, 720)
        reference(fine, geom, 640)
        reference(coarse, geom, 200)
        val truth = pose(10f, 8f, 3f)
        val start = Mat3.exp(Vec3(0.6f, -1.1f, 1.3f).normalized() * (2.5f * DEG)) * truth
        val cg = render(truth, geom, 160)
        val c = Aligner.coarse(coarse, SampleSet.dense(cg, 3), geom, start)
        assertEquals(AlignResult.Status.OK, c.status)
        assertTrue("coarse ${angleDeg(c.devToPano, truth)}", angleDeg(c.devToPano, truth) < 0.4f)
        val fg = render(truth, geom, 600)
        val r = Aligner.refine(fine, SampleSet.textured(fg, 6, 3000), geom, c.devToPano)
        val err = angleDeg(r.devToPano, truth)
        println("coarse err=${angleDeg(c.devToPano, truth)}° fine err=$err° ncc=${r.ncc} valid=${r.valid}")
        assertEquals(AlignResult.Status.OK, r.status)
        assertTrue("fine $err", err < 0.02f)
    }

    @Test
    fun rollingShutterIsModelled() {
        val still = FrameGeom(tanX, tanY)
        val fine = GrayCanvas(3600, 1800)
        reference(fine, still, 640)
        // 40°/s obrotu, odczyt matrycy 25 ms, bufor obrócony o 90°.
        val moving = FrameGeom(tanX, tanY, 90, 0.025f, Vec3(0.2f, 0.65f, 0.1f))
        val truth = pose(-12f, 5f)
        val fg = render(truth, moving, 600)
        val start = Mat3.exp(Vec3(0.3f, 0.2f, -0.4f) * DEG) * truth
        val ok = Aligner.refine(fine, SampleSet.textured(fg, 6, 3000), moving, start)
        val naive = Aligner.refine(fine, SampleSet.textured(fg, 6, 3000), still, start)
        println("rolling shutter: modelled ncc=${ok.ncc} err=${angleDeg(ok.devToPano, truth)}°, naive ncc=${naive.ncc}")
        assertTrue(angleDeg(ok.devToPano, truth) < 0.02f)
        assertTrue(ok.ncc > naive.ncc)
    }

    @Test
    fun fovScaleIsEstimated() {
        val geom = FrameGeom(tanX, tanY)
        val fine = GrayCanvas(3600, 1800)
        reference(fine, geom, 640)
        // Prawdziwy kąt widzenia o 3% szerszy niż deklarowany – pojedyncza klatka to wykrywa.
        val wide = FrameGeom(tanX * 1.03f, tanY * 1.03f)
        val truth = pose(5f, -4f)
        val fg = render(truth, wide, 600)
        var s = 1f
        repeat(12) {
            val r = Aligner.refine(fine, SampleSet.textured(fg, 6, 3000), geom.withTan(tanX * s, tanY * s), truth, estimateFov = true)
            if (r.status == AlignResult.Status.OK) s *= r.fovScale
        }
        println("fov scale=$s")
        assertEquals(1.03f, s, 0.006f)
    }

    @Test
    fun imageCorrectionDoesNotAccumulate() {
        // Obrót w miejscu z telefonem w ręce: obraz pokazuje o 13% większy obrót niż żyroskop.
        val pc = PoseCorrector()
        var worst = 0f
        var yaw = 0f
        for (k in 0 until 300) {
            yaw += 1.2f * DEG // 30°/s, klatka co 40 ms
            val gyro = Mat3.rotZ(yaw)
            // Klatka dopasowana do sąsiadów wskazuje pozę przesuniętą o paralaksę względem
            // tego, gdzie leżą sąsiedzi (a ci leżą tam, gdzie postawiła ich korekta).
            val placedNeighbour = pc.corr * Mat3.rotZ(yaw - 4f * DEG)
            val image = Mat3.rotZ(4f * DEG * 1.13f) * placedNeighbour
            val stitched = pc.stitchPose(image * gyro.transposed(), gyro)
            pc.update(image * gyro.transposed(), 0.04f)
            worst = maxOf(worst, (stitched * gyro.transposed()).log().length() / DEG)
        }
        println("max odchylenie od żyroskopu po 360°: $worst°")
        // Bez ograniczenia byłoby ~47°.
        assertTrue(worst < 2.5f)
        // Prawdziwy dryf żyroskopu (stałe 2°) korekta jednak przyjmuje.
        val drift = Mat3.rotZ(2f * DEG)
        repeat(400) { pc.update(drift, 0.05f) }
        assertEquals(2f, pc.corr.log().length() / DEG, 0.05f)
    }

    @Test
    fun vignetteAndGainAreLearned() {
        val geom = FrameGeom(tanX, tanY)
        val k = 0.35f
        val fine = GrayCanvas(3600, 1800)
        val est = VignetteEstimator()
        // Panorama budowana klatka po klatce z bieżącą korektą – jak w aplikacji.
        val poses = ArrayList<Mat3>()
        for (pitch in listOf(0f, 22f, -22f)) for (yaw in -60..60 step 12) poses += pose(yaw.toFloat(), pitch)
        var gainSeen = 0f
        for ((i, p) in poses.withIndex()) {
            val f = render(p, geom, 400, vig = k, gain = if (i == poses.size - 1) 0.7f else 1f)
            if (i > 0) {
                val st = Aligner.photometry(fine, SampleSet.dense(f, 4), geom, p, est.k)
                est.add(st)
                if (i == poses.size - 1) gainSeen = st.gain
            }
            fine.splat(f, p.transposed(), geom, Photometry(1f, est.k), 1f, boxFor(p, geom, fine))
        }
        println("vignette k=${est.k} gain(0.7 exposure)=$gainSeen")
        assertEquals(k, est.k, 0.12f)
        assertEquals(1f / 0.7f, gainSeen, 0.12f)
    }

    @Test
    fun syncOffsetIsLearned() {
        val est = SyncEstimator()
        val e = 0.035f
        var t = 0L
        // Zmienna prędkość kątowa, korekta = ω·(e − offset) + wolny dryf.
        for (k in 0 until 400) {
            val w = Vec3(0.6f * kotlin.math.sin(k * 0.13f), 0.2f * kotlin.math.cos(k * 0.07f), 0.9f * kotlin.math.sin(k * 0.05f))
            val drift = Vec3(0.001f * k / 400f, 0f, 0.002f * k / 400f)
            val corr = Mat3.exp(w * (e - est.offset) + drift)
            est.add(corr, w, t)
            t += 120_000_000L
        }
        println("sync offset=${est.offset}")
        assertEquals(e, est.offset, 0.004f)
    }

    @Test
    fun blendPutsSeamBetweenCentres() {
        // Środek nowej klatki wygrywa ze skrajem starej i odwrotnie.
        assertEquals(1f, Blend.mix(Blend.weight(0.5f, 0.5f, 1f), Blend.weight(0.95f, 0.5f, 1f), 0f), 1e-6f)
        assertEquals(0f, Blend.mix(Blend.weight(0.95f, 0.5f, 1f), Blend.weight(0.5f, 0.5f, 1f), 0f), 1e-6f)
        // Puste miejsce – zawsze nowa treść.
        assertEquals(1f, Blend.mix(0.01f, 0f, 0f), 0f)
        // Ostrzejsza klatka wypiera rozmytą nawet nieco dalej od środka.
        assertTrue(Blend.mix(Blend.weight(0.3f, 0.5f, 1f), Blend.weight(0.5f, 0.5f, 0.4f), 0f) > 0.9f)
        val c = GrayCanvas(720, 360)
        val geom = FrameGeom(tanX, tanY)
        splat(c, pose(0f, 0f), geom, 160)
        assertTrue(c.coverage(com.blacksingularities.open360.data.CaptureRange.SPHERE) in 0.03f..0.12f)
        val b = c.contentBounds(7200, 3600)!!
        assertTrue(abs(b[2] - 7200 * 2 * kotlin.math.atan(tanX) / DEG / 360f) < 150f)
    }

    @Test
    fun blurryFramesAreDetected() {
        val geom = FrameGeom(tanX, tanY)
        val fine = GrayCanvas(3600, 1800)
        reference(fine, geom, 560)
        val truth = pose(8f, 4f)
        val sharp = render(truth, geom, 560)
        val sharpRatio = Sharpness.relative(fine, sharp, geom, truth)
        // Rozmycie ruchu w poziomie (uśrednienie przesuniętych kopii) o [r] px w obie strony.
        fun motion(r: Int): GrayFrame {
            val b = GrayFrame(sharp.w, sharp.h)
            for (y in 0 until sharp.h) for (x in 0 until sharp.w) {
                var s = 0f
                for (k in -r..r) s += sharp.px[y * sharp.w + (x + k).coerceIn(0, sharp.w - 1)]
                b.px[y * sharp.w + x] = s / (2 * r + 1)
            }
            b.computeStats()
            return b
        }
        val mildRatio = Sharpness.relative(fine, motion(1), geom, truth)
        val blurRatio = Sharpness.relative(fine, motion(3), geom, truth)
        // Ta sama ostrość przy innej ekspozycji nie może być odrzucona.
        val dark = GrayFrame(sharp.w, sharp.h)
        for (i in sharp.px.indices) dark.px[i] = sharp.px[i] * 0.6f
        dark.computeStats()
        val darkRatio = Sharpness.relative(fine, dark, geom, truth)
        println("sharpness: sharp=$sharpRatio dark=$darkRatio mild(3px)=$mildRatio blurred(7px)=$blurRatio")
        assertTrue(sharpRatio > 0.8f)
        assertTrue(darkRatio > 0.8f)
        assertTrue(blurRatio in 0f..0.6f)
        assertTrue(mildRatio > 0.6f)
    }

    @Test
    fun fastMathIsAccurate() {
        var err = 0.0
        for (i in 0 until 20000) {
            val a = (i * 0.000314f) * 2f * kotlin.math.PI.toFloat()
            val r = 0.01f + (i % 97) / 30f
            val x = kotlin.math.cos(a) * r; val y = kotlin.math.sin(a) * r
            err = maxOf(err, abs(com.blacksingularities.open360.math.FastMath.atan2(y, x) - kotlin.math.atan2(y, x)).toDouble())
            val z = -1f + i / 10000f
            err = maxOf(err, abs(com.blacksingularities.open360.math.FastMath.asin(z) - kotlin.math.asin(z.coerceIn(-1f, 1f))).toDouble())
        }
        println("fast atan2/asin max error = $err rad")
        assertTrue(err < 2e-5)
    }
}
