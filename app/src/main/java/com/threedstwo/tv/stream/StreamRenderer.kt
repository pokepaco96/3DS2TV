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
    private var dirty = true
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var presentedFrames = 0L
        private set
    val receiveToDisplayMs = RunningAverage(120)

    private var softwareFallbackLogged = false
    private val matrix = Matrix()
    private val values = FloatArray(9)
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
        synchronized(buffers.lock) { holder = null }
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
        synchronized(buffers.lock) {
            val h = holder ?: return
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
            try {
                canvas.drawColor(Color.BLACK)
                paint.isFilterBitmap = smooth
                for (p in placements) {
                    val slot = buffers.slots[p.screen.ordinal]
                    val bmp = slot.front ?: continue
                    val info = slot.frontInfo ?: continue
                    p.jpegToSurface(info.jpegWidth, info.jpegHeight).toMatrixValues(values)
                    // jpeg dims may differ from bitmap dims only for even/odd reconstruction (full width bitmap)
                    if (bmp.width != info.jpegWidth) {
                        p.jpegToSurface(bmp.width, info.jpegHeight).toMatrixValues(values)
                    }
                    matrix.setValues(values)
                    canvas.drawBitmap(bmp, matrix, paint)
                    if (slot.fresh) {
                        slot.fresh = false
                        hadNew = true
                        receiveToDisplayMs.add((now - slot.frontReceivedAt) / 1e6)
                    }
                }
                drawCursor(canvas)
            } finally {
                try {
                    h.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    AppLog.e("unlockCanvasAndPost falló", e)
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
