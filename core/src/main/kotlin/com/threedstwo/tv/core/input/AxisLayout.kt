package com.threedstwo.tv.core.input

/**
 * Which Android MotionEvent axes carry the right stick and the analog triggers on a given device.
 *
 * Android's standard gamepad layout (Xbox, DualShock/DualSense with the kernel drivers, most
 * modern HID pads) is: right stick = AXIS_Z / AXIS_RZ, triggers = AXIS_LTRIGGER/AXIS_RTRIGGER
 * and/or AXIS_BRAKE/AXIS_GAS. Some generic HID pads instead report the right stick on
 * AXIS_RX / AXIS_RY and the triggers on AXIS_Z / AXIS_RZ. This picks a layout from the axes the
 * device declares (InputDevice.getMotionRange), so no device-specific table is needed.
 *
 * Axis ids are passed in (android.view.MotionEvent constants) to keep this JVM-testable.
 */
data class AxisLayout(
    val rightX: Int,
    val rightY: Int,
    val leftTriggers: List<Int>,
    val rightTriggers: List<Int>,
) {
    data class Ids(
        val x: Int, val y: Int, val z: Int, val rz: Int, val rx: Int, val ry: Int,
        val lTrigger: Int, val rTrigger: Int, val brake: Int, val gas: Int,
    )

    companion object {
        fun choose(ids: Ids, has: (Int) -> Boolean): AxisLayout {
            val hasDedicatedTriggers = has(ids.lTrigger) || has(ids.rTrigger) || has(ids.brake) || has(ids.gas)
            val zIsStick = has(ids.z) && has(ids.rz) && (hasDedicatedTriggers || !(has(ids.rx) && has(ids.ry)))
            return if (zIsStick || !(has(ids.rx) && has(ids.ry))) {
                AxisLayout(ids.z, ids.rz, listOf(ids.lTrigger, ids.brake), listOf(ids.rTrigger, ids.gas))
            } else {
                // Generic HID: right stick on RX/RY, triggers (if any) on Z/RZ
                AxisLayout(ids.rx, ids.ry, listOf(ids.lTrigger, ids.brake, ids.z), listOf(ids.rTrigger, ids.gas, ids.rz))
            }
        }

        /** Normalizes a trigger value to 0..1 using the axis range (some report -1..1 with rest at -1). */
        fun normalizeTrigger(value: Float, min: Float, max: Float): Float {
            if (max <= min) return 0f
            return if (min < 0f) ((value - min) / (max - min)).coerceIn(0f, 1f) else (value / max).coerceIn(0f, 1f)
        }
    }
}
