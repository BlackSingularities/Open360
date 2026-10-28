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
    /** Obrót bufora (°, zgodnie ze wskazówkami), który stawia obraz pionowo. */
    var rotation = 0
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
    NO_MATCH("Nie pasuje – wróć do uchwyconego fragmentu"),
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
private const val GRAY_W = 160

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
    private var gray = GrayFrame(GRAY_W, 213)
    private var mismatch = 0
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

    // Kąt widzenia z danych aparatu i samoczynna poprawka z dopasowania obrazem.
    private var baseTanX = 0.55f
    private var baseTanY = 0.73f
    @Volatile var fovAuto = 1f
        private set
    private var fovChecks = 0

    fun setCamera(tanX: Float, tanY: Float) {
        baseTanX = tanX
        baseTanY = tanY
        this.tanX = tanX * fovAuto
        this.tanY = tanY * fovAuto
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
            fovChecks = 0
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
        // Bufor przychodzi w orientacji matrycy – obrót do pionu robimy sami (CPU i shader).
        val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val uw = if (rot % 180 == 0) w else h
        val uh = if (rot % 180 == 0) h else w
        val plane = image.planes[0]
        val src = plane.buffer
        val rowStride = plane.rowStride

        val gh = (GRAY_W * uh / uw).coerceAtLeast(8)
        val g = if (gray.h == gh) gray else GrayFrame(GRAY_W, gh).also { gray = it }
        makeGray(src, rowStride, w, h, rot, g)

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
            var bad = false
            if (settings.imageAlign && hasContentInternal && g.contrast > 0.02f && spd < 120f) {
                when (val res = align(devToPano, g)) {
                    is Align.Ok -> {
                        corr = res.corr
                        devToPano = corr * reference * r
                        if (settings.autoGain && res.gain > 0f) gain += (res.gain - gain) * 0.3f
                        ok = true
                        mismatch = 0
                        if (settings.autoFov && res.ncc > 0.75f && ++fovChecks % 6 == 0) calibrateFov(devToPano, g)
                    }
                    Align.Mismatch -> {
                        bad = true
                        mismatch++
                    }
                    Align.NoOverlap -> mismatch = 0
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
                        // Klatka sprzeczna z tym, co już zszyte, nie jest malowana (chyba że
                        // niezgodność trwa długo – wtedy scena się zmieniła i trzeba ją przyjąć).
                        val reject = bad && mismatch < 45
                        doStitch = spd < settings.maxSpeed && !reject
                        if (spd >= settings.maxSpeed) curHint = Hint.TOO_FAST else if (reject) curHint = Hint.NO_MATCH
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
                                if (dwell >= 4 && (!bad || dwell >= 20)) {
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
            f.rotation = rot
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

    private class Samples(val dirs: Array<Vec3>, val vals: FloatArray, val coarse: IntArray)

    private var samples: Samples? = null
    private var samplesKey = ""

    private fun samplesFor(g: GrayFrame): Samples {
        val key = "${g.w}x${g.h}:$tanX:$tanY"
        samples?.let { if (samplesKey == key) return it.also { s -> fillVals(s, g) } }
        val step = 3
        val list = ArrayList<Vec3>()
        val coarse = ArrayList<Int>()
        var y = 2
        var row = 0
        while (y < g.h - 2) {
            var x = 2
            var col = 0
            while (x < g.w - 2) {
                if (row % 2 == 0 && col % 2 == 0) coarse += list.size
                col++
                val u = (x + 0.5f) / g.w
                val v = (y + 0.5f) / g.h
                list += Vec3((2 * u - 1) * tanX, (1 - 2 * v) * tanY, -1f).normalized()
                x += step
            }
            y += step
            row++
        }
        val s = Samples(list.toTypedArray(), FloatArray(list.size), coarse.toIntArray())
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

    /** Wynik: (1 − NCC, udział próbek w zakładce, wzmocnienie). [idx] – podzbiór próbek albo null. */
    private fun score(rot: Mat3, s: Samples, idx: IntArray? = null): FloatArray {
        var n = 0
        var sf = 0.0; var sc = 0.0; var sff = 0.0; var scc = 0.0; var sfc = 0.0
        val m = rot.m
        val total = idx?.size ?: s.dirs.size
        for (k in 0 until total) {
            val i = idx?.get(k) ?: k
            val d = s.dirs[i]
            val c = low.sample(
                Vec3(
                    m[0] * d.x + m[1] * d.y + m[2] * d.z,
                    m[3] * d.x + m[4] * d.y + m[5] * d.z,
                    m[6] * d.x + m[7] * d.y + m[8] * d.z,
                ),
            )
            if (c.isNaN()) continue
            val f = s.vals[i].toDouble()
            n++
            sf += f; sc += c; sff += f * f; scc += c * c; sfc += f * c
        }
        if (n < 20) return floatArrayOf(2f, n.toFloat() / total, 0f)
        val mf = sf / n; val mc = sc / n
        val vf = sff / n - mf * mf
        val vc = scc / n - mc * mc
        val cov = sfc / n - mf * mc
        val ncc = if (vf > 1e-6 && vc > 1e-6) cov / sqrt(vf * vc) else 0.0
        val gEst = if (mf > 0.02) (mc / mf).toFloat() else 0f
        return floatArrayOf((1 - ncc).toFloat(), n.toFloat() / total, gEst)
    }

    private fun gainEstimate(devToPano: Mat3, g: GrayFrame): Float {
        val s = samplesFor(g)
        val r = score(devToPano, s)
        return if (r[1] > 0.2f) r[2] else 0f
    }

    private sealed class Align {
        class Ok(val corr: Mat3, val gain: Float, val ncc: Float) : Align()
        data object Mismatch : Align()
        data object NoOverlap : Align()
    }

    /**
     * Szuka obrotu (yaw, pitch, roll do ±4°) najlepiej zgadzającego klatkę z panoramą.
     * Zgrubnie na co czwartej próbce, potem coraz drobniej na wszystkich (piramida kroków
     * 1° → 0,5° → 0,25°). Miarą jest znormalizowana korelacja (odporna na zmiany jasności).
     */
    private fun align(devToPano: Mat3, g: GrayFrame): Align {
        val s = samplesFor(g)
        val base = score(devToPano, s)
        if (base[1] < 0.2f) return Align.NoOverlap
        val forward = devToPano.transform(Vec3(0f, 0f, -1f))
        val right = forward.cross(Vec3(0f, 0f, 1f)).let { if (it.length() < 1e-3f) Vec3(1f, 0f, 0f) else it.normalized() }

        fun delta(yaw: Float, pitch: Float, roll: Float): Mat3 =
            Mat3.axisAngle(forward, roll * DEG) * Mat3.rotZ(yaw * DEG) * Mat3.axisAngle(right, pitch * DEG)

        var bY = 0f; var bP = 0f; var bR = 0f
        var best = score(devToPano, s, s.coarse)
        fun tryAt(y: Float, p: Float, r: Float, idx: IntArray?): Boolean {
            val sc = score(delta(y, p, r) * devToPano, s, idx)
            if (sc[1] >= 0.2f && sc[0] < best[0]) {
                best = sc; bY = y; bP = p; bR = r
                return true
            }
            return false
        }
        // 1) Zgrubnie: yaw × pitch co 1°.
        for (y in -4..4) for (p in -4..4) if (y != 0 || p != 0) tryAt(y.toFloat(), p.toFloat(), 0f, s.coarse)
        // 2) Przechył co 1° w najlepszym miejscu.
        run {
            val y0 = bY; val p0 = bP
            for (r in -3..3) if (r != 0) tryAt(y0, p0, r.toFloat(), s.coarse)
        }
        // 3) Wszystkie próbki: ±0,5° we wszystkich osiach.
        best = score(delta(bY, bP, bR) * devToPano, s)
        run {
            val y0 = bY; val p0 = bP; val r0 = bR
            for (dy in -1..1) for (dp in -1..1) for (dr in -1..1) {
                if (dy == 0 && dp == 0 && dr == 0) continue
                tryAt(y0 + dy * 0.5f, p0 + dp * 0.5f, r0 + dr * 0.5f, null)
            }
        }
        // 4) Doszlifowanie co 0,25°.
        run {
            val y0 = bY; val p0 = bP
            for (dy in -1..1) for (dp in -1..1) if (dy != 0 || dp != 0) tryAt(y0 + dy * 0.25f, p0 + dp * 0.25f, bR, null)
        }
        val ncc = 1f - best[0]
        // Wyraźna sprzeczność w dużej zakładce – klatka nie pasuje (ruch, paralaksa, błąd czujnika).
        if (ncc < 0.35f && best[1] > 0.4f) return Align.Mismatch
        // Słaba zgodność (gładka ściana) – nie ruszamy pozy, ale klatkę można przyjąć.
        if (ncc < 0.6f) return Align.NoOverlap
        if (bY == 0f && bP == 0f && bR == 0f) return Align.Ok(corr, best[2], ncc)
        // Lekkie tłumienie – stabilnie, bez drgań.
        return Align.Ok(delta(bY * 0.8f, bP * 0.8f, bR * 0.8f) * corr, best[2], ncc)
    }

    /**
     * Samokalibracja kąta widzenia: błędny kąt rozjeżdża szwy coraz bardziej ku brzegom kadru.
     * Porównujemy zgodność dla kadru o 2% węższego i szerszego i powoli przesuwamy poprawkę
     * w lepszą stronę (w granicach ±12% danych producenta).
     */
    private fun calibrateFov(devToPano: Mat3, g: GrayFrame) {
        val s = samplesFor(g)
        fun scoreAt(k: Float): Float {
            val dirs = Array(s.dirs.size) { i ->
                val d = s.dirs[i]
                Vec3(d.x / -d.z * k, d.y / -d.z * k, -1f).normalized()
            }
            return score(devToPano, Samples(dirs, s.vals, s.coarse))[0]
        }
        val mid = scoreAt(1f)
        val narrow = scoreAt(0.98f)
        val wide = scoreAt(1.02f)
        val step = when {
            wide < mid - 0.002f && wide <= narrow -> 0.004f
            narrow < mid - 0.002f && narrow < wide -> -0.004f
            else -> 0f
        }
        if (step == 0f) return
        fovAuto = (fovAuto + step).coerceIn(0.88f, 1.12f)
        tanX = baseTanX * fovAuto
        tanY = baseTanY * fovAuto
    }

    // ── Konwersje pikseli ───────────────────────────────────────────────────

    /** Szary obraz w orientacji pionowej; [rot] – obrót bufora zgodnie ze wskazówkami zegara. */
    private fun makeGray(src: ByteBuffer, rowStride: Int, w: Int, h: Int, rot: Int, g: GrayFrame) {
        for (y in 0 until g.h) {
            val v = (y + 0.5f) / g.h
            for (x in 0 until g.w) {
                val u = (x + 0.5f) / g.w
                // (u, v) w obrazie pionowym → (s, t) w buforze (oba z osią y w dół).
                val bs: Float
                val bt: Float
                when (rot) {
                    90 -> { bs = v; bt = 1f - u }
                    180 -> { bs = 1f - u; bt = 1f - v }
                    270 -> { bs = 1f - v; bt = u }
                    else -> { bs = u; bt = v }
                }
                val px = (bs * w).toInt().coerceIn(0, w - 2)
                val py = (bt * h).toInt().coerceIn(0, h - 2)
                var acc = 0f
                for (oy in 0..1) for (ox in 0..1) {
                    val i = (py + oy) * rowStride + (px + ox) * 4
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
