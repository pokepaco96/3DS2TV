package com.threedstwo.tv.core

import com.threedstwo.tv.core.input.Action
import com.threedstwo.tv.core.input.AxisLayout
import com.threedstwo.tv.core.input.ButtonMapping
import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.input.InputMapper
import com.threedstwo.tv.core.input.MappingPreset
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.input.PadSnapshot
import com.threedstwo.tv.core.input.StickSettings
import com.threedstwo.tv.core.input.ThreeDsButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Byte-exact fixtures derived by hand from Luma3DS `sysmodules/rosalina/source/input_redirection.c`
 * (commit 1441535): hidData remote defaults {0x00000FFF, 0x02000000, 0x007FF7FF}, irData {0x80800081},
 * 20-byte datagram read as little-endian u32 words [hid, touch, circle, ir, special].
 * Hex strings are written independently of the encoder to catch endianness/offset mistakes.
 */
class LumaFixturesTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun neutral() {
        assertEquals("ff0f0000" + "00000002" + "fff77f00" + "81008080" + "00000000", hex(InputFrame.NEUTRAL.encode()))
    }

    @Test fun aPlusZrPlusTouchCenterPlusCircleRight() {
        val f = InputFrame(
            pressed = setOf(ThreeDsButton.A),
            zr = true,
            touch = InputFrame.TouchPoint(160f, 120f),
            circleX = 0xDD0, circleY = 0x7FF,
        )
        // hid 0xFFE ; touch 1<<24 | 0x7FF<<12 | 0x7FF = 0x017FF7FF ; circle 0x7FF<<12|0xDD0 = 0x007FFDD0 ; ir 0x80800281
        assertEquals("fe0f0000" + "fff77f01" + "d0fd7f00" + "81028080" + "00000000", hex(f.encode()))
    }

    @Test fun dpadUpLeftAndCstickUp() {
        val f = InputFrame(pressed = setOf(ThreeDsButton.DPAD_UP, ThreeDsButton.DPAD_LEFT), cStickX = 0xDA, cStickY = 0xDA, zl = true)
        // hid 0xFFF & ~(1<<6) & ~(1<<5) = 0xF9F ; ir 0xDA<<24 | 0xDA<<16 | 0x04<<8 | 0x81
        assertEquals("9f0f0000" + "00000002" + "fff77f00" + "8104dada" + "00000000", hex(f.encode()))
    }

    @Test fun powerIsNeverProducedByAnyMapping() {
        // Assign every action to every control and hold everything: POWER must never be set.
        for (a in Action.entries) {
            var m = ButtonMapping.preset(MappingPreset.POSITIONAL)
            for (c in PadControl.entries) m = m.with(c, a)
            val s = StickSettings()
            val r = InputMapper.map(PadSnapshot(held = PadControl.entries.toSet()), m, s, s, allowHome = true, rightStickUsedByCursor = false, touch = null)
            assertFalse("$a power", r.frame.power)
            assertFalse("$a powerLong", r.frame.powerLong)
            assertEquals("special word only HOME at most", 0, r.frame.specialWord() and 0x6)
        }
    }

    @Test fun homeOffByDefaultMapping() {
        val r = InputMapper.map(PadSnapshot(held = PadControl.entries.toSet()), ButtonMapping.preset(MappingPreset.POSITIONAL),
            StickSettings(), StickSettings(), allowHome = true, rightStickUsedByCursor = false, touch = null)
        assertFalse(r.frame.home)
    }

    // Fake axis ids (values only need to be distinct)
    private val ids = AxisLayout.Ids(x = 0, y = 1, z = 11, rz = 14, rx = 12, ry = 13, lTrigger = 17, rTrigger = 18, brake = 23, gas = 22)

    @Test fun standardPadUsesZRz() {
        val l = AxisLayout.choose(ids) { it in setOf(0, 1, 11, 14, 17, 18) }
        assertEquals(11, l.rightX); assertEquals(14, l.rightY)
        val ps = AxisLayout.choose(ids) { it in setOf(0, 1, 11, 14, 12, 13, 17, 18) } // DS4 also exposes RX/RY
        assertEquals(11, ps.rightX)
    }

    @Test fun genericHidUsesRxRyAndZAsTriggers() {
        val l = AxisLayout.choose(ids) { it in setOf(0, 1, 11, 14, 12, 13) }
        assertEquals(12, l.rightX); assertEquals(13, l.rightY)
        assertEquals(true, 11 in l.leftTriggers)
    }

    @Test fun padWithoutRightStick() {
        val l = AxisLayout.choose(ids) { it in setOf(0, 1) }
        assertEquals(11, l.rightX) // reads 0 when absent
    }

    @Test fun triggerNormalization() {
        assertEquals(0f, AxisLayout.normalizeTrigger(-1f, -1f, 1f), 1e-6f)
        assertEquals(1f, AxisLayout.normalizeTrigger(1f, -1f, 1f), 1e-6f)
        assertEquals(0.5f, AxisLayout.normalizeTrigger(0.5f, 0f, 1f), 1e-6f)
        assertEquals(0f, AxisLayout.normalizeTrigger(0.3f, 0f, 0f), 1e-6f)
    }
}
