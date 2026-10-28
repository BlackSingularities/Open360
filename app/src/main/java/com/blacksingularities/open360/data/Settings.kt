package com.blacksingularities.open360.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.blacksingularities.open360.camera.OrientationSource
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Jak nowa klatka łączy się z tym, co już jest w panoramie. */
enum class BlendMode(val label: String, val detail: String) {
    BEST("Najostrzejsza", "Każdy fragment z klatki, w której był najbliżej środka kadru. Najmniej duchów."),
    FEATHER("Miękkie przenikanie", "Średnia ważona jak w OpenPano – gładkie przejścia, wyrównuje jasność."),
    FILL("Tylko puste miejsca", "Raz zapisany fragment już się nie zmienia. Dobre przy ruchomych obiektach."),
    OVERWRITE("Nadpisywanie", "Najnowsza klatka przykrywa starsze. Pozwala „poprawić” fragment."),
}

/** Ciągłe malowanie albo zdjęcia w punktach siatki. */
enum class CaptureMode(val label: String, val detail: String) {
    CONTINUOUS("Malowanie", "Panorama rośnie na żywo, gdy wodzisz telefonem."),
    POINTS("Punkty", "Celuj w kropki – zdjęcie robi się samo, gdy kadr się ustabilizuje."),
}

/** Jaki fragment sfery ma powstać (siatka punktów i licznik pokrycia). */
enum class CaptureRange(val label: String, val detail: String, val latMin: Float, val latMax: Float, val lonSpan: Float) {
    SPHERE("Pełna sfera", "360° × 180° – zenit i nadir", -90f, 90f, 360f),
    NO_NADIR("Bez nadiru", "360° bez samego dołu (statyw, stopy)", -60f, 90f, 360f),
    BAND("Pas horyzontu", "360° wokół, ±30° w pionie", -30f, 30f, 360f),
    HALF("Pół panoramy", "180° przed sobą, ±45° w pionie", -45f, 45f, 180f),
}

/** Czym wypełnić nieuchwycone fragmenty przy eksporcie. */
enum class HoleFill(val label: String) {
    BLUR("Rozmyte przedłużenie"),
    BLACK("Czarne"),
    GRAY("Szare"),
}

/** Kadrowanie przy eksporcie. */
enum class ExportCrop(val label: String, val detail: String) {
    FULL("Pełna sfera 2:1", "Zawsze 360° × 180° – zgodne z każdą przeglądarką"),
    CONTENT("Przytnij do treści", "Tylko uchwycony obszar; metadane opisują położenie w sferze"),
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("open360", Context.MODE_PRIVATE)
    private val all = mutableListOf<() -> Unit>()

    // Zszywanie
    var canvasWidth by pref("canvasWidth", 4096)
    var frameHeight by pref("frameHeight", 960)
    var blend by enumPref("blend", BlendMode.BEST)
    var feather by pref("feather", 0.35f)
    var mode by enumPref("mode", CaptureMode.CONTINUOUS)
    var range by enumPref("range", CaptureRange.SPHERE)
    var maxSpeed by pref("maxSpeed", 45f)
    var imageAlign by pref("imageAlign", true)
    var autoFov by pref("autoFov", true)
    var autoGain by pref("autoGain", true)
    var vignette by pref("vignette", 0.25f)
    var pointOverlap by pref("pointOverlap", 0.35f)
    var autoFinish by pref("autoFinish", true)

    // Kamera i czujniki
    var source by enumPref("source", OrientationSource.GAME)
    var lockExposure by pref("lockExposure", true)
    var exposure by pref("exposure", 0)
    var wide by pref("wide", false)
    var fovScale by pref("fovScale", 1f)
    var syncDelayMs by pref("syncDelayMs", 0)

    // Podgląd
    var viewZoomOut by pref("viewZoomOut", 1.6f)
    var liveOpacity by pref("liveOpacity", 0.9f)
    var showGrid by pref("showGrid", true)
    var showLevel by pref("showLevel", true)
    var haptics by pref("haptics", true)
    var sounds by pref("sounds", true)

    // Eksport
    var jpegQuality by pref("jpegQuality", 92)
    var holeFill by enumPref("holeFill", HoleFill.BLUR)
    var crop by enumPref("crop", ExportCrop.FULL)
    var writeXmp by pref("writeXmp", true)

    // Przeglądarka
    var autoRotate by pref("autoRotate", false)
    var viewerGyro by pref("viewerGyro", false)

    var updates by pref("updates", true)
    var tipsSeen by pref("tipsSeen", false)

    fun resetStitching() {
        prefs.edit().clear().apply()
        all.forEach { it() }
    }

    private fun <T> pref(key: String, default: T): ReadWriteProperty<Any?, T> {
        @Suppress("UNCHECKED_CAST")
        val read: () -> T = {
            when (default) {
                is Int -> prefs.getInt(key, default) as T
                is Float -> prefs.getFloat(key, default) as T
                is Boolean -> prefs.getBoolean(key, default) as T
                is String -> (prefs.getString(key, default) ?: default) as T
                else -> default
            }
        }
        val state = mutableStateOf(read())
        all += { state.value = read() }
        return object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                state.value = value
                prefs.edit().apply {
                    when (value) {
                        is Int -> putInt(key, value)
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }.apply()
            }
        }
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): ReadWriteProperty<Any?, E> {
        val read = { prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default }
        val state = mutableStateOf(read())
        all += { state.value = read() }
        return object : ReadWriteProperty<Any?, E> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): E = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: E) {
                state.value = value
                prefs.edit().putString(key, value.name).apply()
            }
        }
    }
}
