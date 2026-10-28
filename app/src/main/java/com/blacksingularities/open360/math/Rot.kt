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

    /** Kąt obrotu między orientacjami (rad). */
    fun angleTo(o: Quat): Float = 2f * acos(kotlin.math.abs(dot(o)).coerceAtMost(1f))

    companion object {
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
