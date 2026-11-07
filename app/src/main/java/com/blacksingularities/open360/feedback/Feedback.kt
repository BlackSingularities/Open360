package com.blacksingularities.open360.feedback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Zgłoszenia do redzikowski.com:
 *  - raport o błędzie – awaria zapisana przez [install] i wysłana przy następnym
 *    starcie, jeśli użytkownik się zgodzi (trafia do „Raportów błędów” w panelu),
 *  - zgłoszenie błędu albo sugestia funkcjonalności pisane ręcznie (trafia do
 *    „Sugestii i zgłoszeń”).
 *
 * Adres e-mail jest zawsze opcjonalny. Nic nie wychodzi z telefonu bez akcji
 * użytkownika.
 */
object Feedback {
    private const val ENDPOINT = "https://redzikowski.com/api/feedback"
    private const val CRASH_FILE = "feedback-pending-crash.json"

    @Volatile
    var appSlug: String = ""
        private set

    @Volatile
    private var installed = false

    enum class Kind(val wire: String) { BUG("bug"), FEATURE("feature") }

    data class PendingCrash(val stack: String, val at: Long, val thread: String, val appVersion: String)

    /**
     * Wywołaj raz w `onCreate` aktywności (albo w `Application`). [captureCrashes] = false,
     * gdy aplikacja ma już własny dziennik awarii i sama pokazuje go użytkownikowi.
     */
    fun install(context: Context, slug: String, captureCrashes: Boolean = true) {
        appSlug = slug
        if (installed || !captureCrashes) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { savePendingCrash(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun crashFile(context: Context) = File(context.filesDir, CRASH_FILE)

    private fun savePendingCrash(context: Context, thread: Thread, error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val json = JSONObject()
            .put("stack", writer.toString().take(60_000))
            .put("at", System.currentTimeMillis())
            .put("thread", thread.name)
            .put("appVersion", appVersion(context))
        crashFile(context).writeText(json.toString())
    }

    fun pendingCrash(context: Context): PendingCrash? = runCatching {
        val file = crashFile(context)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        PendingCrash(
            stack = json.optString("stack"),
            at = json.optLong("at"),
            thread = json.optString("thread"),
            appVersion = json.optString("appVersion"),
        )
    }.getOrNull()

    fun discardPendingCrash(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /** Wysyłka blokująca – wołaj poza wątkiem głównym (wersje `suspend` są w FeedbackUi.kt). */
    fun sendCrashBlocking(context: Context, crash: PendingCrash, comment: String, email: String): Result<Unit> {
        val body = baseJson(context)
            .put("kind", "crash")
            .put("appVersion", crash.appVersion.ifBlank { appVersion(context) })
            .put("stack", crash.stack)
            .put("message", comment.trim())
            .put("email", email.trim())
            .put("extra", diagnostics(context).put("thread", crash.thread).put("crashedAt", crash.at))
        return post(body).onSuccess { discardPendingCrash(context) }
    }

    /** Raport z gotowego tekstu awarii (np. z własnego dziennika aplikacji). */
    fun sendCrashTextBlocking(context: Context, stack: String, comment: String, email: String): Result<Unit> =
        sendCrashBlocking(context, PendingCrash(stack, System.currentTimeMillis(), "", appVersion(context)), comment, email)

    fun sendBlocking(context: Context, kind: Kind, message: String, email: String, includeDiagnostics: Boolean): Result<Unit> {
        val body = baseJson(context, includeDevice = includeDiagnostics)
            .put("kind", kind.wire)
            .put("message", message.trim())
            .put("email", email.trim())
        if (includeDiagnostics) body.put("extra", diagnostics(context))
        return post(body)
    }

    private fun baseJson(context: Context, includeDevice: Boolean = true): JSONObject {
        val json = JSONObject()
            .put("app", appSlug)
            .put("appName", appName(context))
            .put("appVersion", appVersion(context))
            .put("platform", if (context.packageManager.hasSystemFeature("android.hardware.type.watch")) "wearos" else "android")
            .put("locale", Locale.getDefault().toLanguageTag())
        if (includeDevice) {
            json.put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        }
        return json
    }

    private fun diagnostics(context: Context): JSONObject {
        val json = JSONObject()
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            json.put("ramTotalMb", info.totalMem / 1_048_576)
            json.put("ramFreeMb", info.availMem / 1_048_576)
            json.put("lowMemory", info.lowMemory)
        }
        runCatching { json.put("abi", Build.SUPPORTED_ABIS.joinToString()) }
        runCatching {
            val dm = context.resources.displayMetrics
            json.put("screen", "${dm.widthPixels}×${dm.heightPixels} @${dm.densityDpi}dpi")
        }
        runCatching { json.put("build", Build.DISPLAY) }
        return json
    }

    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun appName(context: Context): String = runCatching {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }.getOrDefault("")

    private fun post(json: JSONObject): Result<Unit> =
        runCatching {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) error("HTTP $code")
            } finally {
                conn.disconnect()
            }
        }
}
