package com.threedstwo.tv.core

import com.threedstwo.tv.core.session.WindowStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowStatsTest {
    @Test fun percentilesOfKnownData() {
        val w = WindowStats(1000)
        for (i in 1..100) w.add(i.toDouble())
        val p = w.percentiles()!!
        assertEquals(100, p.count)
        assertEquals(50.0, p.p50, 1.0)
        assertEquals(95.0, p.p95, 1.0)
        assertEquals(99.0, p.p99, 1.0)
        assertEquals(100.0, p.max, 0.0)
    }

    @Test fun windowIsBoundedAndForgetsOldSamples() {
        val w = WindowStats(10)
        repeat(1000) { w.add(1000.0) }
        repeat(10) { w.add(1.0) }
        val p = w.percentiles()!!
        assertEquals(10, p.count)
        assertEquals(1.0, p.max, 0.0)
        w.clear()
        assertNull(w.percentiles())
    }
}
