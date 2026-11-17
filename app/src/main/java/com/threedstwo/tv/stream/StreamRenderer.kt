package com.threedstwo.tv.stream

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.view.SurfaceHolder
import com.threedstwo.tv.AppLog
import com.threedstwo.tv.core.layout.LayoutSettings
import com.threedstwo.tv.core.layout.Placement
import com.threedstwo.tv.core.layout.ScreenLayout
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.session.RunningAverage

/**
 * Render thread drawing the latest decoded screens on a SurfaceView with the GPU
 * (lockHardwareCanvas on API 26+, software canvas before). Draws only when something changed: new frame, layout, cursor.
 * Nearest-neighbour (pixel art) or bilinear filtering; aspect ratio always preserved by [ScreenLayout].
 */
class StreamRenderer(private val buffers: ScreenBuffers) : SurfaceHolder.Callback {
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile var layout: LayoutSettings = LayoutSettings()
        set(v) { field = v; recompute() }
    @Volatile var smooth = false
        set(v) { field = v; requestRender() }
    /** Touch cursor in native bottom-screen coords, or null when hidden. */
    @Volatile var cursor: Pair<Float, Float>? = null
        set(v) { field = v; requestRender() }
    @Volatile var cursorPressed = false

    @Volatile var placements: List<Placement> = emptyList()
        private set

    private val signal = Object()
    /** Held while drawing on the surface; surfaceDestroyed waits on it. Separate from buffers.lock so the decoder never waits for vsync. */
    private val surfaceLock = Object()
    private val drawBmp = arrayOfNulls<android.graphics.Bitmap>(Screen.entries.size)
    private val drawInfo = arrayOfNulls<com.threedstwo.tv.core.ntr.VideoFrameInfo>(Screen.entries.size)
    private var dirty = true
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var presentedFrames = 0L
        private set
    val receiveToDisplayMs = RunningAverage(120)

    private var softwareFallbackLogged = false
    private val values = FloatArray(9)
    /** Per-screen matrix cache: recomputed only when the placement or the bitmap size changes (no per-frame allocation). */
    private val matrices = Array(Screen.entries.size) { Matrix() }
    private val matPlacement = arrayOfNulls<Placement>(Screen.entries.size)
    private val matW = IntArray(Screen.entries.size)
    private val matH = IntArray(Screen.entries.size)
    private val paint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFF4081.toInt() }

    init {
        buffers.listener = { requestRender() }
    }

    fun start() {
        running = true
        thread = Thread({ loop() }, "renderer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        requestRender()
        thread?.join(1000)
    }

    fun requestRender() {
        synchronized(signal) {
            dirty = true
            signal.notifyAll()
        }
    }

    private fun recompute() {
        placements = ScreenLayout.compute(width, height, layout)
        requestRender()
    }

    fun placementFor(screen: Screen) = placements.firstOrNull { it.screen == screen }

    override fun surfaceCreated(h: SurfaceHolder) {
        holder = h
        requestRender()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
        holder = h
        width = w
        height = hgt
        AppLog.i("Superficie ${w}x$hgt")
        recompute()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        // Block until the render thread is done with this surface.
        synchronized(surfaceLock) { holder = null }
    }

    private fun loop() {
        while (running) {
            synchronized(signal) {
                while (running && !dirty) signal.wait(500)
                dirty = false
            }
            if (!running) break
            drawOnce()
        }
    }

    private fun drawOnce() {
        synchronized(surfaceLock) {
            val h = holder ?: return
            // Dequeue the surface buffer first (this may wait for vsync) WITHOUT buffers.lock,
            // so the decoder keeps publishing meanwhile and we then draw the newest frame.
            val canvas: Canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) h.lockHardwareCanvas() else h.lockCanvas()
            } catch (e: Exception) {
                if (!softwareFallbackLogged) {
                    softwareFallbackLogged = true
                    AppLog.w("lockHardwareCanvas no disponible (${e.message}); usando canvas por software")
                }
                try { h.lockCanvas() } catch (_: Exception) { null }
            } ?: return
            var hadNew = false
            val now = System.nanoTime()
            // Short critical section: pick the current fronts and mark them in use.
            synchronized(buffers.lock) {
                for (i in buffers.slots.indices) {
                    val slot = buffers.slots[i]
                    drawBmp[i] = slot.front
                    drawInfo[i] = slot.frontInfo
                    slot.inUse = slot.front
                    if (slot.fresh && slot.front != null) {
                        slot.fresh = false
                        hadNew = true
                        receiveToDisplayMs.add((now - slot.frontReceivedAt) / 1e6)
                    }
                }
            }
            try {
                canvas.drawColor(Color.BLACK)
                paint.isFilterBitmap = smooth
                for (p in placements) {
                    val bmp = drawBmp[p.screen.ordinal] ?: continue
                    val info = drawInfo[p.screen.ordinal] ?: continue
                    // jpeg dims may differ from bitmap dims only for even/odd reconstruction (full width bitmap)
                    val idx = p.screen.ordinal
                    val matrix = matrices[idx]
                    if (matPlacement[idx] !== p || matW[idx] != bmp.width || matH[idx] != info.jpegHeight) {
                        p.jpegToSurface(bmp.width, info.jpegHeight).toMatrixValues(values)
                        matrix.setValues(values)
                        matPlacement[idx] = p
                        matW[idx] = bmp.width
                        matH[idx] = info.jpegHeight
                    }
                    canvas.drawBitmap(bmp, matrix, paint)
                }
                drawCursor(canvas)
            } finally {
                try {
                    // The hardware canvas copies bitmap contents here, so the bitmaps stay
                    // marked in use until it returns.
                    h.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    AppLog.e("unlockCanvasAndPost falló", e)
                }
                synchronized(buffers.lock) {
                    for (i in buffers.slots.indices) buffers.slots[i].inUse = null
                    java.util.Arrays.fill(drawBmp, null)
                }
            }
            if (hadNew) presentedFrames++
        }
    }

    private fun drawCursor(canvas: Canvas) {
        val c = cursor ?: return
        val p = placementFor(Screen.BOTTOM) ?: return
        val m = p.nativeToSurface()
        val x = m.mapX(c.first, c.second)
        val y = m.mapY(c.first, c.second)
        val r = (p.width.coerceAtMost(p.height) / 40f).coerceAtLeast(10f)
        if (cursorPressed) canvas.drawCircle(x, y, r, cursorFill)
        canvas.drawCircle(x, y, r, cursorPaint)
        canvas.drawLine(x - r * 1.6f, y, x - r * 0.5f, y, cursorPaint)
        canvas.drawLine(x + r * 0.5f, y, x + r * 1.6f, y, cursorPaint)
        canvas.drawLine(x, y - r * 1.6f, x, y - r * 0.5f, cursorPaint)
        canvas.drawLine(x, y + r * 0.5f, x, y + r * 1.6f, cursorPaint)
    }
}
