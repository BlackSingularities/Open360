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
import com.blacksingularities.open360.camera.FrameMeta
import com.blacksingularities.open360.camera.OrientationTracker
import com.blacksingularities.open360.data.CaptureMode
import com.blacksingularities.open360.data.Settings
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.PI_F
import com.blacksingularities.open360.math.Vec3
import com.blacksingularities.open360.math.angleBetween
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Komunikat dla użytkownika w trakcie robienia panoramy. */
enum class Hint(val text: String, val warning: Boolean = false) {
    NONE(""),
    WAIT_SENSOR("Czekam na czujnik orientacji…"),
    TOO_FAST("Za szybko! Zwolnij – klatki się rozmazują", warning = true),
    BLURRY("Rozmazane – kadr odrzucony. Prowadź wolniej", warning = true),
    NO_MATCH("Nie pasuje – wróć do uchwyconego fragmentu", warning = true),
    AIM("Nakieruj środek na kropkę"),
    HOLD("Trzymaj nieruchomo…"),
    PAUSED("Wstrzymano – dotknij, aby malować"),
    READY("Dotknij spustu, aby zacząć"),
    DONE("Wszystkie punkty zebrane"),
}

/**
 * Mózg zszywania (wątek analizy kamery). Dla każdej klatki:
 *  1. poza z czujnika w chwili środka naświetlania (z czasem odczytu matrycy i samoczynnie
 *     wyznaczanym przesunięciem zegarów), prędkość kątowa → model migawki szczelinowej,
 *  2. rozmycie ruchu = prędkość × czas naświetlania w pikselach panoramy – klatki rozmazane
 *     nie są zszywane (i użytkownik dostaje ostrzeżenie),
 *  3. zgrubne dopasowanie obrazem (każda klatka – śledzi dryf żyroskopu),
 *  4. dla klatek kluczowych: dokładne dopasowanie (Gauss-Newton, ułamek piksela), test
 *     ostrości względem już zszytej treści, wyrównanie ekspozycji i winietowania,
 *     samokalibracja kąta widzenia – i zszycie na CPU (odniesienie) oraz GPU (obraz).
 */
class CaptureEngine(
    private val settings: Settings,
    val tracker: OrientationTracker,
    /** Katalog na klatki do zapisu (kolor w JPEG); null – bez globalnego dopasowania. */
    keyframeDir: java.io.File? = null,
) {
    private val main = Handler(Looper.getMainLooper())

    // Kąt widzenia (pion telefonu): połówkowe tangensy z danych aparatu × samokalibracja.
    @Volatile private var baseTanX = 0.55f
    @Volatile private var baseTanY = 0.73f
    private val lens = LensEstimator()
    private var lensKey = ""
    val tanX: Float get() = baseTanX * lens.fovScale
    val tanY: Float get() = baseTanY * lens.fovScale
    /** Dystorsja obiektywu z samokalibracji. */
    val k1: Float get() = lens.k1
    private var frameShort = 1080

    /** Klatki do globalnego dopasowania przy zapisie. */
    val store = KeyframeStore(keyframeDir?.also { it.mkdirs() })
    @Volatile var timestampsRealtime = true

    /** Rozmiar płótna GPU (ustawia renderer). */
    @Volatile var canvasW = 4096
        set(v) {
            field = v
            updateTargets()
        }
    /** Czy renderer trzyma kopię do cofania (przy 16K – nie). */
    @Volatile var undoSupported = true

    /** Docelowy czas naświetlania (ns) na czas zbierania – czyta CameraController. */
    @Volatile var targetExposureNs = 4_000_000L
        private set

    // Referencja kierunku (yaw startowy) i korekta dryfu z dopasowania obrazem.
    @Volatile private var ref: Mat3? = null
    private val corrector = PoseCorrector()
    private val corr: Mat3 get() = corrector.corr
    private var lastCorrNs = 0L

    private val lock = Any()
    private val coarse = GrayCanvas(1440, 720)
    private val fine = GrayCanvas(3600, 1800)
    private val coarseUndo = GrayCanvas(1440, 720)
    private val fineUndo = GrayCanvas(3600, 1800)
    private var undoCorr = Mat3.I
    private var undoTargets = BooleanArray(0)
    private var undoCount = 0

    private var gray = GrayFrame(GRAY_W, 213)
    private var fineGray = GrayFrame(560, 742)
    private var mismatch = 0
    private var gain = 1f
    private var dwell = 0
    private var lastUi = 0L
    private var stitchedSinceCoverage = 0
    private var keyframes = 0
    private var blurryUntil = 0L
    private var lastWarnMs = 0L

    private val vignette = VignetteEstimator()
    private val sync = SyncEstimator()
    private val policy = KeyframePolicy()

    val frame = StitchFrame()
    private val frameLock = Object()

    /** Polecenia dla renderera (czyszczenie, cofanie, eksport) – wykonywane w wątku GL. */
    val glCommands = ConcurrentLinkedQueue<GlCommand>()

    // Stan interfejsu (wątek główny).
    var recording by mutableStateOf(false)
        private set
    var coverage by mutableFloatStateOf(0f)
        private set
    /** Prędkość obrotu (°/s) i dopuszczalna prędkość przy obecnym czasie naświetlania. */
    var speed by mutableFloatStateOf(0f)
        private set
    var speedLimit by mutableFloatStateOf(30f)
        private set
    /** Rozmycie w ułamku dopuszczalnego (≥ 1 – klatki odrzucane). */
    var blurLevel by mutableFloatStateOf(0f)
        private set
    var hint by mutableStateOf(Hint.READY)
        private set
    var stitched by mutableIntStateOf(0)
        private set
    var rejected by mutableIntStateOf(0)
        private set
    var aligned by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var targets by mutableStateOf<List<Target>>(emptyList())
        private set
    var targetsDone by mutableIntStateOf(0)
        private set
    var hasContent by mutableStateOf(false)
        private set

    /** Zdarzenia: zdjęcie w punkcie, ukończenie siatki, zmiana nagrywania, ostrzeżenie o ruchu. */
    var onPointCaptured: (() -> Unit)? = null
    var onAllPointsDone: (() -> Unit)? = null
    var onRecordingChanged: ((Boolean) -> Unit)? = null
    var onMotionWarning: (() -> Unit)? = null

    /**
     * Dane aparatu. [key] identyfikuje obiektyw i tryb (aparat, powiększenie, rozdzielczość) –
     * kalibracja zapamiętana dla tego samego klucza wraca od razu.
     */
    fun setCamera(tanX: Float, tanY: Float, frameShort: Int, key: String = "") {
        synchronized(lock) {
            this.frameShort = frameShort
            this.baseTanX = tanX
            this.baseTanY = tanY
            if (key != lensKey) {
                lensKey = key
                val saved = settings.lensCal.split(';')
                if (saved.size >= 4 && saved[0] == key) {
                    lens.set(saved[1].toFloatOrNull() ?: 1f, saved[2].toFloatOrNull() ?: 0f, saved[3].toIntOrNull() ?: 0)
                } else {
                    lens.set(1f, 0f, 0)
                }
            }
        }
        updateTargets()
        rebuildTargets()
    }

    private fun saveLens() {
        if (lensKey.isEmpty() || lens.samples < 5) return
        val v = "$lensKey;${lens.fovScale};${lens.k1};${lens.samples}"
        main.post { settings.lensCal = v }
    }

    /** Piksele na radian, którymi naprawdę dysponujemy (mniejsze z płótna i klatki). */
    private fun pxPerRad(): Float = min(canvasW / (2f * PI_F), frameShort / (2f * tanX))

    private fun updateTargets() {
        // Czas naświetlania, przy którym obrót 20°/s rozmywa o ~80% dopuszczalnego.
        val t = BLUR_MAX * 0.8f / (20f * DEG * pxPerRad())
        targetExposureNs = (t * 1e9f).toLong().coerceIn(500_000L, 33_000_000L)
    }

    fun rebuildTargets() {
        val t = TargetGrid.build(settings.range, tanX, tanY, POINT_OVERLAP)
        main.post {
            targets = t
            targetsDone = 0
        }
    }

    // ── Sterowanie (wątek główny) ────────────────────────────────────────────

    fun toggle() = if (recording) pause() else start()

    fun start() {
        if (recording) return
        synchronized(lock) {
            coarseUndo.copyFrom(coarse)
            fineUndo.copyFrom(fine)
            undoCorr = corr
            undoTargets = BooleanArray(targets.size) { targets[it].done }
            undoCount = stitched
            policy.reset()
            store.snapshot()
        }
        glCommands += GlCommand.Snapshot
        canUndo = hasContent && undoSupported
        recording = true
        hint = if (settings.mode == CaptureMode.POINTS) Hint.AIM else Hint.NONE
        onRecordingChanged?.invoke(true)
    }

    fun pause() {
        if (!recording) return
        saveLens()
        recording = false
        hint = Hint.PAUSED
        onRecordingChanged?.invoke(false)
    }

    fun undo() {
        if (!canUndo) return
        pause()
        synchronized(lock) {
            coarse.copyFrom(coarseUndo)
            fine.copyFrom(fineUndo)
            corrector.reset(undoCorr)
            targets.forEachIndexed { i, t -> t.done = undoTargets.getOrElse(i) { false } }
            hasContentInternal = undoCount > 0
            policy.reset()
            store.restore()
        }
        glCommands += GlCommand.Restore
        stitched = undoCount
        targetsDone = targets.count { it.done }
        coverage = synchronized(lock) { coarse.coverage(settings.range) }
        hasContent = stitched > 0
        canUndo = false
    }

    fun reset() {
        pause()
        synchronized(lock) {
            coarse.clear()
            fine.clear()
            ref = null
            corrector.reset()
            lastCorrNs = 0L
            gain = 1f
            keyframes = 0
            mismatch = 0
            vignette.reset()
            sync.reset()
            policy.reset()
            store.clear()
            hasContentInternal = false
        }
        glCommands += GlCommand.Clear
        stitched = 0
        rejected = 0
        coverage = 0f
        hasContent = false
        canUndo = false
        hint = Hint.READY
        targets.forEach { it.done = false }
        targetsDone = 0
        rebuildTargets()
    }

    fun contentBounds(fullW: Int, fullH: Int): IntArray? = synchronized(lock) { coarse.contentBounds(fullW, fullH) }

    /** Wyostrzanie przy eksporcie: mocniej, gdy płótno ma więcej pikseli niż klatki. */
    fun exportSharpen(): Float {
        val ratio = (canvasW / (2f * PI_F)) / (frameShort / (2f * tanX))
        return (0.25f + 0.35f * (ratio - 0.7f)).coerceIn(0.2f, 0.6f)
    }

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

    fun onFrame(image: ImageProxy, meta: FrameMeta?) {
        try {
            process(image, meta)
        } finally {
            image.close()
        }
    }

    @Volatile private var hasContentInternal = false
    @Volatile private var alignedInternal = false

    private fun process(image: ImageProxy, meta: FrameMeta?) {
        val ts = image.imageInfo.timestamp
        val expNs = meta?.exposureNs?.takeIf { it > 0 } ?: 20_000_000L
        val skewNs = meta?.skewNs?.takeIf { it in 1..80_000_000 } ?: 0L
        // Poza z chwili środka naświetlania środkowego wiersza.
        val tc = ts + skewNs / 2 + expNs / 2 + (sync.offset * 1e9f).toLong()
        val now = SystemClock.elapsedRealtimeNanos()
        val useTs = timestampsRealtime && abs(now - tc) < 1_000_000_000L
        val q = (if (useTs) tracker.poseAt(tc) else null) ?: tracker.latest
        if (q == null) {
            postHint(Hint.WAIT_SENSOR)
            return
        }
        val r = q.toMat3()
        val wWorld = (if (useTs) tracker.omegaAt(tc) else null) ?: tracker.omegaAt(now) ?: Vec3(0f, 0f, 0f)
        val wDev = r.transformT(wWorld)
        val w = image.width
        val h = image.height
        val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val uw = if (rot % 180 == 0) w else h
        val uh = if (rot % 180 == 0) h else w
        val yPlane = image.planes[0]

        val gh = (GRAY_W * uh / uw).coerceAtLeast(8)
        val g = if (gray.h == gh) gray else GrayFrame(GRAY_W, gh).also { gray = it }
        GrayFrame.fromLuma(yPlane.buffer, yPlane.rowStride, w, h, rot, g)

        // Rozmycie ruchu w pikselach panoramy (lub klatki, jeśli ma mniej szczegółów).
        val ppr = pxPerRad()
        val blurPx = MotionBlur.pixels(wDev, expNs / 1e9f, tanY, ppr)
        val quality = MotionBlur.quality(blurPx)
        // Dopuszczalne rozmycie – nie mniej, niż daje obrót z prędkością MIN_SPEED (drżenie ręki).
        val blurMax = max(BLUR_MAX, MIN_SPEED * DEG * (expNs / 1e9f) * ppr)
        val tooFast = blurPx > blurMax
        val limitDeg = blurMax / (expNs / 1e9f * ppr) / DEG
        val spd = tracker.angularSpeed

        val nowMs = SystemClock.uptimeMillis()
        var doStitch = false
        var devToPano: Mat3
        var geom: FrameGeom
        var pointHit = false
        var allDone = false
        var blurryReject = false
        var curHint: Hint
        synchronized(lock) {
            val reference = ref ?: referenceFor(r).also { ref = it }
            geom = FrameGeom(tanX, tanY, rot, skewNs / 1e9f, wDev, lens.k1)
            val gyroPose = reference * r
            devToPano = corr * gyroPose
            // Poza wskazana przez obraz (zgrubnie, potem dokładnie) – do korekty dryfu żyroskopu.
            var imagePose: Mat3? = null

            // 1) Zgrubne dopasowanie – na każdej (nierozmazanej) klatce.
            var bad = false
            alignedInternal = false
            if (hasContentInternal && g.contrast > 0.02f && blurPx < blurMax * 2.5f) {
                val res = Aligner.coarse(coarse, SampleSet.dense(g, 3), geom, devToPano)
                when (res.status) {
                    AlignResult.Status.OK -> {
                        imagePose = res.devToPano
                        alignedInternal = true
                        mismatch = 0
                    }
                    AlignResult.Status.MISMATCH -> {
                        bad = true
                        mismatch++
                    }
                    else -> mismatch = 0
                }
            }

            // 2) Czy to kandydat do zszycia.
            val forward = devToPano.transform(Vec3(0f, 0f, -1f))
            curHint = Hint.NONE
            var candidate = false
            var pointTarget: Target? = null
            if (recording) {
                val reject = bad && mismatch < 45
                when (settings.mode) {
                    CaptureMode.CONTINUOUS -> {
                        val step = 2f * atan(tanX) * KEYFRAME_STEP
                        candidate = !tooFast && !reject && policy.wants(devToPano, quality, nowMs, step)
                        curHint = when {
                            tooFast -> Hint.TOO_FAST
                            reject -> Hint.NO_MATCH
                            else -> Hint.NONE
                        }
                    }
                    CaptureMode.POINTS -> {
                        val near = targets.filter { !it.done }.minByOrNull { angleBetween(it.dir, forward) }
                        if (near == null) {
                            curHint = Hint.DONE
                        } else if (angleBetween(near.dir, forward) / DEG < 3.5f) {
                            val still = blurPx < blurMax * 0.6f
                            if (still) dwell++ else dwell = 0
                            curHint = if (still) Hint.HOLD else Hint.TOO_FAST
                            if (dwell >= 3 && (!bad || dwell >= 20)) {
                                candidate = true
                                pointTarget = near
                            }
                        } else {
                            dwell = 0
                            curHint = if (tooFast) Hint.TOO_FAST else Hint.AIM
                        }
                    }
                }
            } else {
                curHint = if (hasContentInternal) Hint.PAUSED else Hint.READY
            }

            // 3) Klatka kluczowa: dokładne dopasowanie, test ostrości, fotometria, zszycie.
            if (candidate) {
                val fw = (2f * atan(tanX) / (FINE_DEG * DEG)).roundToInt().coerceIn(240, 720)
                val fh = (fw * uh / uw).coerceAtLeast(8)
                val fg = if (fineGray.w == fw && fineGray.h == fh) fineGray else GrayFrame(fw, fh).also { fineGray = it }
                GrayFrame.fromLuma(yPlane.buffer, yPlane.rowStride, w, h, rot, fg)
                val samples = SampleSet.textured(fg, 6, 3000)
                var accept = true
                // Poza najlepiej pasująca do obrazu – do pomiarów (ostrość, ekspozycja).
                var fitPose = devToPano
                if (hasContentInternal && fg.contrast > 0.015f) {
                    // Samokalibracja obiektywu od kilku klatek (wcześniej brak zakładek w różnych częściach kadru).
                    val calibrate = keyframes >= 3
                    val res = Aligner.refine(fine, samples, geom, imagePose ?: devToPano, estimateFov = calibrate)
                    when (res.status) {
                        AlignResult.Status.OK -> {
                            if (calibrate && res.conditioned && res.overlap > 0.35f && res.ncc > 0.8f) lens.add(res.fovScale, res.k1Delta)
                            imagePose = res.devToPano
                            fitPose = res.devToPano
                            alignedInternal = true
                            mismatch = 0
                            val deviation = res.devToPano * gyroPose.transposed()
                            sync.add(deviation, (corr * reference).transform(wWorld), tc)
                            // Zszywamy w pozie z żyroskopu z korektą dryfu i małym, lokalnym
                            // dosunięciem do obrazu – paralaksa nie może się sumować.
                            devToPano = corrector.stitchPose(deviation, gyroPose)
                        }
                        AlignResult.Status.MISMATCH -> {
                            mismatch++
                            if (mismatch < 45) accept = false
                        }
                        else -> Unit
                    }
                    // Klatka mniej ostra niż to, co już leży w tym miejscu – odrzucamy.
                    if (accept && res.overlap > 0.3f) {
                        val ratio = Sharpness.relative(fine, fg, geom, fitPose)
                        if (ratio in 0f..SHARP_MIN) {
                            accept = false
                            blurryReject = true
                        }
                    }
                }
                if (accept) {
                    if (hasContentInternal) {
                        val st = Aligner.photometry(fine, samples, geom, fitPose, vignette.k)
                        if (st.gain > 0f) gain += (st.gain.coerceIn(0.5f, 2f) - gain) * 0.5f
                        vignette.add(st)
                    }
                    gain = gain.coerceIn(0.5f, 2f)
                    val panoToCam = devToPano.transposed()
                    val photo = Photometry(gain, vignette.k)
                    val border = borderDirs(devToPano, geom)
                    val fwd = devToPano.transform(Vec3(0f, 0f, -1f))
                    val pole = poleVisible(panoToCam)
                    coarse.splat(g, panoToCam, geom, photo, quality, LonLatBox.of(border, fwd, coarse.w, coarse.h, pole))
                    fine.splat(fg, panoToCam, geom, photo, quality, LonLatBox.of(border, fwd, fine.w, fine.h, pole))
                    // Zachowanie klatki do globalnego dopasowania przy zapisie.
                    val decision = store.decide(devToPano, geom, quality)
                    if (decision != KeyframeStore.Decision.SKIP) {
                        val nv = runCatching { KeyframeJpeg.nv21(image) }.getOrNull()
                        if (nv != null) store.add(decision, devToPano, geom, quality, fg, nv, w, h, KeyframeJpeg::write)
                    }
                    hasContentInternal = true
                    stitchedSinceCoverage++
                    keyframes++
                    policy.accepted(devToPano, quality, nowMs)
                    doStitch = true
                    pointTarget?.let {
                        it.done = true
                        dwell = 0
                        pointHit = true
                        allDone = targets.all { t -> t.done }
                    }
                } else if (blurryReject) {
                    blurryUntil = nowMs + 900
                }
            }
            if (recording && nowMs < blurryUntil && curHint != Hint.TOO_FAST) curHint = Hint.BLURRY

            // Korekta dryfu żyroskopu: powoli i w granicach (zob. PoseCorrector).
            imagePose?.let { ip ->
                val dt = if (lastCorrNs == 0L) 0f else (tc - lastCorrNs) / 1e9f
                corrector.update(ip * gyroPose.transposed(), dt)
                lastCorrNs = tc
            }
        }

        // Przekazanie do GPU: zszywana klatka zawsze, podglądowa tylko gdy renderer nadąża.
        val f = frame
        synchronized(frameLock) {
            if (doStitch) {
                val deadline = SystemClock.uptimeMillis() + 500
                while (f.ready && SystemClock.uptimeMillis() < deadline) frameLock.wait(5)
            }
            if (!f.ready) {
                val up = image.planes[1]; val vp = image.planes[2]
                f.y.copyFrom(yPlane.buffer, yPlane.rowStride, 1, w, h)
                f.u.copyFrom(up.buffer, up.rowStride, up.pixelStride, w / 2, h / 2)
                f.v.copyFrom(vp.buffer, vp.rowStride, vp.pixelStride, w / 2, h / 2)
                f.rotation = rot
                devToPano.transposed().toGl(f.panoToCam)
                f.tanX = geom.tanX
                f.tanY = geom.tanY
                f.k1 = geom.k1
                f.gain = gain
                f.vignette = vignette.k
                f.omega[0] = wDev.x; f.omega[1] = wDev.y; f.omega[2] = wDev.z
                f.skew = geom.skew
                f.quality = quality
                f.stitch = doStitch
                if (doStitch) {
                    f.box = LonLatBox.of(borderDirs(devToPano, geom), devToPano.transform(Vec3(0f, 0f, -1f)), canvasW, canvasW / 2, poleVisible(devToPano.transposed()))
                }
                f.ready = true
            }
        }

        if (recording && (tooFast || blurryReject) && nowMs - lastWarnMs > 1200) {
            lastWarnMs = nowMs
            main.post { onMotionWarning?.invoke() }
        }
        val publishCoverage = stitchedSinceCoverage >= 3 || (doStitch && settings.mode == CaptureMode.POINTS)
        val cov = if (publishCoverage) synchronized(lock) { coarse.coverage(settings.range) } else -1f
        if (publishCoverage) stitchedSinceCoverage = 0
        if (doStitch || blurryReject || nowMs - lastUi > 100 || pointHit) {
            lastUi = nowMs
            val al = alignedInternal
            val bl = blurPx / blurMax
            main.post {
                speed = spd
                speedLimit = limitDeg
                blurLevel = if (recording) bl else bl.coerceAtMost(1f)
                if (curHint != Hint.NONE || hint != Hint.PAUSED) hint = curHint
                aligned = al
                if (doStitch) {
                    stitched++
                    hasContent = true
                }
                if (blurryReject) rejected++
                if (cov >= 0f) coverage = cov
                if (pointHit) {
                    targetsDone = targets.count { it.done }
                    onPointCaptured?.invoke()
                }
                if (allDone) {
                    hint = Hint.DONE
                    onAllPointsDone?.invoke()
                    pause()
                }
            }
        }
    }

    /**
     * Globalne dopasowanie zachowanych klatek i plan złożenia panoramy (wątek w tle, po
     * zatrzymaniu zbierania). Null, gdy klatek za mało albo wynik nie jest lepszy niż na żywo –
     * wtedy zapisujemy płótno z podglądu.
     */
    fun prepareGlobal(progress: (Float) -> Unit): RenderPlan? {
        val frames: List<Keyframe>
        val lens0: Lens
        val vig: Float
        synchronized(lock) {
            frames = store.frames()
            lens0 = Lens(tanX, tanY, lens.k1)
            vig = vignette.k
        }
        if (frames.size < 2) return null
        if (!store.awaitWrites(30_000)) return null
        val ok = frames.filter { it.file.length() > 0 }
        if (ok.size < 2 || ok.size < frames.size * 0.9f) return null
        val res = GlobalAligner.optimize(ok, lens0, vig) { progress(0.75f * it) } ?: return null
        if (!(res.rmsDeg < res.rmsBeforeDeg) || res.rmsDeg > 0.5f) return null
        // Kalibracja z całej panoramy jest pewniejsza niż z pojedynczych klatek – ale tylko gdy
        // scena nie miała dużej paralaksy (ta potrafi udawać inną skalę). Uśredniamy z poprzednią.
        if (res.outlierFraction < 0.3f && res.matches > 200) {
            synchronized(lock) {
                val s = res.lens.tanX / baseTanX
                lens.set(0.5f * (lens.fovScale + s), 0.5f * (lens.k1 + res.lens.k1), max(lens.samples, 20))
            }
            saveLens()
        }
        return Seams.build(ok, res, vig) { progress(0.75f + 0.25f * it) }
    }

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

    private fun borderDirs(devToPano: Mat3, geom: FrameGeom): List<Vec3> {
        val out = ArrayList<Vec3>(32)
        for (i in 0..7) {
            val t = i / 7f * 2f - 1f
            out += devToPano.transform(geom.rayAt(t * geom.tanX, geom.tanY).normalized())
            out += devToPano.transform(geom.rayAt(t * geom.tanX, -geom.tanY).normalized())
            out += devToPano.transform(geom.rayAt(geom.tanX, t * geom.tanY).normalized())
            out += devToPano.transform(geom.rayAt(-geom.tanX, t * geom.tanY).normalized())
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

    private companion object {
        const val GRAY_W = 160
        /** Rozdzielczość odniesienia do dokładnego dopasowania (°/px). */
        const val FINE_DEG = 0.1f
        /** Dopuszczalne rozmycie ruchu (px) – powyżej klatka jest odrzucana. */
        const val BLUR_MAX = 1.6f
        /** Najniższy limit prędkości obrotu (°/s) – wolniej nie da się prowadzić telefonu z ręki. */
        const val MIN_SPEED = 3f
        /** Klatka mniej ostra niż ten ułamek tego, co już zszyte w tym miejscu, nie przechodzi. */
        const val SHARP_MIN = 0.6f
        /** Co jaki ułamek kąta widzenia zszywamy kolejną klatkę w malowaniu. */
        const val KEYFRAME_STEP = 0.07f
        const val POINT_OVERLAP = 0.35f
    }
}

/** Polecenia wykonywane przez renderer w wątku GL. */
sealed class GlCommand {
    data object Clear : GlCommand()
    data object Snapshot : GlCommand()
    data object Restore : GlCommand()
    class Export(val export: com.blacksingularities.open360.gl.CanvasExport) : GlCommand()
    /** Ponowne złożenie płótna z zachowanych klatek po globalnym dopasowaniu. */
    class Repaint(val job: com.blacksingularities.open360.gl.RepaintJob) : GlCommand()
}
