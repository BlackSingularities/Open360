package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.PI_F
import com.blacksingularities.open360.math.Vec3
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Klatka zachowana do zapisu: poza z chwili zszycia (na żywo), geometria odczytu matrycy,
 * szary obraz do dopasowania cech i pełny kolor w pliku JPEG (orientacja bufora kamery).
 */
class Keyframe(
    val id: Int,
    /** Urządzenie → panorama, tak jak klatka została zszyta na żywo. */
    val pose: Mat3,
    /** Obrót bufora (°), czas odczytu matrycy (s) i prędkość kątowa (rad/s, układ kamery). */
    val rot: Int,
    val skew: Float,
    val omega: Vec3,
    val quality: Float,
    /** Szary obraz pionowy (gamma, 0..255) – do śledzenia punktów między klatkami. */
    val gray: ByteArray,
    val gw: Int,
    val gh: Int,
    /** Kolor: JPEG w orientacji bufora [imgW]×[imgH]. */
    val file: File,
    val imgW: Int,
    val imgH: Int,
) {
    fun grayAt(x: Int, y: Int): Float = (gray[y * gw + x].toInt() and 0xFF) * (1f / 255f)
}

/**
 * Zbiór klatek do globalnego dopasowania i ponownego złożenia panoramy przy zapisie.
 * Nie trzymamy każdej klatki kluczowej: nowa trafia tu, gdy wnosi sporo treści, której
 * wnętrza zachowanych klatek jeszcze nie obejmują (mapa pokrycia co 1°), albo zastępuje
 * wyraźnie gorszą klatkę z prawie tej samej pozy. Kolor zapisywany jest w tle do JPEG.
 */
class KeyframeStore(private val dir: File?) {
    private val list = ArrayList<Keyframe>()
    private var cover = BooleanArray(COVER_W * COVER_H)
    private var snapList: List<Keyframe> = emptyList()
    private var snapCover = BooleanArray(0)
    private var nextId = 0
    private val writer: ExecutorService? = if (dir != null) Executors.newSingleThreadExecutor { r -> Thread(r, "open360-keyframes").apply { isDaemon = true } } else null
    private val pending = AtomicInteger()

    val size: Int get() = synchronized(list) { list.size }
    fun frames(): List<Keyframe> = synchronized(list) { ArrayList(list) }

    enum class Decision { SKIP, ADD, REPLACE }

    /** Indeks klatki do zastąpienia przy [Decision.REPLACE]. */
    var replaceIndex = -1
        private set

    /**
     * Czy klatka w pozie [pose] (geometria [geom], jakość [quality]) powinna zostać zachowana.
     */
    fun decide(pose: Mat3, geom: FrameGeom, quality: Float): Decision = synchronized(list) {
        replaceIndex = -1
        if (list.size >= MAX_FRAMES) return Decision.SKIP
        var fresh = 0
        var total = 0
        forCells(pose, geom, INNER) { c ->
            total++
            if (!cover[c]) fresh++
        }
        if (total == 0) return Decision.SKIP
        if (fresh > total * NEW_FRACTION) return Decision.ADD
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, k) in list.withIndex()) {
            val d = (pose * k.pose.transposed()).log().length()
            if (d < bestD) { bestD = d; best = i }
        }
        if (best >= 0 && bestD < REPLACE_RAD && quality > list[best].quality + 0.2f) {
            replaceIndex = best
            return Decision.REPLACE
        }
        return Decision.SKIP
    }

    /**
     * Dodaje klatkę. [nv21] – kolor w formacie NV21 ([imgW]×[imgH], orientacja bufora), zapisywany
     * w tle przez [encode] (null w testach – wtedy bez pliku).
     */
    fun add(
        decision: Decision,
        pose: Mat3,
        geom: FrameGeom,
        quality: Float,
        grayFrame: GrayFrame,
        nv21: ByteArray?,
        imgW: Int,
        imgH: Int,
        encode: ((ByteArray, Int, Int, File) -> Unit)?,
    ): Keyframe? {
        if (decision == Decision.SKIP) return null
        val bytes = ByteArray(grayFrame.px.size) { (grayFrame.px[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
        val id: Int
        synchronized(list) { id = nextId++ }
        val file = File(dir ?: File("."), "kf_$id.jpg")
        val kf = Keyframe(id, pose, geom.rot, geom.skew, geom.omega, quality, bytes, grayFrame.w, grayFrame.h, file, imgW, imgH)
        synchronized(list) {
            if (decision == Decision.REPLACE && replaceIndex in list.indices) list[replaceIndex] = kf else list += kf
            forCells(pose, geom, INNER) { cover[it] = true }
        }
        if (nv21 != null && encode != null && writer != null && dir != null) {
            pending.incrementAndGet()
            writer.execute {
                try {
                    encode(nv21, imgW, imgH, file)
                } catch (_: Throwable) {
                    file.delete()
                } finally {
                    pending.decrementAndGet()
                }
            }
        }
        return kf
    }

    /** Czeka, aż wszystkie pliki zostaną zapisane. */
    fun awaitWrites(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (pending.get() > 0) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(10)
        }
        return true
    }

    fun snapshot() = synchronized(list) {
        snapList = ArrayList(list)
        snapCover = cover.copyOf()
    }

    fun restore() = synchronized(list) {
        list.clear()
        list.addAll(snapList)
        if (snapCover.size == cover.size) cover = snapCover.copyOf() else cover.fill(false)
    }

    fun clear() {
        synchronized(list) {
            list.clear()
            cover.fill(false)
            snapList = emptyList()
            snapCover = BooleanArray(0)
        }
        val d = dir ?: return
        writer?.execute { d.listFiles()?.forEach { if (it.name.startsWith("kf_")) it.delete() } }
            ?: d.listFiles()?.forEach { if (it.name.startsWith("kf_")) it.delete() }
    }

    fun shutdown() {
        writer?.shutdown()
        writer?.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** Komórki mapy pokrycia (1°) widziane przez środkową część kadru (|u|, |v| < [inner]). */
    private inline fun forCells(pose: Mat3, geom: FrameGeom, inner: Float, f: (Int) -> Unit) {
        val fovX = 2f * kotlin.math.atan(geom.tanX) * 180f / PI_F
        val fovY = 2f * kotlin.math.atan(geom.tanY) * 180f / PI_F
        val nx = ceil(fovX * inner / 0.7f).toInt().coerceAtLeast(4)
        val ny = ceil(fovY * inner / 0.7f).toInt().coerceAtLeast(4)
        val seen = HashSet<Int>(nx * ny)
        for (j in 0..ny) for (i in 0..nx) {
            val x = (i.toFloat() / nx * 2f - 1f) * inner * geom.tanX
            val y = (j.toFloat() / ny * 2f - 1f) * inner * geom.tanY
            val d = pose.transform(geom.rayAt(x, y)).normalized()
            val cx = floor((d.lon / (2f * PI_F) + 0.5f) * COVER_W).toInt().coerceIn(0, COVER_W - 1)
            val cy = floor((0.5f - d.lat / PI_F) * COVER_H).toInt().coerceIn(0, COVER_H - 1)
            val c = cy * COVER_W + cx
            if (seen.add(c)) f(c)
        }
    }

    companion object {
        const val COVER_W = 360
        const val COVER_H = 180
        /** Część kadru uznawana za „pokrytą” przez zachowaną klatkę (reszta to zakładka). */
        const val INNER = 0.8f
        /**
         * Klatka z co najmniej takim udziałem nowych komórek jest zachowywana – kolejne zachowane
         * klatki nachodzą na siebie w ~75%, a wąskie skrawki, których żadna nie objęła, zostają
         * przy zapisie z płótna na żywo.
         */
        const val NEW_FRACTION = 0.3f
        const val REPLACE_RAD = 3f * (Math.PI.toFloat() / 180f)
        const val MAX_FRAMES = 220
    }
}
