package com.blacksingularities.open360.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.blacksingularities.open360.stitch.RenderPlan
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Zdekodowana klatka planu (null – nie udało się odczytać pliku, pomijamy). */
class DecodedFrame(val index: Int, val bitmap: Bitmap?)

/**
 * Ponowne złożenie płótna z zachowanych klatek po globalnym dopasowaniu. Wątek w tle dekoduje
 * kolejne pliki JPEG ([decodeAll]), wątek GL rysuje je na płótnie po jednej na klatkę ekranu
 * (PanoRenderer). Kolejka ma dwa miejsca, więc w pamięci są najwyżej trzy obrazy naraz.
 */
class RepaintJob(val plan: RenderPlan) {
    val total = plan.frames.size
    val decoded = ArrayBlockingQueue<DecodedFrame>(2)
    private val done = CountDownLatch(1)
    @Volatile var drawn = 0
        internal set
    @Volatile var failed = false
        internal set
    @Volatile var cancelled = false

    // Stan po stronie GL.
    internal var started = false

    internal fun finish(ok: Boolean) {
        if (!ok) failed = true
        done.countDown()
    }

    /** Dekodowanie wszystkich klatek po kolei (blokuje; wywoływać poza wątkiem głównym). */
    fun decodeAll() {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        for ((i, f) in plan.frames.withIndex()) {
            if (failed || cancelled) return
            val bmp = runCatching { BitmapFactory.decodeFile(f.kf.file.path, opts) }.getOrNull()
            while (!decoded.offer(DecodedFrame(i, bmp), 200, TimeUnit.MILLISECONDS)) {
                if (failed || cancelled) {
                    bmp?.recycle()
                    return
                }
            }
        }
    }

    /** Czeka na koniec składania; false przy błędzie albo przekroczeniu czasu. */
    fun await(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS) && !failed

    val progress: Float get() = if (total == 0) 1f else drawn.toFloat() / total
}
