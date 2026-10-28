package com.blacksingularities.open360.stitch

import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3

/**
 * Samouczące się winietowanie: w zakładkach porównujemy jasność klatki z tym, co już zszyte,
 * w funkcji odległości od środka kadru. Regresja z ≈ g·(1 + k·r²) daje k = nachylenie / wyraz
 * wolny. Sumy wygasają powoli, więc oszacowanie stabilizuje się po kilkunastu klatkach.
 */
class VignetteEstimator(initial: Float = 0.1f) {
    var k = initial
        private set
    private var sw = 0.0; private var sx = 0.0; private var sz = 0.0; private var sxx = 0.0; private var sxz = 0.0

    fun reset(initial: Float = 0.1f) {
        k = initial
        sw = 0.0; sx = 0.0; sz = 0.0; sxx = 0.0; sxz = 0.0
    }

    fun add(st: PhotoStats) {
        if (st.n < 60) return
        val decay = 0.92
        sw = sw * decay + st.sw; sx = sx * decay + st.sx; sz = sz * decay + st.sz
        sxx = sxx * decay + st.sxx; sxz = sxz * decay + st.sxz
        val varX = sxx / sw - (sx / sw) * (sx / sw)
        if (sw < 20.0 || varX < 0.01) return
        val slope = (sxz / sw - (sx / sw) * (sz / sw)) / varX
        val icept = sz / sw - slope * sx / sw
        if (icept < 0.3) return
        // z liczymy z surowej klatki, więc pomiar to całe winietowanie (zaburzone tym, co
        // płótno dostało z wcześniejszą korektą) – iteracja punktu stałego zbiega do prawdy.
        val measured = (slope / icept).toFloat().coerceIn(-0.2f, 1f)
        k = (k + 0.5f * (measured - k)).coerceIn(0f, 0.8f)
        sw *= 0.5; sx *= 0.5; sz *= 0.5; sxx *= 0.5; sxz *= 0.5
    }
}

/**
 * Samoczynna synchronizacja czasu klatek z czujnikiem. Błąd czasu e daje korektę pozy
 * proporcjonalną do prędkości kątowej: c ≈ ω·e + dryf. Dryf zmienia się powoli, więc
 * porównujemy przyrosty między kolejnymi klatkami: Δc ≈ Δω·e i e = Σ Δc·Δω / Σ |Δω|².
 */
class SyncEstimator {
    /** Przesunięcie (s) dodawane do czasu klatki przy odczycie pozy z czujnika. */
    var offset = 0f
        private set
    private var prevC: Vec3? = null
    private var prevW: Vec3? = null
    private var prevT = 0L
    private var sxy = 0.0
    private var sxx = 0.0

    fun reset() {
        offset = 0f
        prevC = null; prevW = null
        sxy = 0.0; sxx = 0.0
    }

    /** [corr] – pełna korekta pozy po dopasowaniu, [omega] – prędkość kątowa w układzie panoramy. */
    fun add(corr: Mat3, omega: Vec3, timeNs: Long) {
        val c = corr.log()
        val pc = prevC; val pw = prevW
        if (pc != null && pw != null && timeNs - prevT < 600_000_000L) {
            val dc = c - pc
            val dw = omega - pw
            sxy += dc.dot(dw)
            sxx += dw.dot(dw)
            // Wystarczająco dużo zmian prędkości (≈ kilka zwrotów ruchu) – poprawiamy.
            if (sxx > 0.25) {
                val e = (sxy / sxx).toFloat().coerceIn(-0.03f, 0.03f)
                offset = (offset + 0.8f * e).coerceIn(-0.06f, 0.12f)
                sxy = 0.0; sxx = 0.0
                prevC = null; prevW = null
                return
            }
        }
        prevC = c; prevW = omega; prevT = timeNs
    }
}

/**
 * Korekta pozy obrazem bez narastania błędu. Dopasowanie klatki do panoramy mierzy „obrót
 * pozorny”: przy obracaniu się z telefonem w wyciągniętej ręce aparat także się przesuwa,
 * a bliskie przedmioty przesuwają się w kadrze bardziej niż sam obrót (paralaksa, przy ramieniu
 * 40 cm i ścianie 3 m – o ~13%). Gdyby każdą klatkę stawiać tam, gdzie wskazuje obraz, błąd
 * sumowałby się klatka po klatce i koło „zamykałoby się” po ~315°. Dlatego globalną
 * geometrię wyznacza żyroskop, a obraz:
 *  - koryguje jego powolny dryf – korekta [corr] goni pomiar najwyżej [RATE] na sekundę
 *    i nie przekracza [MAX],
 *  - dosuwa pojedynczą klatkę lokalnie najwyżej o [LOCAL] (opóźnienia, drgania),
 *    co nie przenosi się na kolejne klatki.
 */
class PoseCorrector {
    @Volatile var corr = Mat3.I
        private set

    fun reset(c: Mat3 = Mat3.I) {
        corr = c
    }

    /** [deviation] – poza z obrazu względem żyroskopu (obraz · żyroskopᵀ), [dt] – czas od poprzedniego pomiaru. */
    fun update(deviation: Mat3, dt: Float) {
        val step = (deviation * corr.transposed()).log()
        val maxStep = RATE * dt.coerceIn(0f, 0.25f)
        val l = step.length()
        val applied = if (l > maxStep) step * (maxStep / l) else step
        var next = (Mat3.exp(applied) * corr).orthonormalized()
        val total = next.log()
        if (total.length() > MAX) next = Mat3.exp(total * (MAX / total.length()))
        corr = next
    }

    /** Poza do zszycia: żyroskop z korektą dryfu i ograniczonym lokalnym dosunięciem do obrazu. */
    fun stitchPose(deviation: Mat3, gyroPose: Mat3): Mat3 {
        val local = (deviation * corr.transposed()).log()
        val l = local.length()
        val bounded = if (l > LOCAL) local * (LOCAL / l) else local
        return (Mat3.exp(bounded) * corr * gyroPose).orthonormalized()
    }

    companion object {
        val RATE = 0.15f * DEG
        val MAX = 6f * DEG
        val LOCAL = 0.35f * DEG
    }
}

/**
 * Wybór klatek kluczowych w trybie malowania: zszywamy, gdy kadr przesunął się o kawałek
 * (nowa treść), gdy klatka jest wyraźnie ostrzejsza od ostatniej w tym miejscu albo gdy
 * telefon stoi i warto dołożyć bardzo ostre ujęcie. Mniej klatek = mniej szwów, mniej
 * okazji do rozmycia, a każda dostaje dokładne dopasowanie.
 */
class KeyframePolicy {
    private var last: Mat3? = null
    private var lastQuality = 0f
    private var lastMs = 0L

    fun reset() {
        last = null
        lastQuality = 0f
        lastMs = 0L
    }

    /** [stepRad] – przesunięcie, po którym zszywamy kolejną klatkę. */
    fun wants(pose: Mat3, quality: Float, nowMs: Long, stepRad: Float): Boolean {
        val l = last ?: return true
        val moved = (pose * l.transposed()).log().length()
        if (moved >= stepRad) return true
        if (moved > stepRad * 0.1f && quality > lastQuality + 0.2f) return true
        return nowMs - lastMs > 800 && quality > 0.92f && moved > stepRad * 0.15f
    }

    fun accepted(pose: Mat3, quality: Float, nowMs: Long) {
        last = pose
        lastQuality = quality
        lastMs = nowMs
    }
}
