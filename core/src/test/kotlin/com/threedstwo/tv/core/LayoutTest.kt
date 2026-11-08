package com.threedstwo.tv.core

import com.threedstwo.tv.core.layout.Arrangement
import com.threedstwo.tv.core.layout.LayoutSettings
import com.threedstwo.tv.core.layout.Rotation
import com.threedstwo.tv.core.layout.ScreenLayout
import com.threedstwo.tv.core.layout.TouchCursor
import com.threedstwo.tv.core.layout.ViewMode
import com.threedstwo.tv.core.ntr.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val eps = 0.01f

    @Test fun topOnlyKeepsAspectOn1080p() {
        val p = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.TOP_ONLY)).single()
        assertEquals(Screen.TOP, p.screen)
        assertEquals(1080f, p.height, eps)
        assertEquals(1800f, p.width, eps) // 400:240
        assertEquals(60f, p.left, eps)
    }

    @Test fun bottomOnlyRotated90() {
        val p = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.BOTTOM_ONLY, bottomRotation = Rotation.R90)).single()
        assertEquals(p.width / p.height, 240f / 320f, 1e-4f)
        assertEquals(1080f, p.height, eps)
    }

    @Test fun bothVerticalStackedCenteredNoOverlap() {
        val ps = ScreenLayout.compute(1920, 1080, LayoutSettings(gap = 0))
        val (top, bot) = ps
        assertEquals(Screen.TOP, top.screen)
        assertEquals(top.bottom, bot.top, eps)
        assertEquals(top.width / top.height, 400f / 240f, 1e-4f)
        assertEquals(bot.width / bot.height, 320f / 240f, 1e-4f)
        assertEquals(1080f, top.height + bot.height, eps)
        assertEquals(top.left + top.width / 2, bot.left + bot.width / 2, eps)
        assertEquals(top.width / 400f, bot.width / 320f, 1e-4f) // same pixel density
    }

    @Test fun bothHorizontalAndSwapped() {
        val ps = ScreenLayout.compute(1920, 1080, LayoutSettings(arrangement = Arrangement.HORIZONTAL, bottomFirst = true, gap = 10))
        assertEquals(Screen.BOTTOM, ps[0].screen)
        assertTrue(ps[0].right <= ps[1].left)
        assertTrue(ps.all { it.left >= 0 && it.right <= 1920.01f && it.top >= 0 && it.bottom <= 1080.01f })
        val scale = ps[0].width / 320f
        assertEquals(10f * scale, ps[1].left - ps[0].right, 0.05f)
    }

    @Test fun integerScale() {
        val p = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.TOP_ONLY, integerScale = true)).single()
        assertEquals(1600f, p.width, eps) // 4x
        assertEquals(960f, p.height, eps)
    }

    @Test fun jpegTransformRotatesFramebufferCcw() {
        val p = ScreenLayout.compute(400, 240, LayoutSettings(viewMode = ViewMode.TOP_ONLY)).single()
        val m = p.jpegToSurface(240, 400)
        // JPEG (0,0) is the native bottom-left corner; JPEG (240,0) native top-left; JPEG (0,400) native bottom-right
        assertEquals(0f, m.mapX(0f, 0f), eps); assertEquals(240f, m.mapY(0f, 0f), eps)
        assertEquals(0f, m.mapX(240f, 0f), eps); assertEquals(0f, m.mapY(240f, 0f), eps)
        assertEquals(400f, m.mapX(0f, 400f), eps); assertEquals(240f, m.mapY(0f, 400f), eps)
    }

    @Test fun jpegTransformWithRotationStaysInRect() {
        for (r in Rotation.entries) {
            val p = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.BOTTOM_ONLY, bottomRotation = r)).single()
            val m = p.jpegToSurface(240, 320)
            for ((x, y) in listOf(0f to 0f, 240f to 0f, 0f to 320f, 240f to 320f)) {
                val sx = m.mapX(x, y); val sy = m.mapY(x, y)
                assertTrue("$r $sx", sx >= p.left - eps && sx <= p.right + eps)
                assertTrue("$r $sy", sy >= p.top - eps && sy <= p.bottom + eps)
            }
        }
    }

    @Test fun touchMappingFollowsRotation() {
        for (r in Rotation.entries) {
            val p = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.BOTTOM_ONLY, bottomRotation = r)).single()
            val fwd = p.nativeToSurface()
            for ((nx, ny) in listOf(10f to 20f, 300f to 200f, 160f to 120f)) {
                val back = TouchCursor.surfaceToNative(p, fwd.mapX(nx, ny), fwd.mapY(nx, ny))!!
                assertEquals(nx, back.first, 0.05f)
                assertEquals(ny, back.second, 0.05f)
            }
        }
        val p = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.BOTTOM_ONLY)).single()
        assertNull(TouchCursor.surfaceToNative(p, 0f, 0f))
        // R90: the native top-left corner appears at the top-right of the rect
        val r90 = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.BOTTOM_ONLY, bottomRotation = Rotation.R90)).single()
        val f = r90.nativeToSurface()
        assertEquals(r90.right, f.mapX(0f, 0f), eps)
        assertEquals(r90.top, f.mapY(0f, 0f), eps)
    }

    @Test fun cursorMovesInDisplayDirection() {
        for (r in Rotation.entries) {
            val p = ScreenLayout.compute(1920, 1080, LayoutSettings(viewMode = ViewMode.BOTTOM_ONLY, bottomRotation = r)).single()
            val fwd = p.nativeToSurface()
            val c = TouchCursor()
            val sx0 = fwd.mapX(c.x, c.y); val sy0 = fwd.mapY(c.x, c.y)
            c.move(1f, 0f, 0.1f, 100f, r) // push right on the TV
            val sx1 = fwd.mapX(c.x, c.y); val sy1 = fwd.mapY(c.x, c.y)
            assertTrue("$r moved right", sx1 > sx0 + 1f)
            assertEquals(sy0, sy1, 0.05f)
        }
        val c = TouchCursor()
        c.move(-1f, -1f, 100f, 1000f, Rotation.R0)
        assertEquals(0f, c.x, eps); assertEquals(0f, c.y, eps)
    }
}
