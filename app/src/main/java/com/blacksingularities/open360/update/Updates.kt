package com.blacksingularities.open360.update

import com.blacksingularities.open360.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Nowsza wersja dostępna do pobrania. */
data class Update(val version: String, val apkUrl: String, val notes: String)

object Updates {
    /** Sprawdza ostatnie wydanie; null, gdy brak nowszego albo brak sieci. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val tag = j.getString("tag_name").removePrefix("v")
            if (!newer(tag, BuildConfig.VERSION_NAME)) return@runCatching null
            val assets = j.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
            }
            apk?.let { Update(tag, it, j.optString("body")) }
        }.getOrNull()
    }

    fun newer(remote: String, local: String): Boolean {
        if (local.contains("dev")) return false
        val a = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val b = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
