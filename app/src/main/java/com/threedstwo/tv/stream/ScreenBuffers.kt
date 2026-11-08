package com.threedstwo.tv.stream

import android.graphics.Bitmap
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo

/**
 * Double buffer per screen shared by decoder (writes BACK) and renderer (reads FRONT under [lock]).
 * The renderer holds [lock] while drawing+posting, so the decoder never mutates a bitmap being drawn.
 */
class ScreenBuffers {
    val lock = Object()

    class Slot {
        var front: Bitmap? = null
        var back: Bitmap? = null
        var extra: Bitmap? = null
        var frontInfo: VideoFrameInfo? = null
        var frontReceivedAt = 0L
        var fresh = false
    }

    val slots = Array(Screen.entries.size) { Slot() }

    @Volatile var newFrame = false
    var listener: (() -> Unit)? = null

    /** Bitmap the decoder may write into (never the one on screen). */
    fun backFor(screen: Screen, w: Int, h: Int): Bitmap {
        val s = slots[screen.ordinal]
        val b = s.back
        if (b != null && b.width == w && b.height == h && b.isMutable) return b
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { s.back = it }
    }

    fun extraFor(screen: Screen, w: Int, h: Int): Bitmap {
        val s = slots[screen.ordinal]
        val b = s.extra
        if (b != null && b.width == w && b.height == h) return b
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { s.extra = it }
    }

    fun publish(screen: Screen, bmp: Bitmap, info: VideoFrameInfo, receivedAt: Long) {
        synchronized(lock) {
            val s = slots[screen.ordinal]
            if (bmp === s.extra) {
                // swap extra <-> front so the next merge never writes the displayed bitmap
                s.extra = s.front?.takeIf { it !== s.back }
            } else {
                s.back = s.front?.takeIf { it !== s.extra }
            }
            s.front = bmp
            s.frontInfo = info
            s.frontReceivedAt = receivedAt
            s.fresh = true
            newFrame = true
        }
        listener?.invoke()
    }

    fun clear() {
        synchronized(lock) {
            slots.forEach { it.front = null; it.frontInfo = null; it.fresh = false }
        }
    }
}
