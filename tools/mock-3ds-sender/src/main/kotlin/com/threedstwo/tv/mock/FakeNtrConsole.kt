package com.threedstwo.tv.mock

import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.ntr.JpegPacketizer
import com.threedstwo.tv.core.ntr.NtrConstants
import com.threedstwo.tv.core.ntr.NtrControlPacket
import com.threedstwo.tv.core.ntr.Screen
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Development/testing stand-in for a New 3DS running NTR-HR + Luma3DS.
 *
 * Implements the console side of what 3DS2TV uses:
 *  - TCP control server (NTR port 8000 by default): heartbeats, cmd 901 Remote Play;
 *  - JPEG Compat video over UDP towards the TCP peer (like NTR-HR learning the viewer address);
 *  - optional NTR-HR plain-UDP DSP audio (sine wave);
 *  - optional Luma3DS input redirection receiver (UDP 4950 by default) that records frames.
 * Fault injection: packet loss, reordering, pausing the stream, sending unsupported (KCP-like) data.
 *
 * NOT a 3DS emulator: it never claims to validate behaviour of the real console.
 */
class FakeNtrConsole(
    val controlPort: Int = NtrConstants.CONTROL_PORT,
    val inputPort: Int = -1,
    val bindAddress: String = "0.0.0.0",
    val fps: Int = 60,
    var lossProbability: Double = 0.0,
    var reorder: Boolean = false,
    val log: (String) -> Unit = {},
) {
    data class RemotePlayCommand(val args: IntArray, val from: InetAddress)

    val commands = CopyOnWriteArrayList<RemotePlayCommand>()
    val heartbeats = AtomicInteger()
    val inputFrames = CopyOnWriteArrayList<ByteArray>()
    val framesSent = AtomicInteger()
    val audioPacketsSent = AtomicInteger()

    @Volatile var paused = false
    @Volatile var sendUnsupported = false
    @Volatile var acceptCommands = true
    @Volatile var running = false
        private set
    @Volatile private var target: InetSocketAddress? = null
    @Volatile private var quality = 80
    @Volatile private var audio = false
    @Volatile private var topPriority = true
    @Volatile private var factor = 1

    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var inputSocket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()
    private val random = Random(42)

    val boundControlPort: Int get() = server?.localPort ?: controlPort
    val boundInputPort: Int get() = inputSocket?.localPort ?: inputPort

    fun start() {
        running = true
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(bindAddress), controlPort))
            soTimeout = 200
        }
        udp = DatagramSocket()
        if (inputPort >= 0) {
            inputSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName(bindAddress), inputPort)).apply { soTimeout = 200 }
            spawn("fake-input") { inputLoop() }
        }
        spawn("fake-accept") { acceptLoop() }
        spawn("fake-stream") { streamLoop() }
        log("Fake console: TCP ${boundControlPort}" + if (inputSocket != null) ", input UDP ${boundInputPort}" else "")
    }

    /** Stream without TCP, like Remote Play started from the console menu with a fixed Viewer IP. */
    fun streamTo(address: InetAddress, port: Int, withAudio: Boolean = false) {
        audio = withAudio
        target = InetSocketAddress(address, port)
    }

    fun stopStreaming() {
        target = null
    }

    fun stop() {
        running = false
        server?.close()
        udp?.close()
        inputSocket?.close()
        threads.forEach { it.join(1000) }
    }

    private fun spawn(name: String, body: () -> Unit) {
        threads += Thread(body, name).apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running) {
            val s = try {
                server!!.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }
            spawn("fake-conn") { handle(s) }
        }
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            val input = sock.getInputStream()
            val out = sock.getOutputStream()
            val header = ByteArray(NtrConstants.TCP_HEADER_SIZE)
            try {
                while (running) {
                    readFully(input, header)
                    val p = NtrControlPacket.decode(header)
                    if (p.magic != NtrConstants.TCP_MAGIC) break
                    if (p.dataLen > 0) readFully(input, ByteArray(p.dataLen))
                    when (p.cmd) {
                        NtrConstants.CMD_HEARTBEAT -> {
                            heartbeats.incrementAndGet()
                            val text = if (heartbeats.get() == 1) "fake ntr-hr ready\n".toByteArray() else ByteArray(0)
                            out.write(NtrControlPacket(seq = p.seq, type = 0, cmd = 0, dataLen = text.size).encode())
                            out.write(text)
                            out.flush()
                        }
                        NtrConstants.CMD_REMOTE_PLAY -> if (acceptCommands) {
                            commands += RemotePlayCommand(p.args.copyOf(), sock.inetAddress)
                            quality = p.args[1]
                            topPriority = (p.args[0] shr 8) and 1 == 1
                            factor = p.args[0] and 0xFF
                            val port = if (p.args[3] == NtrConstants.GUARDING_MAGIC) p.args[4] and 0xFFFF else NtrConstants.DEFAULT_VIEWER_PORT
                            audio = p.args[3] == NtrConstants.GUARDING_MAGIC && p.args[5] > 0
                            target = InetSocketAddress(sock.inetAddress, port)
                            paused = false
                            log("901: quality=${p.args[1]} port=$port audio=$audio")
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }
    }

    private fun streamLoop() {
        var frame = 0
        val periodNs = 1_000_000_000L / fps
        var next = System.nanoTime()
        var audioSeq = 0
        var phase = 0.0
        var lastAudioNs = System.nanoTime()
        var audioPrev = ByteArray(NtrConstants.AUDIO_FRAME_BYTES)
        while (running) {
            next += periodNs
            val t = target
            if (t != null && !paused) {
                val screens = when {
                    factor == 0 -> listOf(if (topPriority) Screen.TOP else Screen.BOTTOM)
                    frame % (factor + 1) == factor -> listOf(if (topPriority) Screen.BOTTOM else Screen.TOP)
                    else -> listOf(if (topPriority) Screen.TOP else Screen.BOTTOM)
                }
                for (sc in screens) {
                    if (sendUnsupported) {
                        send(byteArrayOf(0x55, 0x7F, 0x13, 0x10, 0, 0, 0, 0), t)
                        continue
                    }
                    val packets = JpegPacketizer.packetize(TestPattern.frameJpeg(sc, frame, quality), sc, frame and 0xFF)
                    val ordered = if (reorder) packets.shuffled(random) else packets
                    for (p in ordered) if (random.nextDouble() >= lossProbability) send(p, t)
                    framesSent.incrementAndGet()
                }
                if (audio) {
                    // 32728 Hz / 160 samples ≈ 204.5 audio frames per second, generated in real time
                    val now = System.nanoTime()
                    val due = ((now - lastAudioNs) * NtrConstants.AUDIO_SAMPLE_RATE / 1_000_000_000L / NtrConstants.AUDIO_FRAME_SAMPLES).toInt()
                    repeat(due.coerceAtMost(20)) {
                        val cur = ByteArray(NtrConstants.AUDIO_FRAME_BYTES)
                        for (i in 0 until NtrConstants.AUDIO_FRAME_SAMPLES) {
                            val v = (sin(phase) * 6000).toInt()
                            phase += 2 * PI * 440.0 / NtrConstants.AUDIO_SAMPLE_RATE
                            for (c in 0 until 2) {
                                cur[(i * 2 + c) * 2] = v.toByte()
                                cur[(i * 2 + c) * 2 + 1] = (v shr 8).toByte()
                            }
                        }
                        audioSeq = (audioSeq + 1) and 0xFF
                        val pkt = JpegPacketizer.audioPacket(audioSeq, listOf(audioPrev, cur))
                        audioPrev = cur
                        if (random.nextDouble() >= lossProbability) send(pkt, t)
                        audioPacketsSent.incrementAndGet()
                    }
                    if (due > 0) lastAudioNs += due.toLong() * NtrConstants.AUDIO_FRAME_SAMPLES * 1_000_000_000L / NtrConstants.AUDIO_SAMPLE_RATE
                } else {
                    lastAudioNs = System.nanoTime()
                }
                frame++
            }
            val sleep = next - System.nanoTime()
            if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt()) else next = System.nanoTime()
        }
    }

    private fun send(p: ByteArray, t: InetSocketAddress) {
        try {
            udp?.send(DatagramPacket(p, p.size, t))
        } catch (_: IOException) {
        }
    }

    private fun inputLoop() {
        val buf = ByteArray(64)
        val pkt = DatagramPacket(buf, buf.size)
        var last: String? = null
        while (running) {
            try {
                pkt.setData(buf, 0, buf.size)
                inputSocket!!.receive(pkt)
                val data = buf.copyOf(pkt.length)
                inputFrames += data
                val desc = describeInput(data)
                if (desc != last) log("input: $desc")
                last = desc
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var pos = 0
        while (pos < buf.size) {
            val n = input.read(buf, pos, buf.size - pos)
            if (n < 0) throw IOException("closed")
            pos += n
        }
    }

    companion object {
        fun describeInput(d: ByteArray): String {
            if (d.size < 12) return "short(${d.size})"
            fun w(i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or
                ((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 3].toInt() and 0xFF) shl 24)
            val hid = w(0).inv() and 0xFFF
            val names = com.threedstwo.tv.core.input.ThreeDsButton.entries.filter { hid and (1 shl it.bit) != 0 }.map { it.name }
            val touch = w(4)
            val touchS = if (touch == InputFrame.TOUCH_NONE) "-" else "${touch and 0xFFF},${(touch shr 12) and 0xFFF}"
            val cp = w(8)
            val ir = if (d.size >= 20) w(12) else 0x80800081.toInt()
            val zr = (ir shr 9) and 1
            val zl = (ir shr 10) and 1
            return "btn=$names touch=$touchS cpad=${cp and 0xFFF},${(cp shr 12) and 0xFFF} " +
                "cstick=${(ir shr 16) and 0xFF},${(ir ushr 24) and 0xFF} zl=$zl zr=$zr special=${if (d.size >= 20) w(16) else 0}"
        }
    }
}
