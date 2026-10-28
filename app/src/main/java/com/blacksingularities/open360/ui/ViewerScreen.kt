package com.blacksingularities.open360.ui

import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Vrpano
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blacksingularities.open360.camera.OrientationTracker
import com.blacksingularities.open360.data.Settings
import com.blacksingularities.open360.export.PanoGeometry
import com.blacksingularities.open360.export.PanoStore
import com.blacksingularities.open360.gl.ExportRequest
import com.blacksingularities.open360.gl.PanoRenderer
import com.blacksingularities.open360.gl.ViewMode
import com.blacksingularities.open360.gl.ViewState
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun ViewerScreen(settings: Settings, uri: Uri, ownFile: Boolean, onClose: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = remember { ViewState().apply { mode = ViewMode.PERSPECTIVE; grid = false } }
    val renderer = remember { PanoRenderer(null, view) }
    val tracker = remember { OrientationTracker(context) }

    var maxTex by remember { mutableIntStateOf(0) }
    var geometry by remember { mutableStateOf<PanoGeometry?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(ViewMode.PERSPECTIVE) }
    var chrome by remember { mutableStateOf(true) }
    var gyro by remember { mutableStateOf(settings.viewerGyro) }
    var vr by remember { mutableStateOf(false) }
    var spin by remember { mutableStateOf(settings.autoRotate) }
    var touching by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    // Gdy rozglądamy się telefonem: obrót dopasowujący kierunek telefonu do widoku.
    var gyroRef by remember { mutableStateOf<Mat3?>(null) }

    view.mode = mode
    view.vr = vr

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        renderer.onReady = { maxTex = it }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    glView?.onPause()
                    tracker.stop()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    glView?.onResume()
                    if (gyro) tracker.start(settings.source)
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            tracker.stop()
        }
    }

    LaunchedEffect(maxTex) {
        if (maxTex == 0) return@LaunchedEffect
        val res = withContext(Dispatchers.IO) { PanoStore.load(context, uri, min(maxTex, 8192)) }
        loading = false
        if (res == null) {
            failed = true
            return@LaunchedEffect
        }
        geometry = res.second
        view.crop = res.second.cropFractions()
        renderer.setPhoto(res.first)
    }

    LaunchedEffect(gyro) {
        if (gyro) {
            tracker.start(settings.source)
        } else {
            tracker.stop()
            view.sensorView = null
            gyroRef = null
        }
    }

    // Pętla: rozglądanie telefonem i automatyczny obrót.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                if (gyro) {
                    val q = tracker.latest
                    if (q != null) {
                        val r = q.toMat3()
                        val ref = gyroRef ?: run {
                            val f = r.transform(Vec3(0f, 0f, -1f))
                            Mat3.rotZ(f.lon - view.yaw * com.blacksingularities.open360.math.DEG).also { gyroRef = it }
                        }
                        view.sensorView = ref * r
                    }
                } else if (spin && !touching) {
                    if (mode == ViewMode.FLAT) view.flatPanLon += 6f * dt else view.yaw += 6f * dt
                }
            }
        }
    }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    glView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chrome = !chrome },
                        onDoubleTap = {
                            view.halfFov = 45f
                            view.pitch = 0f
                            view.planetZoom = 1f
                            view.flatZoom = 1f
                            view.flatPanLat = 0f
                        },
                    )
                }
                .pointerInput(mode, gyro) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val h = size.height.toFloat()
                            when (mode) {
                                ViewMode.PERSPECTIVE -> {
                                    view.halfFov = (view.halfFov / zoom).coerceIn(12f, 80f)
                                    val degPerPx = 2f * view.halfFov / h
                                    if (gyro) {
                                        gyroRef = gyroRef?.let { Mat3.rotZ(pan.x * degPerPx * com.blacksingularities.open360.math.DEG) * it }
                                    } else {
                                        view.yaw -= pan.x * degPerPx
                                        view.pitch = (view.pitch + pan.y * degPerPx).coerceIn(-90f, 90f)
                                    }
                                }
                                ViewMode.PLANET, ViewMode.TUNNEL -> {
                                    view.planetZoom = (view.planetZoom * zoom).coerceIn(0.25f, 5f)
                                    view.yaw += pan.x * 0.3f
                                }
                                ViewMode.FLAT -> {
                                    view.flatZoom = (view.flatZoom * zoom).coerceIn(1f, 8f)
                                    view.flatPanLon -= pan.x * 0.2f / view.flatZoom
                                    view.flatPanLat = (view.flatPanLat + pan.y * 0.2f / view.flatZoom).coerceIn(-80f, 80f)
                                }
                                ViewMode.LIVE -> Unit
                            }
                            event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                        } while (event.changes.any { it.pressed })
                        touching = false
                    }
                },
        )

        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        if (failed) Text("Nie udało się otworzyć zdjęcia", Modifier.align(Alignment.Center), color = Palette.TextDim)

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, "Wróć") { onClose() }
                Spacer(Modifier.weight(1f))
                RoundButton(Icons.Outlined.Info, "Informacje") { showInfo = true }
                RoundButton(Icons.Outlined.Share, "Udostępnij") {
                    val i = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(i, "Udostępnij panoramę"))
                }
                if (ownFile) RoundButton(Icons.Outlined.DeleteOutline, "Usuń") { confirmDelete = true }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(ViewMode.PERSPECTIVE, ViewMode.PLANET, ViewMode.TUNNEL, ViewMode.FLAT).forEach { m ->
                        Pill(m.label, active = mode == m) {
                            mode = m
                            if (m != ViewMode.PERSPECTIVE) vr = false
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundButton(Icons.Outlined.Explore, "Rozglądanie telefonem", active = gyro, enabled = mode == ViewMode.PERSPECTIVE) { gyro = !gyro }
                    RoundButton(Icons.Outlined.Sync, "Automatyczny obrót", active = spin) { spin = !spin }
                    RoundButton(Icons.Outlined.Vrpano, "Tryb gogli VR", active = vr, enabled = mode == ViewMode.PERSPECTIVE) {
                        vr = !vr
                        if (vr) {
                            gyro = true
                            chrome = false
                        }
                    }
                    RoundButton(Icons.Outlined.Save, "Zapisz bieżący widok jako zdjęcie", enabled = !exporting && geometry != null) {
                        exporting = true
                        val size = if (mode == ViewMode.FLAT) 4096 else 2048
                        renderer.requestViewExport(
                            ExportRequest(ExportRequest.Kind.VIEW, size = size, mode = mode) { bmp, _, _ ->
                                if (bmp == null) {
                                    exporting = false
                                    return@ExportRequest
                                }
                                scope.launch {
                                    val prefix = when (mode) {
                                        ViewMode.PLANET -> "PLANET"
                                        ViewMode.TUNNEL -> "TUNNEL"
                                        ViewMode.FLAT -> "MAP"
                                        else -> "VIEW"
                                    }
                                    val saved = withContext(Dispatchers.IO) { PanoStore.save(context, bmp, settings.jpegQuality, null, prefix) }
                                    bmp.recycle()
                                    exporting = false
                                    Toast.makeText(
                                        context,
                                        if (saved != null) "Zapisano w Zdjęcia › Open360" else "Nie udało się zapisać",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                }
            }
        }

        if (vr && !chrome) {
            // Linia podziału dla gogli.
            Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.25f)))
        }
    }

    if (showInfo) {
        val g = geometry
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK", color = Color.White) } },
            title = { Text("Panorama") },
            text = {
                Column {
                    if (g != null) {
                        InfoLine("Obraz", "${g.w} × ${g.h} px")
                        InfoLine("Pełna sfera", "${g.fullW} × ${g.fullH} px")
                        InfoLine("Zakres poziomy", "${g.w * 360 / g.fullW}°")
                        InfoLine("Zakres pionowy", "${g.h * 180 / g.fullH}°")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(uri.lastPathSegment ?: "", fontSize = 12.sp, color = Palette.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            containerColor = Palette.PanelHigh,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (PanoStore.delete(context, uri)) onDeleted() else Toast.makeText(context, "Nie udało się usunąć", Toast.LENGTH_SHORT).show()
                }) { Text("Usuń", color = Palette.Record, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj", color = Color.White) } },
            title = { Text("Usunąć panoramę?") },
            text = { Text("Plik zniknie z telefonu.") },
            containerColor = Palette.PanelHigh,
        )
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = Palette.TextDim, modifier = Modifier.weight(1f))
        Text(v, style = MonoDigits)
    }
}
