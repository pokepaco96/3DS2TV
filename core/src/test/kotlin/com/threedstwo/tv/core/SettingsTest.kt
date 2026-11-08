package com.threedstwo.tv.core

import com.threedstwo.tv.core.input.Action
import com.threedstwo.tv.core.input.MappingPreset
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.layout.Arrangement
import com.threedstwo.tv.core.layout.Rotation
import com.threedstwo.tv.core.layout.ViewMode
import com.threedstwo.tv.core.settings.AppSettings
import com.threedstwo.tv.core.settings.MemoryStore
import com.threedstwo.tv.core.settings.ScalingFilter
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.util.Ipv4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun defaultsWhenEmpty() {
        assertEquals(AppSettings(), AppSettings.load(MemoryStore()))
    }

    @Test fun roundTrip() {
        val s = AppSettings(
            consoleIp = "192.168.1.50", connectMode = ConnectMode.PASSIVE, jpegQuality = 70, qosMbps = 12,
            topPriority = false, priorityFactor = 3, filter = ScalingFilter.BILINEAR, audioEnabled = true, audioVolume = 40,
            mappingPreset = MappingPreset.XBOX_LABELS,
            mapping = com.threedstwo.tv.core.input.ButtonMapping.preset(MappingPreset.XBOX_LABELS).with(PadControl.R3, Action.TOUCH),
            layout = AppSettings().layout.copy(viewMode = ViewMode.BOTTOM_ONLY, arrangement = Arrangement.HORIZONTAL,
                bottomFirst = true, topRotation = Rotation.R270, bottomRotation = Rotation.R90, gap = 20, integerScale = true),
            circleDeadzone = 0.3f, cursorSpeed = 500, touchButton = PadControl.L1, tvRemoteAsPad = true, preferredController = "abc",
        )
        val store = MemoryStore()
        s.save(store)
        assertEquals(s, AppSettings.load(store))
    }

    @Test fun corruptValuesFallBackOrClamp() {
        val store = MemoryStore(mutableMapOf("jpegQuality" to "999", "viewMode" to "NOPE", "circleDeadzone" to "NaN", "showHud" to "maybe"))
        val s = AppSettings.load(store)
        assertEquals(100, s.jpegQuality)
        assertEquals(ViewMode.BOTH, s.layout.viewMode)
        assertEquals(0.15f, s.circleDeadzone)
        assertFalse(s.showHud)
    }

    @Test fun ipValidation() {
        assertTrue(Ipv4.isValidHostAddress("192.168.1.20"))
        assertFalse(Ipv4.isValidHostAddress("192.168.1.256"))
        assertFalse(Ipv4.isValidHostAddress("192.168.1"))
        assertFalse(Ipv4.isValidHostAddress("192.168.01.2"))
        assertFalse(Ipv4.isValidHostAddress("127.0.0.1"))
        assertFalse(Ipv4.isValidHostAddress("192.168.1.255"))
        assertFalse(Ipv4.isValidHostAddress("3ds.local"))
        assertTrue(Ipv4.sameSubnet("192.168.1.20", "192.168.1.30", 24))
        assertFalse(Ipv4.sameSubnet("192.168.1.20", "192.168.2.30", 24))
        assertTrue(Ipv4.isPrivate("10.0.0.5"))
    }

    @Test fun ipMaskingForReports() {
        assertEquals("consola 192.168.x.x, tv 10.0.x.x:8001", Ipv4.mask("consola 192.168.1.50, tv 10.0.0.7:8001"))
        assertEquals("versión 0.1.0 build 999.1", Ipv4.mask("versión 0.1.0 build 999.1"))
    }
}
