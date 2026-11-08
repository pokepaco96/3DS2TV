package com.threedstwo.tv

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.threedstwo.tv.core.ntr.PacketKind
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import com.threedstwo.tv.core.session.ConnectionChecklist
import com.threedstwo.tv.core.session.ConnectionState
import com.threedstwo.tv.core.session.NtrSession
import com.threedstwo.tv.core.session.SessionConfig
import com.threedstwo.tv.core.util.Ipv4
import com.threedstwo.tv.net.NetworkInfo
import com.threedstwo.tv.ui.OptionList
import com.threedstwo.tv.ui.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Network facts, a connection test, last session metrics and the in-app log, exportable to a file.
 * The connection test runs a real session without drawing and decodes received JPEGs with BitmapFactory;
 * it reports success ONLY when complete frames decode.
 */
class DiagnosticsActivity : Activity() {
    private lateinit var body: TextView
    private lateinit var result: TextView
    private lateinit var testText: TextView
    private var hideIps = true
    private val main = Handler(Looper.getMainLooper())
    private var testSession: NtrSession? = null
    @Volatile private var decodedOk = 0
    @Volatile private var decodeFailed = 0
    @Volatile private var lastFrameSize = ""
    private var testEndsAt = 0L
    private val app get() = ThreeDsTvApp.of(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this).apply {
            val p = Ui.dp(context, 40f)
            setPadding(p * 2, p, p * 2, p)
        }
        col.addView(Ui.title(this, "Diagnóstico"))
        val m = OptionList(this, col)
        m.action("▶  Probar conexión con la consola (12 s)") { startTest() }
        testText = Ui.text(this, "", 18f, R.color.text)
        col.addView(testText)
        m.action("Actualizar informe") { refresh() }
        m.toggle("Ocultar IPs al exportar", { hideIps }) { hideIps = it }
        m.action("Exportar informe a archivo") { export() }
        m.action("Borrar registro del último cierre inesperado") { app.clearLastCrash(); refresh() }
        result = Ui.text(this, "", 16f, R.color.ok)
        col.addView(result)
        m.note("Puertos: TCP 8000 (este dispositivo → 3DS, orden Remote Play) · UDP vídeo (3DS → este dispositivo, 8001 por defecto) · UDP 4950 (este dispositivo → 3DS, Input Redirection). Todo dentro de tu red local.")
        body = Ui.text(this, "", 15f, R.color.text).apply { typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(body)
        setContentView(Ui.scroll(this, col))
        refresh()
        col.getChildAt(1)?.requestFocus()
    }

    override fun onStop() {
        stopTest()
        super.onStop()
    }

    // ------------------------------------------------------------ connection test

    private fun startTest() {
        stopTest()
        val s = app.loadSettings()
        val ip = s.consoleIp.takeIf { it.isNotBlank() }
        if (s.connectMode == com.threedstwo.tv.core.session.ConnectMode.ACTIVE && (ip == null || !Ipv4.isValidHostAddress(ip))) {
            testText.text = "Escribe primero una IP válida de la 3DS en la pantalla de inicio."
            return
        }
        decodedOk = 0; decodeFailed = 0; lastFrameSize = ""
        val opts = BitmapFactory.Options()
        val session = NtrSession(
            SessionConfig(consoleIp = ip, mode = s.connectMode, request = s.remotePlayRequest().copy(audio = false), maxReconnectAttempts = 2),
            object : NtrSession.Listener {
                override fun onStateChanged(state: ConnectionState, detail: String?) {
                    AppLog.i("Prueba: ${state.label}${detail?.let { " — $it" } ?: ""}")
                }
                override fun onVideoFrame(info: VideoFrameInfo, data: ByteArray, length: Int) {
                    if (decodedOk + decodeFailed >= 30) return // a few frames are enough
                    val bmp = BitmapFactory.decodeByteArray(data, 0, length, opts)
                    if (bmp != null && bmp.width == info.jpegWidth && bmp.height == info.jpegHeight) {
                        decodedOk++
                        lastFrameSize = "${info.screen} ${bmp.width}x${bmp.height}"
                        bmp.recycle()
                    } else {
                        decodeFailed++
                    }
                }
                override fun onLog(message: String) = AppLog.i("Prueba: $message")
            },
        )
        testSession = session
        AppLog.i("Prueba de conexión: modo=${s.connectMode} consola=${ip ?: "(aprender)"} puerto=${s.viewerPort}")
        session.start()
        testEndsAt = System.currentTimeMillis() + 12_000
        main.post(testTicker)
    }

    private fun stopTest() {
        main.removeCallbacks(testTicker)
        testSession?.stop()
        testSession = null
    }

    private val testTicker = object : Runnable {
        override fun run() {
            val s = testSession ?: return
            val done = System.currentTimeMillis() >= testEndsAt || s.state == ConnectionState.ERROR
            val items = ConnectionChecklist.evaluate(
                ConnectionChecklist.Inputs(
                    mode = s.config.mode, state = s.state, diag = s.diagnostics,
                    framesCompleted = s.assemblerStats.framesCompleted,
                    recentFrames = s.state == ConnectionState.STREAMING,
                    framesPresented = decodedOk.toLong(), // here "presented" = decoded correctly (nothing is drawn)
                    inputRedirectionEnabled = false, inputRedirectionTarget = null,
                    unsupported = when (s.unsupportedKind) {
                        PacketKind.RELIABLE_STREAM -> "Reliable Stream (no soportado): en NTR-HR elige JPEG UDP"
                        PacketKind.VIDEO_LOSSLESS -> "Lossless (no soportado): en NTR-HR elige JPEG UDP"
                        else -> null
                    },
                ),
            ).filter { !it.label.startsWith("Input") }
            val verdict = when {
                decodedOk > 0 && s.state == ConnectionState.STREAMING ->
                    "RESULTADO: OK — $decodedOk frames completos decodificados ($lastFrameSize)."
                done -> "RESULTADO: FALLO — " + (s.stateDetail ?: "no llegaron frames completos")
                else -> "Probando… ${(testEndsAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000} s"
            }
            testText.text = "$verdict\n" + ConnectionChecklist.format(items).replace("Imagen presentada en pantalla", "JPEG decodificado correctamente") +
                if (decodeFailed > 0) "\n✖ $decodeFailed JPEG no decodificables" else ""
            if (done || (decodedOk >= 10 && s.state == ConnectionState.STREAMING)) {
                AppLog.i(verdict)
                stopTest()
                refresh()
            } else {
                main.postDelayed(this, 300)
            }
        }
    }

    // ------------------------------------------------------------ report

    private fun report(): String = buildString {
        val s = app.loadSettings()
        appendLine("3DS2TV ${BuildInfo.versionName(this@DiagnosticsActivity)} · commit ${BuildConfig.GIT_COMMIT}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DEVICE}")
        val nets = NetworkInfo.candidates(this@DiagnosticsActivity)
        if (nets.isEmpty()) appendLine("Red: sin red Wi-Fi/Ethernet")
        nets.forEachIndexed { i, n -> appendLine("Red${if (i == 0) " (usada)" else ""}: ${n.ip}/${n.prefixLength} por ${n.transport} (${n.iface ?: "?"})") }
        appendLine("Consola: ${s.consoleIp.ifBlank { "(sin IP)" }} · modo ${s.connectMode} · UDP vídeo ${s.viewerPort}")
        appendLine("Calidad ${s.jpegQuality} · QoS ${s.qosMbps} Mbps · prioridad ${if (s.topPriority) "superior" else "inferior"} x${s.priorityFactor} · audio ${s.audioEnabled} · input ${s.inputRedirection}")
        appendLine("Pantallas ${s.layout.viewMode} ${s.layout.arrangement} inferiorPrimero=${s.layout.bottomFirst} rot=${s.layout.topRotation.degrees}/${s.layout.bottomRotation.degrees} filtro=${s.filter}")
        appendLine()
        val st = app.lastStats
        if (st != null) {
            appendLine("— Última sesión —")
            appendLine(StreamActivity.formatStats(st))
        } else {
            appendLine("Sin sesión todavía.")
        }
        app.lastCrash()?.let {
            appendLine()
            appendLine("— Último cierre inesperado —")
            appendLine(it.take(6000))
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
            val text = report()
            f.writeText(if (hideIps) Ipv4.mask(text) else text)
            AppLog.i("Informe exportado (${if (hideIps) "IPs ocultas" else "con IPs"}) a ${f.absolutePath}")
            result.text = "Guardado en ${f.absolutePath}\nDescárgalo con:  adb pull \"${f.absolutePath}\"" +
                if (hideIps) "\nLas IPs aparecen como 192.168.x.x." else "\nIncluye las IPs completas de tu red local."
        } catch (e: Exception) {
            result.text = "No se pudo exportar: ${e.message}"
        }
    }
}
