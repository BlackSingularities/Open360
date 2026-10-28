package com.blacksingularities.open360.stitch

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blacksingularities.open360.camera.OrientationTracker
import com.blacksingularities.open360.data.BlendMode
import com.blacksingularities.open360.data.CaptureMode
import com.blacksingularities.open360.data.Settings
import com.blacksingularities.open360.gl.ExportRequest
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3
import com.blacksingularities.open360.math.angleBetween
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.sqrt

/** Klatka przekazywana do wątku GL: piksele RGBA, poza i polecenie zszycia. */
class StitchFrame {
    var w = 0
    var h = 0
    var buf: ByteBuffer = ByteBuffer.allocateDirect(4)
    val panoToCam = FloatArray(9)
    var tanX = 0.5f
    var tanY = 0.7f
    var gain = 1f
    var stitch = false
    var blend = BlendMode.BEST
    var feather = 0.35f
    var vignette = 0f
    var box = LonLatBox(0, 1, 0, 1)
    @Volatile var ready = false

    fun ensure(w: Int, h: Int) {
        if (buf.capacity() < w * h * 4) buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        this.w = w
        this.h = h
    }
}

/** Komunikat dla użytkownika w trakcie robienia panoramy. */
enum class Hint(val text: String) {
    NONE(""),
    WAIT_SENSOR("Czekam na czujnik orientacji…"),
    TOO_FAST("Wolniej – obraz się rozmywa"),
    AIM("Nakieruj środek na kropkę"),
    HOLD("Trzymaj nieruchomo…"),
    PAUSED("Wstrzymano – dotknij, aby malować"),
    READY("Dotknij spustu, aby zacząć"),
    DONE("Wszystkie punkty zebrane"),
}

/**
 * Mózg zszywania (wątek analizy kamery): przypisuje klatce pozę z czujników, poprawia ją
 * dopasowaniem do panoramy, wyrównuje ekspozycję, decyduje, czy klatkę zszyć, i przekazuje
 * ją do GPU. Stan dla interfejsu publikuje na wątek główny.
 */
class CaptureEngine(private val settings: Settings, val tracker: OrientationTracker) {
    private val main = Handler(Looper.getMainLooper())

    // Parametry kamery (pion telefonu): połówkowe tangensy kąta widzenia.
    @Volatile var tanX = 0.55f
        private set
    @Volatile var tanY = 0.73f
        private set
    @Volatile var timestampsRealtime = true

    /** Rozmiar płótna GPU – do wyznaczania zakresu zszywania. */
    @Volatile var canvasW = 4096

    // Referencja kierunku (yaw startowy) i korekta dryfu z dopasowania obrazem.
    @Volatile private var ref: Mat3? = null
    @Volatile private var corr = Mat3.I

    private val low = LowResCanvas()
    private val lowUndo = LowResCanvas()
    private var undoCorr = Mat3.I
    private var undoTargets = BooleanArray(0)
    private var undoCount = 0
    private var gray = GrayFrame(96, 128)
    private var gain = 1f
    private var dwell = 0
    private var lastUi = 0L
    private var stitchedSinceCoverage = 0

    val frame = StitchFrame()
    private val frameLock = Object()

    /** Polecenia dla renderera (czyszczenie, cofanie, eksport) – wykonywane w wątku GL. */
    val glCommands = ConcurrentLinkedQueue<GlCommand>()

    // Stan interfejsu (wątek główny).
    var recording by mutableStateOf(false)
        private set
    var coverage by mutableFloatStateOf(0f)
        private set
    var speed by mutableFloatStateOf(0f)
        private set
    var hint by mutableStateOf(Hint.READY)
        private set
    var stitched by mutableIntStateOf(0)
        private set
    var aligned by mutableStateOf(false)
        private set
    var exposureGain by mutableFloatStateOf(1f)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var targets by mutableStateOf<List<Target>>(emptyList())
        private set
    var targetsDone by mutableIntStateOf(0)
        private set
    var hasContent by mutableStateOf(false)
        private set

    /** Zdarzenia: zdjęcie w punkcie, ukończenie siatki, zmiana nagrywania (blokada ekspozycji). */
    var onPointCaptured: (() -> Unit)? = null
    var onAllPointsDone: (() -> Unit)? = null
    var onRecordingChanged: ((Boolean) -> Unit)? = null

    fun setCamera(tanX: Float, tanY: Float) {
        this.tanX = tanX
        this.tanY = tanY
        rebuildTargets()
    }

    fun rebuildTargets() {
        val t = TargetGrid.build(settings.range, tanX, tanY, settings.pointOverlap)
        main.post {
            targets = t
            targetsDone = 0
        }
    }

    // ── Sterowanie (wątek główny) ────────────────────────────────────────────

    fun toggle() = if (recording) pause() else start()

    fun start() {
        if (recording) return
        synchronized(low) {
            lowUndo.copyFrom(low)
            undoCorr = corr
            undoTargets = BooleanArray(targets.size) { targets[it].done }
            undoCount = stitched
        }
        glCommands += GlCommand.Snapshot
        canUndo = hasContent
        recording = true
        hint = if (settings.mode == CaptureMode.POINTS) Hint.AIM else Hint.NONE
        onRecordingChanged?.invoke(true)
    }

    fun pause() {
        if (!recording) return
        recording = false
        hint = Hint.PAUSED
        onRecordingChanged?.invoke(false)
    }

    fun undo() {
        if (!canUndo) return
        pause()
        synchronized(low) {
            low.copyFrom(lowUndo)
            corr = undoCorr
            targets.forEachIndexed { i, t -> t.done = undoTargets.getOrElse(i) { false } }
            hasContentInternal = undoCount > 0
        }
        glCommands += GlCommand.Restore
        stitched = undoCount
        targetsDone = targets.count { it.done }
        coverage = low.coverage(settings.range)
        hasContent = stitched > 0
        canUndo = false
    }

    fun reset() {
        pause()
        synchronized(low) {
            low.clear()
            ref = null
            corr = Mat3.I
            gain = 1f
            hasContentInternal = false
        }
        glCommands += GlCommand.Clear
        stitched = 0
        coverage = 0f
        hasContent = false
        canUndo = false
        hint = Hint.READY
        targets.forEach { it.done = false }
        targetsDone = 0
        rebuildTargets()
    }

    /** Ustawia bieżący kierunek jako środek panoramy (tylko przed pierwszą klatką). */
    fun recenter() {
        if (!hasContent) ref = null
    }

    fun contentBounds(fullW: Int, fullH: Int): IntArray? = synchronized(low) { low.contentBounds(fullW, fullH) }

    /** Obrót: układ urządzenia teraz → panorama (dla podglądu, z najświeższej próbki czujnika). */
    fun viewDevToPano(): Mat3? {
        val q = tracker.latest ?: return null
        val r = ref ?: referenceFor(q.toMat3())
        return corr * r * q.toMat3()
    }

    private fun referenceFor(r: Mat3): Mat3 {
        val f = r.transform(Vec3(0f, 0f, -1f))
        // Poziomy kierunek patrzenia staje się długością 0° (środkiem panoramy).
        return if (abs(f.z) > 0.97f) Mat3.I else Mat3.rotZ(f.lon)
    }

    // ── Klatki (wątek analizy) ───────────────────────────────────────────────

    fun onFrame(image: ImageProxy) {
        try {
            process(image)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        val ts = image.imageInfo.timestamp - settings.syncDelayMs * 1_000_000L
        val now = SystemClock.elapsedRealtimeNanos()
        val q = (if (timestampsRealtime && abs(now - ts) < 1_000_000_000L) tracker.poseAt(ts) else null)
            ?: tracker.latest
        if (q == null) {
            postHint(Hint.WAIT_SENSOR)
            return
        }
        val r = q.toMat3()
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val src = plane.buffer
        val rowStride = plane.rowStride

        val g = if (gray.w == 96 && gray.h == (96 * h / w).coerceAtLeast(8)) gray else GrayFrame(96, (96 * h / w).coerceAtLeast(8)).also { gray = it }
        makeGray(src, rowStride, w, h, g)

        val blend = settings.blend
        val feather = settings.feather
        val spd = tracker.angularSpeed
        var doStitch = false
        var devToPano: Mat3
        var pointHit = false
        var allDone = false
        var curHint: Hint
        synchronized(low) {
            val reference = ref ?: referenceFor(r).also { ref = it }
            devToPano = corr * reference * r

            // Korekta dryfu: dopasowanie klatki do zszytej już panoramy.
            var ok = false
            if (settings.imageAlign && hasContentInternal && g.contrast > 0.03f && spd < 90f) {
                val res = align(devToPano, g)
                if (res != null) {
                    corr = res.first
                    devToPano = corr * reference * r
                    if (settings.autoGain && res.second > 0f) gain += (res.second - gain) * 0.3f
                    ok = true
                }
            } else if (settings.autoGain && hasContentInternal) {
                val gEst = gainEstimate(devToPano, g)
                if (gEst > 0f) gain += (gEst - gain) * 0.3f
            }
            if (!settings.autoGain) gain = 1f
            gain = gain.coerceIn(0.6f, 1.6f)
            alignedInternal = ok

            val forward = devToPano.transform(Vec3(0f, 0f, -1f))
            curHint = Hint.NONE
            if (recording) {
                when (settings.mode) {
                    CaptureMode.CONTINUOUS -> {
                        doStitch = spd < settings.maxSpeed
                        if (!doStitch) curHint = Hint.TOO_FAST
                    }
                    CaptureMode.POINTS -> {
                        val ts2 = targets
                        val near = ts2.filter { !it.done }.minByOrNull { angleBetween(it.dir, forward) }
                        if (near == null) {
                            curHint = Hint.DONE
                        } else {
                            val ang = angleBetween(near.dir, forward) / DEG
                            if (ang < 3.5f) {
                                if (spd < 14f) dwell++ else dwell = 0
                                curHint = if (spd < 14f) Hint.HOLD else Hint.TOO_FAST
                                if (dwell >= 4) {
                                    doStitch = true
                                    near.done = true
                                    dwell = 0
                                    pointHit = true
                                    allDone = ts2.all { it.done }
                                }
                            } else {
                                dwell = 0
                                curHint = Hint.AIM
                            }
                        }
                    }
                }
            } else {
                curHint = if (hasContentInternal) Hint.PAUSED else Hint.READY
            }

            if (doStitch) {
                val panoToCam = devToPano.transposed()
                val border = borderDirs(devToPano)
                val poleInside = poleVisible(panoToCam)
                low.splat(g, panoToCam, tanX, tanY, gain, blend, feather, LonLatBox.of(border, forward, low.w, low.h, poleInside))
                hasContentInternal = true
                stitchedSinceCoverage++
            }
        }

        // Przekazanie do GPU (podgląd na żywo + ewentualne zszycie).
        val f = frame
        synchronized(frameLock) {
            val deadline = SystemClock.uptimeMillis() + 80
            while (f.ready && SystemClock.uptimeMillis() < deadline) frameLock.wait(10)
            f.ensure(w, h)
            copyRgba(src, rowStride, w, h, f.buf)
            devToPano.transposed().toGl(f.panoToCam)
            f.tanX = tanX
            f.tanY = tanY
            f.gain = gain
            f.blend = blend
            f.feather = feather
            f.vignette = settings.vignette
            f.stitch = doStitch
            if (doStitch) {
                val panoToCam = devToPano.transposed()
                f.box = LonLatBox.of(borderDirs(devToPano), devToPano.transform(Vec3(0f, 0f, -1f)), canvasW, canvasW / 2, poleVisible(panoToCam))
            }
            f.ready = true
        }

        val nowMs = SystemClock.uptimeMillis()
        val publishCoverage = stitchedSinceCoverage >= 6 || (doStitch && settings.mode == CaptureMode.POINTS)
        val cov = if (publishCoverage) synchronized(low) { low.coverage(settings.range) } else -1f
        if (publishCoverage) stitchedSinceCoverage = 0
        if (doStitch || nowMs - lastUi > 120 || pointHit) {
            lastUi = nowMs
            val al = alignedInternal
            val gn = gain
            main.post {
                speed = spd
                if (curHint != Hint.NONE || hint != Hint.PAUSED) hint = curHint
                aligned = al
                exposureGain = gn
                if (doStitch) {
                    stitched++
                    hasContent = true
                }
                if (cov >= 0f) coverage = cov
                if (pointHit) {
                    targetsDone = targets.count { it.done }
                    onPointCaptured?.invoke()
                }
                if (allDone) {
                    hint = Hint.DONE
                    onAllPointsDone?.invoke()
                    if (settings.autoFinish) pause()
                }
            }
        }
    }

    @Volatile private var hasContentInternal = false
    @Volatile private var alignedInternal = false

    /** Renderer zużył klatkę. */
    fun frameConsumed() {
        synchronized(frameLock) {
            frame.ready = false
            frameLock.notifyAll()
        }
    }

    private fun postHint(h: Hint) {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastUi < 200) return
        lastUi = nowMs
        main.post { hint = h }
    }

    private fun borderDirs(devToPano: Mat3): List<Vec3> {
        val out = ArrayList<Vec3>(16)
        for (i in 0..3) {
            val t = i / 3f * 2f - 1f
            out += devToPano.transform(Vec3(t * tanX, tanY, -1f).normalized())
            out += devToPano.transform(Vec3(t * tanX, -tanY, -1f).normalized())
            out += devToPano.transform(Vec3(tanX, t * tanY, -1f).normalized())
            out += devToPano.transform(Vec3(-tanX, t * tanY, -1f).normalized())
        }
        return out
    }

    private fun poleVisible(panoToCam: Mat3): Boolean {
        for (z in floatArrayOf(1f, -1f)) {
            val d = panoToCam.transform(Vec3(0f, 0f, z))
            if (d.z < 0) {
                val u = (d.x / -d.z) / tanX
                val v = (d.y / -d.z) / tanY
                if (abs(u) < 1.05f && abs(v) < 1.05f) return true
            }
        }
        return false
    }

    // ── Dopasowanie obrazem ─────────────────────────────────────────────────

    private class Samples(val dirs: Array<Vec3>, val vals: FloatArray)

    private var samples: Samples? = null
    private var samplesKey = ""

    private fun samplesFor(g: GrayFrame): Samples {
        val key = "${g.w}x${g.h}:$tanX:$tanY"
        samples?.let { if (samplesKey == key) return it.also { s -> fillVals(s, g) } }
        val step = 3
        val list = ArrayList<Vec3>()
        var y = 2
        while (y < g.h - 2) {
            var x = 2
            while (x < g.w - 2) {
                val u = (x + 0.5f) / g.w
                val v = (y + 0.5f) / g.h
                list += Vec3((2 * u - 1) * tanX, (1 - 2 * v) * tanY, -1f).normalized()
                x += step
            }
            y += step
        }
        val s = Samples(list.toTypedArray(), FloatArray(list.size))
        samples = s
        samplesKey = key
        fillVals(s, g)
        return s
    }

    private fun fillVals(s: Samples, g: GrayFrame) {
        for (i in s.dirs.indices) {
            val d = s.dirs[i]
            val u = 0.5f + 0.5f * (d.x / -d.z) / tanX
            val v = 0.5f - 0.5f * (d.y / -d.z) / tanY
            s.vals[i] = g.sample(u, v)
        }
    }

    /** Wynik: (1 − NCC, liczba próbek w zakładce, wzmocnienie). */
    private fun score(rot: Mat3, s: Samples): FloatArray {
        var n = 0
        var sf = 0.0; var sc = 0.0; var sff = 0.0; var scc = 0.0; var sfc = 0.0
        for (i in s.dirs.indices) {
            val c = low.sample(rot.transform(s.dirs[i]))
            if (c.isNaN()) continue
            val f = s.vals[i].toDouble()
            n++
            sf += f; sc += c; sff += f * f; scc += c * c; sfc += f * c
        }
        if (n < 20) return floatArrayOf(2f, n.toFloat(), 0f)
        val mf = sf / n; val mc = sc / n
        val vf = sff / n - mf * mf
        val vc = scc / n - mc * mc
        val cov = sfc / n - mf * mc
        val ncc = if (vf > 1e-6 && vc > 1e-6) cov / sqrt(vf * vc) else 0.0
        val gEst = if (mf > 0.02) (mc / mf).toFloat() else 0f
        return floatArrayOf((1 - ncc).toFloat(), n.toFloat(), gEst)
    }

    private fun gainEstimate(devToPano: Mat3, g: GrayFrame): Float {
        val s = samplesFor(g)
        val r = score(devToPano, s)
        return if (r[1] > s.dirs.size * 0.2f) r[2] else 0f
    }

    /** Szuka małego obrotu (yaw, pitch, roll ≤ 2°) poprawiającego zgodność z panoramą. */
    private fun align(devToPano: Mat3, g: GrayFrame): Pair<Mat3, Float>? {
        val s = samplesFor(g)
        val base = score(devToPano, s)
        if (base[1] < s.dirs.size * 0.25f) return null
        val forward = devToPano.transform(Vec3(0f, 0f, -1f))
        val right = forward.cross(Vec3(0f, 0f, 1f)).let { if (it.length() < 1e-3f) Vec3(1f, 0f, 0f) else it.normalized() }

        fun delta(yaw: Float, pitch: Float, roll: Float): Mat3 =
            Mat3.axisAngle(forward, roll * DEG) * Mat3.rotZ(yaw * DEG) * Mat3.axisAngle(right, pitch * DEG)

        var bestY = 0f; var bestP = 0f; var bestR = 0f
        var best = base
        val coarse = floatArrayOf(-1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f)
        for (dy in coarse) for (dp in coarse) {
            if (dy == 0f && dp == 0f) continue
            val sc = score(delta(dy, dp, 0f) * devToPano, s)
            if (sc[1] >= s.dirs.size * 0.25f && sc[0] < best[0]) {
                best = sc; bestY = dy; bestP = dp
            }
        }
        val fine = floatArrayOf(-0.25f, -0.125f, 0f, 0.125f, 0.25f)
        val cy = bestY; val cp = bestP
        for (dy in fine) for (dp in fine) {
            if (dy == 0f && dp == 0f) continue
            val sc = score(delta(cy + dy, cp + dp, 0f) * devToPano, s)
            if (sc[1] >= s.dirs.size * 0.25f && sc[0] < best[0]) {
                best = sc; bestY = cy + dy; bestP = cp + dp
            }
        }
        for (dr in floatArrayOf(-1f, -0.5f, 0.5f, 1f)) {
            val sc = score(delta(bestY, bestP, dr) * devToPano, s)
            if (sc[1] >= s.dirs.size * 0.25f && sc[0] < best[0]) {
                best = sc; bestR = dr
            }
        }
        // Zbyt słaba zgodność (np. pusta ściana, ruchome obiekty) – nie ruszamy pozy.
        if (1f - best[0] < 0.55f) return null
        if (base[0] - best[0] < 0.004f) return Pair(corr, best[2])
        // Tłumienie: połowa poprawki na klatkę – stabilnie i bez skoków.
        val d = delta(bestY * 0.6f, bestP * 0.6f, bestR * 0.6f)
        return Pair(d * corr, best[2])
    }

    // ── Konwersje pikseli ───────────────────────────────────────────────────

    private fun makeGray(src: ByteBuffer, rowStride: Int, w: Int, h: Int, g: GrayFrame) {
        val sx = w.toFloat() / g.w
        val sy = h.toFloat() / g.h
        for (y in 0 until g.h) {
            val py = ((y + 0.5f) * sy).toInt().coerceIn(0, h - 2)
            for (x in 0 until g.w) {
                val pxl = ((x + 0.5f) * sx).toInt().coerceIn(0, w - 2)
                var acc = 0f
                for (oy in 0..1) for (ox in 0..1) {
                    val i = (py + oy) * rowStride + (pxl + ox) * 4
                    val rr = src.get(i).toInt() and 0xFF
                    val gg = src.get(i + 1).toInt() and 0xFF
                    val bb = src.get(i + 2).toInt() and 0xFF
                    acc += 0.299f * rr + 0.587f * gg + 0.114f * bb
                }
                g.px[y * g.w + x] = acc / (4f * 255f)
            }
        }
        g.computeStats()
    }

    private fun copyRgba(src: ByteBuffer, rowStride: Int, w: Int, h: Int, dst: ByteBuffer) {
        dst.clear()
        val row = w * 4
        if (rowStride == row) {
            val dup = src.duplicate()
            dup.position(0)
            dup.limit(row * h)
            dst.put(dup)
        } else {
            val dup = src.duplicate()
            for (y in 0 until h) {
                dup.limit(y * rowStride + row)
                dup.position(y * rowStride)
                dst.put(dup)
            }
        }
        dst.flip()
    }
}

/** Polecenia wykonywane przez renderer w wątku GL. */
sealed class GlCommand {
    data object Clear : GlCommand()
    data object Snapshot : GlCommand()
    data object Restore : GlCommand()
    class Export(val request: ExportRequest) : GlCommand()
}
