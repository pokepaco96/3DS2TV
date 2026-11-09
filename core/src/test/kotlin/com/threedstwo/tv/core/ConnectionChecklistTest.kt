package com.threedstwo.tv.core

import com.threedstwo.tv.core.session.CheckItem.Status
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.session.ConnectionChecklist
import com.threedstwo.tv.core.session.ConnectionState
import com.threedstwo.tv.core.session.SessionDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionChecklistTest {
    private fun eval(
        d: SessionDiagnostics, completed: Long = 0, recent: Boolean = false, presented: Long = 0,
        mode: ConnectMode = ConnectMode.ACTIVE, ir: Boolean = true, irTarget: String? = "192.168.1.50", unsupported: String? = null,
    ) = ConnectionChecklist.evaluate(
        ConnectionChecklist.Inputs(mode, ConnectionState.CONNECTED, d, completed, recent, presented, ir, irTarget, unsupported),
    ).associateBy { it.label.substringBefore(' ') + it.label.substringAfter(' ').substringBefore(' ') }

    @Test fun tcpFailureIsShownAsFailed() {
        val d = SessionDiagnostics().apply { tcpFailures = 3 }
        val items = eval(d)
        assertEquals(Status.FAILED, items.getValue("ConexiónTCP").status)
        assertEquals(Status.PENDING, items.getValue("Imagenpresentada").status)
    }

    @Test fun commandSentButNoUdpIsPending() {
        val d = SessionDiagnostics().apply { tcpConnects = 1; remotePlayCommands = 1 }
        val items = eval(d)
        assertEquals(Status.OK, items.getValue("ConexiónTCP").status)
        assertEquals(Status.OK, items.getValue("OrdenRemote").status)
        assertEquals(Status.PENDING, items.getValue("PaquetesUDP").status)
    }

    @Test fun imageOkOnlyWithRecentCompleteAndPresentedFrames() {
        val d = SessionDiagnostics().apply { tcpConnects = 1; remotePlayCommands = 1; udpPacketsFromConsole = 100; videoPackets = 100 }
        assertEquals(Status.PENDING, eval(d, completed = 0).getValue("FramesJPEG").status)
        assertEquals(Status.PENDING, eval(d, completed = 10, recent = true, presented = 0).getValue("Imagenpresentada").status)
        assertEquals(Status.FAILED, eval(d, completed = 10, recent = false, presented = 10).getValue("FramesJPEG").status)
        val ok = eval(d, completed = 10, recent = true, presented = 10)
        assertEquals(Status.OK, ok.getValue("FramesJPEG").status)
        assertEquals(Status.OK, ok.getValue("Imagenpresentada").status)
    }

    @Test fun foreignSourceAndUnsupportedFormat() {
        val d = SessionDiagnostics().apply { noteForeign("192.168.1.77") }
        assertTrue(eval(d).getValue("PaquetesUDP").hint!!.contains("192.168.1.77"))
        assertEquals(Status.FAILED, eval(SessionDiagnostics(), unsupported = "RS").getValue("FramesJPEG").status)
    }

    @Test fun passiveModeAndInputRedirection() {
        val items = eval(SessionDiagnostics(), mode = ConnectMode.PASSIVE, ir = false)
        assertEquals(Status.NOT_APPLICABLE, items.values.first { it.label.startsWith("Conexión TCP") }.status)
        assertEquals(Status.NOT_APPLICABLE, items.getValue("InputRedirection").status)
        assertTrue(items.getValue("InputRedirection").hint!!.contains("Desactivado"))
        val ir = eval(SessionDiagnostics()).getValue("InputRedirection")
        assertTrue("explains there is no ACK", ir.hint!!.contains("sin confirmación"))
    }

    @Test fun videoWithoutTcpWarnsThatSettingsDoNotApply() {
        // Seen on real hardware: console already streaming, TCP 8000 refused.
        val d = SessionDiagnostics().apply { tcpFailures = 2; lastTcpError = "conexión rechazada"; udpPacketsFromConsole = 1001; videoPackets = 1001 }
        val tcp = eval(d, completed = 10, recent = true, presented = 10).getValue("ConexiónTCP")
        assertEquals(Status.FAILED, tcp.status)
        assertTrue(tcp.hint!!.contains("NO se aplican"))
        assertTrue(tcp.hint!!.contains("rechazada"))
    }
}
