package com.threedstwo.tv.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.input.PadSnapshot

/**
 * Tracks the state of ONE active game controller from Android KeyEvent/MotionEvent.
 *
 * Android normalises Xbox/PlayStation/most HID pads to the same KEYCODE_BUTTON_* and AXIS_* codes
 * (https://developer.android.com/training/game-controllers/controller-input), so profiles only
 * differ in labels. Axis sources handled: X/Y (left), Z/RZ (right), LTRIGGER/BRAKE, RTRIGGER/GAS, HAT_X/HAT_Y.
 */
class GamepadTracker {
    /** Device id of the controller currently driving the 3DS, -1 = none yet. */
    var activeDeviceId = -1
        private set
    var activeDeviceName: String? = null
        private set
    var preferredDescriptor: String = ""

    private val held = mutableSetOf<PadControl>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f; private var hx = 0f; private var hy = 0f

    fun snapshot() = PadSnapshot(held.toSet(), lx, ly, rx, ry, lt, rt, hx, hy)

    fun reset() {
        held.clear()
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f; hx = 0f; hy = 0f
    }

    /** Called when a device is removed: if it was the active one, everything is released. */
    fun onDeviceRemoved(deviceId: Int): Boolean {
        if (deviceId != activeDeviceId) return false
        reset()
        activeDeviceId = -1
        activeDeviceName = null
        return true
    }

    /** Whether [device] should drive the console (claims it if no controller is active). */
    private fun accept(device: InputDevice?): Boolean {
        if (device == null || !isGameController(device)) return false
        if (device.id == activeDeviceId) return true
        if (activeDeviceId != -1) {
            // Switch only to the explicitly preferred controller.
            if (preferredDescriptor.isEmpty() || device.descriptor != preferredDescriptor) return false
            reset()
        } else if (preferredDescriptor.isNotEmpty() && device.descriptor != preferredDescriptor &&
            InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.descriptor == preferredDescriptor }
        ) {
            return false // the preferred controller is connected: ignore others
        }
        activeDeviceId = device.id
        activeDeviceName = device.name
        return true
    }

    /** Returns true if the event was a game controller event and has been consumed. */
    fun onKey(event: KeyEvent): Boolean {
        val control = controlFor(event.keyCode) ?: return false
        if (!isGameControllerEvent(event)) return false
        if (!accept(event.device)) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> held += control
            KeyEvent.ACTION_UP -> held -= control
        }
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) return false
        if (!accept(event.device)) return true
        lx = event.getAxisValue(MotionEvent.AXIS_X)
        ly = event.getAxisValue(MotionEvent.AXIS_Y)
        rx = event.getAxisValue(MotionEvent.AXIS_Z)
        ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        return true
    }

    companion object {
        fun isGameController(d: InputDevice): Boolean {
            val s = d.sources
            return !d.isVirtual && (s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }

        fun isGameControllerEvent(e: KeyEvent): Boolean {
            val s = e.source
            return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                KeyEvent.isGamepadButton(e.keyCode) && e.device?.let { isGameController(it) } == true
        }

        fun controlFor(keyCode: Int): PadControl? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> PadControl.SOUTH
            KeyEvent.KEYCODE_BUTTON_B -> PadControl.EAST
            KeyEvent.KEYCODE_BUTTON_X -> PadControl.WEST
            KeyEvent.KEYCODE_BUTTON_Y -> PadControl.NORTH
            KeyEvent.KEYCODE_BUTTON_L1 -> PadControl.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> PadControl.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> PadControl.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> PadControl.R2
            KeyEvent.KEYCODE_BUTTON_THUMBL -> PadControl.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> PadControl.R3
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> PadControl.SELECT
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadControl.START
            KeyEvent.KEYCODE_BUTTON_MODE -> PadControl.GUIDE
            KeyEvent.KEYCODE_DPAD_UP -> PadControl.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadControl.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadControl.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadControl.DPAD_RIGHT
            else -> null
        }

        fun describe(d: InputDevice): String {
            val kinds = buildList {
                if (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) add("gamepad")
                if (d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) add("joystick")
                if (d.sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) add("dpad")
                if (d.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD) add("teclas")
                if (d.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) add("ratón")
            }
            val vidPid = "%04x:%04x".format(d.vendorId, d.productId)
            return "${d.name} [$vidPid] (${kinds.joinToString()})" + if (d.isVirtual) " virtual" else ""
        }
    }
}
