package com.threedstwo.tv.mock

import com.threedstwo.tv.core.ntr.JpegPacketizer
import com.threedstwo.tv.core.ntr.RemotePlayRequest
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import com.threedstwo.tv.core.session.CheckItem
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.session.ConnectionChecklist
import com.threedstwo.tv.core.session.ConnectionState
import com.threedstwo.tv.core.session.NtrSession
import com.threedstwo.tv.core.session.SessionConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Negative / regression tests found in the continuation audit (docs/CONTINUATION_AUDIT.md).
 * Uses several loopback addresses (127.0.0.1/.2/.3) as different hosts.
 */
class SessionRobustnessTest {
    private val cleanups = mutableListOf<() -> Unit>()
    @After fun tearDown() = cleanups.reversed().forEach { runCatching { it() } }

    private fun freeUdpPort() = DatagramSocket(0).use { it.localPort }
    private fun freeTcpPort() = ServerSocket(0).use { it.localPort }

    private class Rec : NtrSession.Listener {
        val states = CopyOnWriteArrayList<ConnectionState>()
        val frames = AtomicInteger()
        val learned = AtomicReference<String>()
        override fun onStateChanged(state: ConnectionState, detail: String?) { states += state }
        override fun onVideoFrame(info: VideoFrameInfo, data: ByteArray, length: Int) { frames.incrementAndGet() }
        override fun onConsoleAddressLearned(ip: String) { learned.set(ip) }
    }

    private fun waitFor(ms: Long = 6000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private fun console(addr: String = "127.0.0.1", configure: FakeNtrConsole.() -> Unit = {}) =
        FakeNtrConsole(controlPort = freeTcpPort(), bindAddress = addr, fps = 60).apply(configure)
            .also { it.start(); cleanups += { it.stop() } }

    private fun session(
        rec: Rec, consoleIp: String?, controlPort: Int, mode: ConnectMode = ConnectMode.ACTIVE,
        port: Int = freeUdpPort(), maxAttempts: Int = 3,
    ) = NtrSession(
        SessionConfig(
            consoleIp = consoleIp, mode = mode, request = RemotePlayRequest(viewerPort = port), controlPort = controlPort,
            bindAddress = "127.0.0.1", videoTimeoutMs = 800, firstFrameRetryMs = 600, firstFrameTimeoutMs = 1500,
            maxReconnectAttempts = maxAttempts, heartbeatIntervalMs = 100,
        ),
        rec,
    ).also { s -> s.start(); cleanups += { s.stop() } }

    private fun checklist(s: NtrSession, framesSeen: Int) = ConnectionChecklist.evaluate(
        ConnectionChecklist.Inputs(
            s.config.mode, s.state, s.diagnostics, s.assemblerStats.framesCompleted,
            s.state == ConnectionState.STREAMING, framesSeen.toLong(), true, s.consoleIp(), null,
        ),
    )

    @Test fun tcpRepliesFragmentedByteByByteAreParsed() {
        val c = console { fragmentTcpReplies = true }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", c.boundControlPort)
        assertTrue(waitFor { s.state == ConnectionState.STREAMING })
        assertTrue("heartbeat replies parsed", waitFor { s.diagnostics.tcpReplies >= 3 })
        assertEquals(0L, s.diagnostics.tcpProtocolErrors)
    }

    @Test fun badMagicReplyIsReportedAndVideoContinues() {
        val c = console { badMagicReplies = true }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", c.boundControlPort)
        assertTrue(waitFor { s.diagnostics.tcpProtocolErrors > 0 })
        assertTrue(waitFor { s.state == ConnectionState.STREAMING && rec.frames.get() > 20 })
    }

    @Test fun hugeDataLenIsRejectedWithoutAllocating() {
        val c = console { hugeDataLenReplies = true }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", c.boundControlPort)
        assertTrue(waitFor { s.diagnostics.tcpProtocolErrors > 0 })
        assertTrue(waitFor { rec.frames.get() > 20 })
    }

    @Test fun consoleClosingTcpAfterCommandKeepsVideo() {
        val c = console { closeTcpAfterCommand = true }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", c.boundControlPort)
        assertTrue(waitFor { s.state == ConnectionState.STREAMING })
        val before = rec.frames.get()
        Thread.sleep(1500)
        assertTrue("video keeps flowing without TCP", rec.frames.get() > before + 30)
        assertEquals(ConnectionState.STREAMING, s.state)
    }

    @Test fun ignoredFirstCommandIsResent() {
        val c = console { ignoreFirstCommands = 1 }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", c.boundControlPort)
        assertTrue(waitFor { s.state == ConnectionState.STREAMING })
        assertTrue(s.diagnostics.remotePlayCommands >= 2)
        assertTrue(c.commands.size >= 2)
    }

    @Test fun datagramsWithoutCompleteFramesNeverCountAsStreaming() {
        val c = console { dropEndPackets = true }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", c.boundControlPort, maxAttempts = 1)
        assertTrue(waitFor(10000) { s.state == ConnectionState.ERROR })
        assertFalse(rec.states.contains(ConnectionState.STREAMING))
        assertEquals(0, rec.frames.get())
        assertTrue(s.diagnostics.videoPackets > 0)
        assertTrue(s.stateDetail!!.contains("ningún frame completo"))
        val items = checklist(s, 0)
        assertEquals(CheckItem.Status.OK, items.first { it.label.startsWith("Paquetes UDP") }.status)
        assertTrue(items.first { it.label.startsWith("Frames JPEG") }.status != CheckItem.Status.OK)
        assertTrue(items.first { it.label.startsWith("Imagen") }.status != CheckItem.Status.OK)
    }

    @Test fun duplicatedPacketsDoNotDuplicateFrames() {
        val c = console { duplicatePackets = true }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", c.boundControlPort)
        assertTrue(waitFor { rec.frames.get() > 30 })
        Thread.sleep(300)
        assertTrue(s.assemblerStats.duplicatePackets > 0)
        assertTrue("frames ≤ frames sent", rec.frames.get() <= c.framesSent.get())
    }

    @Test fun videoFromAnotherHostIsIgnoredAndDiagnosed() {
        // The user typed 127.0.0.2 but the console that streams to us is 127.0.0.3.
        val port = freeUdpPort()
        val other = console("127.0.0.3")
        other.streamTo(InetAddress.getByName("127.0.0.1"), port)
        val rec = Rec()
        val s = session(rec, "127.0.0.2", freeTcpPort(), port = port, maxAttempts = 1)
        assertTrue(waitFor { s.diagnostics.foreignNtrPackets > 10 })
        assertEquals("127.0.0.3", s.diagnostics.lastForeignSource)
        assertEquals(0, rec.frames.get())
        assertFalse(rec.states.contains(ConnectionState.STREAMING))
        val udp = checklist(s, 0).first { it.label.startsWith("Paquetes UDP") }
        assertEquals(CheckItem.Status.FAILED, udp.status)
        assertTrue(udp.hint!!.contains("127.0.0.3"))
    }

    @Test fun passiveModeIsNotHijackedByIncompleteStrayVideo() {
        val port = freeUdpPort()
        val rec = Rec()
        val s = session(rec, null, freeTcpPort(), ConnectMode.PASSIVE, port)
        // Intruder at 127.0.0.3 keeps sending the first packet of frames that never complete.
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val intruder = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.3"), 0))
        cleanups += { stop.set(true); intruder.close() }
        val first = JpegPacketizer.packetize(ByteArray(5000) { 7 }, Screen.TOP, 1)[0]
        Thread {
            while (!stop.get()) {
                runCatching { intruder.send(DatagramPacket(first, first.size, InetSocketAddress("127.0.0.1", port))) }
                Thread.sleep(10)
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        assertNull("no lock without a complete frame", s.consoleIp())
        val real = console("127.0.0.1")
        real.streamTo(InetAddress.getByName("127.0.0.1"), port)
        assertTrue(waitFor(8000) { s.state == ConnectionState.STREAMING })
        assertEquals("127.0.0.1", rec.learned.get())
        assertEquals("127.0.0.1", s.consoleIp())
    }

    @Test fun udpPortAlreadyInUseGivesClearError() {
        val port = freeUdpPort()
        val busy = DatagramSocket(InetSocketAddress(port))
        cleanups += { busy.close() }
        val rec = Rec()
        val s = session(rec, "127.0.0.1", freeTcpPort(), port = port)
        assertEquals(ConnectionState.ERROR, s.state)
        assertTrue(s.stateDetail!!.contains("puerto UDP $port"))
    }

    @Test fun stopIsFastEvenWhileConnecting() {
        val rec = Rec()
        // TEST-NET-1 (RFC 5737) is never routed on a LAN: connect() blocks until timeout or fails fast.
        val s = NtrSession(
            SessionConfig(consoleIp = "192.0.2.1", request = RemotePlayRequest(viewerPort = freeUdpPort()),
                bindAddress = "127.0.0.1", connectTimeoutMs = 5000),
            rec,
        )
        s.start()
        Thread.sleep(300)
        val t0 = System.nanoTime()
        s.stop()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stop took $ms ms", ms < 1200)
        assertEquals(ConnectionState.DISCONNECTED, s.state)
    }

    @Test fun invalidIpInActiveModeIsAnError() {
        val rec = Rec()
        val s = session(rec, "300.1.1.1", freeTcpPort())
        assertEquals(ConnectionState.ERROR, s.state)
    }
}
