package com.blacksingularities.open360.stitch

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** Kolor zachowanych klatek: kopia NV21 z kamery (szybko, w wątku analizy) i JPEG w tle. */
object KeyframeJpeg {
    private const val QUALITY = 93

    /** NV21 (Y, potem przeplecione V/U) w orientacji bufora. */
    fun nv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h + 2 * (w / 2) * (h / 2))
        val yp = image.planes[0]
        val yb = yp.buffer.duplicate()
        for (r in 0 until h) {
            yb.position(r * yp.rowStride)
            yb.get(out, r * w, w)
        }
        val up = image.planes[1]
        val vp = image.planes[2]
        val ub = up.buffer.duplicate()
        val vb = vp.buffer.duplicate()
        val cw = w / 2
        val ch = h / 2
        val rowU = ByteArray(up.rowStride)
        val rowV = ByteArray(vp.rowStride)
        var o = w * h
        for (r in 0 until ch) {
            ub.position(r * up.rowStride)
            ub.get(rowU, 0, minOf(up.rowStride, ub.remaining()))
            vb.position(r * vp.rowStride)
            vb.get(rowV, 0, minOf(vp.rowStride, vb.remaining()))
            val su = up.pixelStride
            val sv = vp.pixelStride
            for (x in 0 until cw) {
                out[o++] = rowV[x * sv]
                out[o++] = rowU[x * su]
            }
        }
        return out
    }

    fun write(nv21: ByteArray, w: Int, h: Int, file: File) {
        val img = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { img.compressToJpeg(Rect(0, 0, w, h), QUALITY, it) }
    }
}
