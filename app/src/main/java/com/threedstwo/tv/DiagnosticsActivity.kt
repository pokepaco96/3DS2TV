package com.threedstwo.tv

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import com.threedstwo.tv.net.NetworkInfo
import com.threedstwo.tv.ui.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Network facts, last session metrics and the in-app log, exportable to a file. */
class DiagnosticsActivity : Activity() {
    private lateinit var body: TextView
    private lateinit var result: TextView
    private val app get() = ThreeDsTvApp.of(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this).apply {
            val p = Ui.dp(context, 40f)
            setPadding(p * 2, p, p * 2, p)
        }
        col.addView(Ui.title(this, "Diagnóstico"))
        col.addView(Ui.button(this, "Actualizar") { refresh() })
        col.addView(Ui.button(this, "Exportar log a archivo") { export() })
        result = Ui.text(this, "", 16f, R.color.ok)
        col.addView(result)
        body = Ui.text(this, "", 15f, R.color.text).apply { typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(body)
        setContentView(Ui.scroll(this, col))
        refresh()
        col.getChildAt(1)?.requestFocus()
    }

    private fun report(): String = buildString {
        val s = app.loadSettings()
        appendLine("3DS2TV ${BuildInfo.versionName(this@DiagnosticsActivity)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        val local = NetworkInfo.local(this@DiagnosticsActivity)
        appendLine("Red: ${local?.let { "${it.ip}/${it.prefixLength} por ${it.transport}" } ?: "sin red"}")
        appendLine("Consola: ${s.consoleIp.ifBlank { "(sin IP)" }} · modo ${s.connectMode} · UDP vídeo ${s.viewerPort}")
        appendLine("Calidad ${s.jpegQuality} · QoS ${s.qosMbps} Mbps · prioridad ${if (s.topPriority) "superior" else "inferior"} x${s.priorityFactor} · audio ${s.audioEnabled}")
        appendLine()
        val st = app.lastStats
        if (st != null) {
            appendLine("— Última sesión —")
            appendLine(StreamActivity.formatStats(st))
        } else {
            appendLine("Sin sesión todavía.")
        }
        appendLine()
        appendLine("— Log (más reciente al final) —")
        AppLog.snapshot().takeLast(300).forEach { appendLine(it) }
    }

    private fun refresh() {
        body.text = report()
    }

    private fun export() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val name = "3ds2tv-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val f = File(dir, name)
            f.writeText(report())
            AppLog.i("Log exportado a ${f.absolutePath}")
            result.text = "Guardado en ${f.absolutePath}\nDescárgalo con:  adb pull \"${f.absolutePath}\""
        } catch (e: Exception) {
            result.text = "No se pudo exportar: ${e.message}"
        }
    }
}
