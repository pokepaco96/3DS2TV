package com.threedstwo.tv

import android.app.Activity
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.threedstwo.tv.core.input.Action
import com.threedstwo.tv.core.input.ButtonMapping
import com.threedstwo.tv.core.input.InputMapper
import com.threedstwo.tv.core.input.MappingPreset
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.input.StickSettings
import com.threedstwo.tv.core.settings.AppSettings
import com.threedstwo.tv.core.settings.CursorStick
import com.threedstwo.tv.input.GamepadTracker
import com.threedstwo.tv.ui.OptionList
import com.threedstwo.tv.ui.Ui
import java.util.Locale

/** Controller selection, remapping, sticks, touch cursor and a live tester. */
class ControllerActivity : Activity(), InputManager.InputDeviceListener {
    private lateinit var s: AppSettings
    private lateinit var options: OptionList
    private lateinit var devicesText: TextView
    private lateinit var testText: TextView
    private val tracker = GamepadTracker()
    private var testing = false
    private var comboStart = 0L
    private val app get() = ThreeDsTvApp.of(this)
    private val main = Handler(Looper.getMainLooper())

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
        options = m
        col.addView(Ui.title(this, "Mandos y pantalla táctil"))

        m.header("Mandos detectados")
        devicesText = Ui.text(this, "", 16f)
        col.addView(devicesText)
        m.choice("Mando principal", listOf("") + gamepads().map { it.descriptor }, { s.preferredController }, { d ->
            if (d.isEmpty()) "El primero que pulse un botón" else gamepads().firstOrNull { it.descriptor == d }?.name ?: "(desconectado)"
        }) { v -> update { it.copy(preferredController = v) } }
        m.action("🎮  Probar mando (salir: BACK del mando de TV o Select+Start 1 s)") { setTesting(true) }
        testText = Ui.text(this, "", 20f, R.color.text).apply {
            setBackgroundResource(R.drawable.panel_bg)
            val p = Ui.dp(context, 20f)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        col.addView(testText)

        m.header("Mapeo de botones")
        m.choice("Perfil", MappingPreset.entries, { s.mappingPreset }, { it.label }) { v ->
            update { it.copy(mappingPreset = v, mapping = ButtonMapping.preset(v)) }
            m.refreshAll()
        }
        m.action("Intercambiar A/B y X/Y") {
            update { it.copy(mapping = it.mapping.swapFaceButtons()) }
            m.refreshAll()
        }
        m.note("Pulsa ◀ ▶ (o OK) sobre cada control para cambiar su función.")
        for (c in PadControl.entries) {
            m.choice(c.label, Action.entries, { s.mapping.actionFor(c) }, { it.label }) { v -> update { it.copy(mapping = it.mapping.with(c, v)) } }
        }
        m.note("Menú de 3DS2TV: control asignado a \"Abrir menú\" (por defecto L3), Select+Start 1 s, o BACK del mando de TV.")

        m.header("Sticks")
        m.intRange("Zona muerta Circle Pad", 0..50 step 5, { (s.circleDeadzone * 100).toInt().roundTo5() }, " %") { v -> update { it.copy(circleDeadzone = v / 100f) } }
        m.intRange("Sensibilidad Circle Pad", 50..200 step 10, { (s.circleSensitivity * 100).toInt().roundTo10() }, " %") { v -> update { it.copy(circleSensitivity = v / 100f) } }
        m.intRange("Zona muerta C-Stick", 0..50 step 5, { (s.cStickDeadzone * 100).toInt().roundTo5() }, " %") { v -> update { it.copy(cStickDeadzone = v / 100f) } }
        m.intRange("Sensibilidad C-Stick", 50..200 step 10, { (s.cStickSensitivity * 100).toInt().roundTo10() }, " %") { v -> update { it.copy(cStickSensitivity = v / 100f) } }

        m.header("Pantalla táctil (cursor virtual)")
        m.toggle("Permitir cursor táctil", { s.touchCursorEnabled }) { v -> update { it.copy(touchCursorEnabled = v) } }
        m.choice("Mover cursor con", CursorStick.entries, { s.cursorStick }, { it.label }) { v -> update { it.copy(cursorStick = v) } }
        m.choice("Botón para tocar", PadControl.entries, { s.touchButton }, { it.label }) { v -> update { it.copy(touchButton = v) } }
        m.intRange("Velocidad del cursor", 100..800 step 50, { s.cursorSpeed.roundTo50() }, " px/s") { v -> update { it.copy(cursorSpeed = v) } }
        m.note("Activa/desactiva el cursor con el control asignado a \"Activar/desactivar cursor táctil\" (por defecto R3) o desde el menú. Con el cursor activo, el stick elegido mueve el cursor y el botón elegido toca la pantalla inferior.")

        m.header("Seguridad y mando de TV")
        m.toggle("Permitir HOME de la 3DS", { s.allowHome }) { v -> update { it.copy(allowHome = v) } }
        m.note("Desactivado por defecto para evitar salir del juego por accidente. Si lo activas, asigna HOME a un control.")
        m.toggle("Usar mando de TV como control básico", { s.tvRemoteAsPad }) { v -> update { it.copy(tvRemoteAsPad = v) } }
        m.note("Cruceta = cruceta 3DS, OK = A. BACK sigue abriendo el menú. No sustituye a un mando completo.")
        m.toggle("Input Redirection activado", { s.inputRedirection }) { v -> update { it.copy(inputRedirection = v) } }
        m.note("Requiere activarlo en la consola: Rosalina (L+↓+Select) → Miscellaneous options → Start InputRedirection.")

        setContentView(Ui.scroll(this, col))
        refreshDevices()
        col.getChildAt(3)?.requestFocus()
    }

    private fun Int.roundTo5() = (this + 2) / 5 * 5
    private fun Int.roundTo10() = (this + 5) / 10 * 10
    private fun Int.roundTo50() = ((this + 25) / 50 * 50).coerceIn(100, 800)

    private fun gamepads(): List<InputDevice> =
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { GamepadTracker.isGameController(it) }

    private fun refreshDevices() {
        val all = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { !it.isVirtual }
        val pads = all.filter { GamepadTracker.isGameController(it) }
        val others = all.filter { !GamepadTracker.isGameController(it) }
        devicesText.text = buildString {
            if (pads.isEmpty()) {
                append("Ningún mando reconocido. Empareja uno en Ajustes de Google TV → Mandos remotos y accesorios.\n")
            } else {
                pads.forEach { append("✔ ").append(GamepadTracker.describe(it)).append('\n') }
            }
            if (others.isNotEmpty()) {
                append("Otros dispositivos de entrada (no usables como mando):\n")
                others.forEach { append("· ").append(GamepadTracker.describe(it)).append('\n') }
            }
        }
    }

    private fun setTesting(on: Boolean) {
        testing = on
        tracker.reset()
        tracker.preferredDescriptor = s.preferredController
        testText.visibility = if (on) View.VISIBLE else View.GONE
        testText.text = if (on) "Pulsa botones y mueve los sticks del mando…" else ""
        if (on) testText.requestFocus()
    }

    private fun renderTest() {
        val pad = tracker.snapshot()
        val r = InputMapper.map(pad, s.mapping, StickSettings(s.circleDeadzone, s.circleSensitivity),
            StickSettings(s.cStickDeadzone, s.cStickSensitivity), s.allowHome, false, null)
        val f = r.frame
        fun fmt(v: Float) = String.format(Locale.US, "%+.2f", v)
        testText.text = buildString {
            append("Mando: ${tracker.activeDeviceName ?: "—"}\n")
            append("Controles: ${pad.effectiveHeld().joinToString { it.name }.ifEmpty { "—" }}\n")
            append("Stick izq ${fmt(pad.leftX)}, ${fmt(pad.leftY)}   Stick der ${fmt(pad.rightX)}, ${fmt(pad.rightY)}\n")
            append("Gatillos ${fmt(pad.leftTrigger)} / ${fmt(pad.rightTrigger)}   HAT ${fmt(pad.hatX)}, ${fmt(pad.hatY)}\n\n")
            append("→ 3DS: ${f.pressed.joinToString { it.name }.ifEmpty { "—" }}")
            if (f.zl) append(" ZL")
            if (f.zr) append(" ZR")
            if (f.home) append(" HOME")
            append("\n→ Circle Pad 0x%03X,0x%03X   C-Stick 0x%02X,0x%02X".format(f.circleX, f.circleY, f.cStickX, f.cStickY))
            if (r.app.openMenu) append("\n→ (abrir menú 3DS2TV)")
            if (r.app.toggleTouchCursor) append("\n→ (cursor táctil)")
        }
        val held = pad.effectiveHeld()
        if (PadControl.SELECT in held && PadControl.START in held) {
            if (comboStart == 0L) comboStart = System.currentTimeMillis()
            else if (System.currentTimeMillis() - comboStart > 1000) { comboStart = 0; setTesting(false) }
        } else comboStart = 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (testing) {
            if (GamepadTracker.isGameControllerEvent(event) && tracker.onKey(event)) {
                renderTest(); return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) setTesting(false)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (testing && tracker.onMotion(event)) {
            renderTest(); return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onResume() {
        super.onResume()
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(this, main)
        refreshDevices()
    }

    override fun onPause() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
    override fun onInputDeviceRemoved(deviceId: Int) {
        tracker.onDeviceRemoved(deviceId)
        refreshDevices()
    }
    override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
}
