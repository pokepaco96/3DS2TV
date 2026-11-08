package com.threedstwo.tv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.threedstwo.tv.core.session.StreamStats
import com.threedstwo.tv.core.settings.AppSettings
import com.threedstwo.tv.core.settings.KeyValueStore

class ThreeDsTvApp : Application() {
    lateinit var prefs: SharedPreferences
        private set

    /** Last stats of the previous/current session for the diagnostics screen. */
    @Volatile var lastStats: StreamStats? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        installCrashRecorder()
        AppLog.i("3DS2TV ${BuildInfo.versionName(this)} (${BuildConfig.GIT_COMMIT}) iniciado")
    }

    private val store = object : KeyValueStore {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putAll(values: Map<String, String>) {
            val e = prefs.edit()
            values.forEach { (k, v) -> e.putString(k, v) }
            e.apply()
        }
    }

    /** Keeps the last uncaught exception on disk so it can be exported from Diagnostics after a restart. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()} hilo=${t.name} 3DS2TV ${BuildInfo.versionName(this)} (${BuildConfig.GIT_COMMIT})\n" +
                        AppLog.snapshot().takeLast(80).joinToString("\n") + "\n\n" + sw,
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun lastCrash(): String? = java.io.File(filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()

    fun clearLastCrash() {
        java.io.File(filesDir, CRASH_FILE).delete()
    }

    fun loadSettings(): AppSettings = AppSettings.load(store)

    fun saveSettings(s: AppSettings) = s.save(store)

    companion object {
        const val CRASH_FILE = "last-crash.txt"
        fun of(ctx: Context) = ctx.applicationContext as ThreeDsTvApp
    }
}

object BuildInfo {
    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
