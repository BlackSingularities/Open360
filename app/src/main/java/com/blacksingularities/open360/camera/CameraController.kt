package com.blacksingularities.open360.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
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
)

/**
 * CameraX: tylny aparat, klatki RGBA obrócone do pionu (shader obsłuży też bufor nieobrócony), analiza na osobnym wątku.
 * Kąt widzenia liczony z ogniskowej i fizycznego rozmiaru matrycy (z uwzględnieniem kadrowania).
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
        val longSide = settings.frameHeight * 4 / 3
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(longSide, settings.frameHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        // Stabilizacja obrazu przycina i przesuwa kadr – psułaby geometrię zszycia.
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val analysis = builder.build()
        analysis.setAnalyzer(executor) { img -> engine.onFrame(img) }
        val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        camera = cam

        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val tsSource = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        engine.timestampsRealtime = tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

        val zoom = cam.cameraInfo.zoomState.value
        val minZoom = zoom?.minZoomRatio ?: 1f
        val ratio = if (settings.wide && minZoom < 1f) minZoom else 1f
        cam.cameraControl.setZoomRatio(ratio)

        val res = analysis.resolutionInfo?.resolution ?: Size(longSide, settings.frameHeight)
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
        tLong *= settings.fovScale
        tShort *= settings.fovScale

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
        )
        info = i
        engine.setCamera(tShort, tLong)
        onInfo?.invoke(i)
        setLocked(false)
    }

    /** Blokada ekspozycji i balansu bieli – cała panorama w jednym świetle. */
    fun setLocked(locked: Boolean) {
        val cam = camera ?: return
        val lock = locked && settings.lockExposure
        runCatching {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
                    .build(),
            )
        }
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
