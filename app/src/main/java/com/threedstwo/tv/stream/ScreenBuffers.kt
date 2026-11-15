package com.threedstwo.tv.stream

import android.graphics.Bitmap
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo

/**
 * Bitmap pool per screen shared by the decoder (writes into acquired bitmaps) and the renderer
 * (draws FRONT). [lock] is only held for short bookkeeping, never while the renderer waits for
 * vsync: the renderer marks the bitmap it is drawing as [Slot.inUse], and [acquire] never hands
 * out FRONT or an in-use bitmap, so the decoder can publish a new frame while the previous one is
 * still being posted (before, publish() blocked for up to one display frame).
 */
class ScreenBuffers {
    val lock = Object()

    class Slot {
        var front: Bitmap? = null
        var frontInfo: VideoFrameInfo? = null
        var frontReceivedAt = 0L
        var fresh = false
        /** Bitmap the renderer is drawing right now (set and cleared by the renderer under [lock]). */
        var inUse: Bitmap? = null
        internal val pool = ArrayList<Bitmap>(4)
        internal val reserved = ArrayList<Bitmap>(2)
    }

    val slots = Array(Screen.entries.size) { Slot() }

    @Volatile var newFrame = false
    var listener: (() -> Unit)? = null

    private fun Slot.isFree(b: Bitmap) = b !== front && b !== inUse && reserved.none { it === b }

    /**
     * A w x h bitmap the decoder may write into: never FRONT, never the one being drawn, never one
     * already acquired for this frame. Stays reserved until the next [publish]/[cancel] of that screen.
     */
    fun acquire(screen: Screen, w: Int, h: Int): Bitmap {
        synchronized(lock) {
            val s = slots[screen.ordinal]
            for (b in s.pool) {
                if (b.width == w && b.height == h && b.isMutable && s.isFree(b)) {
                    s.reserved.add(b)
                    return b
                }
            }
            // Forget pooled bitmaps of another size that nobody holds (resolution change).
            s.pool.removeAll { (it.width != w || it.height != h) && s.isFree(it) }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                s.pool.add(it)
                s.reserved.add(it)
            }
        }
    }

    fun publish(screen: Screen, bmp: Bitmap, info: VideoFrameInfo, receivedAt: Long) {
        synchronized(lock) {
            val s = slots[screen.ordinal]
            // A bitmap BitmapFactory allocated itself (inBitmap fallback) joins the pool.
            if (s.pool.none { it === bmp }) s.pool.add(bmp)
            s.reserved.clear()
            s.front = bmp
            s.frontInfo = info
            s.frontReceivedAt = receivedAt
            s.fresh = true
            newFrame = true
        }
        listener?.invoke()
    }

    /** Releases bitmaps acquired for a frame that will not be published (decode error). */
    fun cancel(screen: Screen) {
        synchronized(lock) { slots[screen.ordinal].reserved.clear() }
    }

    fun clear() {
        synchronized(lock) {
            slots.forEach { it.front = null; it.frontInfo = null; it.fresh = false; it.reserved.clear() }
        }
    }
}
