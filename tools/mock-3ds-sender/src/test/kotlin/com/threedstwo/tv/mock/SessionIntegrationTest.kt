package com.threedstwo.tv.mock

import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.input.InputRedirectionSender
import com.threedstwo.tv.core.input.ThreeDsButton
import com.threedstwo.tv.core.layout.LayoutSettings
import com.threedstwo.tv.core.layout.ScreenLayout
import com.threedstwo.tv.core.layout.ViewMode
import com.threedstwo.tv.core.ntr.RemotePlayRequest
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.session.ConnectionState
import com.threedstwo.tv.core.session.NtrSession
import com.threedstwo.tv.core.session.SessionConfig
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.io.ByteArrayInputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * End-to-end tests over real loopback sockets: NtrSession (receiver used by the app) against
 * FakeNtrConsole (simulated NTR-HR). These validate our implementation of the protocol, NOT the
 * behaviour of a real New 3DS (see docs/HARDWARE_VALIDATION.md).
 */
class SessionIntegrationTest {
    private val loopback = "127.0.0.1"
    private val cleanups = mutableListOf<() -> Unit>()

    @After fun tearDown() = cleanups.reversed().forEach { it() }

    private fun freeUdpPort() = DatagramSocket(0).use { it.localPort }
    private fun freeTcpPort() = ServerSocket(0).use { it.localPort }

    private class Recorder : NtrSession.Listener {
        val states = CopyOnWriteArrayList<ConnectionState>()
        val details = CopyOnWriteArrayList<String?>()
        val frames = ConcurrentHashMap<Screen, AtomicInteger>()
        val lastJpeg = ConcurrentHashMap<Screen, ByteArray>()
        val lastInfo = AtomicReference<VideoFrameInfo>()
        val audioFrames = AtomicInteger()
        val learned = AtomicReference<String>()
        override fun onStateChanged(state: ConnectionState, detail: String?) { states += state; details += detail }
        override fun onVideoFrame(info: VideoFrameInfo, data: ByteArray, length: Int) {
            frames.computeIfAbsent(info.screen) { AtomicInteger() }.incrementAndGet()
            lastJpeg[info.screen] = data.copyOf(length)
            lastInfo.set(info)
        }
        override fun onAudioFrame(pcm: ByteArray, offset: Int, silenceFramesBefore: Int) { audioFrames.incrementAndGet() }
        override fun onConsoleAddressLearned(ip: String) { learned.set(ip) }
        fun count(s: Screen) = frames[s]?.get() ?: 0
    }

    private fun waitFor(timeoutMs: Long = 6000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun console(fps: Int = 60, loss: Double = 0.0, inputPort: Int = -1): FakeNtrConsole =
        FakeNtrConsole(controlPort = freeTcpPort(), inputPort = inputPort, bindAddress = loopback, fps = fps, lossProbability = loss)
            .also { it.start(); cleanups += { it.stop() } }

    private fun session(c: FakeNtrConsole?, rec: Recorder, mode: ConnectMode = ConnectMode.ACTIVE, request: RemotePlayRequest = RemotePlayRequest(viewerPort = freeUdpPort()), maxAttempts: Int = 8): NtrSession =
        NtrSession(
            SessionConfig(
                consoleIp = if (mode == ConnectMode.ACTIVE) loopback else null,
                mode = mode,
                request = request,
                controlPort = c?.boundControlPort ?: freeTcpPort(),
                bindAddress = loopback,
                videoTimeoutMs = 800,
                firstFrameRetryMs = 600,
                firstFrameTimeoutMs = 1500,
                maxReconnectAttempts = maxAttempts,
                heartbeatIntervalMs = 200,
            ),
            rec,
        ).also { s -> s.start(); cleanups += { s.stop() } }

    private fun near(c: Int, expected: Color) =
        abs(((c shr 16) and 0xFF) - expected.red) < 40 && abs(((c shr 8) and 0xFF) - expected.green) < 40 && abs((c and 0xFF) - expected.blue) < 40

    @Test fun activeSessionStreamsBothScreensWithCorrectOrientation() {
        val c = console()
        val rec = Recorder()
        val s = session(c, rec)
        assertTrue("streaming", waitFor { s.state == ConnectionState.STREAMING })
        assertTrue("both screens", waitFor { rec.count(Screen.TOP) > 5 && rec.count(Screen.BOTTOM) > 5 })
        assertTrue(rec.states.contains(ConnectionState.CONNECTING))

        // Command as NTR-HR expects it (JPEG compat, our UDP port).
        val cmd = c.commands.first()
        assertEquals(1404036572, cmd.args[3])
        assertEquals(s.config.viewerPort, cmd.args[4])
        assertTrue("heartbeats", waitFor { c.heartbeats.get() > 0 })

        // Decode exactly like the app would conceptually: JPEG -> framebuffer -> transform to native.
        val jpeg = rec.lastJpeg[Screen.TOP]!!
        val fb = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(240, fb.width)
        assertEquals(400, fb.height)
        val p = ScreenLayout.compute(400, 240, LayoutSettings(viewMode = ViewMode.TOP_ONLY)).single()
        val inv = p.jpegToSurface(240, 400).inverse()
        fun nativePixel(nx: Float, ny: Float): Int {
            val jx = inv.mapX(nx, ny).toInt().coerceIn(0, 239)
            val jy = inv.mapY(nx, ny).toInt().coerceIn(0, 399)
            return fb.getRGB(jx, jy)
        }
        assertTrue("red top-left", near(nativePixel(15f, 15f), TestPattern.TOP_LEFT_COLOR))
        assertTrue("blue top-right", near(nativePixel(385f, 15f), TestPattern.TOP_RIGHT_COLOR))
        assertTrue("green bottom-left", near(nativePixel(15f, 225f), TestPattern.BOTTOM_LEFT_COLOR))
    }

    @Test fun reconnectsAfterVideoLoss() {
        val c = console()
        val rec = Recorder()
        val s = session(c, rec)
        assertTrue(waitFor { s.state == ConnectionState.STREAMING })
        c.stopStreaming() // Wi-Fi drop / NTR-HR stopped
        assertTrue("reconnecting", waitFor { s.state == ConnectionState.RECONNECTING })
        val before = rec.count(Screen.TOP)
        assertTrue("streaming again", waitFor(8000) { s.state == ConnectionState.STREAMING && rec.count(Screen.TOP) > before + 5 })
        assertTrue(c.commands.size >= 2)
        assertTrue(s.reconnections >= 1)
    }

    @Test fun survivesPacketLoss() {
        val c = console(loss = 0.03)
        val rec = Recorder()
        val s = session(c, rec)
        assertTrue(waitFor { rec.count(Screen.TOP) + rec.count(Screen.BOTTOM) > 30 })
        assertTrue("some frames incomplete", waitFor { s.assemblerStats.framesIncomplete > 0 })
        assertEquals(ConnectionState.STREAMING, s.state)
    }

    @Test fun passiveModeLearnsConsoleAddress() {
        val c = console()
        val rec = Recorder()
        val port = freeUdpPort()
        val s = session(null, rec, ConnectMode.PASSIVE, RemotePlayRequest(viewerPort = port))
        assertTrue(waitFor { rec.states.firstOrNull() == ConnectionState.SEARCHING })
        c.streamTo(InetAddress.getByName(loopback), port)
        assertTrue(waitFor { s.state == ConnectionState.STREAMING })
        assertEquals(loopback, rec.learned.get())
        assertEquals(loopback, s.consoleIp())
    }

    @Test fun audioIsDeliveredWhenRequested() {
        val c = console()
        val rec = Recorder()
        session(c, rec, request = RemotePlayRequest(viewerPort = freeUdpPort(), audio = true))
        assertTrue(waitFor { rec.audioFrames.get() > 100 })
        assertEquals(1, c.commands.first().args[5])
    }

    @Test fun unsupportedStreamReportsError() {
        val c = console()
        c.sendUnsupported = true
        val rec = Recorder()
        val s = session(c, rec)
        assertTrue(waitFor { s.state == ConnectionState.ERROR })
        assertTrue(s.stateDetail!!.contains("Reliable Stream"))
    }

    @Test fun unreachableConsoleGivesUpWithError() {
        val rec = Recorder()
        val s = session(null, rec, maxAttempts = 2)
        assertTrue(waitFor(10000) { s.state == ConnectionState.ERROR })
        assertTrue(s.stateDetail!!.contains("NTR-HR"))
    }

    @Test fun connectedButNoVideoIsDiagnosed() {
        val c = console()
        c.acceptCommands = false // TCP works but console never streams (e.g. client isolation)
        val rec = Recorder()
        val s = session(c, rec, maxAttempts = 1)
        assertTrue(waitFor(12000) { s.state == ConnectionState.ERROR })
        assertTrue(rec.states.contains(ConnectionState.CONNECTED))
        assertTrue(s.stateDetail!!.contains("UDP"))
    }

    @Test fun settingsUpdateIsSentLive() {
        val c = console()
        val rec = Recorder()
        val s = session(c, rec)
        assertTrue(waitFor { s.state == ConnectionState.STREAMING })
        s.updateRequest(RemotePlayRequest(jpegQuality = 55, topScreenPriority = false, priorityFactor = 0))
        assertTrue(waitFor { c.commands.any { it.args[1] == 55 && it.args[0] == 0 } })
        assertEquals(s.config.viewerPort, c.commands.last().args[4])
        val bottomBefore = rec.count(Screen.BOTTOM)
        val topBefore = rec.count(Screen.TOP)
        assertTrue(waitFor { rec.count(Screen.BOTTOM) > bottomBefore + 10 })
        assertTrue(rec.count(Screen.TOP) - topBefore <= 2)
    }

    @Test fun stopReleasesPortForNextSession() {
        val c = console()
        val port = freeUdpPort()
        repeat(3) {
            val rec = Recorder()
            val s = session(c, rec, request = RemotePlayRequest(viewerPort = port))
            assertTrue(waitFor { s.state == ConnectionState.STREAMING })
            s.stop()
            assertEquals(ConnectionState.DISCONNECTED, s.state)
        }
    }

    @Test fun inputRedirectionSenderNeutralizesOnStop() {
        val c = console(inputPort = 0)
        val sender = InputRedirectionSender(loopback, c.boundInputPort, keepAliveMs = 16)
        sender.start()
        sender.update(InputFrame(pressed = setOf(ThreeDsButton.A), zr = true))
        assertTrue(waitFor { c.inputFrames.any { (it[0].toInt() and 0xFF) == 0xFE } })
        val countBefore = c.inputFrames.size
        Thread.sleep(200)
        assertTrue("keep-alive repeats", c.inputFrames.size >= countBefore + 5)
        sender.stop()
        assertTrue(waitFor { c.inputFrames.isNotEmpty() && c.inputFrames.last().contentEquals(InputFrame.NEUTRAL.encode()) })
        assertTrue(c.inputFrames.all { it.size == 20 })
        assertArrayEquals(InputFrame.NEUTRAL.encode(), c.inputFrames.last())
    }
}
