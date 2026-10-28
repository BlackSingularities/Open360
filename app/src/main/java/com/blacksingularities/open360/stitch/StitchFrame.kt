package com.blacksingularities.open360.stitch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Płaszczyzna obrazu kopiowana do tekstury jednym ruchem: [components] = 2, gdy próbki
 * są przeplecione z drugą płaszczyzną (NV12/NV21) – wtedy liczy się tylko kanał czerwony.
 */
class Plane {
    var w = 0
    var h = 0
    /** Długość wiersza w pikselach tekstury (GL_UNPACK_ROW_LENGTH). */
    var rowPx = 0
    var components = 1
    var buf: ByteBuffer = ByteBuffer.allocateDirect(4)

    /** Kopiuje płaszczyznę z bufora kamery ([rowStride] bajtów na wiersz, [pixelStride] 1 lub 2). */
    fun copyFrom(src: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int) {
        components = if (pixelStride == 2 && rowStride % 2 == 0) 2 else 1
        this.w = w
        this.h = h
        rowPx = rowStride / components
        val need = rowStride * h
        if (buf.capacity() < need) buf = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
        buf.clear()
        val d = src.duplicate()
        d.position(0)
        d.limit(minOf(d.capacity(), need))
        buf.put(d)
        buf.position(0)
        buf.limit(need)
    }
}

/**
 * Klatka przekazywana do wątku GL: płaszczyzny YUV, poza i parametry zszycia. Zamiana na RGB
 * odbywa się dopiero w shaderze.
 */
class StitchFrame {
    val y = Plane()
    val u = Plane()
    val v = Plane()

    val panoToCam = FloatArray(9)
    var tanX = 0.5f
    var tanY = 0.7f
    var gain = 1f
    var vignette = 0f
    /** Prędkość kątowa w układzie kamery (rad/s) i czas odczytu matrycy (s). */
    val omega = FloatArray(3)
    var skew = 0f
    var quality = 1f
    /** Obrót bufora (°, zgodnie ze wskazówkami), który stawia obraz pionowo. */
    var rotation = 0
    var stitch = false
    var box = LonLatBox(0, 1, 0, 1)
    @Volatile var ready = false
}
