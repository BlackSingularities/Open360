package com.blacksingularities.open360.ui

import android.media.MediaActionSound
import android.net.Uri
import android.opengl.GLSurfaceView
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blacksingularities.open360.camera.CameraController
import com.blacksingularities.open360.camera.CameraInfo
import com.blacksingularities.open360.camera.OrientationTracker
import com.blacksingularities.open360.data.CaptureMode
import com.blacksingularities.open360.data.CaptureRange
import com.blacksingularities.open360.data.Settings
import com.blacksingularities.open360.export.PanoExporter
import com.blacksingularities.open360.export.PanoGeometry
import com.blacksingularities.open360.export.PanoStore
import com.blacksingularities.open360.gl.CanvasExport
import com.blacksingularities.open360.gl.PanoRenderer
import com.blacksingularities.open360.gl.ViewMode
import com.blacksingularities.open360.gl.ViewState
import com.blacksingularities.open360.math.PI_F
import com.blacksingularities.open360.math.Vec3
import com.blacksingularities.open360.stitch.CaptureEngine
import com.blacksingularities.open360.stitch.GlCommand
import com.blacksingularities.open360.stitch.Hint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

@Composable
fun CaptureScreen(settings: Settings, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val tracker = remember { OrientationTracker(context) }
    val engine = remember { CaptureEngine(settings, tracker) }
    val view = remember { ViewState() }
    val renderer = remember { PanoRenderer(engine, view, settings.effectiveCanvas(4096)) }
    val camera = remember { CameraController(context, owner, engine, settings) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    var camInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var maxTex by remember { mutableIntStateOf(4096) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var mode by remember { mutableStateOf(ViewMode.LIVE) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveProgress by remember { mutableFloatStateOf(0f) }
    var exportJob by remember { mutableStateOf<CanvasExport?>(null) }
    var showTips by remember { mutableStateOf(!settings.tipsSeen) }
    var error by remember { mutableStateOf<String?>(null) }

    // Ustawienia widoku – czytane przez renderer w każdej klatce.
    view.mode = mode
    view.grid = settings.showGrid
    view.liveZoomOut = settings.viewZoomOut
    view.liveAlpha = 0.9f
    view.recording = engine.recording

    DisposableEffect(Unit) {
        engine.onRecordingChanged = { camera.setLocked(it) }
        engine.onPointCaptured = {
            if (settings.feedback) {
                glView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
            }
        }
        engine.onAllPointsDone = {
            if (settings.feedback) glView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        // Za szybko albo rozmazana klatka – krótkie „odrzucenie” w dłoni, bez patrzenia na ekran.
        engine.onMotionWarning = {
            if (settings.feedback) {
                val k = if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                glView?.performHapticFeedback(k)
            }
        }
        camera.onInfo = { camInfo = it }
        camera.onError = { error = it }
        renderer.onReady = { maxTex = it }
        renderer.onCanvasFallback = { w ->
            Toast.makeText(context, "Za mało pamięci grafiki – panorama w ${w / 1024}K.", Toast.LENGTH_LONG).show()
        }
        renderer.onContextLost = {
            engine.reset()
            Toast.makeText(context, "Kontekst grafiki został odtworzony – panorama zaczyna się od nowa.", Toast.LENGTH_LONG).show()
        }
        if (!tracker.available) error = "Ten telefon nie ma czujnika orientacji (żyroskopu) – panoramy na żywo nie są możliwe."
        tracker.start()
        camera.start()
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> {
                    engine.pause()
                    glView?.onPause()
                    tracker.stop()
                }
                Lifecycle.Event.ON_RESUME -> {
                    glView?.onResume()
                    tracker.start()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            exportJob?.cancelled = true
            owner.lifecycle.removeObserver(obs)
            camera.shutdown()
            tracker.stop()
            sound.release()
        }
    }

    fun requestClose() {
        if (engine.hasContent && !saving) confirmExit = true else onClose()
    }
    BackHandler { if (showSettings) showSettings = false else requestClose() }

    fun save() {
        if (saving || !engine.hasContent) return
        engine.pause()
        val cw = engine.canvasW
        val ch = cw / 2
        val bounds = engine.contentBounds(cw, ch) ?: return
        val crop = PanoExporter.crop(bounds, cw, ch)
        val job = CanvasExport(crop, engine.exportSharpen())
        saving = true
        saveProgress = 0f
        exportJob = job
        engine.glCommands += GlCommand.Export(job)
        scope.launch {
            val geo = PanoGeometry(cw, ch, crop[0], crop[1], crop[2], crop[3])
            val uri = withContext(Dispatchers.IO) {
                PanoStore.saveStreaming(context, crop[2], crop[3]) { out ->
                    PanoExporter.encode(job, out, PanoStore.xmpPayload(geo)) { p -> scope.launch { saveProgress = p } }
                }
            }
            if (uri == null) job.cancelled = true
            exportJob = null
            saving = false
            if (uri != null) onSaved(uri) else Toast.makeText(context, "Nie udało się zapisać panoramy", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    keepScreenOn = true
                    glView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesty: szczypanie zmienia oddalenie, przeciąganie obraca planetę / przesuwa mapę.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(mode) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        when (mode) {
                            ViewMode.LIVE -> settings.viewZoomOut = (settings.viewZoomOut / zoom).coerceIn(1f, 3f)
                            ViewMode.PLANET -> {
                                view.planetZoom = (view.planetZoom * zoom).coerceIn(0.3f, 4f)
                                view.yaw += pan.x * 0.25f
                            }
                            ViewMode.FLAT -> {
                                view.flatZoom = (view.flatZoom * zoom).coerceIn(1f, 6f)
                                view.flatPanLon -= pan.x * 0.15f / view.flatZoom
                                view.flatPanLat = (view.flatPanLat + pan.y * 0.15f / view.flatZoom).coerceIn(-80f, 80f)
                            }
                            else -> Unit
                        }
                    }
                },
        )

        OverlayLayer(engine, view, settings, mode)

        // Górny pasek.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Outlined.Close, "Zamknij") { requestClose() }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Palette.Scrim).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RoundButton(Icons.Outlined.ViewInAr, "Na żywo", size = 40.dp, active = mode == ViewMode.LIVE, background = Color.Transparent) { mode = ViewMode.LIVE }
                    RoundButton(Icons.Outlined.Map, "Mapa", size = 40.dp, active = mode == ViewMode.FLAT, background = Color.Transparent) { mode = ViewMode.FLAT }
                    RoundButton(Icons.Outlined.Public, "Mała planeta", size = 40.dp, active = mode == ViewMode.PLANET, background = Color.Transparent) { mode = ViewMode.PLANET }
                }
                Spacer(Modifier.weight(1f))
                RoundButton(Icons.Outlined.Settings, "Ustawienia") { showSettings = true }
            }
            Spacer(Modifier.height(10.dp))
            StatusBar(engine, settings, camInfo, Modifier.align(Alignment.CenterHorizontally))
            AnimatedVisibility(engine.recording, Modifier.align(Alignment.CenterHorizontally), enter = fadeIn(), exit = fadeOut()) {
                SpeedMeter(engine, Modifier.padding(top = 8.dp))
            }
        }

        // Dół: podpowiedź, tryby, spust.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hint = engine.hint
            AnimatedVisibility(hint != Hint.NONE, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    hint.text,
                    color = Color.White,
                    fontSize = if (hint.warning) 15.sp else 14.sp,
                    fontWeight = if (hint.warning) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (hint.warning) Palette.Record else Palette.Scrim)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureMode.entries.forEach { m ->
                    Pill(m.label, active = settings.mode == m) {
                        settings.mode = m
                        engine.pause()
                    }
                }
                Pill(settings.range.label, icon = Icons.Outlined.Language) {
                    val all = CaptureRange.entries
                    settings.range = all[(all.indexOf(settings.range) + 1) % all.size]
                    engine.rebuildTargets()
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundButton(Icons.AutoMirrored.Outlined.Undo, "Cofnij ostatni fragment", enabled = engine.canUndo && engine.undoSupported) { engine.undo() }
                    RoundButton(Icons.Outlined.DeleteOutline, "Zacznij od nowa", enabled = engine.hasContent) { confirmReset = true }
                }
                Shutter(engine.recording, settings.mode) {
                    if (settings.feedback) glView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    engine.toggle()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundButton(Icons.Outlined.GridOn, "Siatka", active = settings.showGrid) { settings.showGrid = !settings.showGrid }
                    RoundButton(Icons.Outlined.Check, "Zapisz panoramę", enabled = engine.hasContent, active = engine.hasContent) { save() }
                }
            }
        }

        if (showTips) TipsCard(Modifier.align(Alignment.Center)) {
            showTips = false
            settings.tipsSeen = true
        }

        if (saving) {
            Box(Modifier.fillMaxSize().background(Palette.Scrim), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(14.dp))
                    Text("Zapisywanie panoramy… ${(saveProgress * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { saveProgress },
                        modifier = Modifier.width(200.dp),
                        color = Color.White,
                        trackColor = Palette.PanelHigh,
                    )
                    Text("${engine.canvasW / 1024}K · wyostrzanie i wypełnianie dziur", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        error?.let { msg ->
            AlertDialog(
                onDismissRequest = { error = null },
                confirmButton = { TextButton(onClick = { error = null; onClose() }) { Text("Zamknij", color = Color.White) } },
                title = { Text("Aparat niedostępny") },
                text = { Text(msg) },
                containerColor = Palette.PanelHigh,
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            confirmButton = { TextButton(onClick = { confirmExit = false; save() }) { Text("Zapisz", color = Color.White, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmExit = false; onClose() }) { Text("Odrzuć", color = Palette.Record) } },
            title = { Text("Zapisać panoramę?") },
            text = { Text("Niezapisana panorama przepadnie po wyjściu.") },
            containerColor = Palette.PanelHigh,
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = { TextButton(onClick = { confirmReset = false; engine.reset() }) { Text("Wyczyść", color = Palette.Record, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Anuluj", color = Color.White) } },
            title = { Text("Zacząć od nowa?") },
            text = { Text("Bieżąca panorama zostanie usunięta. Środek nowej panoramy ustawi się tam, gdzie celujesz.") },
            containerColor = Palette.PanelHigh,
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings, camInfo, maxTex,
            object : SettingsEffects {
                override fun cameraChanged() = camera.bind()
                override fun canvasChanged() {
                    renderer.resizeCanvas(settings.effectiveCanvas(maxTex))
                    if (!engine.hasContent) engine.reset()
                }
                override fun exposureChanged() {
                    camera.setExposure(settings.exposure)
                    camera.setLocked(engine.recording)
                }
            },
        ) { showSettings = false }
    }
}

@Composable
private fun StatusBar(engine: CaptureEngine, settings: Settings, cam: CameraInfo?, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Palette.Scrim).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (engine.recording) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Record))
        }
        Text("${(engine.coverage * 100).roundToInt()}%", style = MonoDigits, fontWeight = FontWeight.Bold)
        if (settings.mode == CaptureMode.POINTS) {
            Text("${engine.targetsDone}/${engine.targets.size} pkt", style = MonoDigits, color = Palette.TextDim)
        } else {
            Text("${engine.stitched} kl.", style = MonoDigits, color = Palette.TextDim)
        }
        if (engine.rejected > 0) Text("${engine.rejected} odrz.", style = MonoDigits, color = Palette.Record)
        if (engine.hasContent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (engine.aligned) Color.White else Palette.TextFaint))
                Spacer(Modifier.width(4.dp))
                Text("dopasowanie", fontSize = 12.sp, color = Palette.TextDim)
            }
        }
        Text("${engine.canvasW / 1024}K", style = MonoDigits, color = Palette.TextFaint)
        if (cam != null) Text("${cam.fovH.roundToInt()}°", style = MonoDigits, color = Palette.TextFaint)
    }
}

/**
 * Prędkość obrotu względem limitu wynikającego z czasu naświetlania i rozdzielczości:
 * biały pasek – w porządku, pomarańczowy – blisko limitu, czerwony – klatki odrzucane.
 */
@Composable
private fun SpeedMeter(engine: CaptureEngine, modifier: Modifier) {
    val level = engine.blurLevel
    val color by animateColorAsState(
        when {
            level >= 1f -> Palette.Record
            level >= 0.75f -> Color(0xFFFFB020)
            else -> Color.White
        },
        tween(120), label = "speed",
    )
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Scrim).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    level >= 1f -> "Za szybko"
                    level >= 0.75f -> "Blisko limitu"
                    else -> "Tempo dobre"
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
            )
            Spacer(Modifier.width(10.dp))
            Text("${engine.speed.roundToInt()}°/s · limit ${engine.speedLimit.roundToInt()}°/s", style = MonoDigits, color = Palette.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.width(180.dp).height(6.dp)) {
            val r = size.height / 2
            drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            // Skala do 1,5× limitu; kreska oznacza limit.
            val f = (level / 1.5f).coerceIn(0f, 1f)
            drawRoundRect(color, size = size.copy(width = size.width * f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            val x = size.width / 1.5f
            drawLine(Color.White, Offset(x, -2f), Offset(x, size.height + 2f), 2.dp.toPx())
        }
    }
}

@Composable
private fun Shutter(recording: Boolean, mode: CaptureMode, onClick: () -> Unit) {
    val inner by animateDpAsState(if (recording) 30.dp else 58.dp, tween(180), label = "inner")
    val corner by animateDpAsState(if (recording) 8.dp else 29.dp, tween(180), label = "corner")
    val color by animateColorAsState(if (recording) Palette.Record else Color.White, tween(180), label = "shutter")
    Box(
        Modifier
            .pressable(if (recording) "Wstrzymaj" else if (mode == CaptureMode.POINTS) "Zacznij zbierać punkty" else "Zacznij malować") { onClick() }
            .size(78.dp)
            .border(4.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(color))
        if (!recording && mode == CaptureMode.POINTS) {
            Box(Modifier.size(14.dp).clip(CircleShape).border(2.5.dp, Palette.Ink, CircleShape))
        }
    }
}

/** Warstwa nad obrazem: cele trybu punktów, strzałka do najbliższego, poziomica. */
@Composable
private fun OverlayLayer(engine: CaptureEngine, view: ViewState, settings: Settings, mode: ViewMode) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { tick = it } }
    val points = settings.mode == CaptureMode.POINTS
    Canvas(Modifier.fillMaxSize()) {
        if (tick < 0L) return@Canvas
        val m = engine.viewDevToPano() ?: return@Canvas
        val w = size.width
        val h = size.height
        val aspect = w / h

        fun project(d: Vec3): Offset? {
            return when (mode) {
                ViewMode.LIVE -> {
                    val dev = m.transposed().transform(d)
                    if (dev.z >= -0.05f) return null
                    val tx = engine.tanX * view.liveZoomOut
                    val ty = tx / aspect
                    Offset((0.5f + 0.5f * (dev.x / -dev.z) / tx) * w, (0.5f - 0.5f * (dev.y / -dev.z) / ty) * h)
                }
                ViewMode.FLAT -> {
                    val fit = if (aspect > 2f) PI_F * aspect / 2f else PI_F
                    val tx = fit / view.flatZoom
                    val ty = tx / aspect
                    var dl = d.lon - view.flatPanLon * PI_F / 180f
                    while (dl > PI_F) dl -= 2 * PI_F
                    while (dl < -PI_F) dl += 2 * PI_F
                    Offset((0.5f + 0.5f * dl / tx) * w, (0.5f - 0.5f * (d.lat - view.flatPanLat * PI_F / 180f) / ty) * h)
                }
                else -> null
            }
        }

        val forward = m.transform(Vec3(0f, 0f, -1f))
        if (points && mode != ViewMode.PLANET) {
            val ts = engine.targets
            val next = ts.filter { !it.done }.minByOrNull { com.blacksingularities.open360.math.angleBetween(it.dir, forward) }
            for (t in ts) {
                val p = project(t.dir) ?: continue
                if (t.done) {
                    drawCircle(Color.White.copy(alpha = 0.55f), 5.dp.toPx(), p)
                } else if (t === next) {
                    drawCircle(Color.Black.copy(alpha = 0.35f), 22.dp.toPx(), p)
                    drawCircle(Color.White, 22.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 6.dp.toPx(), p)
                } else {
                    drawCircle(Color.White.copy(alpha = 0.8f), 11.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                }
            }
            // Celownik na środku kadru.
            if (mode == ViewMode.LIVE) {
                val c = Offset(w / 2, h / 2)
                drawCircle(Color.White, 26.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.5f), 27.5f.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                // Strzałka do następnego celu, gdy jest poza ekranem.
                if (next != null) {
                    val dev = m.transposed().transform(next.dir)
                    val p = project(next.dir)
                    val off = p == null || p.x < 0 || p.x > w || p.y < 0 || p.y > h
                    if (off) {
                        val ang = atan2(-dev.y, dev.x)
                        val r = 70.dp.toPx()
                        val tip = Offset(c.x + kotlin.math.cos(ang) * r, c.y + kotlin.math.sin(ang) * r)
                        drawCircle(Color.White, 8.dp.toPx(), tip)
                        drawLine(Color.White, c + (tip - c) * 0.45f, tip, 3.dp.toPx())
                    }
                }
            }
        }
        // Poziomica: linia przechyłu i pochylenie względem horyzontu.
        if (mode == ViewMode.LIVE) {
            val right = m.transform(Vec3(1f, 0f, 0f))
            val roll = -kotlin.math.asin(right.z.coerceIn(-1f, 1f))
            val c = Offset(w / 2, h / 2)
            val len = 46.dp.toPx()
            val dx = kotlin.math.cos(roll) * len
            val dy = kotlin.math.sin(roll) * len
            val level = abs(roll) < 0.02f
            val col = if (level) Color.White else Color.White.copy(alpha = 0.6f)
            val gap = 30.dp.toPx() / len
            drawLine(col, Offset(c.x - dx, c.y + dy), Offset(c.x - dx * gap, c.y + dy * gap), 2.dp.toPx())
            drawLine(col, Offset(c.x + dx * gap, c.y - dy * gap), Offset(c.x + dx, c.y - dy), 2.dp.toPx())
            if (!points) drawCircle(Color.White, 3.dp.toPx(), c)
        }
    }
}

@Composable
private fun TipsCard(modifier: Modifier, onDismiss: () -> Unit) {
    Column(
        modifier
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.PanelHigh)
            .padding(22.dp),
    ) {
        Text("Jak zrobić dobrą panoramę", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        val tips = listOf(
            "Obracaj telefon wokół obiektywu, nie wokół siebie – trzymaj go blisko, łokcie przy ciele.",
            "Ruszaj się powoli i płynnie. Pasek tempa u góry pokazuje limit – rozmazane klatki są odrzucane, a telefon zawibruje, gdy jest za szybko.",
            "Zacznij od horyzontu, potem niebo i ziemia. Wszystko widać na żywo – dopełniaj puste miejsca.",
            "Tryb „Punkty” prowadzi po siatce i robi zdjęcie sam, gdy kadr jest nieruchomy.",
            "Nie musisz robić całej sfery – zapisz w dowolnej chwili.",
        )
        tips.forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}", style = MonoDigits, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Text(t, fontSize = 14.sp, color = Palette.TextDim)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Rozumiem", null, Modifier.fillMaxWidth()) { onDismiss() }
        Text(
            "Podpowiedź wróci po przywróceniu ustawień domyślnych.",
            fontSize = 12.sp, color = Palette.TextFaint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
