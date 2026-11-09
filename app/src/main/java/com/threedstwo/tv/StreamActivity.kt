package com.threedstwo.tv

import android.app.Activity
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.threedstwo.tv.core.input.Action
import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.input.InputMapper
import com.threedstwo.tv.core.input.InputRedirectionSender
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.input.PadSnapshot
import com.threedstwo.tv.core.input.StickMath
import com.threedstwo.tv.core.input.StickSettings
import com.threedstwo.tv.core.layout.Arrangement
import com.threedstwo.tv.core.layout.Rotation
import com.threedstwo.tv.core.layout.TouchCursor
import com.threedstwo.tv.core.layout.ViewMode
import com.threedstwo.tv.core.ntr.FrameMailbox
import com.threedstwo.tv.core.ntr.PacketKind
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.session.CheckItem
import com.threedstwo.tv.core.session.ConnectionChecklist
import com.threedstwo.tv.core.session.ConnectionState
import com.threedstwo.tv.core.session.SessionDiagnostics
import com.threedstwo.tv.core.session.NtrSession
import com.threedstwo.tv.core.session.RateMeter
import com.threedstwo.tv.core.session.SessionConfig
import com.threedstwo.tv.core.session.StreamStats
import com.threedstwo.tv.core.settings.AppSettings
import com.threedstwo.tv.core.settings.CursorStick
import com.threedstwo.tv.core.settings.ScalingFilter
import com.threedstwo.tv.input.GamepadTracker
import com.threedstwo.tv.net.NetworkInfo
import com.threedstwo.tv.stream.AudioPlayer
import com.threedstwo.tv.stream.FrameDecoder
import com.threedstwo.tv.stream.ScreenBuffers
import com.threedstwo.tv.stream.StreamRenderer
import com.threedstwo.tv.ui.OptionList
import com.threedstwo.tv.ui.Ui
import java.util.Locale

/**
 * Full-screen session: NTR-HR video -> decode -> compose on the TV; gamepad -> Luma3DS input redirection.
 */
class StreamActivity : Activity(), NtrSession.Listener, InputManager.InputDeviceListener {
    private lateinit var app: ThreeDsTvApp
    private lateinit var settings: AppSettings
    private val main = Handler(Looper.getMainLooper())

    // pipeline
    private var session: NtrSession? = null
    private val mailbox = FrameMailbox()
    private val buffers = ScreenBuffers()
    private val renderer = StreamRenderer(buffers)
    private var decoder: FrameDecoder? = null
    private val audio = AudioPlayer()
    private var sender: InputRedirectionSender? = null

    // input
    private val tracker = GamepadTracker()
    private val remoteHeld = mutableSetOf<PadControl>()
    private val cursor = TouchCursor()
    private var cursorMode = false
    private var lastActions = InputMapper.AppActions(false, false, false)
    private var comboStart = 0L
    private var lastTickNanos = 0L

    // views
    private lateinit var surface: SurfaceView
    private lateinit var statusPanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var hud: TextView
    private lateinit var toast: TextView
    private lateinit var menuScroll: ScrollView
    private lateinit var menu: OptionList
    private var menuVisible = false

    // metrics
    private val fpsTop = RateMeter()
    private val fpsBottom = RateMeter()
    private val fpsShown = RateMeter()
    private val kbps = RateMeter()
    @Volatile private var framesTop = 0L
    @Volatile private var framesBottom = 0L

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = ThreeDsTvApp.of(this)
        settings = app.loadSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        applyVisualSettings()
        tracker.preferredDescriptor = settings.preferredController
    }

    // ------------------------------------------------------------------ views

    private fun buildViews() {
        val root = FrameLayout(this)
        surface = SurfaceView(this)
        surface.holder.addCallback(renderer)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hud = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA000000.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(Ui.dp(context, 10f), Ui.dp(context, 6f), Ui.dp(context, 10f), Ui.dp(context, 6f))
            visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(Ui.dp(this@StreamActivity, 24f), Ui.dp(this@StreamActivity, 16f), 0, 0)
        })

        statusPanel = Ui.column(this).apply {
            setBackgroundResource(R.drawable.panel_bg)
            val p = Ui.dp(context, 28f)
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        statusText = Ui.text(this, "", 24f, R.color.text).apply { gravity = Gravity.CENTER }
        statusPanel.addView(statusText)
        statusPanel.addView(Ui.button(this, "Reintentar") { restartSession() })
        statusPanel.addView(Ui.button(this, "Menú") { showMenu(true) })
        statusPanel.addView(Ui.button(this, "Volver al inicio") { finish() })
        root.addView(statusPanel, FrameLayout.LayoutParams(Ui.dp(this, 620f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        toast = Ui.text(this, "", 20f, R.color.text).apply {
            setBackgroundResource(R.drawable.panel_bg)
            val p = Ui.dp(context, 14f)
            setPadding(p * 2, p, p * 2, p)
            visibility = View.GONE
        }
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@StreamActivity, 40f)
        })

        val menuCol = Ui.column(this).apply {
            setBackgroundResource(R.drawable.panel_bg)
            val p = Ui.dp(context, 20f)
            setPadding(p, p, p, p)
        }
        menuScroll = Ui.scroll(this, menuCol).apply { visibility = View.GONE }
        root.addView(menuScroll, FrameLayout.LayoutParams(Ui.dp(this, 520f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            setMargins(0, Ui.dp(this@StreamActivity, 24f), Ui.dp(this@StreamActivity, 24f), Ui.dp(this@StreamActivity, 24f))
        })
        menu = OptionList(this, menuCol)
        buildMenu()
        setContentView(root)
        hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun update(change: (AppSettings) -> AppSettings) {
        settings = change(settings)
        app.saveSettings(settings)
        applyVisualSettings()
    }

    private fun applyVisualSettings() {
        renderer.layout = settings.layout
        if (cursorMode && settings.layout.viewMode == ViewMode.TOP_ONLY) {
            // Never send touches at a position the user cannot see.
            cursorMode = false
            renderer.cursor = null
        }
        renderer.smooth = settings.filter == ScalingFilter.BILINEAR
        hud.visibility = if (settings.showHud) View.VISIBLE else View.GONE
        audio.setVolume(settings.audioVolume / 100f)
    }

    private fun buildMenu() {
        val m = menu
        m.container.addView(Ui.title(this, "3DS2TV", 28f))
        m.action("Volver al juego") { showMenu(false) }
        m.header("Pantallas")
        m.choice("Mostrar", ViewMode.entries, { settings.layout.viewMode }, { it.label }) { v -> update { it.copy(layout = it.layout.copy(viewMode = v)) } }
        m.choice("Diseño", Arrangement.entries, { settings.layout.arrangement }, { it.label }) { v -> update { it.copy(layout = it.layout.copy(arrangement = v)) } }
        m.toggle("Inferior primero (intercambiar)", { settings.layout.bottomFirst }) { v -> update { it.copy(layout = it.layout.copy(bottomFirst = v)) } }
        m.choice("Rotación superior", Rotation.entries, { settings.layout.topRotation }, { "${it.degrees}°" }) { v -> update { it.copy(layout = it.layout.copy(topRotation = v)) } }
        m.choice("Rotación inferior", Rotation.entries, { settings.layout.bottomRotation }, { "${it.degrees}°" }) { v -> update { it.copy(layout = it.layout.copy(bottomRotation = v)) } }
        m.choice("Tamaño inferior", listOf(50, 75, 100, 125, 150, 200), { (settings.layout.bottomScale * 100).toInt() }, { "$it %" }) { v -> update { it.copy(layout = it.layout.copy(bottomScale = v / 100f)) } }
        m.choice("Tamaño superior", listOf(50, 75, 100, 125, 150, 200), { (settings.layout.topScale * 100).toInt() }, { "$it %" }) { v -> update { it.copy(layout = it.layout.copy(topScale = v / 100f)) } }
        m.intRange("Separación", 0..64 step 4, { settings.layout.gap }, " px") { v -> update { it.copy(layout = it.layout.copy(gap = v)) } }
        m.choice("Filtro", ScalingFilter.entries, { settings.filter }, { it.label }) { v -> update { it.copy(filter = v) } }
        m.toggle("Escalado entero (píxel perfecto)", { settings.layout.integerScale }) { v -> update { it.copy(layout = it.layout.copy(integerScale = v)) } }
        m.choice("Margen de pantalla", listOf(100, 95, 90, 85), { (settings.layout.fill * 100).toInt() }, { "$it %" }) { v -> update { it.copy(layout = it.layout.copy(fill = v / 100f)) } }

        m.header("Calidad (se envía a NTR-HR)")
        m.intRange("Calidad JPEG", 30..100 step 5, { settings.jpegQuality }) { v -> update { it.copy(jpegQuality = v) }; pushRequest() }
        m.intRange("Ancho de banda (QoS)", 4..20, { settings.qosMbps }, " Mbps") { v -> update { it.copy(qosMbps = v) }; pushRequest() }
        m.toggle("Prioridad pantalla superior", { settings.topPriority }) { v -> update { it.copy(topPriority = v) }; pushRequest() }
        m.intRange("Factor de prioridad", 0..4, { settings.priorityFactor }) { v -> update { it.copy(priorityFactor = v) }; pushRequest() }
        m.note("Factor 0 = solo se emite la pantalla prioritaria.")

        m.header("Audio (DSP, experimental)")
        m.toggle("Audio en el TV", { settings.audioEnabled }) { v -> update { it.copy(audioEnabled = v) }; applyAudio(); pushRequest() }
        m.intRange("Volumen", 0..100 step 10, { settings.audioVolume }, " %") { v -> update { it.copy(audioVolume = v) } }

        m.header("Controles")
        m.toggle("Input Redirection (Luma3DS)", { settings.inputRedirection }) { v -> update { it.copy(inputRedirection = v) }; applyInputRedirection() }
        m.toggle("Cursor táctil activo", { cursorMode }) { v -> setCursorMode(v) }
        m.choice("Cursor se mueve con", CursorStick.entries, { settings.cursorStick }, { it.label }) { v -> update { it.copy(cursorStick = v) } }
        m.toggle("Mando de TV como control básico", { settings.tvRemoteAsPad }) { v -> update { it.copy(tvRemoteAsPad = v) } }
        m.action("Configurar mando…") { startActivity(android.content.Intent(this, ControllerActivity::class.java)) }

        m.header("Sesión")
        m.toggle("HUD de diagnóstico", { settings.showHud }) { v -> update { it.copy(showHud = v) } }
        m.action("Diagnóstico completo…") { startActivity(android.content.Intent(this, DiagnosticsActivity::class.java)) }
        m.action("Reconectar") { restartSession(); showMenu(false) }
        m.action("Desconectar y salir") { finish() }
    }

    private fun showMenu(show: Boolean) {
        menuVisible = show
        menuScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            menu.refreshAll()
            sender?.neutralize()
            menuScroll.post { (menu.container.getChildAt(1))?.requestFocus() }
        } else {
            surface.requestFocus()
            hideSystemUi()
        }
    }

    private fun showToast(text: String, ms: Long = 2500) {
        toast.text = text
        toast.visibility = View.VISIBLE
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, ms)
    }

    private val hideToast = Runnable { toast.visibility = View.GONE }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        settings = app.loadSettings()
        tracker.preferredDescriptor = settings.preferredController
        applyVisualSettings()
        menu.refreshAll()
        decoder = FrameDecoder(mailbox, buffers).also { it.start() }
        renderer.start()
        startSession()
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(this, main)
        registerNetworkCallback()
        lastTickNanos = System.nanoTime()
        main.post(tick)
        main.post(hudUpdater)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        main.removeCallbacks(hudUpdater)
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(this)
        unregisterNetworkCallback()
        stopSession()
        renderer.stop()
        decoder?.stop()
        decoder = null
        SessionProbe.state = ConnectionState.DISCONNECTED
        super.onStop()
    }

    override fun onPause() {
        // Never leave buttons pressed on the console when we lose focus.
        tracker.reset()
        remoteHeld.clear()
        sender?.neutralize()
        super.onPause()
    }

    private fun startSession() {
        val ip = settings.consoleIp.takeIf { it.isNotBlank() }
        val cfg = SessionConfig(
            consoleIp = ip,
            mode = settings.connectMode,
            request = settings.remotePlayRequest(),
            maxReconnectAttempts = settings.maxReconnectAttempts,
        )
        framesTop = 0; framesBottom = 0
        buffers.clear()
        mailbox.clear()
        AppLog.i("Sesión: modo=${cfg.mode} consola=${ip ?: "(aprender)"} puerto UDP=${cfg.viewerPort} calidad=${cfg.request.jpegQuality} QoS=${cfg.request.qosMbps}Mbps audio=${cfg.request.audio}")
        session = NtrSession(cfg, this).also { it.start() }
        applyAudio()
        applyInputRedirection()
    }

    private fun stopSession() {
        sender?.stop()
        sender = null
        session?.stop()
        session = null
        audio.stop()
        app.lastStats = buildStats()
    }

    private fun restartSession() {
        stopSession()
        mailbox.clear()
        startSession()
    }

    private fun pushRequest() {
        val s = session ?: return
        s.updateRequest(settings.remotePlayRequest())
        if (s.diagnostics.tcpConnects == 0L) {
            // Without the TCP control channel NTR-HR never receives these values.
            showToast("Sin conexión TCP con NTR-HR: este cambio no llega a la consola. Cámbialo en la 3DS (X+Y → Remote Play).", 5000)
        }
    }

    private fun applyAudio() {
        if (settings.audioEnabled) audio.start(settings.audioVolume / 100f) else audio.stop()
    }

    private fun applyInputRedirection() {
        val ip = session?.consoleIp() ?: settings.consoleIp.takeIf { it.isNotBlank() }
        if (settings.inputRedirection && ip != null) {
            if (sender?.consoleAddress != ip) {
                sender?.stop()
                sender = try {
                    InputRedirectionSender(ip).also { it.start() }
                } catch (e: Exception) {
                    AppLog.e("Input redirection", e)
                    null
                }
                AppLog.i("Input Redirection → $ip:${InputFrame.PORT}")
            }
        } else {
            sender?.stop()
            sender = null
        }
    }

    // ------------------------------------------------------------------ session callbacks (background threads)

    override fun onStateChanged(state: ConnectionState, detail: String?) {
        AppLog.i("Estado: ${state.label}${detail?.let { " — $it" } ?: ""}")
        main.post { renderState(state, detail) }
    }

    override fun onVideoFrame(info: VideoFrameInfo, data: ByteArray, length: Int) {
        if (info.screen == Screen.TOP) framesTop++ else framesBottom++
        mailbox.offer(info, data, length)
    }

    override fun onAudioFrame(pcm: ByteArray, offset: Int, silenceFramesBefore: Int) {
        audio.write(pcm, offset, silenceFramesBefore)
    }

    override fun onConsoleAddressLearned(ip: String) {
        AppLog.i("Consola detectada en $ip")
        main.post {
            update { it.copy(consoleIp = ip) }
            applyInputRedirection()
            showToast("Consola detectada: $ip")
        }
    }

    override fun onLog(message: String) = AppLog.i(message)

    private fun renderState(state: ConnectionState, detail: String?) {
        if (state == ConnectionState.STREAMING) {
            statusPanel.visibility = View.GONE
            if (!menuVisible) surface.requestFocus()
            return
        }
        statusPanel.visibility = View.VISIBLE
        val color = when (state) {
            ConnectionState.ERROR -> R.color.error
            ConnectionState.RECONNECTING -> R.color.warn
            else -> R.color.text
        }
        statusText.setTextColor(getColor(color))
        val ip = settings.consoleIp.ifBlank { "—" }
        statusText.text = buildString {
            append(state.label)
            if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) append("\n3DS: $ip")
            detail?.let { append("\n\n").append(it) }
            if (state == ConnectionState.SEARCHING) {
                NetworkInfo.local(this@StreamActivity)?.let { append("\n\nViewer IP para NTR-HR: ${it.ip}  ·  puerto ${settings.viewerPort}") }
            }
        }
        statusText.append("\n\n" + ConnectionChecklist.format(checklist()))
        if (!menuVisible && statusPanel.findFocus() == null) statusPanel.getChildAt(1)?.requestFocus()
    }

    private fun checklist(): List<CheckItem> {
        val s = session
        return ConnectionChecklist.evaluate(
            ConnectionChecklist.Inputs(
                mode = settings.connectMode,
                state = s?.state ?: ConnectionState.DISCONNECTED,
                diag = s?.diagnostics ?: SessionDiagnostics(),
                framesCompleted = s?.assemblerStats?.framesCompleted ?: 0,
                recentFrames = s?.state == ConnectionState.STREAMING,
                framesPresented = renderer.presentedFrames,
                inputRedirectionEnabled = settings.inputRedirection,
                inputRedirectionTarget = sender?.consoleAddress,
                unsupported = when (s?.unsupportedKind) {
                    PacketKind.RELIABLE_STREAM -> "Reliable Stream (no soportado): en NTR-HR elige JPEG UDP"
                    PacketKind.VIDEO_LOSSLESS -> "Lossless/Uncompressed (no soportado): en NTR-HR elige JPEG UDP"
                    else -> null
                },
            ),
        )
    }

    // ------------------------------------------------------------------ input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isGamepad = GamepadTracker.isGameControllerEvent(event)
        if (menuVisible || statusPanel.visibility == View.VISIBLE) {
            if (isGamepad) {
                // Menu hot key closes the menu; everything else navigates (Android maps A->select, B->back).
                val c = GamepadTracker.controlFor(code)
                if (c != null && settings.mapping.actionFor(c) == Action.OPEN_MENU && menuVisible) {
                    if (event.action == KeyEvent.ACTION_UP) showMenu(false)
                    return true
                }
                if (code == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP && menuVisible) {
                    showMenu(false)
                    return true
                }
            }
            if (code == KeyEvent.KEYCODE_BACK && menuVisible) {
                if (event.action == KeyEvent.ACTION_UP) showMenu(false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (isGamepad && tracker.onKey(event)) {
            onPadChanged()
            return true
        }
        // TV remote / keyboard
        if (settings.tvRemoteAsPad) {
            val c = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> PadControl.DPAD_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> PadControl.DPAD_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> PadControl.DPAD_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> PadControl.DPAD_RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> PadControl.EAST // 3DS "A" position
                else -> null
            }
            if (c != null) {
                if (event.action == KeyEvent.ACTION_DOWN) remoteHeld += c else if (event.action == KeyEvent.ACTION_UP) remoteHeld -= c
                onPadChanged()
                return true
            }
        }
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) showMenu(true)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!menuVisible && tracker.onMotion(event)) {
            onPadChanged()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun padSnapshot(): PadSnapshot {
        var p = tracker.snapshot()
        if (remoteHeld.isNotEmpty()) p = p.copy(held = p.held + remoteHeld)
        return p
    }

    /** Maps the current controller state and sends it (called on every input event and every tick). */
    private fun onPadChanged() {
        if (menuVisible) return
        var pad = padSnapshot()
        val s = settings
        var mapping = s.mapping
        val usesRight = cursorMode && s.cursorStick == CursorStick.RIGHT_STICK
        if (cursorMode) {
            mapping = mapping.with(s.touchButton, Action.TOUCH)
            when (s.cursorStick) {
                CursorStick.LEFT_STICK -> pad = pad.copy(leftX = 0f, leftY = 0f)
                CursorStick.DPAD -> pad = pad.copy(
                    held = pad.held - setOf(PadControl.DPAD_UP, PadControl.DPAD_DOWN, PadControl.DPAD_LEFT, PadControl.DPAD_RIGHT),
                    hatX = 0f, hatY = 0f,
                )
                CursorStick.RIGHT_STICK -> {}
            }
        }
        val r = InputMapper.map(
            pad, mapping,
            StickSettings(s.circleDeadzone, s.circleSensitivity),
            StickSettings(s.cStickDeadzone, s.cStickSensitivity),
            s.allowHome, usesRight, if (cursorMode) cursor.touchPoint() else null,
        )
        // Edge-triggered app actions
        if (r.app.openMenu && !lastActions.openMenu) {
            lastActions = r.app
            sender?.neutralize()
            showMenu(true)
            return
        }
        if (r.app.toggleTouchCursor && !lastActions.toggleTouchCursor) setCursorMode(!cursorMode)
        lastActions = r.app
        // Select + Start held for 1 s also opens the menu
        val held = pad.effectiveHeld()
        val combo = PadControl.SELECT in held && PadControl.START in held
        if (combo) {
            if (comboStart == 0L) comboStart = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - comboStart > 1000) {
                comboStart = 0L
                sender?.neutralize()
                showMenu(true)
                return
            }
        } else comboStart = 0L

        renderer.cursorPressed = r.app.touchPressed && cursorMode
        sender?.update(r.frame)
    }

    private fun setCursorMode(on: Boolean) {
        if (on && settings.layout.viewMode == ViewMode.TOP_ONLY) {
            showToast("El cursor táctil necesita ver la pantalla inferior")
            return
        }
        if (!settings.touchCursorEnabled && on) return
        cursorMode = on
        renderer.cursor = if (on) cursor.x to cursor.y else null
        showToast(if (on) "Cursor táctil ON: mueve con ${settings.cursorStick.label}, toca con ${settings.touchButton.label}" else "Cursor táctil OFF")
    }

    /** ~60 Hz: moves the touch cursor and refreshes the state sent to the console. */
    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastTickNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastTickNanos = now
            if (cursorMode && !menuVisible) {
                val p = padSnapshot()
                val (dx, dy) = when (settings.cursorStick) {
                    CursorStick.RIGHT_STICK -> StickMath.shape(p.rightX, p.rightY, 0.15f, 1f)
                    CursorStick.LEFT_STICK -> StickMath.shape(p.leftX, p.leftY, 0.15f, 1f)
                    CursorStick.DPAD -> {
                        val h = p.effectiveHeld()
                        val x = (if (PadControl.DPAD_RIGHT in h) 1f else 0f) - (if (PadControl.DPAD_LEFT in h) 1f else 0f)
                        val y = (if (PadControl.DPAD_DOWN in h) 1f else 0f) - (if (PadControl.DPAD_UP in h) 1f else 0f)
                        x to y
                    }
                }
                if (dx != 0f || dy != 0f) {
                    cursor.move(dx, dy, dt, settings.cursorSpeed.toFloat(), settings.layout.bottomRotation)
                    renderer.cursor = cursor.x to cursor.y
                    onPadChanged()
                }
            }
            main.postDelayed(this, 16)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val d = InputDevice.getDevice(deviceId) ?: return
        if (GamepadTracker.isGameController(d)) {
            AppLog.i("Mando conectado: ${GamepadTracker.describe(d)}")
            showToast("Mando conectado: ${d.name}")
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (tracker.onDeviceRemoved(deviceId)) {
            sender?.neutralize() // release every button on the 3DS immediately
            AppLog.w("Mando activo desconectado; entradas liberadas")
            showToast("Mando desconectado: botones liberados")
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    // ------------------------------------------------------------------ network & HUD

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                AppLog.w("Red perdida")
                main.post { showToast("Red perdida: esperando reconexión…", 4000) }
            }

            override fun onAvailable(network: Network) {
                AppLog.i("Red disponible")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            AppLog.e("NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        netCallback = null
    }

    private fun buildStats(): StreamStats {
        val s = session
        val now = System.nanoTime()
        val st = s?.assemblerStats
        val audioState = when {
            !settings.audioEnabled -> "desactivado"
            audio.error != null -> "error: ${audio.error}"
            (s?.audioPackets ?: 0) == 0L -> "activado, sin paquetes (¿NTR-HR < 0.3.8 o juego CSND?)"
            else -> "recibiendo · perdidos ${s?.audioFramesLost} · descartados ${audio.droppedFrames}"
        }
        val inputState = when {
            !settings.inputRedirection -> "desactivado"
            sender == null -> "sin IP de consola"
            sender?.lastError != null -> "error: ${sender?.lastError}"
            else -> "→ ${sender?.consoleAddress}:4950 · ${sender?.packetsSent} enviados (UDP sin confirmación)" + if (cursorMode) " · cursor táctil" else ""
        }
        val controller = tracker.activeDeviceName ?: "ninguno activo (pulsa un botón)"
        val transport = when (s?.unsupportedKind) {
            PacketKind.RELIABLE_STREAM -> "Reliable Stream detectado (no soportado)"
            PacketKind.VIDEO_LOSSLESS -> "Lossless detectado (no soportado)"
            else -> "JPEG Compat · UDP ${settings.viewerPort}"
        }
        return StreamStats(
            state = s?.state ?: ConnectionState.DISCONNECTED,
            transport = transport,
            receivedFpsTop = fpsTop.update(framesTop, now),
            receivedFpsBottom = fpsBottom.update(framesBottom, now),
            displayedFps = fpsShown.update(renderer.presentedFrames, now),
            kbps = kbps.update(st?.bytes ?: 0, now) * 8 / 1000,
            framesIncomplete = st?.framesIncomplete ?: 0,
            framesDroppedBeforeDecode = mailbox.droppedBeforeDecode,
            framesDecodeErrors = decoder?.errors ?: 0,
            invalidPackets = st?.invalidPackets ?: 0,
            decodeMsAvg = decoder?.decodeMs?.average(),
            receiveToDisplayMsAvg = renderer.receiveToDisplayMs.average(),
            audio = audioState,
            input = inputState,
            controller = controller,
        )
    }

    private val hudUpdater = object : Runnable {
        override fun run() {
            val st = buildStats()
            app.lastStats = st
            SessionProbe.state = st.state
            SessionProbe.presentedFrames = renderer.presentedFrames
            SessionProbe.decodedFrames = decoder?.decoded ?: 0
            SessionProbe.decodeErrors = decoder?.errors ?: 0
            SessionProbe.surfaceWidth = surface.width
            if (settings.showHud) hud.text = formatStats(st) + "\n\n" + ConnectionChecklist.format(checklist())
            // keep the checklist in the status panel live while not streaming
            if (statusPanel.visibility == View.VISIBLE) session?.let { renderState(it.state, it.stateDetail) }
            main.postDelayed(this, 500)
        }
    }

    companion object {
        fun formatStats(st: StreamStats): String = buildString {
            fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            appendLine("Estado: ${st.state.label}  ·  ${st.transport}")
            appendLine("FPS recibidos  sup ${f(st.receivedFpsTop)}  inf ${f(st.receivedFpsBottom)}")
            appendLine("FPS mostrados (frames nuevos) ${f(st.displayedFps)}")
            appendLine("Bitrate ${f(st.kbps)} kbit/s")
            appendLine("Frames incompletos ${st.framesIncomplete}  descartados ${st.framesDroppedBeforeDecode}  errores ${st.framesDecodeErrors}")
            appendLine("Decodificación ${f(st.decodeMsAvg)} ms  ·  recepción→pantalla ${f(st.receiveToDisplayMsAvg)} ms")
            appendLine("Audio: ${st.audio}")
            appendLine("Input: ${st.input}")
            append("Mando: ${st.controller}")
        }
    }
}

