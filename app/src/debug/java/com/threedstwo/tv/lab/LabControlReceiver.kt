package com.threedstwo.tv.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.threedstwo.tv.AppLog
import com.threedstwo.tv.BuildConfig
import com.threedstwo.tv.BuildInfo
import com.threedstwo.tv.LabHooks
import com.threedstwo.tv.SessionProbe
import com.threedstwo.tv.ThreeDsTvApp
import com.threedstwo.tv.core.lab.LabControl
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only control for tools/hardware-lab (see docs/HARDWARE_LAB.md):
 *   adb shell am broadcast -n com.threedstwo.tv/.lab.LabControlReceiver -a com.threedstwo.tv.debug.START --es ip 192.168.1.23 --es profile ULTRA_LOW_LATENCY
 *   adb shell am start -n com.threedstwo.tv/.MainActivity --ez lab_autostart true
 *   adb shell am broadcast -n com.threedstwo.tv/.lab.LabControlReceiver -a com.threedstwo.tv.debug.EXPORT_METRICS
 *   adb shell am broadcast -n com.threedstwo.tv/.lab.LabControlReceiver -a com.threedstwo.tv.debug.STOP
 * Results are logged with tag 3DS2TV-LAB and returned as the broadcast result data.
 */
class LabControlReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ThreeDsTvApp.of(ctx)
        val result = when (intent.action) {
            LabControl.ACTION_START -> {
                val extras = intent.extras?.keySet()?.associateWith { k -> intent.extras?.get(k)?.toString() ?: "" } ?: emptyMap()
                val r = LabControl.applyOverrides(app.loadSettings(), extras)
                if (r.errors.isEmpty()) {
                    app.saveSettings(r.settings)
                    "OK settings saved: ip=${r.settings.consoleIp} mode=${r.settings.connectMode} profile=${r.settings.profile} " +
                        "quality=${r.settings.jpegQuality} qos=${r.settings.qosMbps} audio=${r.settings.audioEnabled} " +
                        "input=${r.settings.inputRedirection} view=${r.settings.layout.viewMode}. Now: am start -n com.threedstwo.tv/.MainActivity --ez ${LabHooks.EXTRA_AUTOSTART} true"
                } else {
                    "ERROR " + r.errors.joinToString("; ")
                }
            }
            LabControl.ACTION_STOP -> {
                val a = LabHooks.stream?.get()
                if (a == null) "OK no session" else {
                    Handler(Looper.getMainLooper()).post { a.finish() } // normal path: neutral input is sent
                    "OK stopping"
                }
            }
            LabControl.ACTION_EXPORT -> export(ctx, app)
            else -> "ERROR unknown action ${intent.action}"
        }
        Log.i(TAG, result)
        AppLog.i("LAB: $result")
        resultData = result
    }

    private fun export(ctx: Context, app: ThreeDsTvApp): String {
        val st = app.lastStats ?: return "ERROR no stats yet (start a session first)"
        val s = app.loadSettings()
        val meta = linkedMapOf(
            "time" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()),
            "app_version" to BuildInfo.versionName(ctx),
            "commit" to BuildConfig.GIT_COMMIT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "profile" to s.profile.name,
            "quality_setting" to s.jpegQuality.toString(),
            "qos_setting" to s.qosMbps.toString(),
            "view" to s.layout.viewMode.name,
        )
        val counters = linkedMapOf(
            "presented_frames" to SessionProbe.presentedFrames,
            "decoded_frames" to SessionProbe.decodedFrames,
            "decode_errors" to SessionProbe.decodeErrors,
        )
        val json = LabControl.toJson(st, meta, counters)
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "lab").apply { mkdirs() }
        File(dir, "latest.json").writeText(json)
        return "OK ${File(dir, "latest.json").absolutePath}"
    }

    companion object {
        const val TAG = "3DS2TV-LAB"
    }
}
