package com.threedstwo.tv.core

import com.threedstwo.tv.core.lab.LabControl
import com.threedstwo.tv.core.layout.Arrangement
import com.threedstwo.tv.core.layout.ViewMode
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.session.ConnectionState
import com.threedstwo.tv.core.session.StreamStats
import com.threedstwo.tv.core.settings.AppSettings
import com.threedstwo.tv.core.settings.PerformanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabControlTest {
    @Test fun overridesAreApplied() {
        val r = LabControl.applyOverrides(AppSettings(), mapOf(
            "ip" to "192.168.1.23", "mode" to "active", "profile" to "ULTRA_LOW_LATENCY", "audio" to "on",
            "hud" to "true", "input" to "0", "view" to "both", "layout" to "HORIZONTAL", "adaptive" to "false",
        ))
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val s = r.settings
        assertEquals("192.168.1.23", s.consoleIp)
        assertEquals(ConnectMode.ACTIVE, s.connectMode)
        assertEquals(PerformanceProfile.ULTRA_LOW_LATENCY, s.profile)
        assertEquals(70, s.jpegQuality)
        assertTrue(s.audioEnabled); assertTrue(s.showHud); assertFalse(s.inputRedirection); assertFalse(s.adaptiveQuality)
        assertEquals(ViewMode.BOTH, s.layout.viewMode)
        assertEquals(Arrangement.HORIZONTAL, s.layout.arrangement)
    }

    @Test fun explicitQualityOverridesProfile() {
        val s = LabControl.applyOverrides(AppSettings(), mapOf("profile" to "quality", "quality" to "65")).settings
        assertEquals(65, s.jpegQuality)
        assertEquals(PerformanceProfile.CUSTOM, s.profile)
    }

    @Test fun invalidValuesAreReportedAndNotApplied() {
        val base = AppSettings(consoleIp = "192.168.1.5")
        val r = LabControl.applyOverrides(base, mapOf("ip" to "999.1.1.1", "quality" to "500", "audio" to "maybe", "bogus" to "x", "view" to "SIDE"))
        assertEquals(5, r.errors.size)
        assertEquals(base, r.settings)
    }

    @Test fun jsonIsFlatAndEscaped() {
        val st = StreamStats(ConnectionState.STREAMING, "JPEG \"Compat\"", 30.0, 0.0, 29.5, 2786.4, 899, 23, 0, 0, 6.7, 7.3,
            "recibiendo", "→ 192.168.1.23:4950", "Xbox\nController", decodeP95Ms = null, stalePackets = 4)
        val j = LabControl.toJson(st, mapOf("commit" to "86e5dd8"), mapOf("presented_frames" to 1234L))
        assertTrue(j.startsWith("{") && j.endsWith("}"))
        assertTrue(j.contains("\"commit\":\"86e5dd8\""))
        assertTrue(j.contains("\"transport\":\"JPEG \\\"Compat\\\"\""))
        assertTrue(j.contains("\"controller\":\"Xbox\\nController\""))
        assertTrue(j.contains("\"decode_p95_ms\":null"))
        assertTrue(j.contains("\"fps_presented\":29.500"))
        assertTrue(j.contains("\"presented_frames\":1234"))
        assertFalse(j.contains("\n"))
    }
}
