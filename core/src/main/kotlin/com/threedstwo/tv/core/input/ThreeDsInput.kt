package com.threedstwo.tv.core.input

import com.threedstwo.tv.core.util.LittleEndian
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Buttons of the 3DS HID pad register as consumed by Luma3DS Rosalina input redirection
 * (bit numbers of HID PAD; verified against Luma3DS `input_redirection.c` default 0x00000FFF and
 * the mappings in ntrviewer-hr `ui_input_redirection.c` and InputRedirectionClient-Qt).
 */
enum class ThreeDsButton(val bit: Int) {
    A(0), B(1), SELECT(2), START(3), DPAD_RIGHT(4), DPAD_LEFT(5), DPAD_UP(6), DPAD_DOWN(7),
    R(8), L(9), X(10), Y(11),
}

/**
 * Complete state sent to Luma3DS. Value object; [encode] produces the 20-byte datagram.
 *
 * Datagram layout (UDP port 4950, all u32 little-endian), from Luma3DS
 * `sysmodules/rosalina/source/input_redirection.c`:
 *  [0..3]   HID pad, ACTIVE LOW: bit set = released. Neutral 0x00000FFF.
 *  [4..7]   touch: 0x02000000 when not touching; else 1<<24 | y<<12 | x (12-bit, 0..0xFFF scaled from 320x240).
 *  [8..11]  circle pad: y<<12 | x (12-bit each, centre 0x7FF). Neutral 0x007FF7FF.
 *  [12..15] "IR" (New 3DS C-stick + ZL/ZR): y<<24 | x<<16 | buttons<<8 | 0x81, C-stick centre 0x80,
 *           buttons bit1 = ZR, bit2 = ZL. Neutral 0x80800081.
 *  [16..19] special buttons: bit0 HOME, bit1 POWER, bit2 POWER (long press). Neutral 0.
 * Rosalina ignores datagrams < 12 bytes and only reads the last two words if >= 20 bytes.
 */
data class InputFrame(
    val pressed: Set<ThreeDsButton> = emptySet(),
    /** Touch position in native bottom-screen pixels (0..320, 0..240) or null if not touching. */
    val touch: TouchPoint? = null,
    /** Circle pad raw 12-bit values. */
    val circleX: Int = CIRCLE_CENTER,
    val circleY: Int = CIRCLE_CENTER,
    /** C-stick raw 8-bit values (already rotated as expected by the console). */
    val cStickX: Int = CSTICK_CENTER,
    val cStickY: Int = CSTICK_CENTER,
    val zl: Boolean = false,
    val zr: Boolean = false,
    val home: Boolean = false,
    val power: Boolean = false,
    val powerLong: Boolean = false,
) {
    data class TouchPoint(val x: Float, val y: Float)

    fun hidWord(): Int {
        var v = 0x0FFF
        for (b in pressed) v = v and (1 shl b.bit).inv()
        return v
    }

    fun touchWord(): Int {
        val t = touch ?: return TOUCH_NONE
        val x = (t.x.coerceIn(0f, BOTTOM_W) * 0xFFF / BOTTOM_W).toInt().coerceIn(0, 0xFFF)
        val y = (t.y.coerceIn(0f, BOTTOM_H) * 0xFFF / BOTTOM_H).toInt().coerceIn(0, 0xFFF)
        return (1 shl 24) or (y shl 12) or x
    }

    fun circleWord(): Int = (circleY.coerceIn(0, 0xFFF) shl 12) or circleX.coerceIn(0, 0xFFF)

    fun irWord(): Int {
        val buttons = (if (zr) 1 shl 1 else 0) or (if (zl) 1 shl 2 else 0)
        return (cStickY.coerceIn(0, 0xFF) shl 24) or (cStickX.coerceIn(0, 0xFF) shl 16) or (buttons shl 8) or 0x81
    }

    fun specialWord(): Int = (if (home) 1 else 0) or (if (power) 2 else 0) or (if (powerLong) 4 else 0)

    fun encode(out: ByteArray = ByteArray(PACKET_SIZE)): ByteArray {
        LittleEndian.putInt(out, 0, hidWord())
        LittleEndian.putInt(out, 4, touchWord())
        LittleEndian.putInt(out, 8, circleWord())
        LittleEndian.putInt(out, 12, irWord())
        LittleEndian.putInt(out, 16, specialWord())
        return out
    }

    val isNeutral: Boolean get() = this == NEUTRAL

    companion object {
        const val PORT = 4950
        const val PACKET_SIZE = 20
        const val CIRCLE_CENTER = 0x7FF
        const val CSTICK_CENTER = 0x80
        const val TOUCH_NONE = 0x02000000
        const val BOTTOM_W = 320f
        const val BOTTOM_H = 240f
        val NEUTRAL = InputFrame()
    }
}

/** Analog stick shaping shared by circle pad, C-stick and touch cursor. */
object StickMath {
    /** Radius (in 12-bit units) the reference viewer uses for full circle pad deflection. */
    const val CIRCLE_PAD_BOUND = 0x5D0
    const val CSTICK_BOUND = 0x7F

    /**
     * Radial deadzone + sensitivity curve. Input/outputs in [-1, 1].
     * @param deadzone fraction of the radius ignored (0..0.9)
     * @param sensitivity output multiplier (0.5..2), result clamped to the unit circle
     */
    fun shape(x: Float, y: Float, deadzone: Float, sensitivity: Float): Pair<Float, Float> {
        val mag = hypot(x, y)
        val dz = deadzone.coerceIn(0f, 0.9f)
        if (mag <= dz || mag == 0f) return 0f to 0f
        val scaled = ((mag - dz) / (1f - dz)).coerceAtMost(1f) * sensitivity
        val outMag = scaled.coerceAtMost(1f)
        return (x / mag * outMag) to (y / mag * outMag)
    }

    /**
     * Android axis convention (x right +, y down +) to 3DS circle pad 12-bit values (y up +).
     */
    fun circlePad(x: Float, y: Float): Pair<Int, Int> {
        if (x == 0f && y == 0f) return InputFrame.CIRCLE_CENTER to InputFrame.CIRCLE_CENTER
        val cx = (x * CIRCLE_PAD_BOUND).roundToInt() + 0x800
        val cy = (-y * CIRCLE_PAD_BOUND).roundToInt() + 0x800
        return cx.coerceIn(1, 0xFFF) to cy.coerceIn(1, 0xFFF)
    }

    /**
     * Android right stick (x right +, y down +) to C-stick values. The console expects the
     * position rotated by 45 degrees (documented by TuxSH, used by ntrviewer-hr).
     */
    fun cStick(x: Float, y: Float): Pair<Int, Int> {
        if (x == 0f && y == 0f) return InputFrame.CSTICK_CENTER to InputFrame.CSTICK_CENTER
        val r = (1.0 / sqrt(2.0)).toFloat()
        val cx = (r * (x - y) * CSTICK_BOUND).roundToInt() + 0x80
        val cy = (r * (-y - x) * CSTICK_BOUND).roundToInt() + 0x80
        return cx.coerceIn(1, 0xFF) to cy.coerceIn(1, 0xFF)
    }

    fun isTriggerPressed(value: Float, threshold: Float = 0.35f) = abs(value) >= threshold
}
