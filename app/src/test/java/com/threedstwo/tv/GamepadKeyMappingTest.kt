package com.threedstwo.tv

import android.view.KeyEvent
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.input.GamepadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Android key codes (compile-time constants) → abstract pad controls. */
class GamepadKeyMappingTest {
    @Test fun standardGamepadButtons() {
        val expected = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadControl.SOUTH, KeyEvent.KEYCODE_BUTTON_B to PadControl.EAST,
            KeyEvent.KEYCODE_BUTTON_X to PadControl.WEST, KeyEvent.KEYCODE_BUTTON_Y to PadControl.NORTH,
            KeyEvent.KEYCODE_BUTTON_L1 to PadControl.L1, KeyEvent.KEYCODE_BUTTON_R1 to PadControl.R1,
            KeyEvent.KEYCODE_BUTTON_L2 to PadControl.L2, KeyEvent.KEYCODE_BUTTON_R2 to PadControl.R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL to PadControl.L3, KeyEvent.KEYCODE_BUTTON_THUMBR to PadControl.R3,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadControl.SELECT, KeyEvent.KEYCODE_BUTTON_START to PadControl.START,
            KeyEvent.KEYCODE_BUTTON_MODE to PadControl.GUIDE,
            KeyEvent.KEYCODE_DPAD_UP to PadControl.DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN to PadControl.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to PadControl.DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to PadControl.DPAD_RIGHT,
            // Some pads send BACK/MENU for View/Menu buttons (only when the event comes from a game controller)
            KeyEvent.KEYCODE_BACK to PadControl.SELECT, KeyEvent.KEYCODE_MENU to PadControl.START,
        )
        for ((code, control) in expected) assertEquals(KeyEvent.keyCodeToString(code), control, GamepadTracker.controlFor(code))
    }

    @Test fun unknownOrAbsentCodesAreIgnored() {
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z, KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_UNKNOWN)) {
            assertNull(GamepadTracker.controlFor(code))
        }
    }
}
