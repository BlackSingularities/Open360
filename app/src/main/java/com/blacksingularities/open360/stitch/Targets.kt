package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.data.CaptureRange
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/** Punkt siatki w trybie „Punkty”. */
class Target(val dir: Vec3, val row: Int) {
    @Volatile var done = false
}

object TargetGrid {
    /**
     * Siatka celów pokrywająca [range] kadrami o połówkowych tangensach [tanX] × [tanY]
     * (pion telefonu) z zakładką [overlap]. Rzędy od horyzontu ku biegunom, w rzędzie – od 0°
     * w prawo, więc kolejne punkty leżą obok siebie.
     */
    fun build(range: CaptureRange, tanX: Float, tanY: Float, overlap: Float): List<Target> {
        val fovH = 2 * atan(tanX) / DEG
        val fovV = 2 * atan(tanY) / DEG
        val hStep = fovH * (1 - overlap)
        val vStep = fovV * (1 - overlap)
        val out = ArrayList<Target>()

        val rows = ArrayList<Float>()
        rows += 0f
        var k = 1
        while (true) {
            val lat = k * vStep
            if (lat > 90f - fovV * 0.3f) break
            val up = lat - fovV / 2 < range.latMax
            val down = -lat + fovV / 2 > range.latMin
            if (!up && !down) break
            if (up) rows += lat
            if (down) rows += -lat
            k++
        }
        if (range.lonSpan < 360f) {
            // Pół panoramy – wąski zakres, rzędy przycięte do zakresu.
            rows.retainAll { it - fovV / 2 < range.latMax && it + fovV / 2 > range.latMin }
        }

        rows.forEachIndexed { ri, lat ->
            val inner = max(0f, abs(lat) - fovV / 2)
            val span = range.lonSpan
            val n = max(1, ceil(span * cos(inner * DEG) / hStep).toInt())
            val step = span / n
            for (i in 0 until n) {
                val lon = if (span >= 360f) {
                    // 0, +1, −1, +2, −2… – zaczynamy przed sobą i rozchodzimy się w obie strony.
                    val j = (i + 1) / 2
                    (if (i % 2 == 1) j else -j) * step
                } else {
                    -span / 2 + step * (i + 0.5f)
                }
                out += Target(Vec3.fromLonLat(lon * DEG, lat * DEG), ri)
            }
        }
        if (range.latMax >= 90f) out += Target(Vec3(0f, 0f, 1f), rows.size)
        if (range.latMin <= -90f) out += Target(Vec3(0f, 0f, -1f), rows.size + 1)
        return out
    }
}
