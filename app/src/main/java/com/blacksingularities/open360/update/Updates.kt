package com.blacksingularities.open360.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.blacksingularities.open360.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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

    /**
     * Pobiera APK do pamięci podręcznej aplikacji; [onProgress] dostaje ułamek 0–1
     * (albo -1, gdy serwer nie podał rozmiaru). Zwraca plik albo null przy błędzie.
     */
    suspend fun download(context: Context, update: Update, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val part = File(dir, "Open360-${update.version}.apk.part")
        runCatching {
            val c = URL(update.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                onProgress(done.toFloat() / total)
                            }
                        } else if (last != -2) {
                            last = -2
                            onProgress(-1f)
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) error("niepełny plik")
            File(dir, "Open360-${update.version}.apk").also { part.renameTo(it) }
        }.getOrElse {
            part.delete()
            null
        }
    }

    /** Czy system pozwala tej aplikacji instalować pobrane APK. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Ekran ustawień, w którym użytkownik zezwala na instalowanie aktualizacji z Open360. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    /** Otwiera systemowy instalator dla pobranego pliku. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
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
