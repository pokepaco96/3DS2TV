package com.threedstwo.tv.core

import com.threedstwo.tv.core.session.AdaptiveQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveQualityTest {
    /** Feeds per-second windows of (complete, incomplete) frames; returns the decisions. */
    private fun run(a: AdaptiveQuality, windows: List<Pair<Int, Int>>): List<Int?> {
        var ok = 0L; var bad = 0L
        a.onWindow(0, 0)
        return windows.map { (o, b) -> ok += o; bad += b; a.onWindow(ok, bad) }
    }

    @Test fun dropsAfterTwoBadWindowsThenCoolsDown() {
        val a = AdaptiveQuality(ceiling = 80)
        val d = run(a, List(6) { 45 to 15 }) // 25 % loss every second (worse than the real test: 10 %)
        assertEquals(listOf(null, 70, null, null, null, null), d)
        assertEquals(70, a.quality)
    }

    @Test fun neverBelowFloorAndNoOscillationOnSteadyModerateLoss() {
        val a = AdaptiveQuality(ceiling = 80, floor = 60)
        run(a, List(60) { 40 to 20 })
        assertEquals(60, a.quality)
        // 7 % loss: between thresholds -> no change in either direction
        val b = AdaptiveQuality(ceiling = 80)
        assertEquals(List(60) { null }, run(b, List(60) { 93 to 7 }))
    }

    @Test fun recoversSlowlyUpToCeiling() {
        val a = AdaptiveQuality(ceiling = 80)
        run(a, List(2) { 40 to 20 })
        assertEquals(70, a.quality)
        val d = run(a, List(40) { 60 to 0 })
        assertEquals(80, a.quality)
        assertEquals(2, d.count { it != null }) // +5 twice, spaced by 8 good windows + cooldown
    }

    @Test fun idleWindowsAndCounterResetsAreIgnored() {
        val a = AdaptiveQuality(ceiling = 80)
        assertEquals(List(10) { null }, run(a, List(10) { 3 to 3 })) // < 10 frames: menu/pause
        assertNull(a.onWindow(0, 0)) // new session: counters went back to 0
        assertEquals(80, a.quality)
    }

    @Test fun ceilingChangeClampsQuality() {
        val a = AdaptiveQuality(ceiling = 90)
        a.ceiling = 70
        assertEquals(70, a.quality)
    }
}
