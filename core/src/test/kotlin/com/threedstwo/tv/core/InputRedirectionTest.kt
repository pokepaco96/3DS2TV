package com.threedstwo.tv.core

import com.threedstwo.tv.core.input.Action
import com.threedstwo.tv.core.input.ButtonMapping
import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.input.InputMapper
import com.threedstwo.tv.core.input.MappingPreset
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.input.PadSnapshot
import com.threedstwo.tv.core.input.StickMath
import com.threedstwo.tv.core.input.StickSettings
import com.threedstwo.tv.core.input.ThreeDsButton
import com.threedstwo.tv.core.util.LittleEndian
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRedirectionTest {
    private fun words(f: InputFrame): IntArray {
        val b = f.encode()
        assertEquals(20, b.size)
        return IntArray(5) { LittleEndian.getInt(b, it * 4) }
    }

    @Test fun neutralFrameMatchesLumaDefaults() {
        // Luma3DS input_redirection.c: hidData remote = 0xFFF, 0x02000000, 0x007FF7FF ; irData = 0x80800081
        assertArrayEquals(intArrayOf(0x00000FFF, 0x02000000, 0x007FF7FF, 0x80800081.toInt(), 0), words(InputFrame.NEUTRAL))
        val bytes = InputFrame.NEUTRAL.encode()
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x0F, 0, 0), bytes.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0, 0x80.toByte(), 0x80.toByte()), bytes.copyOfRange(12, 16))
    }

    @Test fun buttonsAreActiveLow() {
        assertEquals(0xFFE, words(InputFrame(pressed = setOf(ThreeDsButton.A)))[0])
        assertEquals(0xFFD, words(InputFrame(pressed = setOf(ThreeDsButton.B)))[0])
        assertEquals(0x7FF, words(InputFrame(pressed = setOf(ThreeDsButton.Y)))[0])
        assertEquals(0xBFF, words(InputFrame(pressed = setOf(ThreeDsButton.X)))[0])
        assertEquals(0xDFF, words(InputFrame(pressed = setOf(ThreeDsButton.L)))[0])
        assertEquals(0xEFF, words(InputFrame(pressed = setOf(ThreeDsButton.R)))[0])
        assertEquals(0xFF7, words(InputFrame(pressed = setOf(ThreeDsButton.START)))[0])
        assertEquals(0xFFB, words(InputFrame(pressed = setOf(ThreeDsButton.SELECT)))[0])
        assertEquals(0xFEF, words(InputFrame(pressed = setOf(ThreeDsButton.DPAD_RIGHT)))[0])
        assertEquals(0xFDF, words(InputFrame(pressed = setOf(ThreeDsButton.DPAD_LEFT)))[0])
        assertEquals(0xFBF, words(InputFrame(pressed = setOf(ThreeDsButton.DPAD_UP)))[0])
        assertEquals(0xF7F, words(InputFrame(pressed = setOf(ThreeDsButton.DPAD_DOWN)))[0])
        assertEquals(0, words(InputFrame(pressed = ThreeDsButton.entries.toSet()))[0])
    }

    @Test fun zlZrAndHomePower() {
        assertEquals(0x80800281.toInt(), words(InputFrame(zr = true))[3])
        assertEquals(0x80800481.toInt(), words(InputFrame(zl = true))[3])
        assertEquals(0x80800681.toInt(), words(InputFrame(zl = true, zr = true))[3])
        assertEquals(1, words(InputFrame(home = true))[4])
        assertEquals(2, words(InputFrame(power = true))[4])
        assertEquals(4, words(InputFrame(powerLong = true))[4])
    }

    @Test fun touchScaling() {
        assertEquals((1 shl 24), words(InputFrame(touch = InputFrame.TouchPoint(0f, 0f)))[1])
        assertEquals((1 shl 24) or (0xFFF shl 12) or 0xFFF, words(InputFrame(touch = InputFrame.TouchPoint(320f, 240f)))[1])
        val mid = words(InputFrame(touch = InputFrame.TouchPoint(160f, 120f)))[1]
        assertEquals(0x7FF, mid and 0xFFF)
        assertEquals(0x7FF, (mid shr 12) and 0xFFF)
        // clamped
        assertEquals((1 shl 24), words(InputFrame(touch = InputFrame.TouchPoint(-50f, -1f)))[1])
    }

    @Test fun circlePadAxes() {
        assertEquals(0x7FF to 0x7FF, StickMath.circlePad(0f, 0f))
        val (rx, _) = StickMath.circlePad(1f, 0f)
        assertEquals(0x800 + 0x5D0, rx)
        val (_, upY) = StickMath.circlePad(0f, -1f) // Android up = negative y
        assertEquals(0x800 + 0x5D0, upY)
        val (_, downY) = StickMath.circlePad(0f, 1f)
        assertEquals(0x800 - 0x5D0, downY)
        val w = words(InputFrame(circleX = 0xABC, circleY = 0x123))[2]
        assertEquals(0x123ABC, w)
    }

    @Test fun cStickRotated45Degrees() {
        assertEquals(0x80 to 0x80, StickMath.cStick(0f, 0f))
        val (x, y) = StickMath.cStick(1f, 0f) // right
        assertEquals(0x80 + 90, x) // round(0.7071*127) = 90
        assertEquals(0x80 - 90, y)
        val (ux, uy) = StickMath.cStick(0f, -1f) // up
        assertEquals(0x80 + 90, ux)
        assertEquals(0x80 + 90, uy)
    }

    @Test fun deadzoneAndSensitivity() {
        assertEquals(0f to 0f, StickMath.shape(0.1f, 0.05f, 0.15f, 1f))
        val (x, _) = StickMath.shape(1f, 0f, 0.15f, 1f)
        assertEquals(1f, x, 1e-5f)
        val (hx, _) = StickMath.shape(0.575f, 0f, 0.15f, 1f)
        assertEquals(0.5f, hx, 1e-3f)
        val (sx, _) = StickMath.shape(0.575f, 0f, 0.15f, 2f)
        assertEquals(1f, sx, 1e-3f)
    }

    private val s = StickSettings(0.15f, 1f)

    @Test fun positionalPresetMapsLikeA3ds() {
        val m = ButtonMapping.preset(MappingPreset.POSITIONAL)
        val r = InputMapper.map(PadSnapshot(held = setOf(PadControl.EAST, PadControl.SOUTH)), m, s, s, false, false, null)
        assertEquals(setOf(ThreeDsButton.A, ThreeDsButton.B), r.frame.pressed)
        val x = InputMapper.map(PadSnapshot(held = setOf(PadControl.SOUTH)), ButtonMapping.preset(MappingPreset.XBOX_LABELS), s, s, false, false, null)
        assertEquals(setOf(ThreeDsButton.A), x.frame.pressed)
    }

    @Test fun swapFaceButtons() {
        val m = ButtonMapping.preset(MappingPreset.POSITIONAL).swapFaceButtons()
        assertEquals(Action.A, m.actionFor(PadControl.SOUTH))
        assertEquals(Action.Y, m.actionFor(PadControl.NORTH))
    }

    @Test fun triggersAndHatAndAnalogs() {
        val m = ButtonMapping.preset(MappingPreset.POSITIONAL)
        val r = InputMapper.map(
            PadSnapshot(leftTrigger = 0.9f, rightTrigger = 0.1f, hatX = 1f, hatY = -1f, leftX = 1f, rightY = -1f),
            m, s, s, false, false, null,
        )
        assertTrue(r.frame.zl)
        assertFalse(r.frame.zr)
        assertEquals(setOf(ThreeDsButton.DPAD_RIGHT, ThreeDsButton.DPAD_UP), r.frame.pressed)
        assertEquals(0x800 + 0x5D0, r.frame.circleX)
        assertTrue(r.frame.cStickY > 0x80)
        // R2 via key also counts as ZR
        assertTrue(InputMapper.map(PadSnapshot(held = setOf(PadControl.R2)), m, s, s, false, false, null).frame.zr)
    }

    @Test fun homeOnlyWhenAllowed() {
        val m = ButtonMapping.preset(MappingPreset.POSITIONAL).with(PadControl.GUIDE, Action.HOME)
        val pad = PadSnapshot(held = setOf(PadControl.GUIDE))
        assertFalse(InputMapper.map(pad, m, s, s, false, false, null).frame.home)
        assertTrue(InputMapper.map(pad, m, s, s, true, false, null).frame.home)
    }

    @Test fun appActionsAndTouch() {
        val m = ButtonMapping.preset(MappingPreset.POSITIONAL).with(PadControl.R1, Action.TOUCH)
        val t = InputFrame.TouchPoint(10f, 20f)
        val r = InputMapper.map(PadSnapshot(held = setOf(PadControl.L3, PadControl.R3, PadControl.R1), rightX = 1f), m, s, s, false, true, t)
        assertTrue(r.app.openMenu)
        assertTrue(r.app.toggleTouchCursor)
        assertEquals(t, r.frame.touch)
        assertTrue(r.frame.pressed.isEmpty())
        assertEquals(0x80, r.frame.cStickX) // right stick used by cursor
        assertEquals(InputFrame.TOUCH_NONE, InputMapper.map(PadSnapshot(), m, s, s, false, true, t).frame.touchWord())
    }

    @Test fun emptyPadIsNeutral() {
        val r = InputMapper.map(PadSnapshot(), ButtonMapping.preset(MappingPreset.POSITIONAL), s, s, true, false, null)
        assertTrue(r.frame.isNeutral)
        assertArrayEquals(InputFrame.NEUTRAL.encode(), r.frame.encode())
    }

    @Test fun mappingSerializationRoundTrip() {
        val m = ButtonMapping.preset(MappingPreset.XBOX_LABELS).with(PadControl.R3, Action.TOUCH)
        assertEquals(m, ButtonMapping.deserialize(m.serialize()))
        val partial = ButtonMapping.deserialize("SOUTH=X;BOGUS=A;EAST=NOTHING")
        assertEquals(Action.X, partial.actionFor(PadControl.SOUTH))
        assertEquals(Action.A, partial.actionFor(PadControl.EAST))
    }
}
