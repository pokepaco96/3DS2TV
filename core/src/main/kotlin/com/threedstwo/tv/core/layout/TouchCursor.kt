package com.threedstwo.tv.core.layout

import com.threedstwo.tv.core.input.InputFrame

/**
 * Virtual cursor over the bottom screen, kept in NATIVE bottom-screen coordinates (0..320, 0..240),
 * so the coordinates sent to the console are right whatever the rotation/scale/position on the TV.
 * Movement input is given in TV (display) directions and converted through the inverse rotation.
 */
class TouchCursor {
    var x = InputFrame.BOTTOM_W / 2f
        private set
    var y = InputFrame.BOTTOM_H / 2f
        private set

    fun reset() {
        x = InputFrame.BOTTOM_W / 2f
        y = InputFrame.BOTTOM_H / 2f
    }

    fun setNative(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, InputFrame.BOTTOM_W - 1f)
        y = ny.coerceIn(0f, InputFrame.BOTTOM_H - 1f)
    }

    /**
     * @param dirX,dirY stick deflection in display directions ([-1, 1], y down +)
     * @param speed native pixels per second at full deflection
     */
    fun move(dirX: Float, dirY: Float, dtSeconds: Float, speed: Float, rotation: Rotation) {
        if (dirX == 0f && dirY == 0f) return
        val inv = rotation.transform(InputFrame.BOTTOM_W, InputFrame.BOTTOM_H).inverse()
        val nx = inv.mapVectorX(dirX, dirY)
        val ny = inv.mapVectorY(dirX, dirY)
        setNative(x + nx * speed * dtSeconds, y + ny * speed * dtSeconds)
    }

    fun touchPoint() = InputFrame.TouchPoint(x, y)

    companion object {
        /** Maps a surface point inside [p] (must be the bottom screen) to native coords, or null if outside. */
        fun surfaceToNative(p: Placement, sx: Float, sy: Float): Pair<Float, Float>? {
            if (!p.contains(sx, sy)) return null
            val inv = p.nativeToSurface().inverse()
            return inv.mapX(sx, sy) to inv.mapY(sx, sy)
        }
    }
}
