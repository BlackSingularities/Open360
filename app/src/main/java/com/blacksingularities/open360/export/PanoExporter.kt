package com.blacksingularities.open360.export

import com.blacksingularities.open360.gl.CanvasExport
import com.blacksingularities.open360.jpeg.JpegWriter
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Zapis płótna: wybór kadru i kodowanie pasów z renderera do JPEG. */
object PanoExporter {
    const val QUALITY = 94

    /**
     * Kadr zapisu: pełna sfera 2:1, gdy uchwycono pełne 360° i prawie cały pion (wtedy
     * brakujące bieguny wypełnia rozmyte przedłużenie i plik pasuje do każdej przeglądarki);
     * inaczej tylko uchwycona treść – metadane GPano opisują jej położenie w sferze.
     */
    fun crop(bounds: IntArray, cw: Int, ch: Int): IntArray {
        val fullRing = bounds[2] >= cw
        if (fullRing && bounds[3] >= ch * 0.83f) return intArrayOf(0, 0, cw, ch)
        val w = bounds[2].coerceIn(16, cw)
        val h = bounds[3].coerceIn(16, ch)
        return intArrayOf(if (fullRing) 0 else bounds[0], bounds[1].coerceIn(0, ch - h), w, h)
    }

    /**
     * Odbiera pasy z [job] i koduje je do [out]. Rzuca wyjątek przy błędzie renderera albo
     * gdy pasy przestaną napływać (np. utrata kontekstu grafiki).
     */
    fun encode(job: CanvasExport, out: OutputStream, xmp: ByteArray?, progress: (Float) -> Unit) {
        val w = job.width
        JpegWriter(out, w, job.height, QUALITY, xmp).use { jpeg ->
            val rows = ByteArray(w * job.stripRows * 4)
            while (true) {
                val s = job.filled.poll(15, TimeUnit.SECONDS) ?: throw IOException("renderer przestał odpowiadać")
                if (s.failed) throw IOException("błąd renderowania")
                val n = s.rows * w * 4
                s.buf.position(0)
                s.buf.get(rows, 0, n)
                job.free.put(s.buf)
                jpeg.writeRows(rows, w * 4, s.rows)
                progress(jpeg.progress)
                if (s.last) break
            }
            jpeg.finish()
        }
    }
}
