package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.math.Vec3
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Przejście między wartościami z aparatu (gamma ≈ 2,2) a światłem liniowym. Wzmocnienie
 * ekspozycji i winietowanie są mnożnikami światła, więc liczymy je liniowo – tak samo
 * na CPU (tablice) i w shaderze (pow).
 */
object Gamma {
    const val G = 2.2f
    private const val N = 4096
    private val toLin = FloatArray(N + 2) { (it.toFloat() / N).coerceAtMost(1f).pow(G) }
    private val toGam = FloatArray(N + 2) { (it.toFloat() / N).coerceAtMost(1f).pow(1f / G) }

    fun lin(v: Float): Float = lut(toLin, v)
    fun gam(v: Float): Float = lut(toGam, v)

    private fun lut(t: FloatArray, v: Float): Float {
        val f = v.coerceIn(0f, 1f) * N
        val i = f.toInt()
        val a = t[i]
        return a + (t[i + 1] - a) * (f - i)
    }
}

/**
 * Geometria jednej klatki: kąt widzenia, obrót bufora względem obrazu pionowego i migawka
 * szczelinowa (rolling shutter). Wiersze matrycy są czytane po kolei przez [skew] sekund,
 * więc przy obrocie telefonu każdy wiersz ma trochę inną pozę – [omega] (rad/s, układ
 * urządzenia) pozwala to odwrócić. Poza klatki dotyczy środka odczytu (τ = 0).
 *
 * Dystorsja radialna obiektywu [k1]: punkt obrazu o tangensach (x, y) leży na promieniu
 * (x, y)·(1 + k1·r²), r² = (x² + y²)/(tanX² + tanY²) – 0 w środku, 1 w rogu kadru.
 * Tę samą formułę liczą shadery (Shaders.COMMON).
 */
class FrameGeom(
    val tanX: Float,
    val tanY: Float,
    /** Obrót bufora (°, zgodnie ze wskazówkami), który stawia obraz pionowo. */
    val rot: Int = 0,
    val skew: Float = 0f,
    val omega: Vec3 = Vec3(0f, 0f, 0f),
    val k1: Float = 0f,
) {
    private val shutter = skew > 0f && omega.length() > 1e-4f
    private val invDiag2 = 1f / (tanX * tanX + tanY * tanY)

    /** Mnożnik (1 + k1·r²) dla punktu obrazu o tangensach (x, y). */
    fun undistortFactor(x: Float, y: Float): Float = 1f + k1 * (x * x + y * y) * invDiag2

    /** Tangensy obrazu (z dystorsją) dla promienia o tangensach [xu], [yu]; wynik w [out]. */
    fun distort(xu: Float, yu: Float, out: FloatArray) {
        if (k1 == 0f) {
            out[0] = xu; out[1] = yu
            return
        }
        var xd = xu; var yd = yu
        repeat(4) {
            val f = 1f + k1 * (xd * xd + yd * yd) * invDiag2
            xd = xu / f; yd = yu / f
        }
        out[0] = xd; out[1] = yd
    }

    /** Współrzędna wiersza bufora (0..1) dla punktu (u, v) obrazu pionowego (v w dół). */
    fun bufferRow(u: Float, v: Float): Float = when (rot) {
        90 -> 1f - u
        180 -> 1f - v
        270 -> u
        else -> v
    }

    /** Czas odczytu (s, względem środka klatki) punktu obrazu (u, v). */
    fun rowTime(u: Float, v: Float): Float = (bufferRow(u, v) - 0.5f) * skew

    /**
     * Kierunek w układzie kamery z chwili środka klatki dla piksela o współrzędnych
     * tangensowych (x, y) (x w prawo, y w górę): p + τ·(ω × p).
     */
    fun rayAt(x: Float, y: Float): Vec3 {
        val k = if (k1 == 0f) 1f else undistortFactor(x, y)
        val xu = x * k; val yu = y * k
        val n = 1f / sqrt(xu * xu + yu * yu + 1f)
        val px = xu * n; val py = yu * n; val pz = -n
        if (!shutter) return Vec3(px, py, pz)
        val u = 0.5f + 0.5f * x / tanX
        val v = 0.5f - 0.5f * y / tanY
        val t = rowTime(u, v)
        val o = omega
        return Vec3(
            px + t * (o.y * pz - o.z * py),
            py + t * (o.z * px - o.x * pz),
            pz + t * (o.x * py - o.y * px),
        )
    }

    /**
     * Odwrotność [rayAt]: kierunek kamery (chwila środka klatki) → (u, v) w obrazie pionowym,
     * albo null, gdy za kamerą. Jedna iteracja punktu stałego wystarcza (τ·|ω| ≪ 1).
     */
    fun project(cx: Float, cy: Float, cz: Float, out: FloatArray): Boolean {
        if (cz >= -1e-4f) return false
        distort(cx / -cz, cy / -cz, out)
        var u = 0.5f + 0.5f * out[0] / tanX
        var v = 0.5f - 0.5f * out[1] / tanY
        if (shutter) {
            val t = rowTime(u, v)
            val o = omega
            val x = cx - t * (o.y * cz - o.z * cy)
            val y = cy - t * (o.z * cx - o.x * cz)
            val z = cz - t * (o.x * cy - o.y * cx)
            if (z >= -1e-4f) return false
            distort(x / -z, y / -z, out)
            u = 0.5f + 0.5f * out[0] / tanX
            v = 0.5f - 0.5f * out[1] / tanY
        }
        out[0] = u
        out[1] = v
        return true
    }

    fun withTan(tx: Float, ty: Float) = FrameGeom(tx, ty, rot, skew, omega, k1)
    fun withLens(tx: Float, ty: Float, k: Float) = FrameGeom(tx, ty, rot, skew, omega, k)
}

/** Korekta jasności klatki: wzmocnienie ekspozycji i winietowanie (oba liniowo). */
data class Photometry(val gain: Float = 1f, val vignette: Float = 0f) {
    /** Mnożnik światła w punkcie (u, v) kadru. */
    fun factor(u: Float, v: Float, geom: FrameGeom): Float = gain * (1f + vignette * radius2(u, v, geom))

    companion object {
        /** Kwadrat odległości od środka kadru w ułamku półprzekątnej (0 w środku, 1 w rogu). */
        fun radius2(u: Float, v: Float, geom: FrameGeom): Float {
            val x = (2f * u - 1f) * geom.tanX
            val y = (2f * v - 1f) * geom.tanY
            return (x * x + y * y) / (geom.tanX * geom.tanX + geom.tanY * geom.tanY)
        }
    }
}

/**
 * Reguła łączenia (identyczna na GPU – zob. Shaders.STITCH): waga piksela to centralność
 * w kadrze razy jakość klatki. Nowa klatka wygrywa tam, gdzie jej waga jest większa, a szew
 * wypada w połowie między środkami kadrów (najmniej zniekształceń i paralaksy), z wąskim
 * pasem przenikania. Przy dużej różnicy treści (ruch, paralaksa) przejście jest ostre –
 * zamiast „duchów”.
 */
object Blend {
    const val BAND = 0.1f
    const val GHOST = 0.08f
    const val MIN_W = 1f / 255f

    fun weight(u: Float, v: Float, quality: Float): Float {
        val x = 2f * u - 1f
        val y = 2f * v - 1f
        return ((1f - x * x) * (1f - y * y) * quality).coerceAtLeast(MIN_W)
    }

    /** Udział nowej klatki (0..1). [diff] – różnica jasności (gamma) między nową a starą treścią. */
    fun mix(wNew: Float, wOld: Float, diff: Float): Float {
        if (wOld <= 0f) return 1f
        var t = ((wNew - wOld) / BAND + 0.5f).coerceIn(0f, 1f)
        if (diff > GHOST) t = smooth(0.35f, 0.65f, t)
        return t
    }

    fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * Rozmycie ruchu: o ile pikseli przesuwa się obraz w trakcie naświetlania. Obrót wokół osi
 * x/y przesuwa cały kadr, przechył (z) – obraca go wokół środka.
 */
object MotionBlur {
    fun pixels(omega: Vec3, exposureS: Float, tanY: Float, pxPerRad: Float): Float {
        val pan = sqrt(omega.x * omega.x + omega.y * omega.y)
        val roll = kotlin.math.abs(omega.z) * tanY * 0.6f
        return (pan + roll) * exposureS * pxPerRad
    }

    /** Jakość 0,25..1: pełna do ~1 px rozmycia, potem szybko maleje. */
    fun quality(blurPx: Float): Float {
        val b = blurPx / 1.5f
        return 0.25f + 0.75f / (1f + b * b * b * b)
    }
}
