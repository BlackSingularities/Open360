package com.blacksingularities.open360.gl

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.stitch.CaptureEngine
import com.blacksingularities.open360.stitch.GlCommand
import com.blacksingularities.open360.stitch.StitchFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Sposób oglądania panoramy. */
enum class ViewMode(val label: String) {
    LIVE("Na żywo"),
    PERSPECTIVE("Perspektywa"),
    PLANET("Mała planeta"),
    TUNNEL("Tunel"),
    FLAT("Mapa"),
}

/** Parametry widoku ustawiane z interfejsu (wątek główny), czytane w wątku GL. */
class ViewState {
    @Volatile var mode = ViewMode.LIVE
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    /** Połowa pionowego kąta widzenia (stopnie) w perspektywie. */
    @Volatile var halfFov = 45f
    /** Skala małej planety / tunelu. */
    @Volatile var planetZoom = 1f
    @Volatile var flatZoom = 1f
    @Volatile var flatPanLon = 0f
    @Volatile var flatPanLat = 0f
    @Volatile var vr = false
    /** Gdy ustawione – orientacja z czujników zamiast przeciągania (przeglądarka). */
    @Volatile var sensorView: Mat3? = null
    @Volatile var crop = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile var grid = true
    @Volatile var liveZoomOut = 1.6f
    @Volatile var liveAlpha = 0.9f
    @Volatile var recording = false
}

/** Zamówienie zapisu bieżącego widoku (przeglądarka) jako zdjęcia. */
class ExportRequest(
    val size: Int = 2048,
    val mode: ViewMode = ViewMode.PLANET,
    val onDone: (Bitmap?) -> Unit,
)

/**
 * Renderer GLES 3. Płótno panoramy jest siatką kafelków (do 8K – jeden kafelek, 16K – 4×2
 * kafelki 4096²), zszywane przez mały bufor roboczy zamiast drugiej kopii całego płótna.
 * Podgląd na żywo korzysta z pomniejszonej kopii (4096 px), aktualizowanej po każdym zszyciu.
 * Klatki kamery przychodzą jako YUV i są mipmapowane przed zszyciem (antyaliasing).
 */
class PanoRenderer(
    private val engine: CaptureEngine?,
    val view: ViewState,
    private var canvasWidth: Int = 4096,
) : GLSurfaceView.Renderer {

    private class Tile(val x: Int, val y: Int, val w: Int, val h: Int) {
        var tex = 0
        var fbo = 0
        var undoTex = 0
        var undoFbo = 0
    }

    private val main = Handler(Looper.getMainLooper())

    private var stitchProg = 0
    private var downProg = 0
    private var viewProg = 0
    private var premulProg = 0
    private var exportProg = 0
    private var quad = 0
    private val uniforms = HashMap<Int, HashMap<String, Int>>()

    private var tiles = emptyList<Tile>()
    private var cw = 0
    private var ch = 0
    private var previewTex = 0
    private var previewFbo = 0
    private var previewOwn = false
    private var previewW = 0
    private var previewFactor = 1
    private var scratchTex = 0
    private var scratchFbo = 0
    private var scratchSize = 2048
    private var hasUndo = false

    private val camY = CamTex()
    private val camU = CamTex()
    private val camV = CamTex()
    private var hasCam = false
    private var anisotropy = 0f

    // Przeglądarka: zdjęcie panoramy.
    private var photoTex = 0
    private var photoW = 0
    private val pendingPhoto = ConcurrentLinkedQueue<Bitmap>()

    private var surfaceW = 1
    private var surfaceH = 1
    var maxTexture = 4096
        private set

    private val camPanoToCam = FloatArray(9)
    private var camTanX = 0.5f
    private var camTanY = 0.7f
    private var camRot = 0
    private val tmp9 = FloatArray(9)
    private val viewerQueue = ConcurrentLinkedQueue<ExportRequest>()

    private var export: CanvasExport? = null
    private var stripTex = 0
    private var stripFbo = 0

    /** Wywoływane, gdy kontekst GL powstał od nowa (treść płótna przepadła). */
    var onContextLost: (() -> Unit)? = null
    var onReady: ((maxTexture: Int) -> Unit)? = null
    /** Płótno żądanej wielkości nie zmieściło się w pamięci – użyto mniejszego. */
    var onCanvasFallback: ((width: Int) -> Unit)? = null
    private var created = false

    fun setPhoto(bmp: Bitmap) {
        pendingPhoto += bmp
    }

    fun requestViewExport(req: ExportRequest) {
        viewerQueue += req
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val mt = IntArray(1)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, mt, 0)
        maxTexture = mt[0]
        uniforms.clear()
        stitchProg = GlUtil.program(Shaders.VERTEX, Shaders.STITCH)
        downProg = GlUtil.program(Shaders.VERTEX, Shaders.DOWN)
        viewProg = GlUtil.program(Shaders.VERTEX, Shaders.VIEW)
        premulProg = GlUtil.program(Shaders.VERTEX, Shaders.PREMUL)
        exportProg = GlUtil.program(Shaders.VERTEX, Shaders.EXPORT)
        quad = GlUtil.quad()
        val ext = glGetString(GL_EXTENSIONS) ?: ""
        anisotropy = if (ext.contains("GL_EXT_texture_filter_anisotropic")) {
            val a = FloatArray(1)
            glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, a, 0)
            min(8f, a[0])
        } else {
            0f
        }
        for (t in arrayOf(camY, camU, camV)) {
            t.tex = camTexture()
            t.w = 0; t.h = 0; t.comps = 0
        }
        hasCam = false
        tiles = emptyList()
        export = null
        stripTex = 0; stripFbo = 0
        if (engine != null) allocCanvas()
        photoTex = 0
        if (created) main.post { onContextLost?.invoke() }
        created = true
        val m = maxTexture
        main.post { onReady?.invoke(m) }
    }

    private fun camTexture(): Int {
        val t = GlUtil.mutableTexture()
        if (anisotropy > 1f) glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy)
        return t
    }

    private fun u(prog: Int, name: String): Int =
        uniforms.getOrPut(prog) { HashMap() }.getOrPut(name) { glGetUniformLocation(prog, name) }

    // ── Płótno ──────────────────────────────────────────────────────────────

    private fun allocCanvas() {
        var w = max(1024, canvasWidth)
        while (!tryAlloc(w)) {
            freeCanvas()
            if (w <= 2048) {
                tryAlloc(2048)
                break
            }
            w /= 2
            val fw = w
            main.post { onCanvasFallback?.invoke(fw) }
        }
        engine?.canvasW = cw
        engine?.undoSupported = hasUndo
    }

    private fun tryAlloc(w: Int): Boolean {
        while (glGetError() != GL_NO_ERROR) Unit
        cw = w
        ch = w / 2
        val t = if (w <= maxTexture && w <= 8192) w else min(4096, maxTexture)
        val list = ArrayList<Tile>()
        var y = 0
        while (y < ch) {
            var x = 0
            while (x < cw) {
                val tile = Tile(x, y, min(t, cw - x), min(t, ch - y))
                tile.tex = GlUtil.texture(tile.w, tile.h, GL_LINEAR, GL_REPEAT, clampT = true)
                tile.fbo = GlUtil.fbo(tile.tex)
                list += tile
                x += t
            }
            y += t
        }
        tiles = list
        if (list.size == 1 && cw <= 4096) {
            previewTex = list[0].tex
            previewFbo = list[0].fbo
            previewOwn = false
            previewW = cw
            previewFactor = 1
        } else {
            previewW = min(4096, cw)
            previewFactor = cw / previewW
            previewTex = GlUtil.texture(previewW, previewW / 2, GL_LINEAR, GL_REPEAT, clampT = true)
            previewFbo = GlUtil.fbo(previewTex)
            previewOwn = true
        }
        scratchSize = min(2048, maxTexture)
        scratchTex = GlUtil.texture(scratchSize, scratchSize, GL_NEAREST, GL_CLAMP_TO_EDGE)
        scratchFbo = GlUtil.fbo(scratchTex)
        // Cofanie wymaga kopii całego płótna – przy 16K to za dużo pamięci.
        hasUndo = cw <= 8192
        for (tile in tiles) clearFbo(tile.fbo)
        if (previewOwn) clearFbo(previewFbo)
        glFinish()
        val err = glGetError()
        if (err == GL_OUT_OF_MEMORY) {
            Log.w("Open360", "Płótno ${cw}px nie mieści się w pamięci")
            return false
        }
        return true
    }

    private fun freeCanvas() {
        for (t in tiles) {
            GlUtil.deleteTextures(t.tex, t.undoTex)
            GlUtil.deleteFbos(t.fbo, t.undoFbo)
        }
        tiles = emptyList()
        if (previewOwn) {
            GlUtil.deleteTextures(previewTex)
            GlUtil.deleteFbos(previewFbo)
        }
        previewTex = 0; previewFbo = 0; previewOwn = false
        GlUtil.deleteTextures(scratchTex)
        GlUtil.deleteFbos(scratchFbo)
        scratchTex = 0; scratchFbo = 0
    }

    private fun clearFbo(fbo: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glDisable(GL_SCISSOR_TEST)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /** Zmiana rozmiaru płótna – obowiązuje od następnego czyszczenia. */
    fun resizeCanvas(width: Int) {
        canvasWidth = width
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        engine?.let { e ->
            while (true) {
                val cmd = e.glCommands.poll() ?: break
                runCommand(cmd)
            }
            consumeFrame(e)
            export?.let { stepExport(it) }
        }
        pendingPhoto.poll()?.let { uploadPhoto(it) }
        viewerQueue.poll()?.let { exportView(it) }

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDisable(GL_SCISSOR_TEST)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (view.vr && view.mode != ViewMode.FLAT) {
            val half = surfaceW / 2
            drawView(0, 0, half, surfaceH, flip = false)
            drawView(half, 0, surfaceW - half, surfaceH, flip = false)
        } else {
            drawView(0, 0, surfaceW, surfaceH, flip = false)
        }
    }

    private fun runCommand(cmd: GlCommand) {
        when (cmd) {
            GlCommand.Clear -> {
                if (max(1024, canvasWidth) != cw) {
                    freeCanvas()
                    allocCanvas()
                } else {
                    for (t in tiles) clearFbo(t.fbo)
                    if (previewOwn) clearFbo(previewFbo)
                }
            }
            GlCommand.Snapshot -> if (hasUndo) {
                for (t in tiles) {
                    if (t.undoTex == 0) {
                        t.undoTex = GlUtil.texture(t.w, t.h, GL_NEAREST, GL_CLAMP_TO_EDGE)
                        t.undoFbo = GlUtil.fbo(t.undoTex)
                    }
                    blit(t.fbo, t.undoFbo, 0, 0, 0, 0, t.w, t.h)
                }
            }
            GlCommand.Restore -> if (hasUndo) {
                for (t in tiles) if (t.undoFbo != 0) blit(t.undoFbo, t.fbo, 0, 0, 0, 0, t.w, t.h)
                updatePreview(0, 0, cw, ch)
            }
            is GlCommand.Export -> {
                export?.let { finishExport(it, failed = true) }
                export = cmd.export
            }
        }
    }

    private fun blit(src: Int, dst: Int, sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, src)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dst)
        glBlitFramebuffer(sx, sy, sx + w, sy + h, dx, dy, dx + w, dy + h, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    // ── Kamera i zszywanie ──────────────────────────────────────────────────

    private fun consumeFrame(e: CaptureEngine) {
        val f = e.frame
        if (!f.ready) return
        try {
            upload(f)
            f.panoToCam.copyInto(camPanoToCam)
            camTanX = f.tanX
            camTanY = f.tanY
            camRot = f.rotation
            hasCam = true
            if (f.stitch && tiles.isNotEmpty()) stitch(f)
        } finally {
            e.frameConsumed()
        }
    }

    private class CamTex(var tex: Int = 0, var w: Int = 0, var h: Int = 0, var comps: Int = 0)

    private fun upload(f: StitchFrame) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        uploadPlane(camY, f.y)
        uploadPlane(camU, f.u)
        uploadPlane(camV, f.v)
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
    }

    private fun uploadPlane(t: CamTex, p: com.blacksingularities.open360.stitch.Plane) {
        glPixelStorei(GL_UNPACK_ROW_LENGTH, p.rowPx)
        p.buf.position(0)
        glBindTexture(GL_TEXTURE_2D, t.tex)
        val internal = if (p.components == 2) GL_RG8 else GL_R8
        val format = if (p.components == 2) GL_RG else GL_RED
        if (p.w != t.w || p.h != t.h || p.components != t.comps) {
            t.w = p.w; t.h = p.h; t.comps = p.components
            glTexImage2D(GL_TEXTURE_2D, 0, internal, p.w, p.h, 0, format, GL_UNSIGNED_BYTE, p.buf)
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, p.w, p.h, format, GL_UNSIGNED_BYTE, p.buf)
        }
    }

    private fun setCamMipmaps(on: Boolean) {
        for (t in arrayOf(camY, camU, camV)) {
            glBindTexture(GL_TEXTURE_2D, t.tex)
            if (on) glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (on) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR)
        }
    }

    private fun stitch(f: StitchFrame) {
        val b = f.box
        val y0 = b.y0.coerceIn(0, ch - 1)
        val hh = (b.y1 - b.y0 + 1).coerceIn(1, ch - y0)
        val rects = ArrayList<IntArray>(2)
        if (b.x0 + b.width <= cw) {
            rects += intArrayOf(b.x0, y0, b.width, hh)
        } else {
            rects += intArrayOf(b.x0, y0, cw - b.x0, hh)
            rects += intArrayOf(0, y0, b.x0 + b.width - cw, hh)
        }
        setCamMipmaps(true)
        val p = stitchProg
        glUseProgram(p)
        GlUtil.bindTex(p, "uY", 1, camY.tex)
        GlUtil.bindTex(p, "uU", 2, camU.tex)
        GlUtil.bindTex(p, "uV", 3, camV.tex)
        glUniformMatrix3fv(u(p, "uPanoToCam"), 1, false, camPanoToCam, 0)
        glUniform2f(u(p, "uCamTan"), camTanX, camTanY)
        glUniform1i(u(p, "uCamRot"), camRot)
        glUniform2f(u(p, "uCanvasSize"), cw.toFloat(), ch.toFloat())
        glUniform1f(u(p, "uGain"), f.gain)
        glUniform1f(u(p, "uVignette"), f.vignette)
        glUniform3f(u(p, "uOmega"), f.omega[0], f.omega[1], f.omega[2])
        glUniform1f(u(p, "uSkew"), f.skew)
        glUniform1f(u(p, "uQuality"), f.quality)
        glDisable(GL_SCISSOR_TEST)
        for (r in rects) {
            for (t in tiles) {
                val ix0 = max(r[0], t.x); val ix1 = min(r[0] + r[2], t.x + t.w)
                val iy0 = max(r[1], t.y); val iy1 = min(r[1] + r[3], t.y + t.h)
                if (ix0 >= ix1 || iy0 >= iy1) continue
                var cy = iy0
                while (cy < iy1) {
                    val chH = min(scratchSize, iy1 - cy)
                    var cx = ix0
                    while (cx < ix1) {
                        val chW = min(scratchSize, ix1 - cx)
                        glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo)
                        glViewport(0, 0, chW, chH)
                        glUseProgram(p)
                        GlUtil.bindTex(p, "uTile", 0, t.tex)
                        glUniform2f(u(p, "uOrigin"), cx.toFloat(), cy.toFloat())
                        glUniform2f(u(p, "uTileOrigin"), t.x.toFloat(), t.y.toFloat())
                        GlUtil.drawQuad(quad)
                        blit(scratchFbo, t.fbo, 0, 0, cx - t.x, cy - t.y, chW, chH)
                        cx += chW
                    }
                    cy += chH
                }
            }
        }
        setCamMipmaps(false)
        for (r in rects) updatePreview(r[0], r[1], r[2], r[3])
    }

    /** Odświeża pomniejszony podgląd w prostokącie płótna (x, y, w, h). */
    private fun updatePreview(x: Int, y: Int, w: Int, h: Int) {
        if (!previewOwn) return
        val f = previewFactor
        val px0 = x / f; val py0 = y / f
        val px1 = (x + w + f - 1) / f; val py1 = (y + h + f - 1) / f
        val p = downProg
        glBindFramebuffer(GL_FRAMEBUFFER, previewFbo)
        glViewport(0, 0, previewW, previewW / 2)
        glUseProgram(p)
        glUniform1i(u(p, "uFactor"), f)
        glEnable(GL_SCISSOR_TEST)
        for (t in tiles) {
            val sx0 = max(px0, t.x / f); val sx1 = min(px1, (t.x + t.w) / f)
            val sy0 = max(py0, t.y / f); val sy1 = min(py1, (t.y + t.h) / f)
            if (sx0 >= sx1 || sy0 >= sy1) continue
            glScissor(sx0, sy0, sx1 - sx0, sy1 - sy0)
            GlUtil.bindTex(p, "uTile", 0, t.tex)
            glUniform2f(u(p, "uTileOrigin"), t.x.toFloat(), t.y.toFloat())
            GlUtil.drawQuad(quad)
        }
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun uploadPhoto(bmp: Bitmap) {
        if (photoTex != 0) GlUtil.deleteTextures(photoTex)
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        photoTex = t[0]
        glBindTexture(GL_TEXTURE_2D, photoTex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        if (anisotropy > 1f) glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        glGenerateMipmap(GL_TEXTURE_2D)
        photoW = bmp.width
    }

    // ── Widok ───────────────────────────────────────────────────────────────

    private fun drawView(x: Int, y: Int, w: Int, h: Int, flip: Boolean, modeOverride: ViewMode? = null) {
        val pano = if (engine != null) previewTex else photoTex
        glViewport(x, y, w, h)
        glUseProgram(viewProg)
        val p = viewProg
        val aspect = w.toFloat() / max(1, h)
        val mode = modeOverride ?: view.mode
        GlUtil.bindTex(p, "uPano", 0, pano)
        GlUtil.bindTex(p, "uY", 1, camY.tex)
        GlUtil.bindTex(p, "uU", 2, camU.tex)
        GlUtil.bindTex(p, "uV", 3, camV.tex)
        glUniform1i(u(p, "uFlipY"), if (flip) 1 else 0)
        glUniform1i(u(p, "uGrid"), if (view.grid || engine == null && pano == 0) 1 else 0)
        val c = view.crop
        glUniform4f(u(p, "uCrop"), c[0], c[1], c[2], c[3])
        val live = engine != null && hasCam
        glUniform1i(u(p, "uHasCam"), if (live) 1 else 0)
        glUniformMatrix3fv(u(p, "uPanoToCam"), 1, false, camPanoToCam, 0)
        glUniform2f(u(p, "uCamTan"), camTanX, camTanY)
        glUniform1i(u(p, "uCamRot"), camRot)
        glUniform1f(u(p, "uLiveAlpha"), view.liveAlpha)
        if (view.recording) {
            glUniform4f(u(p, "uFrameColor"), 1f, 1f, 1f, 1f)
        } else {
            glUniform4f(u(p, "uFrameColor"), 1f, 0.23f, 0.28f, 0.9f)
        }

        val texW = max(1, photoW).toFloat() / max(0.05f, c[2])
        var lod = 0f
        val modeIndex: Int
        var rot = Mat3.I
        var tx: Float
        var ty: Float
        when (mode) {
            ViewMode.LIVE, ViewMode.PERSPECTIVE -> {
                modeIndex = 0
                if (mode == ViewMode.LIVE && engine != null) {
                    rot = engine.viewDevToPano() ?: Mat3.I
                    tx = camTanX * view.liveZoomOut
                    ty = tx / aspect
                } else {
                    rot = view.sensorView ?: Mat3.view(view.yaw * DEG, view.pitch * DEG)
                    ty = tan(view.halfFov * DEG)
                    tx = ty * aspect
                }
                // Poziom mipmapy: ile tekseli przypada na piksel ekranu.
                val radPerPx = 2f * atan(ty) / h
                lod = log2(radPerPx * texW / (2f * PI.toFloat()))
            }
            ViewMode.PLANET, ViewMode.TUNNEL -> {
                modeIndex = if (mode == ViewMode.PLANET) 1 else 2
                rot = Mat3.rotZ(-view.yaw * DEG)
                val s = 1.2f / view.planetZoom
                ty = s
                tx = s * aspect
                lod = log2(2f * s / h * 2f * texW / (2f * PI.toFloat())) - 1f
            }
            ViewMode.FLAT -> {
                modeIndex = 3
                // Cała sfera mieści się w kadrze (2:1), przybliżenie i przesunięcie gestami.
                val fitW = if (aspect > 2f) PI.toFloat() * aspect / 2f else PI.toFloat()
                tx = fitW / view.flatZoom
                ty = tx / aspect
                lod = log2(2f * tx / w * texW / (2f * PI.toFloat()))
            }
        }
        rot.toGl(tmp9)
        glUniformMatrix3fv(u(p, "uScreenToPano"), 1, false, tmp9, 0)
        glUniform1i(u(p, "uMode"), modeIndex)
        glUniform2f(u(p, "uViewTan"), tx, ty)
        glUniform2f(u(p, "uPan"), view.flatPanLon * DEG, view.flatPanLat * DEG)
        glUniform1f(u(p, "uLod"), if (engine != null) 0f else lod.coerceIn(0f, 12f))
        GlUtil.drawQuad(quad)
    }

    private fun log2(v: Float) = if (v <= 0f) 0f else (ln(v) / ln(2f))

    // ── Eksport płótna (pasami) ─────────────────────────────────────────────

    private fun stepExport(job: CanvasExport) {
        if (job.cancelled) {
            finishExport(job, failed = false, notify = false)
            return
        }
        try {
            if (!job.started) startExport(job)
            val deadline = SystemClock.uptimeMillis() + 22
            while (job.nextRow < job.height && SystemClock.uptimeMillis() < deadline) {
                val buf = job.free.poll() ?: break
                val rows = min(job.stripRows, job.height - job.nextRow)
                renderStrip(job, buf, job.nextRow, rows)
                job.nextRow += rows
                job.rendered = job.nextRow.toFloat() / job.height
                val last = job.nextRow >= job.height
                job.filled.put(ExportStrip(buf, rows, last))
                if (last) {
                    finishExport(job, failed = false, notify = false)
                    return
                }
            }
        } catch (t: Throwable) {
            Log.e("Open360", "export", t)
            finishExport(job, failed = true)
        }
    }

    private fun startExport(job: CanvasExport) {
        job.started = true
        // Piramida do wypełniania dziur – z podglądu (dziury są i tak gładkie).
        val fw = max(256, previewW / 2)
        val fh = fw / 2
        job.fillTex = GlUtil.texture(fw, fh, GL_LINEAR_MIPMAP_LINEAR, GL_REPEAT, clampT = true, mipmaps = true)
        job.fillFbo = GlUtil.fbo(job.fillTex)
        glBindFramebuffer(GL_FRAMEBUFFER, job.fillFbo)
        glViewport(0, 0, fw, fh)
        glUseProgram(premulProg)
        GlUtil.bindTex(premulProg, "uCanvas", 0, previewTex)
        GlUtil.drawQuad(quad)
        glBindTexture(GL_TEXTURE_2D, job.fillTex)
        glGenerateMipmap(GL_TEXTURE_2D)
        job.fillLod = log2(max(fw, fh).toFloat()).toInt().toFloat()
        val tw = tiles.maxOf { it.w }
        stripTex = GlUtil.texture(tw, job.stripRows, GL_NEAREST, GL_CLAMP_TO_EDGE)
        stripFbo = GlUtil.fbo(stripTex)
    }

    private fun tileAt(x: Int, y: Int): Tile = tiles.first { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }

    private fun renderStrip(job: CanvasExport, buf: ByteBuffer, row0: Int, rows: Int) {
        val p = exportProg
        val outW = job.width
        glBindFramebuffer(GL_FRAMEBUFFER, stripFbo)
        glUseProgram(p)
        GlUtil.bindTex(p, "uFill", 1, job.fillTex)
        glUniform2f(u(p, "uCanvasSize"), cw.toFloat(), ch.toFloat())
        glUniform1f(u(p, "uMaxLod"), job.fillLod)
        glUniform1f(u(p, "uSharpen"), job.sharpen)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glPixelStorei(GL_PACK_ROW_LENGTH, outW)
        buf.clear()
        var ox = 0
        while (ox < outW) {
            val gx = ((job.crop[0] + ox) % cw + cw) % cw
            var oy = 0
            var pw = 0
            while (oy < rows) {
                val gy = (job.crop[1] + row0 + oy).coerceIn(0, ch - 1)
                val t = tileAt(gx, gy)
                pw = min(outW - ox, t.x + t.w - gx)
                val ph = min(rows - oy, t.y + t.h - gy)
                glViewport(0, 0, pw, ph)
                GlUtil.bindTex(p, "uTile", 0, t.tex)
                glUniform2f(u(p, "uOrigin"), gx.toFloat(), gy.toFloat())
                glUniform2f(u(p, "uTileOrigin"), t.x.toFloat(), t.y.toFloat())
                GlUtil.drawQuad(quad)
                buf.position((oy * outW + ox) * 4)
                glReadPixels(0, 0, pw, ph, GL_RGBA, GL_UNSIGNED_BYTE, buf)
                oy += ph
            }
            ox += pw
        }
        glPixelStorei(GL_PACK_ROW_LENGTH, 0)
        glPixelStorei(GL_PACK_ALIGNMENT, 4)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        buf.position(0)
        buf.limit(outW * rows * 4)
    }

    private fun finishExport(job: CanvasExport, failed: Boolean, notify: Boolean = true) {
        GlUtil.deleteFbos(job.fillFbo, stripFbo)
        GlUtil.deleteTextures(job.fillTex, stripTex)
        job.fillTex = 0; job.fillFbo = 0
        stripTex = 0; stripFbo = 0
        if (export === job) export = null
        if (failed && notify) job.filled.put(ExportStrip(ByteBuffer.allocate(0), 0, last = true, failed = true))
    }

    /** Eksport bieżącego sposobu oglądania (np. mała planeta) do kwadratu/prostokąta. */
    private fun exportView(req: ExportRequest) {
        val bmp = try {
            val w = req.size
            val h = if (req.mode == ViewMode.FLAT) req.size / 2 else req.size
            val tex = GlUtil.texture(w, h, GL_LINEAR, GL_CLAMP_TO_EDGE)
            val fbo = GlUtil.fbo(tex)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            drawView(0, 0, w, h, flip = true, modeOverride = req.mode)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val stripH = 256
            val buf = ByteBuffer.allocateDirect(w * stripH * 4).order(ByteOrder.nativeOrder())
            val ints = IntArray(w * stripH)
            var top = 0
            while (top < h) {
                val sh = min(stripH, h - top)
                buf.clear()
                glReadPixels(0, top, w, sh, GL_RGBA, GL_UNSIGNED_BYTE, buf)
                rgbaToArgb(buf, ints, w * sh)
                out.setPixels(ints, 0, w, 0, top, w, sh)
                top += sh
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            GlUtil.deleteFbos(fbo)
            GlUtil.deleteTextures(tex)
            out
        } catch (t: Throwable) {
            Log.e("Open360", "view export", t)
            null
        }
        main.post { req.onDone(bmp) }
    }

    private fun rgbaToArgb(buf: ByteBuffer, out: IntArray, n: Int) {
        buf.position(0)
        for (i in 0 until n) {
            val r = buf.get().toInt() and 0xFF
            val g = buf.get().toInt() and 0xFF
            val b = buf.get().toInt() and 0xFF
            buf.get()
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private companion object {
        const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF
    }
}
