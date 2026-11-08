package com.threedstwo.tv.core.layout

import com.threedstwo.tv.core.ntr.Screen
import kotlin.math.floor
import kotlin.math.min

enum class ViewMode(val label: String) { TOP_ONLY("Solo superior"), BOTTOM_ONLY("Solo inferior"), BOTH("Ambas") }

enum class Arrangement(val label: String) { VERTICAL("Vertical"), HORIZONTAL("Horizontal") }

/** Clockwise rotation applied to a screen as it appears on the TV. */
enum class Rotation(val degrees: Int) {
    R0(0), R90(90), R180(180), R270(270);

    fun next(): Rotation = entries[(ordinal + 1) % entries.size]
    val swapsAxes: Boolean get() = this == R90 || this == R270

    /** Maps native landscape coords (w x h) to rotated coords. */
    fun transform(w: Float, h: Float): Affine = when (this) {
        R0 -> Affine.IDENTITY
        R90 -> Affine(a = 0f, b = -1f, c = h, d = 1f, e = 0f, f = 0f)
        R180 -> Affine(a = -1f, b = 0f, c = w, d = 0f, e = -1f, f = h)
        R270 -> Affine(a = 0f, b = 1f, c = 0f, d = -1f, e = 0f, f = w)
    }
}

data class LayoutSettings(
    val viewMode: ViewMode = ViewMode.BOTH,
    val arrangement: Arrangement = Arrangement.VERTICAL,
    /** false: top first (top/left); true: bottom first. */
    val bottomFirst: Boolean = false,
    val topRotation: Rotation = Rotation.R0,
    val bottomRotation: Rotation = Rotation.R0,
    /** Gap between screens in native 3DS pixels (scaled with the image). */
    val gap: Int = 8,
    /** Relative size of each screen (1.0 = same pixel density as the console). */
    val topScale: Float = 1f,
    val bottomScale: Float = 1f,
    /** Fraction of the TV used (overscan safety / zoom out). */
    val fill: Float = 1f,
    /** Round the scale down to an integer factor when possible (pixel-perfect). */
    val integerScale: Boolean = false,
)

/** Where a screen is drawn on the TV. [rect] is in surface pixels; [rotation] as configured. */
data class Placement(
    val screen: Screen,
    val left: Float, val top: Float, val width: Float, val height: Float,
    val rotation: Rotation,
) {
    val right get() = left + width
    val bottom get() = top + height

    /** Native landscape screen coordinates (0..nativeWidth, 0..240) -> surface coordinates. */
    fun nativeToSurface(): Affine {
        val nw = screen.nativeWidth.toFloat()
        val nh = screen.nativeHeight.toFloat()
        val rot = rotation.transform(nw, nh)
        val rw = if (rotation.swapsAxes) nh else nw
        val rh = if (rotation.swapsAxes) nw else nh
        return rot.then(Affine.scale(width / rw, height / rh)).then(Affine.translate(left, top))
    }

    /**
     * Transform for a decoded NTR JPEG (jpegWidth x jpegHeight, i.e. the 3DS framebuffer which is stored
     * rotated: 240 px wide, 400/320 px tall) into surface coordinates.
     *
     * The framebuffer is shown rotated 90° counter-clockwise: native X = jpeg y, native Y = 240 - jpeg x
     * (verified against ntrviewer-hr `vs_data_str` shader and Chokistream `ImageManipulator.adjustStandard`).
     */
    fun jpegToSurface(jpegWidth: Int, jpegHeight: Int): Affine {
        val nw = screen.nativeWidth.toFloat()
        val nh = screen.nativeHeight.toFloat()
        val toNative = Affine(
            a = 0f, b = nw / jpegHeight, c = 0f,
            d = -nh / jpegWidth, e = 0f, f = nh,
        )
        return toNative.then(nativeToSurface())
    }

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

object ScreenLayout {
    fun compute(surfaceWidth: Int, surfaceHeight: Int, s: LayoutSettings): List<Placement> {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return emptyList()
        val screens = when (s.viewMode) {
            ViewMode.TOP_ONLY -> listOf(Screen.TOP)
            ViewMode.BOTTOM_ONLY -> listOf(Screen.BOTTOM)
            ViewMode.BOTH -> if (s.bottomFirst) listOf(Screen.BOTTOM, Screen.TOP) else listOf(Screen.TOP, Screen.BOTTOM)
        }
        // Rotated, user-scaled sizes in "native units"
        data class Item(val screen: Screen, val w: Float, val h: Float, val rotation: Rotation)
        val items = screens.map { sc ->
            val rot = if (sc == Screen.TOP) s.topRotation else s.bottomRotation
            val scale = (if (sc == Screen.TOP) s.topScale else s.bottomScale).coerceIn(0.25f, 4f)
            val nw = sc.nativeWidth * scale
            val nh = sc.nativeHeight * scale
            if (rot.swapsAxes) Item(sc, nh, nw, rot) else Item(sc, nw, nh, rot)
        }
        val gap = if (items.size > 1) s.gap.coerceIn(0, 200).toFloat() else 0f
        val vertical = s.arrangement == Arrangement.VERTICAL
        val totalW = if (vertical) items.maxOf { it.w } else items.sumOf { it.w.toDouble() }.toFloat() + gap
        val totalH = if (vertical) items.sumOf { it.h.toDouble() }.toFloat() + gap else items.maxOf { it.h }
        val fill = s.fill.coerceIn(0.5f, 1f)
        var k = min(surfaceWidth * fill / totalW, surfaceHeight * fill / totalH)
        if (s.integerScale && k >= 1f) k = floor(k)
        val originX = (surfaceWidth - totalW * k) / 2f
        val originY = (surfaceHeight - totalH * k) / 2f
        var cursor = 0f
        return items.map { it ->
            val w = it.w * k
            val h = it.h * k
            val p = if (vertical) {
                Placement(it.screen, originX + (totalW * k - w) / 2f, originY + cursor, w, h, it.rotation)
                    .also { _ -> cursor += h + gap * k }
            } else {
                Placement(it.screen, originX + cursor, originY + (totalH * k - h) / 2f, w, h, it.rotation)
                    .also { _ -> cursor += w + gap * k }
            }
            p
        }
    }
}
