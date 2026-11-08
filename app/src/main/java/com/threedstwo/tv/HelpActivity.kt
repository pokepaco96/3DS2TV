package com.threedstwo.tv

import android.app.Activity
import android.os.Bundle
import com.threedstwo.tv.ui.Ui

/** Short on-TV version of docs/SETUP_3DS.md. */
class HelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this).apply {
            val p = Ui.dp(context, 40f)
            setPadding(p * 3, p, p * 3, p)
        }
        col.addView(Ui.title(this, "Preparar la New 3DS"))
        val steps = listOf(
            "1. Necesitas una New 3DS / New 3DS XL / New 2DS XL con Luma3DS actualizado (guía oficial: 3ds.hacks.guide). Las 3DS/2DS originales no están soportadas por NTR-HR.",
            "2. Instala NTR-HR (github.com/xzn/ntr-hr, Releases) siguiendo su README. En New 3DS usa la versión normal, NO la Mode3.",
            "3. Conecta la 3DS a la misma red Wi-Fi que este Chromecast (no uses red de invitados ni aislamiento de clientes).",
            "4. Arranca NTR-HR (BootNTRSelector) y abre su menú con X+Y. En Remote Play verás la IP de la consola.",
            "5. Opcional para jugar con mando: abre Rosalina (L+↓+Select) → Miscellaneous options → Start InputRedirection.",
            "6. En 3DS2TV escribe la IP de la 3DS y pulsa Conectar. 3DS2TV pide a NTR-HR el modo JPEG por UDP.",
            "Modo pasivo: en el menú NTR-HR (X+Y → Remote Play) pon Viewer IP = la IP de este Chromecast (se muestra en la pantalla de inicio), formato JPEG y protocolo UDP, e inicia Remote Play.",
            "Limitaciones: juegos de DS/DSiWare/GBA no se pueden emitir. Algunos juegos no son compatibles. El audio solo funciona con NTR-HR 0.3.8+ y juegos con audio DSP (CSND no soportado).",
            "Si Pokémon (USUM, etc.) se cuelga al cargar partida con Remote Play: activa Input Redirection o el Debugger en Rosalina (recomendación de NTR-HR).",
            "Guía completa en el repositorio: docs/SETUP_3DS.md",
        )
        steps.forEach { col.addView(Ui.text(this, it, 20f, R.color.text)) }
        col.addView(Ui.button(this, "Volver") { finish() })
        setContentView(Ui.scroll(this, col))
        col.getChildAt(col.childCount - 1)?.requestFocus()
    }
}
