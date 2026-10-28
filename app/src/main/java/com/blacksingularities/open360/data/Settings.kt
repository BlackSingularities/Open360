package com.blacksingularities.open360.data

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Ustawienia. Wszystko, co da się dobrać samemu (łączenie klatek, ekspozycja, winietowanie,
 * kąt widzenia, synchronizacja czujnika, limit prędkości, kadrowanie, jakość JPEG), aplikacja
 * dobiera i kalibruje automatycznie – tu zostaje tylko to, co jest decyzją użytkownika.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("open360", Context.MODE_PRIVATE)
    private val all = mutableListOf<() -> Unit>()

    /** Ile pamięci ma telefon (GB) – 16K potrzebuje ok. 0,6 GB pamięci grafiki. */
    val memoryGb: Float = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / 1_073_741_824f
    }.getOrDefault(4f)

    // Jakość
    var canvasWidth by pref("canvasWidth2", 4096)
    /** Krótszy bok klatki z aparatu; 0 = dobierany do rozdzielczości panoramy. */
    var frameHeight by pref("frameHeight2", 0)

    // Zbieranie
    var mode by enumPref("mode", CaptureMode.CONTINUOUS)
    var range by enumPref("range", CaptureRange.SPHERE)

    // Aparat
    var exposure by pref("exposure", 0)
    var wide by pref("wide", false)

    // Podgląd (gestem na ekranie i przyciskiem siatki)
    var viewZoomOut by pref("viewZoomOut", 1.6f)
    var showGrid by pref("showGrid", true)
    var feedback by pref("feedback", true)

    // Przeglądarka – zapamiętuje ostatni wybór przycisków.
    var autoRotate by pref("autoRotate", false)
    var viewerGyro by pref("viewerGyro", false)

    var updates by pref("updates", true)
    var tipsSeen by pref("tipsSeen", false)

    /** Rozdzielczości panoramy możliwe na tym telefonie. */
    fun canvasSizes(maxTexture: Int): List<Int> = buildList {
        add(2048)
        add(4096)
        if (maxTexture >= 4096) add(8192)
        if (maxTexture >= 4096 && memoryGb >= 5.5f) add(16384)
    }

    fun effectiveCanvas(maxTexture: Int): Int {
        val sizes = canvasSizes(maxTexture)
        return sizes.lastOrNull { it <= canvasWidth } ?: sizes.first()
    }

    /**
     * Klatka dobrana do panoramy: tyle pikseli na stopień, ile ma płótno, z lekkim zapasem
     * (pomniejszanie z mipmap daje czystszy obraz niż powiększanie).
     */
    fun effectiveFrameHeight(): Int = if (frameHeight > 0) frameHeight else autoFrameHeight(canvasWidth)

    fun resetAll() {
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

    companion object {
        val FRAME_HEIGHTS = listOf(720, 1080, 1440, 2560)

        fun autoFrameHeight(canvas: Int): Int = when {
            canvas <= 2048 -> 720
            canvas <= 4096 -> 1080
            canvas <= 8192 -> 1440
            else -> 2560
        }
    }
}
