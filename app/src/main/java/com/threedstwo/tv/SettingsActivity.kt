package com.threedstwo.tv

import android.app.Activity
import android.os.Bundle
import com.threedstwo.tv.core.layout.Arrangement
import com.threedstwo.tv.core.layout.Rotation
import com.threedstwo.tv.core.layout.ViewMode
import com.threedstwo.tv.core.settings.AppSettings
import com.threedstwo.tv.core.settings.ScalingFilter
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.ui.OptionList
import com.threedstwo.tv.ui.Ui

class SettingsActivity : Activity() {
    private lateinit var s: AppSettings
    private val app get() = ThreeDsTvApp.of(this)

    private fun update(f: (AppSettings) -> AppSettings) {
        s = f(s)
        app.saveSettings(s)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = app.loadSettings()
        val col = Ui.column(this).apply {
            val p = Ui.dp(context, 48f)
            setPadding(p * 3, p, p * 3, p)
        }
        val m = OptionList(this, col)
        col.addView(Ui.title(this, "Ajustes"))
        m.header("Pantallas")
        m.choice("Mostrar", ViewMode.entries, { s.layout.viewMode }, { it.label }) { v -> update { it.copy(layout = it.layout.copy(viewMode = v)) } }
        m.choice("Diseño con ambas", Arrangement.entries, { s.layout.arrangement }, { it.label }) { v -> update { it.copy(layout = it.layout.copy(arrangement = v)) } }
        m.toggle("Inferior primero (orden inverso)", { s.layout.bottomFirst }) { v -> update { it.copy(layout = it.layout.copy(bottomFirst = v)) } }
        m.choice("Rotación superior", Rotation.entries, { s.layout.topRotation }, { "${it.degrees}°" }) { v -> update { it.copy(layout = it.layout.copy(topRotation = v)) } }
        m.choice("Rotación inferior", Rotation.entries, { s.layout.bottomRotation }, { "${it.degrees}°" }) { v -> update { it.copy(layout = it.layout.copy(bottomRotation = v)) } }
        m.choice("Tamaño superior", listOf(50, 75, 100, 125, 150, 200), { (s.layout.topScale * 100).toInt() }, { "$it %" }) { v -> update { it.copy(layout = it.layout.copy(topScale = v / 100f)) } }
        m.choice("Tamaño inferior", listOf(50, 75, 100, 125, 150, 200), { (s.layout.bottomScale * 100).toInt() }, { "$it %" }) { v -> update { it.copy(layout = it.layout.copy(bottomScale = v / 100f)) } }
        m.intRange("Separación entre pantallas", 0..64 step 4, { s.layout.gap }, " px") { v -> update { it.copy(layout = it.layout.copy(gap = v)) } }
        m.choice("Filtro de escalado", ScalingFilter.entries, { s.filter }, { it.label }) { v -> update { it.copy(filter = v) } }
        m.toggle("Escalado entero (píxel perfecto)", { s.layout.integerScale }) { v -> update { it.copy(layout = it.layout.copy(integerScale = v)) } }
        m.choice("Margen de pantalla (overscan)", listOf(100, 95, 90, 85), { (s.layout.fill * 100).toInt() }, { "$it %" }) { v -> update { it.copy(layout = it.layout.copy(fill = v / 100f)) } }
        m.note("La imagen nunca se estira: se conserva la proporción de cada pantalla con bandas negras.")

        m.header("Streaming (parámetros que acepta NTR-HR)")
        m.choice("Conexión", ConnectMode.entries, { s.connectMode }, {
            if (it == ConnectMode.ACTIVE) "Conectar a la consola (TCP 8000)" else "Pasivo: la consola inicia Remote Play"
        }) { v -> update { it.copy(connectMode = v) } }
        m.intRange("Calidad JPEG", 30..100 step 5, { s.jpegQuality }) { v -> update { it.copy(jpegQuality = v) } }
        m.note("Recomendado 75–90. Más calidad = menos FPS.")
        m.intRange("Ancho de banda (QoS)", 4..20, { s.qosMbps }, " Mbps") { v -> update { it.copy(qosMbps = v) } }
        m.toggle("Prioridad pantalla superior", { s.topPriority }) { v -> update { it.copy(topPriority = v) } }
        m.intRange("Factor de prioridad", 0..4, { s.priorityFactor }) { v -> update { it.copy(priorityFactor = v) } }
        m.note("La pantalla prioritaria recibe N frames por cada frame de la otra. 0 = solo la prioritaria (máximos FPS).")
        m.choice("Puerto UDP de vídeo", listOf(8001, 8002, 8003, 8004), { s.viewerPort }) { v -> update { it.copy(viewerPort = v) } }
        m.intRange("Reintentos de reconexión", 1..20, { s.maxReconnectAttempts }) { v -> update { it.copy(maxReconnectAttempts = v) } }

        m.header("Audio (experimental, NTR-HR 0.3.8 o superior)")
        m.toggle("Pedir audio DSP a la consola", { s.audioEnabled }) { v -> update { it.copy(audioEnabled = v) } }
        m.intRange("Volumen en 3DS2TV", 0..100 step 10, { s.audioVolume }, " %") { v -> update { it.copy(audioVolume = v) } }
        m.note("Solo audio DSP. Los juegos que usan CSND no tendrán audio. El vídeo funciona igual sin audio.")

        m.header("Diagnóstico")
        m.toggle("Mostrar HUD durante el juego", { s.showHud }) { v -> update { it.copy(showHud = v) } }
        m.action("Restablecer todos los ajustes") {
            val ip = s.consoleIp
            s = AppSettings(consoleIp = ip)
            app.saveSettings(s)
            m.refreshAll()
        }
        setContentView(Ui.scroll(this, col))
        col.getChildAt(2)?.requestFocus()
    }
}
