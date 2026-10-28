package com.blacksingularities.open360.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/** Pas wierszy RGBA gotowy do zakodowania (pełna szerokość eksportu). */
class ExportStrip(val buf: ByteBuffer, val rows: Int, val last: Boolean, val failed: Boolean = false)

/**
 * Eksport płótna pasami: wątek GL renderuje kolejne pasy (po kilka na klatkę ekranu, żeby nie
 * blokować interfejsu), a koder JPEG na innym wątku je odbiera. Bufory krążą w puli, więc
 * nawet 16K zajmuje kilkadziesiąt MB zamiast pół gigabajta.
 *
 * [crop] – (x, y, w, h) w pikselach płótna; x może zawijać się przez szew 180°.
 */
class CanvasExport(val crop: IntArray, val sharpen: Float) {
    val width = crop[2]
    val height = crop[3]
    val stripRows = 128
    val free = ArrayBlockingQueue<ByteBuffer>(3)
    val filled = LinkedBlockingQueue<ExportStrip>()
    @Volatile var cancelled = false

    /** Postęp renderowania (0..1) – czytany z wątku głównego. */
    @Volatile var rendered = 0f

    // Stan po stronie GL.
    internal var nextRow = 0
    internal var fillTex = 0
    internal var fillFbo = 0
    internal var fillLod = 0f
    internal var started = false

    init {
        repeat(3) { free += ByteBuffer.allocateDirect(width * stripRows * 4).order(ByteOrder.nativeOrder()) }
    }
}
