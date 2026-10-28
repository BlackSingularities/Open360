package com.blacksingularities.open360.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.blacksingularities.open360.math.Quat
import com.blacksingularities.open360.math.Vec3
import kotlin.math.max

/**
 * Orientacja urządzenia z historią (bufor kołowy), żeby każdej klatce kamery przypisać pozę
 * z chwili naświetlenia – interpolacją sferyczną między próbkami czujnika – oraz prędkość
 * kątową (do odwracania migawki szczelinowej i oceny rozmycia ruchu). Źródłem jest wektor
 * obrotu bez magnetometru: płynny i odporny na metal; dryf koryguje dopasowanie obrazem.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val size = 1024
    private val times = LongArray(size)
    private val quats = arrayOfNulls<Quat>(size)
    private var head = 0
    private var count = 0
    private val lock = Any()
    private val q = FloatArray(4)

    /** Prędkość kątowa (°/s), wygładzona. */
    @Volatile var angularSpeed = 0f
        private set

    @Volatile var latest: Quat? = null
        private set

    val available: Boolean
        get() = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    fun start() {
        stop()
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        sm.unregisterListener(this)
        synchronized(lock) {
            count = 0
            head = 0
        }
        latest = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getQuaternionFromVector(q, e.values)
        var nq = Quat(q[0], q[1], q[2], q[3]).normalized()
        synchronized(lock) {
            if (count > 0) {
                val prevI = (head - 1 + size) % size
                val prev = quats[prevI]!!
                // Ta sama orientacja, krótsza droga – ułatwia interpolację.
                if (prev.dot(nq) < 0f) nq = Quat(-nq.w, -nq.x, -nq.y, -nq.z)
                val dt = (e.timestamp - times[prevI]) / 1e9f
                if (dt > 0f) {
                    val speed = Math.toDegrees(prev.angleTo(nq).toDouble()).toFloat() / dt
                    // Filtr – odporny na nierówne odstępy próbek.
                    val a = (dt / 0.06f).coerceIn(0.02f, 1f)
                    angularSpeed += (speed.coerceAtMost(720f) - angularSpeed) * a
                }
            }
            times[head] = e.timestamp
            quats[head] = nq
            head = (head + 1) % size
            count = minOf(count + 1, size)
        }
        latest = nq
    }

    private fun at(k: Int) = (head - 1 - k + size) % size

    /**
     * Poza z chwili [timestampNs] (zegar czujników); null, gdy brak danych z tego okresu.
     * Chwilę po ostatniej próbce (do 40 ms) ekstrapolujemy stałą prędkością kątową.
     */
    fun poseAt(timestampNs: Long): Quat? = synchronized(lock) {
        if (count == 0) return null
        val newest = at(0)
        if (timestampNs >= times[newest]) {
            val ahead = timestampNs - times[newest]
            if (ahead > 40_000_000L) return null
            if (count < 2 || ahead == 0L) return quats[newest]
            val w = omegaLocked(times[newest]) ?: return quats[newest]
            return (Quat.fromRotVec(w * (ahead / 1e9f)) * quats[newest]!!).normalized()
        }
        var newer = newest
        for (k in 1 until count) {
            val i = at(k)
            if (times[i] <= timestampNs) {
                val t0 = times[i]
                val t1 = times[newer]
                val t = ((timestampNs - t0).toFloat() / max(1L, t1 - t0)).coerceIn(0f, 1f)
                return Quat.slerp(quats[i]!!, quats[newer]!!, t)
            }
            newer = i
        }
        null
    }

    /** Prędkość kątowa (rad/s) w układzie świata w chwili [timestampNs]. */
    fun omegaAt(timestampNs: Long): Vec3? = synchronized(lock) { omegaLocked(timestampNs) }

    private fun omegaLocked(t: Long): Vec3? {
        if (count < 2) return null
        // Różnica między próbkami odległymi o ~16 ms wokół chwili t (mniej szumu niż sąsiednie).
        var i1 = at(0)
        var k = 0
        while (k + 1 < count && times[at(k + 1)] >= t + 8_000_000L) { k++; i1 = at(k) }
        var j = k + 1
        var i0 = at(j)
        while (j + 1 < count && times[i1] - times[i0] < 16_000_000L) { j++; i0 = at(j) }
        val dt = (times[i1] - times[i0]) / 1e9f
        if (dt <= 0f || dt > 0.2f) return null
        val dq = quats[i1]!! * quats[i0]!!.conj()
        return dq.toRotVec() * (1f / dt)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
