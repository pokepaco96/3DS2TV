package com.threedstwo.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.ntr.JpegPacketizer
import com.threedstwo.tv.core.ntr.NtrConstants
import com.threedstwo.tv.core.ntr.NtrControlPacket
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.session.ConnectionState
import com.threedstwo.tv.core.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs on an Android (TV) emulator: a minimal NTR-HR stand-in inside the test process talks to
 * the REAL app pipeline (NtrSession -> BitmapFactory decode -> SurfaceView renderer) over loopback,
 * and receives the Luma3DS input-redirection datagrams the app sends.
 * Proves the Android side works end to end; it is NOT a test against a real New 3DS.
 */
@RunWith(AndroidJUnit4::class)
class StreamEndToEndTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    @Volatile private var running = true
    private val threads = mutableListOf<Thread>()
    private val commands = CopyOnWriteArrayList<IntArray>()
    private val inputPackets = AtomicInteger()
    private val inputNeutral = AtomicInteger()
    private var server: ServerSocket? = null
    private var inputSocket: DatagramSocket? = null

    @After fun tearDown() {
        running = false
        server?.close()
        inputSocket?.close()
        threads.forEach { it.join(1000) }
    }

    private fun jpeg(screen: Screen, frame: Int): ByteArray {
        // framebuffer orientation: 240 wide, 400/320 tall
        val bmp = Bitmap.createBitmap(240, if (screen == Screen.TOP) 400 else 320, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if (screen == Screen.TOP) Color.rgb(30, 30, 80) else Color.rgb(80, 30, 30))
        c.drawRect(0f, (frame * 4 % 300).toFloat(), 240f, (frame * 4 % 300 + 20).toFloat(), Paint().apply { color = Color.YELLOW })
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun spawn(body: () -> Unit) {
        threads += Thread(body).apply { isDaemon = true; start() }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var pos = 0
        while (pos < buf.size) {
            val n = input.read(buf, pos, buf.size - pos)
            if (n < 0) throw java.io.IOException("closed")
            pos += n
        }
    }

    private fun startFakeConsole() {
        val srv = ServerSocket()
        srv.reuseAddress = true
        srv.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), NtrConstants.CONTROL_PORT))
        server = srv
        val udp = DatagramSocket()
        spawn {
            try {
                val sock = srv.accept()
                val input = sock.getInputStream()
                val out = sock.getOutputStream()
                val hdr = ByteArray(NtrConstants.TCP_HEADER_SIZE)
                var target: InetSocketAddress? = null
                spawn {
                    var frame = 0
                    while (running) {
                        val t = target
                        if (t != null) {
                            for (sc in Screen.entries) {
                                for (p in JpegPacketizer.packetize(jpeg(sc, frame), sc, frame and 0xFF)) {
                                    udp.send(DatagramPacket(p, p.size, t))
                                }
                            }
                            frame++
                        }
                        Thread.sleep(33)
                    }
                }
                while (running) {
                    readFully(input, hdr)
                    val p = NtrControlPacket.decode(hdr)
                    if (p.dataLen > 0) readFully(input, ByteArray(p.dataLen))
                    if (p.cmd == NtrConstants.CMD_REMOTE_PLAY) {
                        commands += p.args
                        target = InetSocketAddress(sock.inetAddress, p.args[4] and 0xFFFF)
                    } else {
                        out.write(NtrControlPacket(seq = p.seq, type = 0, cmd = 0).encode())
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        val ir = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), InputFrame.PORT))
        ir.soTimeout = 200
        inputSocket = ir
        spawn {
            val buf = ByteArray(64)
            val pkt = DatagramPacket(buf, buf.size)
            val neutral = InputFrame.NEUTRAL.encode()
            while (running) {
                try {
                    pkt.setData(buf, 0, buf.size)
                    ir.receive(pkt)
                    inputPackets.incrementAndGet()
                    if (pkt.length == 20 && buf.copyOf(20).contentEquals(neutral)) inputNeutral.incrementAndGet()
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return cond()
    }

    @Test fun streamsDecodesAndRendersFromFakeConsole() {
        startFakeConsole()
        val app = ThreeDsTvApp.of(ctx)
        app.saveSettings(AppSettings(consoleIp = "127.0.0.1", connectMode = ConnectMode.ACTIVE, inputRedirection = true, showHud = true))
        // ensure prefs are flushed before the activity reads them
        Thread.sleep(300)
        val intent = Intent(ctx, StreamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = inst.startActivitySync(intent)
        try {
            assertTrue("streaming state, got ${SessionProbe.state}", waitFor(30000) { SessionProbe.state == ConnectionState.STREAMING })
            assertTrue("frames presented: ${SessionProbe.presentedFrames}", waitFor(30000) { SessionProbe.presentedFrames > 30 })
            assertTrue("decoded: ${SessionProbe.decodedFrames}", SessionProbe.decodedFrames > 30)
            assertEquals(0L, SessionProbe.decodeErrors)
            assertTrue(SessionProbe.surfaceWidth > 0)
            val cmd = commands.first()
            assertEquals(NtrConstants.GUARDING_MAGIC, cmd[3])
            assertEquals(NtrConstants.DEFAULT_VIEWER_PORT, cmd[4])
            // Input redirection keep-alive reaches the console address with the neutral state
            assertTrue("input packets: ${inputPackets.get()}", waitFor(5000) { inputNeutral.get() > 10 })
        } finally {
            activity.finish()
        }
        assertTrue(waitFor(5000) { SessionProbe.state == ConnectionState.DISCONNECTED || !running })
    }
}
