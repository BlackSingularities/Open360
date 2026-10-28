package com.blacksingularities.open360.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.blacksingularities.open360.math.Quat
import kotlin.math.max

/** Źródło orientacji: fuzja z magnetometrem (stały kierunek) albo tylko żyroskop i akcelerometr. */
enum class OrientationSource(val label: String, val detail: String) {
    GAME("Żyroskop", "Płynny, odporny na metal i magnesy. Dryf koryguje obraz."),
    ROTATION("Żyroskop + kompas", "Stały kierunek północy, ale wrażliwy na zakłócenia magnetyczne."),
}

/**
 * Orientacja urządzenia z historią (bufor kołowy), żeby każdej klatce kamery przypisać pozę
 * z chwili naświetlenia – interpolacją sferyczną między próbkami czujnika.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val size = 512
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

    var source = OrientationSource.GAME
        private set

    val available: Boolean
        get() = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    fun start(src: OrientationSource) {
        stop()
        source = src
        val type = when (src) {
            OrientationSource.GAME -> Sensor.TYPE_GAME_ROTATION_VECTOR
            OrientationSource.ROTATION -> Sensor.TYPE_ROTATION_VECTOR
        }
        val sensor = sm.getDefaultSensor(type)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
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
        val nq = Quat(q[0], q[1], q[2], q[3]).normalized()
        synchronized(lock) {
            if (count > 0) {
                val prevI = (head - 1 + size) % size
                val dt = (e.timestamp - times[prevI]) / 1e9f
                if (dt > 0f) {
                    val prev = quats[prevI]!!
                    val speed = Math.toDegrees(prev.angleTo(nq).toDouble()).toFloat() / dt
                    // Filtr – odporny na nierówne odstępy próbek.
                    val a = (dt / 0.08f).coerceIn(0.02f, 1f)
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

    /** Poza z chwili [timestampNs] (zegar czujników); null, gdy brak danych z tego okresu. */
    fun poseAt(timestampNs: Long): Quat? = synchronized(lock) {
        if (count == 0) return null
        var newer = -1
        for (k in 0 until count) {
            val i = (head - 1 - k + size) % size
            if (times[i] <= timestampNs) {
                if (newer < 0) {
                    // Klatka nowsza niż ostatnia próbka – ekstrapolacja byłaby ryzykowna.
                    return if (timestampNs - times[i] < 50_000_000L) quats[i] else null
                }
                val t0 = times[i]
                val t1 = times[newer]
                val t = ((timestampNs - t0).toFloat() / max(1L, t1 - t0)).coerceIn(0f, 1f)
                return Quat.slerp(quats[i]!!, quats[newer]!!, t)
            }
            newer = i
        }
        null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
