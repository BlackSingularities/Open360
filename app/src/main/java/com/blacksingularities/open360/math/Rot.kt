package com.blacksingularities.open360.math

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Układ panoramy (jak w OpenPano): X – wschód, Y – „przód” (długość 0°), Z – zenit.
 * Długość = atan2(x, y), szerokość = asin(z). Macierze 3×3 trzymane wierszami.
 */
class Mat3(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {

    operator fun get(r: Int, c: Int) = m[r * 3 + c]

    operator fun times(o: Mat3): Mat3 {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        }
        return Mat3(r)
    }

    fun transform(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    fun transposed() = Mat3(floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    /** Transpozycja razy wektor (dla obrotu – obrót odwrotny) bez tworzenia nowej macierzy. */
    fun transformT(v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )

    /** Wektor obrotu (oś × kąt, rad) – odwrotność [exp]. */
    fun log(): Vec3 {
        val tr = m[0] + m[4] + m[8]
        val c = ((tr - 1f) * 0.5f).coerceIn(-1f, 1f)
        val a = acos(c)
        val v = Vec3(m[7] - m[5], m[2] - m[6], m[3] - m[1])
        if (a < 1e-4f) return v * 0.5f
        val s = sin(a)
        if (s < 1e-4f) {
            // Okolice 180° – oś z przekątnej (w praktyce nie występuje przy korektach).
            val x = sqrt(((m[0] + 1f) * 0.5f).coerceAtLeast(0f))
            val y = sqrt(((m[4] + 1f) * 0.5f).coerceAtLeast(0f))
            val z = sqrt(((m[8] + 1f) * 0.5f).coerceAtLeast(0f))
            return Vec3(x, y, z).normalized() * a
        }
        return v * (a / (2f * s))
    }

    /** Ortonormalizacja (Gram–Schmidt na wierszach) – usuwa błędy zaokrągleń po wielu złożeniach. */
    fun orthonormalized(): Mat3 {
        val r0 = Vec3(m[0], m[1], m[2]).normalized()
        var r1 = Vec3(m[3], m[4], m[5])
        r1 = (r1 - r0 * r0.dot(r1)).normalized()
        val r2 = r0.cross(r1)
        return Mat3(floatArrayOf(r0.x, r0.y, r0.z, r1.x, r1.y, r1.z, r2.x, r2.y, r2.z))
    }

    /** Kolejność kolumnowa dla glUniformMatrix3fv (transpose = false). */
    fun toGl(out: FloatArray = FloatArray(9)): FloatArray {
        out[0] = m[0]; out[1] = m[3]; out[2] = m[6]
        out[3] = m[1]; out[4] = m[4]; out[5] = m[7]
        out[6] = m[2]; out[7] = m[5]; out[8] = m[8]
        return out
    }

    companion object {
        val I get() = Mat3()

        fun rotX(a: Float): Mat3 {
            val c = cos(a); val s = sin(a)
            return Mat3(floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c))
        }

        fun rotY(a: Float): Mat3 {
            val c = cos(a); val s = sin(a)
            return Mat3(floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c))
        }

        fun rotZ(a: Float): Mat3 {
            val c = cos(a); val s = sin(a)
            return Mat3(floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f))
        }

        /** Obrót o kąt [a] wokół jednostkowej osi [k] (wzór Rodriguesa). */
        fun axisAngle(k: Vec3, a: Float): Mat3 {
            val c = cos(a); val s = sin(a); val t = 1 - c
            return Mat3(
                floatArrayOf(
                    t * k.x * k.x + c, t * k.x * k.y - s * k.z, t * k.x * k.z + s * k.y,
                    t * k.x * k.y + s * k.z, t * k.y * k.y + c, t * k.y * k.z - s * k.x,
                    t * k.x * k.z - s * k.y, t * k.y * k.z + s * k.x, t * k.z * k.z + c,
                ),
            )
        }

        /** Obrót o wektor [v] (oś × kąt). */
        fun exp(v: Vec3): Mat3 {
            val a = v.length()
            if (a < 1e-9f) return I
            return axisAngle(v * (1f / a), a)
        }

        /**
         * Kamera podglądu: patrzy wzdłuż −Z, góra = +Y. Przy yaw = pitch = 0 patrzy na długość 0°
         * (oś +Y panoramy), a jej góra to zenit.
         */
        private val VIEW_BASE = Mat3(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f))

        /** Kierunek kamery (układ kamery) → kierunek w panoramie. */
        fun view(yaw: Float, pitch: Float, roll: Float = 0f): Mat3 =
            rotZ(-yaw) * rotX(pitch) * VIEW_BASE * rotZ(roll)
    }
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) this else Vec3(x / l, y / l, z / l)
    }

    val lon get() = atan2(x, y)
    val lat get() = kotlin.math.asin(z.coerceIn(-1f, 1f))

    companion object {
        fun fromLonLat(lon: Float, lat: Float) = Vec3(cos(lat) * sin(lon), cos(lat) * cos(lon), sin(lat))
    }
}

/** Kąt między dwoma kierunkami jednostkowymi (rad). */
fun angleBetween(a: Vec3, b: Vec3): Float = acos(a.dot(b).coerceIn(-1f, 1f))

/** Kwaternion (w, x, y, z) – orientacja urządzenia z czujnika wektora obrotu. */
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    fun normalized(): Quat {
        val l = sqrt(w * w + x * x + y * y + z * z)
        return Quat(w / l, x / l, y / l, z / l)
    }

    /** Macierz obrotu: współrzędne urządzenia → świat. */
    fun toMat3(): Mat3 {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return Mat3(
            floatArrayOf(
                1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
                2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
                2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
            ),
        )
    }

    fun dot(o: Quat) = w * o.w + x * o.x + y * o.y + z * o.z

    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conj() = Quat(w, -x, -y, -z)

    /** Wektor obrotu (oś × kąt) tego kwaternionu, krótszą drogą. */
    fun toRotVec(): Vec3 {
        val q = if (w < 0) Quat(-w, -x, -y, -z) else this
        val s = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        if (s < 1e-9f) return Vec3(2f * q.x, 2f * q.y, 2f * q.z)
        val a = 2f * atan2(s, q.w)
        return Vec3(q.x / s * a, q.y / s * a, q.z / s * a)
    }

    /** Kąt obrotu między orientacjami (rad). */
    fun angleTo(o: Quat): Float = 2f * acos(kotlin.math.abs(dot(o)).coerceAtMost(1f))

    companion object {
        /** Kwaternion z wektora obrotu. */
        fun fromRotVec(v: Vec3): Quat {
            val a = v.length()
            if (a < 1e-9f) return Quat(1f, v.x * 0.5f, v.y * 0.5f, v.z * 0.5f).normalized()
            val s = sin(a / 2) / a
            return Quat(cos(a / 2), v.x * s, v.y * s, v.z * s)
        }

        fun slerp(a: Quat, b0: Quat, t: Float): Quat {
            var b = b0
            var d = a.dot(b)
            if (d < 0) {
                b = Quat(-b.w, -b.x, -b.y, -b.z)
                d = -d
            }
            if (d > 0.9995f) {
                return Quat(a.w + t * (b.w - a.w), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z)).normalized()
            }
            val th = acos(d)
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return Quat(wa * a.w + wb * b.w, wa * a.x + wb * b.x, wa * a.y + wb * b.y, wa * a.z + wb * b.z)
        }
    }
}

const val DEG = (Math.PI / 180.0).toFloat()
const val PI_F = Math.PI.toFloat()

/**
 * Szybkie atan2/asin (wielomian minimaksowy 11. stopnia, błąd < 2·10⁻⁵ rad ≈ 0,001°) –
 * próbkowanie panoramy robi to setki tysięcy razy na klatkę.
 */
object FastMath {
    fun atan2(y: Float, x: Float): Float {
        val ax = kotlin.math.abs(x)
        val ay = kotlin.math.abs(y)
        if (ax < 1e-30f && ay < 1e-30f) return 0f
        val swap = ay > ax
        val t = if (swap) ax / ay else ay / ax
        val t2 = t * t
        var r = t * (0.99997726f + t2 * (-0.33262347f + t2 * (0.19354346f + t2 * (-0.11643287f + t2 * (0.05265332f + t2 * -0.01172120f)))))
        if (swap) r = 1.5707964f - r
        if (x < 0f) r = 3.1415927f - r
        return if (y < 0f) -r else r
    }

    fun asin(z: Float): Float {
        val c = z.coerceIn(-1f, 1f)
        return atan2(c, kotlin.math.sqrt(1f - c * c))
    }
}
