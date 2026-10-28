package com.blacksingularities.open360.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.blacksingularities.open360.data.Settings
import com.blacksingularities.open360.stitch.CaptureEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Co wiemy o kamerze po uruchomieniu. */
data class CameraInfo(
    val tanX: Float,
    val tanY: Float,
    val fovH: Float,
    val fovV: Float,
    val frameW: Int,
    val frameH: Int,
    val minZoom: Float,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    /** Czy można sterować czasem naświetlania ręcznie (krótsze czasy = ostrzejsze klatki). */
    val manualExposure: Boolean,
)

/** Metadane klatki z wyniku zdjęcia (Camera2). */
class FrameMeta(val timestamp: Long, val exposureNs: Long, val skewNs: Long, val iso: Int, val focus: Float?)

/**
 * CameraX: tylny aparat, klatki YUV w orientacji matrycy (obrót robią shadery i CPU),
 * analiza na osobnym wątku. Kąt widzenia liczony z ogniskowej i fizycznego rozmiaru matrycy
 * (z uwzględnieniem kadrowania). Z wyników zdjęć zbieramy czas naświetlania i czas odczytu
 * matrycy każdej klatki – do oceny rozmycia i korekty migawki szczelinowej.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val engine: CaptureEngine,
    private val settings: Settings,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    var info: CameraInfo? = null
        private set
    var onInfo: ((CameraInfo) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val metaSize = 64
    private val metas = arrayOfNulls<FrameMeta>(metaSize)
    private var metaHead = 0
    @Volatile private var lastMeta: FrameMeta? = null

    private var exposureRange: android.util.Range<Long>? = null
    private var isoRange: android.util.Range<Int>? = null
    private var manualSensor = false
    private var manualFocus = false
    private var locked = false

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
            val m = FrameMeta(
                ts,
                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                result.get(android.hardware.camera2.CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE),
            )
            synchronized(metas) {
                metas[metaHead] = m
                metaHead = (metaHead + 1) % metaSize
            }
            if (!locked) lastMeta = m
        }
    }

    /** Metadane klatki o znaczniku czasu [ts] (albo najbliższej, gdy brak dokładnej). */
    fun metaFor(ts: Long): FrameMeta? = synchronized(metas) {
        var best: FrameMeta? = null
        var bd = Long.MAX_VALUE
        for (m in metas) {
            if (m == null) continue
            val d = abs(m.timestamp - ts)
            if (d < bd) { bd = d; best = m }
        }
        if (bd < 50_000_000L) best else null
    }

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (t: Throwable) {
                onError?.invoke(t.message ?: "Nie udało się uruchomić aparatu")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun bind() {
        val p = provider ?: return
        p.unbindAll()
        val short = settings.effectiveFrameHeight()
        val longSide = short * 4 / 3
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(longSide, short), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setOutputImageRotationEnabled(false)
            .setTargetRotation(Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        val ext = Camera2Interop.Extender(builder)
        // Stabilizacja (cyfrowa i optyczna) przesuwa kadr względem żyroskopu – psułaby geometrię.
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        ext.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        ext.setSessionCaptureCallback(resultCallback)
        val analysis = builder.build()
        analysis.setAnalyzer(executor) { img -> engine.onFrame(img, metaFor(img.imageInfo.timestamp)) }
        val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        camera = cam

        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val tsSource = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        engine.timestampsRealtime = tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        exposureRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        manualFocus = manualSensor && (c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        distortionMode = c2.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            ?.let { m -> if (m.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)) CaptureRequest.DISTORTION_CORRECTION_MODE_FAST else null }

        val zoom = cam.cameraInfo.zoomState.value
        val minZoom = zoom?.minZoomRatio ?: 1f
        val ratio = if (settings.wide && minZoom < 1f) minZoom else 1f
        cam.cameraControl.setZoomRatio(ratio)

        val res = analysis.resolutionInfo?.resolution ?: Size(longSide, short)
        val outLong = max(res.width, res.height).toFloat()
        val outShort = min(res.width, res.height).toFloat()

        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.3f
        val phys = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixel = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        var effW = phys?.width ?: 5.6f
        var effH = phys?.height ?: 4.2f
        if (pixel != null && active != null && pixel.width > 0 && pixel.height > 0) {
            effW *= active.width().toFloat() / pixel.width
            effH *= active.height().toFloat() / pixel.height
        }
        var tLong = max(effW, effH) / (2f * focal)
        var tShort = min(effW, effH) / (2f * focal)
        val outAspect = outLong / outShort
        val sensorAspect = tLong / tShort
        if (outAspect < sensorAspect) tLong = tShort * outAspect else tShort = tLong / outAspect
        tLong /= ratio
        tShort /= ratio

        val ev = cam.cameraInfo.exposureState
        val range = ev.exposureCompensationRange
        val step = ev.exposureCompensationStep.toFloat()
        if (ev.isExposureCompensationSupported) {
            cam.cameraControl.setExposureCompensationIndex(settings.exposure.coerceIn(range.lower, range.upper))
        }

        val i = CameraInfo(
            tanX = tShort,
            tanY = tLong,
            fovH = Math.toDegrees(2.0 * kotlin.math.atan(tShort.toDouble())).toFloat(),
            fovV = Math.toDegrees(2.0 * kotlin.math.atan(tLong.toDouble())).toFloat(),
            frameW = outShort.toInt(),
            frameH = outLong.toInt(),
            minZoom = minZoom,
            evMin = range.lower,
            evMax = range.upper,
            evStep = step,
            manualExposure = manualSensor,
        )
        info = i
        engine.setCamera(tShort, tLong, outShort.toInt())
        onInfo?.invoke(i)
        setLocked(false)
    }

    private var distortionMode: Int? = null

    /**
     * Na czas zbierania: stała ekspozycja i biel (cała panorama w jednym świetle), ostrość
     * zablokowana (zmiana ostrości zmienia kąt widzenia) i – jeśli aparat pozwala – krótszy
     * czas naświetlania przy wyższej czułości o tej samej jasności. Krótszy czas to mniej
     * rozmazanych klatek przy ruchu; czułość rośnie najwyżej 4× (szum).
     */
    fun setLocked(locked: Boolean) {
        val cam = camera ?: return
        this.locked = locked
        val o = CaptureRequestOptions.Builder()
        distortionMode?.let { o.setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
        val m = lastMeta
        if (locked) {
            val er = exposureRange
            val ir = isoRange
            if (manualSensor && m != null && m.exposureNs > 0 && m.iso > 0 && er != null && ir != null) {
                val target = engine.targetExposureNs
                var t = min(m.exposureNs, target).coerceAtLeast(m.exposureNs / 4).coerceIn(er.lower, er.upper)
                val isoMax = min(ir.upper, max(3200, m.iso))
                var iso = (m.iso.toLong() * m.exposureNs / t).toInt()
                if (iso > isoMax) {
                    iso = isoMax
                    t = (m.exposureNs * m.iso.toLong() / iso).coerceIn(er.lower, er.upper)
                }
                iso = iso.coerceIn(ir.lower, ir.upper)
                o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                o.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                o.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                o.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(t, 33_333_333L))
            } else {
                o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            val focus = m?.focus
            if (manualFocus && focus != null) {
                o.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                o.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            }
        } else {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        runCatching { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(o.build()) }
    }

    fun setExposure(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    fun stop() {
        provider?.unbindAll()
        camera = null
    }

    fun shutdown() {
        stop()
        executor.shutdown()
    }
}
