package com.threedstwo.tv.core.session

import com.threedstwo.tv.core.ntr.AudioSequencer
import com.threedstwo.tv.core.ntr.JpegFrameAssembler
import com.threedstwo.tv.core.ntr.NtrConstants
import com.threedstwo.tv.core.ntr.NtrControlPacket
import com.threedstwo.tv.core.ntr.PacketClassifier
import com.threedstwo.tv.core.ntr.PacketKind
import com.threedstwo.tv.core.ntr.RemotePlayRequest
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import com.threedstwo.tv.core.util.Ipv4
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One streaming session with an NTR-HR console.
 *
 *  - UDP socket bound to the viewer port receives video/audio for the whole session
 *    (kept open across TCP reconnections so no frame is lost while re-negotiating).
 *  - In ACTIVE mode a control thread connects to TCP 8000, sends cmd 901 (Remote Play, JPEG Compat)
 *    and heartbeats; NTR-HR learns our address from that TCP connection.
 *  - Video silence triggers reconnection with exponential backoff, up to a limit.
 *
 * Callbacks run on the session's background threads.
 */
class NtrSession(
    initialConfig: SessionConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: ConnectionState, detail: String?)
        fun onVideoFrame(info: VideoFrameInfo, data: ByteArray, length: Int)
        fun onAudioFrame(pcm: ByteArray, offset: Int, silenceFramesBefore: Int) {}
        fun onConsoleAddressLearned(ip: String) {}
        fun onLog(message: String) {}
    }

    @Volatile var config: SessionConfig = initialConfig
        private set
    @Volatile var state: ConnectionState = ConnectionState.DISCONNECTED
        private set
    @Volatile var stateDetail: String? = null
        private set
    @Volatile var unsupportedKind: PacketKind? = null
        private set

    private val assembler = JpegFrameAssembler(listener = { info, data, len ->
        lastVideoNanos = System.nanoTime()
        listener.onVideoFrame(info, data, len)
    })
    private val audioSequencer = AudioSequencer()
    val assemblerStats get() = assembler.stats
    val audioFramesPlayed get() = audioSequencer.framesPlayed
    val audioFramesLost get() = audioSequencer.framesLost
    @Volatile var audioPackets = 0L
        private set
    @Volatile var reliableStreamPackets = 0L
        private set
    @Volatile var losslessPackets = 0L
        private set
    @Volatile var foreignPackets = 0L
        private set
    @Volatile var reconnections = 0L
        private set

    @Volatile private var running = false
    @Volatile private var lastVideoNanos = 0L
    @Volatile private var consoleAddress: InetAddress? = null
    @Volatile private var pendingRequest: RemotePlayRequest? = null
    private var udpSocket: DatagramSocket? = null
    private var udpThread: Thread? = null
    private var controlThread: Thread? = null
    private val lock = Object()

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        val cfg = config
        consoleAddress = cfg.consoleIp?.takeIf { Ipv4.parse(it) != null }?.let { InetAddress.getByName(it) }
        if (cfg.mode == ConnectMode.ACTIVE && consoleAddress == null) {
            running = false
            setState(ConnectionState.ERROR, "IP de la consola no válida")
            return
        }
        try {
            val sock = if (cfg.bindAddress != null) {
                DatagramSocket(InetSocketAddress(InetAddress.getByName(cfg.bindAddress), cfg.viewerPort))
            } else {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(cfg.viewerPort))
                }
            }
            sock.receiveBufferSize = 2 * 1024 * 1024
            sock.soTimeout = 200
            udpSocket = sock
        } catch (e: IOException) {
            running = false
            setState(ConnectionState.ERROR, "No se pudo abrir el puerto UDP ${cfg.viewerPort}: ${e.message}")
            return
        }
        lastVideoNanos = 0L
        assembler.reset()
        audioSequencer.reset()
        unsupportedKind = null
        udpThread = Thread({ udpLoop() }, "ntr-udp").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        controlThread = Thread({ controlLoop() }, "ntr-control").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        udpSocket?.close()
        controlThread?.interrupt()
        try {
            udpThread?.join(1000)
            controlThread?.join(1500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        udpSocket = null
        setState(ConnectionState.DISCONNECTED, null)
    }

    /** Applies new quality/priority settings; re-sent to the console on the live TCP connection. */
    fun updateRequest(request: RemotePlayRequest) {
        config = config.copy(request = request.copy(viewerPort = config.viewerPort))
        pendingRequest = config.request
    }

    fun consoleIp(): String? = consoleAddress?.hostAddress

    private fun setState(s: ConnectionState, detail: String?) {
        val changed = s != state || detail != stateDetail
        state = s
        stateDetail = detail
        if (changed) listener.onStateChanged(s, detail)
    }

    private fun log(msg: String) = listener.onLog(msg)

    private fun videoAgeMs(): Long =
        if (lastVideoNanos == 0L) Long.MAX_VALUE else (System.nanoTime() - lastVideoNanos) / 1_000_000

    // ---------------------------------------------------------------- UDP

    private fun udpLoop() {
        val sock = udpSocket ?: return
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                packet.setData(buf, 0, buf.size)
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running) log("UDP: ${e.message}")
                break
            }
            val from = packet.address
            val expected = consoleAddress
            if (expected != null && from != expected) {
                foreignPackets++
                continue
            }
            when (val kind = PacketClassifier.classify(buf, 0, packet.length)) {
                PacketKind.VIDEO_JPEG -> {
                    if (expected == null && config.mode == ConnectMode.PASSIVE) {
                        consoleAddress = from
                        listener.onConsoleAddressLearned(from.hostAddress)
                    }
                    assembler.feed(buf, 0, packet.length)
                }
                PacketKind.AUDIO -> {
                    if (expected != null) {
                        audioPackets++
                        audioSequencer.feed(buf, 0, packet.length) { pcm, off, silence ->
                            listener.onAudioFrame(pcm, off, silence)
                        }
                    }
                }
                PacketKind.RELIABLE_STREAM -> {
                    reliableStreamPackets++
                    unsupportedKind = kind
                }
                PacketKind.VIDEO_LOSSLESS -> {
                    losslessPackets++
                    unsupportedKind = kind
                }
                PacketKind.INVALID -> assembler.stats.invalidPackets++
            }
        }
    }

    // ---------------------------------------------------------------- control

    private class Control(val socket: Socket, val out: OutputStream, val input: InputStream) {
        var seq = 0
        fun close() {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun sleepMs(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            running
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun controlLoop() {
        val passive = config.mode == ConnectMode.PASSIVE
        if (passive) {
            setState(ConnectionState.SEARCHING, "Inicia Remote Play desde el menú NTR-HR (X+Y) con Viewer IP = este dispositivo")
            passiveLoop()
            return
        }
        var attempt = 0
        while (running) {
            setState(if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING,
                if (attempt == 0) null else "Intento $attempt de ${config.maxReconnectAttempts}")
            val control = connect()
            if (control == null) {
                if (!running) break
                if (videoAgeMs() < config.videoTimeoutMs) {
                    // NFC patch / console-started stream: video flows even if TCP 8000 is closed.
                    setState(ConnectionState.STREAMING, "Sin canal TCP; recibiendo vídeo")
                    if (!monitorWithoutControl()) break
                    attempt = 0
                    continue
                }
                attempt++
                if (attempt > config.maxReconnectAttempts) {
                    setState(ConnectionState.ERROR,
                        "No responde ${config.consoleIp}:${config.controlPort}. Comprueba que NTR-HR está activo, la IP y que ambos están en la misma red.")
                    break
                }
                setState(ConnectionState.RECONNECTING, "Reintento en ${config.backoffMs(attempt) / 1000} s")
                if (!sleepMs(config.backoffMs(attempt))) break
                continue
            }
            val outcome = runControl(control)
            control.close()
            if (!running) break
            when (outcome) {
                Outcome.UNSUPPORTED -> {
                    setState(ConnectionState.ERROR, unsupportedMessage())
                    break
                }
                Outcome.LOST -> {
                    reconnections++
                    attempt = 1
                    setState(ConnectionState.RECONNECTING, "Se perdió la imagen; reconectando")
                    if (!sleepMs(config.backoffMs(attempt))) break
                }
                Outcome.NO_VIDEO -> {
                    attempt++
                    if (attempt > config.maxReconnectAttempts) {
                        setState(ConnectionState.ERROR,
                            "La consola acepta la conexión pero no llega vídeo UDP al puerto ${config.viewerPort}. " +
                                "Revisa aislamiento de clientes Wi-Fi, que el protocolo sea JPEG/UDP y el Viewer IP en NTR-HR.")
                        break
                    }
                    if (!sleepMs(config.backoffMs(attempt))) break
                }
                Outcome.STOPPED -> break
            }
        }
    }

    private fun unsupportedMessage(): String = when (unsupportedKind) {
        PacketKind.RELIABLE_STREAM -> "La consola envía Reliable Stream (KCP), no soportado aún. En NTR-HR elige JPEG + UDP."
        PacketKind.VIDEO_LOSSLESS -> "La consola envía Lossless/Uncompressed, no soportado aún. En NTR-HR elige JPEG + UDP."
        else -> "Formato de vídeo no soportado"
    }

    private fun passiveLoop() {
        while (running) {
            val age = videoAgeMs()
            when {
                unsupportedKind != null && age == Long.MAX_VALUE -> setState(ConnectionState.ERROR, unsupportedMessage())
                age < config.videoTimeoutMs -> setState(ConnectionState.STREAMING, null)
                lastVideoNanos != 0L -> setState(ConnectionState.SEARCHING, "Sin imagen; esperando a que la consola vuelva a emitir")
            }
            if (!sleepMs(100)) break
        }
    }

    private fun monitorWithoutControl(): Boolean {
        while (running) {
            if (videoAgeMs() >= config.videoTimeoutMs) return true
            if (!sleepMs(100)) return false
        }
        return false
    }

    private enum class Outcome { LOST, NO_VIDEO, UNSUPPORTED, STOPPED }

    private fun connect(): Control? {
        val addr = consoleAddress ?: return null
        val s = Socket()
        return try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, config.controlPort), config.connectTimeoutMs)
            s.soTimeout = 0
            log("TCP conectado a ${addr.hostAddress}:${config.controlPort}")
            Control(s, s.getOutputStream(), s.getInputStream())
        } catch (e: IOException) {
            log("TCP: ${e.message}")
            try { s.close() } catch (_: IOException) {}
            null
        }
    }

    private fun send(c: Control, p: NtrControlPacket): Boolean = try {
        synchronized(c) {
            c.out.write(p.encode())
            c.out.flush()
        }
        true
    } catch (e: IOException) {
        log("TCP envío: ${e.message}")
        false
    }

    private fun runControl(c: Control): Outcome {
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        val reader = Thread({
            val header = ByteArray(NtrConstants.TCP_HEADER_SIZE)
            try {
                while (running) {
                    readFully(c.input, header)
                    val p = NtrControlPacket.decode(header)
                    if (p.magic != NtrConstants.TCP_MAGIC) {
                        log("TCP: protocolo inesperado")
                        break
                    }
                    if (p.dataLen in 1..(1 shl 20)) {
                        val data = ByteArray(p.dataLen)
                        readFully(c.input, data)
                        if (p.cmd == NtrConstants.CMD_HEARTBEAT) {
                            val text = String(data, Charsets.UTF_8).trim()
                            if (text.isNotEmpty()) log("NTR: $text")
                        }
                    } else if (p.dataLen != 0) {
                        break
                    }
                }
            } catch (_: IOException) {
            }
            alive.set(false)
        }, "ntr-tcp-reader").apply { isDaemon = true; start() }

        try {
            pendingRequest = null
            if (!send(c, config.request.toPacket(c.seq++))) return Outcome.LOST
            val sentAt = System.nanoTime()
            var resent = false
            var lastHeartbeat = System.nanoTime()
            if (videoAgeMs() >= config.videoTimeoutMs) setState(ConnectionState.CONNECTED, null)
            var everStreamed = false
            while (running) {
                val now = System.nanoTime()
                val age = videoAgeMs()
                if (age < config.videoTimeoutMs) {
                    everStreamed = true
                    setState(ConnectionState.STREAMING, null)
                } else if (everStreamed) {
                    return Outcome.LOST
                } else {
                    if (unsupportedKind != null) return Outcome.UNSUPPORTED
                    val waited = (now - sentAt) / 1_000_000
                    if (!resent && waited >= config.firstFrameRetryMs) {
                        resent = true
                        log("Sin vídeo todavía; reenviando orden Remote Play")
                        if (!send(c, config.request.toPacket(c.seq++))) return Outcome.NO_VIDEO
                    }
                    if (waited >= config.firstFrameTimeoutMs) return Outcome.NO_VIDEO
                }
                pendingRequest?.let {
                    pendingRequest = null
                    if (!send(c, it.toPacket(c.seq++))) return if (everStreamed) Outcome.LOST else Outcome.NO_VIDEO
                }
                if ((now - lastHeartbeat) / 1_000_000 >= config.heartbeatIntervalMs) {
                    lastHeartbeat = now
                    if (!send(c, NtrControlPacket.heartbeat(c.seq++)) || !alive.get()) {
                        // TCP dropped. If video still flows keep going without control channel.
                        if (age < config.videoTimeoutMs) {
                            log("Canal TCP cerrado; el vídeo continúa")
                            return if (monitorWithoutControl()) Outcome.LOST else Outcome.STOPPED
                        }
                        return if (everStreamed) Outcome.LOST else Outcome.NO_VIDEO
                    }
                }
                if (!sleepMs(50)) return Outcome.STOPPED
            }
            return Outcome.STOPPED
        } finally {
            c.close()
            reader.interrupt()
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
}
