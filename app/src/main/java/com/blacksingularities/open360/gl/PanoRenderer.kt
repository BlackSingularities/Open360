package com.blacksingularities.open360.gl

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.blacksingularities.open360.data.BlendMode
import com.blacksingularities.open360.data.HoleFill
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.stitch.CaptureEngine
import com.blacksingularities.open360.stitch.GlCommand
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

/** Zamówienie eksportu z wątku GL. */
class ExportRequest(
    val kind: Kind,
    val fill: HoleFill = HoleFill.BLUR,
    /** Kadr płótna (x, y, w, h) w pikselach albo null = całość. */
    val crop: IntArray? = null,
    val size: Int = 2048,
    val mode: ViewMode = ViewMode.PLANET,
    val onDone: (Bitmap?, Int, Int) -> Unit,
) {
    enum class Kind { CANVAS, VIEW }
}

/**
 * Renderer GLES 3: płótno panoramy (dwie tekstury – zszywanie A→B i kopia zakresu z powrotem),
 * migawka do cofania, tekstura klatki kamery i widoki. W przeglądarce zamiast płótna – zdjęcie.
 */
class PanoRenderer(
    private val engine: CaptureEngine?,
    val view: ViewState,
    private var canvasWidth: Int = 4096,
) : GLSurfaceView.Renderer {

    private val main = Handler(Looper.getMainLooper())

    private var stitchProg = 0
    private var viewProg = 0
    private var premulProg = 0
    private var exportProg = 0
    private var quad = 0

    private var texA = 0
    private var texB = 0
    private var texU = 0
    private var fboA = 0
    private var fboB = 0
    private var fboU = 0
    private var camTex = 0
    private var camW = 0
    private var camH = 0
    private var hasCam = false
    private var cw = 0
    private var ch = 0

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

    /** Wywoływane, gdy kontekst GL powstał od nowa (treść płótna przepadła). */
    var onContextLost: (() -> Unit)? = null
    var onReady: ((maxTexture: Int) -> Unit)? = null
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
        stitchProg = GlUtil.program(Shaders.VERTEX, Shaders.STITCH)
        viewProg = GlUtil.program(Shaders.VERTEX, Shaders.VIEW)
        premulProg = GlUtil.program(Shaders.VERTEX, Shaders.PREMUL)
        exportProg = GlUtil.program(Shaders.VERTEX, Shaders.EXPORT)
        quad = GlUtil.quad()
        camTex = GlUtil.mutableTexture()
        camW = 0
        camH = 0
        hasCam = false
        if (engine != null) allocCanvas()
        photoTex = 0
        if (created) main.post { onContextLost?.invoke() }
        created = true
        val m = maxTexture
        main.post { onReady?.invoke(m) }
    }

    private fun allocCanvas() {
        val w = min(canvasWidth, maxTexture).coerceAtLeast(1024)
        cw = w
        ch = w / 2
        engine?.canvasW = cw
        texA = GlUtil.texture(cw, ch, GL_LINEAR, GL_REPEAT, clampT = true)
        texB = GlUtil.texture(cw, ch, GL_LINEAR, GL_REPEAT, clampT = true)
        fboA = GlUtil.fbo(texA)
        fboB = GlUtil.fbo(texB)
        clearFbo(fboA)
        clearFbo(fboB)
        texU = 0
        fboU = 0
    }

    private fun clearFbo(fbo: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glDisable(GL_SCISSOR_TEST)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /** Zmiana rozmiaru płótna – tylko na pustym płótnie (po wyczyszczeniu). */
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
                if (min(canvasWidth, maxTexture) != cw) {
                    GlUtil.deleteTextures(texA, texB, texU)
                    GlUtil.deleteFbos(fboA, fboB, fboU)
                    allocCanvas()
                } else {
                    clearFbo(fboA)
                    clearFbo(fboB)
                }
            }
            GlCommand.Snapshot -> {
                // Migawka do cofania (przy największym płótnie pomijana – za dużo pamięci).
                if (cw > 6144) return
                if (texU == 0) {
                    texU = GlUtil.texture(cw, ch, GL_LINEAR, GL_REPEAT, clampT = true)
                    fboU = GlUtil.fbo(texU)
                }
                blit(fboA, fboU, 0, 0, cw, ch)
            }
            GlCommand.Restore -> if (fboU != 0) {
                blit(fboU, fboA, 0, 0, cw, ch)
            }
            is GlCommand.Export -> exportCanvas(cmd.request)
        }
    }

    private fun blit(src: Int, dst: Int, x: Int, y: Int, w: Int, h: Int) {
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, src)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dst)
        glBlitFramebuffer(x, y, x + w, y + h, x, y, x + w, y + h, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun consumeFrame(e: CaptureEngine) {
        val f = e.frame
        if (!f.ready) return
        try {
            glBindTexture(GL_TEXTURE_2D, camTex)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
            if (f.w != camW || f.h != camH) {
                camW = f.w
                camH = f.h
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, camW, camH, 0, GL_RGBA, GL_UNSIGNED_BYTE, f.buf)
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, camW, camH, GL_RGBA, GL_UNSIGNED_BYTE, f.buf)
            }
            f.panoToCam.copyInto(camPanoToCam)
            camTanX = f.tanX
            camTanY = f.tanY
            camRot = f.rotation
            hasCam = true
            if (f.stitch && cw > 0) stitch(f.blend, f.feather, f.gain, f.vignette, f.box.x0, f.box.width, f.box.y0, f.box.y1)
        } finally {
            e.frameConsumed()
        }
    }

    private fun stitch(blend: BlendMode, feather: Float, gain: Float, vignette: Float, bx0: Int, bw: Int, by0: Int, by1: Int) {
        // Zakres z płótna niskiej rozdzielczości przeliczony na bieżący rozmiar.
        val rects = ArrayList<IntArray>(2)
        val y0 = by0.coerceIn(0, ch - 1)
        val hh = (by1 - by0 + 1).coerceIn(1, ch - y0)
        if (bx0 + bw <= cw) {
            rects += intArrayOf(bx0, y0, bw, hh)
        } else {
            rects += intArrayOf(bx0, y0, cw - bx0, hh)
            rects += intArrayOf(0, y0, bx0 + bw - cw, hh)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fboB)
        glViewport(0, 0, cw, ch)
        glUseProgram(stitchProg)
        GlUtil.bindTex(stitchProg, "uCanvas", 0, texA)
        GlUtil.bindTex(stitchProg, "uCam", 1, camTex)
        glUniformMatrix3fv(glGetUniformLocation(stitchProg, "uPanoToCam"), 1, false, camPanoToCam, 0)
        glUniform2f(glGetUniformLocation(stitchProg, "uCamTan"), camTanX, camTanY)
        glUniform1i(glGetUniformLocation(stitchProg, "uCamRot"), camRot)
        glUniform2f(glGetUniformLocation(stitchProg, "uCanvasSize"), cw.toFloat(), ch.toFloat())
        glUniform1i(glGetUniformLocation(stitchProg, "uBlend"), blendIndex(blend))
        glUniform1f(glGetUniformLocation(stitchProg, "uFeather"), feather)
        glUniform1f(glGetUniformLocation(stitchProg, "uGain"), gain)
        glUniform1f(glGetUniformLocation(stitchProg, "uVignette"), vignette)
        glEnable(GL_SCISSOR_TEST)
        for (r in rects) {
            glScissor(r[0], r[1], r[2], r[3])
            GlUtil.drawQuad(quad)
        }
        glDisable(GL_SCISSOR_TEST)
        for (r in rects) blit(fboB, fboA, r[0], r[1], r[2], r[3])
    }

    private fun blendIndex(b: BlendMode) = when (b) {
        BlendMode.BEST -> 0
        BlendMode.FEATHER -> 1
        BlendMode.FILL -> 2
        BlendMode.OVERWRITE -> 3
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
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        glGenerateMipmap(GL_TEXTURE_2D)
        photoW = bmp.width
    }

    // ── Widok ───────────────────────────────────────────────────────────────

    private fun drawView(x: Int, y: Int, w: Int, h: Int, flip: Boolean, modeOverride: ViewMode? = null, tex: Int = -1) {
        val pano = if (tex >= 0) tex else if (engine != null) texA else photoTex
        glViewport(x, y, w, h)
        glUseProgram(viewProg)
        val p = viewProg
        val aspect = w.toFloat() / max(1, h)
        val mode = modeOverride ?: view.mode
        GlUtil.bindTex(p, "uPano", 0, pano)
        GlUtil.bindTex(p, "uCam", 1, camTex)
        glUniform1i(glGetUniformLocation(p, "uFlipY"), if (flip) 1 else 0)
        glUniform1i(glGetUniformLocation(p, "uGrid"), if (view.grid || engine == null && pano == 0) 1 else 0)
        val c = view.crop
        glUniform4f(glGetUniformLocation(p, "uCrop"), c[0], c[1], c[2], c[3])
        val live = engine != null && hasCam
        glUniform1i(glGetUniformLocation(p, "uHasCam"), if (live) 1 else 0)
        glUniformMatrix3fv(glGetUniformLocation(p, "uPanoToCam"), 1, false, camPanoToCam, 0)
        glUniform2f(glGetUniformLocation(p, "uCamTan"), camTanX, camTanY)
        glUniform1i(glGetUniformLocation(p, "uCamRot"), camRot)
        glUniform1f(glGetUniformLocation(p, "uLiveAlpha"), view.liveAlpha)
        if (view.recording) {
            glUniform4f(glGetUniformLocation(p, "uFrameColor"), 1f, 1f, 1f, 1f)
        } else {
            glUniform4f(glGetUniformLocation(p, "uFrameColor"), 1f, 0.23f, 0.28f, 0.9f)
        }

        val texW = if (engine != null) cw.toFloat() else max(1, photoW).toFloat() / max(0.05f, c[2])
        var lod = 0f
        val modeIndex: Int
        var rot = Mat3.I
        var tx = 1f
        var ty = 1f
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
        glUniformMatrix3fv(glGetUniformLocation(p, "uScreenToPano"), 1, false, tmp9, 0)
        glUniform1i(glGetUniformLocation(p, "uMode"), modeIndex)
        glUniform2f(glGetUniformLocation(p, "uViewTan"), tx, ty)
        glUniform2f(glGetUniformLocation(p, "uPan"), view.flatPanLon * DEG, view.flatPanLat * DEG)
        glUniform1f(glGetUniformLocation(p, "uLod"), if (engine != null) 0f else lod.coerceIn(0f, 12f))
        GlUtil.drawQuad(quad)
    }

    private fun log2(v: Float) = if (v <= 0f) 0f else (ln(v) / ln(2f))

    // ── Eksport ─────────────────────────────────────────────────────────────

    private fun exportCanvas(req: ExportRequest) {
        val bmp = try {
            renderCanvasExport(req)
        } catch (t: Throwable) {
            Log.e("Open360", "export", t)
            null
        }
        val w = cw
        val h = ch
        main.post { req.onDone(bmp, w, h) }
    }

    private fun renderCanvasExport(req: ExportRequest): Bitmap {
        val crop = req.crop ?: intArrayOf(0, 0, cw, ch)
        val ox = crop[0]; val oy = crop[1]
        val ow = crop[2].coerceIn(1, cw); val oh = crop[3].coerceIn(1, ch - oy)

        // Piramida do rozmytego wypełniania dziur (połowa rozdzielczości, mipmapy).
        val fw = cw / 2; val fh = ch / 2
        val fillTex = GlUtil.texture(fw, fh, GL_LINEAR_MIPMAP_LINEAR, GL_REPEAT, clampT = true, mipmaps = true)
        val fillFbo = GlUtil.fbo(fillTex)
        if (req.fill == HoleFill.BLUR) {
            glBindFramebuffer(GL_FRAMEBUFFER, fillFbo)
            glViewport(0, 0, fw, fh)
            glUseProgram(premulProg)
            GlUtil.bindTex(premulProg, "uCanvas", 0, texA)
            GlUtil.drawQuad(quad)
            glBindTexture(GL_TEXTURE_2D, fillTex)
            glGenerateMipmap(GL_TEXTURE_2D)
        }

        val stripH = 256
        val stripTex = GlUtil.texture(ow, stripH, GL_NEAREST, GL_CLAMP_TO_EDGE)
        val stripFbo = GlUtil.fbo(stripTex)
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val buf = ByteBuffer.allocateDirect(ow * stripH * 4).order(ByteOrder.nativeOrder())
        val ints = IntArray(ow * stripH)
        val maxLod = log2(max(fw, fh).toFloat()).toInt().toFloat()
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, stripFbo)
            glUseProgram(exportProg)
            GlUtil.bindTex(exportProg, "uCanvas", 0, texA)
            GlUtil.bindTex(exportProg, "uFill", 1, fillTex)
            glUniform1i(glGetUniformLocation(exportProg, "uFillMode"), if (req.fill == HoleFill.BLUR) 0 else 1)
            val fc = if (req.fill == HoleFill.GRAY) 0.5f else 0f
            glUniform3f(glGetUniformLocation(exportProg, "uFillColor"), fc, fc, fc)
            glUniform2f(glGetUniformLocation(exportProg, "uFullSize"), cw.toFloat(), ch.toFloat())
            glUniform1f(glGetUniformLocation(exportProg, "uMaxLod"), maxLod)
            var top = 0
            while (top < oh) {
                val sh = min(stripH, oh - top)
                glViewport(0, 0, ow, sh)
                glUniform2f(glGetUniformLocation(exportProg, "uOffset"), ox.toFloat(), (oy + top).toFloat())
                GlUtil.drawQuad(quad)
                buf.clear()
                glPixelStorei(GL_PACK_ALIGNMENT, 4)
                glReadPixels(0, 0, ow, sh, GL_RGBA, GL_UNSIGNED_BYTE, buf)
                rgbaToArgb(buf, ints, ow * sh)
                bmp.setPixels(ints, 0, ow, 0, top, ow, sh)
                top += sh
            }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            GlUtil.deleteFbos(stripFbo, fillFbo)
            GlUtil.deleteTextures(stripTex, fillTex)
        }
        return bmp
    }

    /** Eksport bieżącego sposobu oglądania (np. mała planeta) do kwadratu/prostokąta. */
    private fun exportView(req: ExportRequest) {
        val bmp = try {
            val w = req.size
            val h = if (req.mode == ViewMode.FLAT) req.size / 2 else req.size
            val tex = GlUtil.texture(w, h, GL_LINEAR, GL_CLAMP_TO_EDGE)
            val fbo = GlUtil.fbo(tex)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            val savedVr = view.vr
            drawView(0, 0, w, h, flip = true, modeOverride = req.mode)
            view.vr = savedVr
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
        main.post { req.onDone(bmp, bmp?.width ?: 0, bmp?.height ?: 0) }
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
}
