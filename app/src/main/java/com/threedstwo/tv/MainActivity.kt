package com.threedstwo.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.util.Ipv4
import com.threedstwo.tv.net.NetworkInfo
import com.threedstwo.tv.ui.Ui

/** Home screen: console IP, connect, settings, controller, diagnostics, help. 100 % D-pad. */
class MainActivity : Activity() {
    private lateinit var ipField: EditText
    private lateinit var modeButton: android.widget.Button
    private lateinit var info: TextView
    private lateinit var status: TextView
    private val app get() = ThreeDsTvApp.of(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = Ui.dp(context, 48f)
            setPadding(p, p, p, p)
        }
        val left = Ui.column(this)
        left.addView(Ui.title(this, "3DS2TV", 48f))
        left.addView(Ui.text(this, "New Nintendo 3DS (NTR-HR) → tu televisor. Mando → Luma3DS Input Redirection.", 18f))
        left.addView(Ui.text(this, "IP de la New 3DS (menú NTR-HR: X+Y)", 20f, R.color.text).apply { setPadding(0, Ui.dp(context, 24f), 0, 0) })
        ipField = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.1.50"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundResource(R.drawable.row_bg)
            val p = Ui.dp(context, 14f)
            setPadding(p * 2, p, p * 2, p)
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text_dim))
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    connect(); true
                } else false
            }
        }
        left.addView(ipField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@MainActivity, 8f) })
        left.addView(Ui.button(this, "▶  Conectar") { connect() })
        modeButton = Ui.button(this, "") { toggleMode() }
        left.addView(modeButton)
        status = Ui.text(this, "", 18f, R.color.warn)
        left.addView(status)

        val right = Ui.column(this).apply { setPadding(Ui.dp(context, 48f), Ui.dp(context, 70f), 0, 0) }
        right.addView(Ui.button(this, "⚙  Ajustes de imagen y red") { startActivity(Intent(this, SettingsActivity::class.java)) })
        right.addView(Ui.button(this, "🎮  Mandos y pantalla táctil") { startActivity(Intent(this, ControllerActivity::class.java)) })
        right.addView(Ui.button(this, "📊  Diagnóstico y logs") { startActivity(Intent(this, DiagnosticsActivity::class.java)) })
        right.addView(Ui.button(this, "❓  Cómo preparar la 3DS") { startActivity(Intent(this, HelpActivity::class.java)) })
        info = Ui.text(this, "", 16f).apply { setPadding(0, Ui.dp(context, 24f), 0, 0) }
        right.addView(info)

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.gravity = Gravity.TOP
        setContentView(Ui.scroll(this, root))
    }

    override fun onResume() {
        super.onResume()
        val s = app.loadSettings()
        if (ipField.text.isNullOrBlank()) ipField.setText(s.consoleIp)
        refresh()
    }

    private fun refresh() {
        val s = app.loadSettings()
        modeButton.text = if (s.connectMode == ConnectMode.ACTIVE) {
            "Modo: Conectar a la consola (recomendado)"
        } else {
            "Modo: Esperar a que la consola emita (pasivo)"
        }
        val local = NetworkInfo.local(this)
        info.text = buildString {
            if (local == null) {
                append("Sin red Wi-Fi/Ethernet. Conecta el Chromecast a la misma red que la 3DS.")
            } else {
                append("IP de este dispositivo (Viewer IP para NTR-HR): ${local.ip}/${local.prefixLength} (${local.transport})\n")
                append("Consola: ${s.consoleIp.ifBlank { "—" }} · vídeo JPEG Compat por UDP ${s.viewerPort}\n")
                append("Puertos: TCP 8000 → consola, UDP ${s.viewerPort} ← vídeo, UDP 4950 → mandos\n")
                append("Reliable Stream / Lossless de NTR-HR: no soportados (elige JPEG UDP)\n")
                append("Input Redirection: ${if (s.inputRedirection) "activado (actívalo también en Rosalina)" else "desactivado"}")
            }
        }
        status.text = ""
    }

    private fun toggleMode() {
        val s = app.loadSettings()
        app.saveSettings(s.copy(connectMode = if (s.connectMode == ConnectMode.ACTIVE) ConnectMode.PASSIVE else ConnectMode.ACTIVE))
        refresh()
    }

    private fun connect() {
        val s = app.loadSettings()
        val ip = ipField.text.toString().trim()
        val local = NetworkInfo.local(this)
        if (ip.isEmpty() && s.connectMode == ConnectMode.PASSIVE) {
            app.saveSettings(s.copy(consoleIp = ""))
            startActivity(Intent(this, StreamActivity::class.java))
            return
        }
        if (!Ipv4.isValidHostAddress(ip)) {
            status.text = "IP no válida. Usa el formato 192.168.1.50 (la ves en el menú NTR-HR con X+Y)."
            ipField.requestFocus()
            return
        }
        if (local != null && !Ipv4.sameSubnet(ip, local.ip, local.prefixLength)) {
            status.text = "Aviso: $ip no está en la subred de este dispositivo (${local.ip}/${local.prefixLength}). Intentando igualmente…"
            AppLog.w(status.text.toString())
        }
        app.saveSettings(s.copy(consoleIp = ip))
        startActivity(Intent(this, StreamActivity::class.java))
    }
}
