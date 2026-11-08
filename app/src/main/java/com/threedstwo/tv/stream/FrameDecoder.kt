package com.threedstwo.tv.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.threedstwo.tv.AppLog
import com.threedstwo.tv.core.ntr.Downsample
import com.threedstwo.tv.core.ntr.FrameMailbox
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.session.RunningAverage

/**
 * Decoder thread: takes the newest JPEG per screen from [FrameMailbox], decodes it with
 * BitmapFactory (Skia's libjpeg-turbo) INTO A REUSED BITMAP (inBitmap, no per-frame allocation)
 * and publishes it to [ScreenBuffers].
 */
class FrameDecoder(
    private val mailbox: FrameMailbox,
    private val buffers: ScreenBuffers,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    val decodeMs = RunningAverage(120)
    @Volatile var decoded = 0L
        private set
    @Volatile var errors = 0L
        private set

    // even/odd downsample reconstruction state (rare console option)
    private val halfPrev = arrayOfNulls<IntArray>(2)
    private var halfCurr: IntArray? = null
    private var fullTmp: IntArray? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "jpeg-decoder").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    /** Stops the thread; the mailbox stays usable so a new decoder can be started (activity restart). */
    fun stop() {
        running = false
        mailbox.wakeUp()
        thread?.join(1000)
    }

    private fun loop() {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        while (running) {
            val f = mailbox.take(250) ?: continue
            try {
                val info = f.info
                val back = buffers.backFor(info.screen, info.jpegWidth, info.jpegHeight)
                opts.inBitmap = back
                val t0 = System.nanoTime()
                val bmp = try {
                    BitmapFactory.decodeByteArray(f.data, 0, f.length, opts)
                } catch (e: IllegalArgumentException) {
                    // inBitmap not reusable (unexpected dimensions): decode into a new bitmap
                    opts.inBitmap = null
                    BitmapFactory.decodeByteArray(f.data, 0, f.length, opts)
                }
                if (bmp == null || bmp.width != info.jpegWidth || bmp.height != info.jpegHeight) {
                    errors++
                    if (errors % 50 == 1L) AppLog.w("JPEG inválido o con tamaño inesperado (${bmp?.width}x${bmp?.height})")
                    continue
                }
                val out = if (info.downsample == 2) mergeEvenOdd(bmp, info.screen, info.frameId % 2 == 1) else bmp
                decodeMs.add((System.nanoTime() - t0) / 1e6)
                decoded++
                buffers.publish(info.screen, out, info, f.receivedAtNanos)
            } catch (e: Exception) {
                errors++
                AppLog.e("Error decodificando frame", e)
            } finally {
                mailbox.release(f)
            }
        }
    }

    private fun mergeEvenOdd(half: Bitmap, screen: Screen, evenOdd: Boolean): Bitmap {
        val hw = half.width
        val h = half.height
        val w = hw * 2
        val n = hw * h
        val curr = (halfCurr?.takeIf { it.size == n } ?: IntArray(n)).also { halfCurr = it }
        half.getPixels(curr, 0, hw, 0, 0, hw, h)
        val prev = halfPrev[screen.ordinal]?.takeIf { it.size == n } ?: curr.copyOf()
        val full = (fullTmp?.takeIf { it.size == w * h } ?: IntArray(w * h)).also { fullTmp = it }
        Downsample.mergeEvenOdd(curr, prev, full, evenOdd, w, h)
        halfPrev[screen.ordinal] = curr.copyOf()
        val target = buffers.extraFor(screen, w, h)
        target.setPixels(full, 0, w, 0, 0, w, h)
        return target
    }
}
