package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.math.FastMath
import com.blacksingularities.open360.math.Mat3
import kotlin.math.sqrt

/**
 * Ostrość klatki względem tego, co już zszyte w tym samym miejscu. Porównujemy średni
 * gradient (na radian) w obu obrazach osobno wzdłuż poziomej i pionowej osi kadru – rozmycie
 * ruchu działa w jednym kierunku, więc liczy się gorsza oś – znormalizowany kontrastem,
 * więc różnice ekspozycji się znoszą. Rozmycie (ruch, nieostrość, drgania w trakcie
 * naświetlania) obniża energię gradientu dużo silniej niż kontrast – wynik ≈ 1 dla równie
 * ostrej klatki, poniżej ≈ 0,5 dla wyraźnie rozmazanej. Łapie też rozmycie, którego żyroskop nie widzi.
 */
object Sharpness {
    /** Iloraz ostrości klatka / panorama albo −1, gdy za mało wspólnej treści. */
    fun relative(canvas: GrayCanvas, frame: GrayFrame, geom: FrameGeom, pose: Mat3, step: Int = 5): Float {
        val w = frame.w; val h = frame.h; val p = frame.px
        val m = pose.m
        val inv = 1f / canvas.radPerPx
        // Kąt piksela klatki w środku kadru.
        val framePxRad = 2f * geom.tanX / w
        var n = 0
        var sfx = 0.0; var sfy = 0.0; var scx = 0.0; var scy = 0.0
        // Osie kadru w panoramie (poziom i pion obrazu).
        val ax = pose.transform(com.blacksingularities.open360.math.Vec3(1f, 0f, 0f))
        val ay = pose.transform(com.blacksingularities.open360.math.Vec3(0f, 1f, 0f))
        var sf = 0.0; var sff = 0.0; var sc = 0.0; var scc = 0.0
        var y = 2
        while (y < h - 2) {
            var x = 2
            while (x < w - 2) {
                val i = y * w + x
                val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
                val r = geom.rayAt((2f * u - 1f) * geom.tanX, (1f - 2f * v) * geom.tanY)
                val dx = m[0] * r.x + m[1] * r.y + m[2] * r.z
                val dy = m[3] * r.x + m[4] * r.y + m[5] * r.z
                val dz = m[6] * r.x + m[7] * r.y + m[8] * r.z
                val cl = sqrt(dx * dx + dy * dy)
                if (cl > 0.15f) {
                    val fx = canvas.px(FastMath.atan2(dx, dy)); val fy = canvas.py(FastMath.asin(dz))
                    val c = canvas.sampleAt(fx, fy)
                    val cxp = canvas.sampleAt(fx + 1f, fy); val cxm = canvas.sampleAt(fx - 1f, fy)
                    val cyp = canvas.sampleAt(fx, fy + 1f); val cym = canvas.sampleAt(fx, fy - 1f)
                    if (!c.isNaN() && !cxp.isNaN() && !cxm.isNaN() && !cyp.isNaN() && !cym.isNaN()) {
                        val gx = (p[i + 1] - p[i - 1]) * 0.5f / framePxRad
                        val gy = (p[i + w] - p[i - w]) * 0.5f / framePxRad
                        // Gradient panoramy jako wektor 3D (równoleżnik E, południk N).
                        val gE = (cxp - cxm) * 0.5f * inv / cl
                        val gN = -(cyp - cym) * 0.5f * inv
                        val sinL = dx / cl; val cosL = dy / cl
                        val g3x = gE * cosL - gN * dz * sinL
                        val g3y = -gE * sinL - gN * dz * cosL
                        val g3z = gN * cl
                        // Energia gradientu – silniej karze utratę ostrych krawędzi niż średnia.
                        val cxd = g3x * ax.x + g3y * ax.y + g3z * ax.z
                        val cyd = g3x * ay.x + g3y * ay.y + g3z * ay.z
                        sfx += gx * gx; sfy += gy * gy
                        scx += cxd * cxd; scy += cyd * cyd
                        val f = p[i].toDouble()
                        sf += f; sff += f * f; sc += c; scc += c.toDouble() * c
                        n++
                    }
                }
                x += step
            }
            y += step
        }
        if (n < 100 || scx < 1e-6 || scy < 1e-6) return -1f
        val vf = sff / n - (sf / n) * (sf / n)
        val vc = scc / n - (sc / n) * (sc / n)
        if (vf < 1e-6 || vc < 1e-6) return -1f
        return (minOf(sfx / scx, sfy / scy) * (vc / vf)).toFloat()
    }
}
