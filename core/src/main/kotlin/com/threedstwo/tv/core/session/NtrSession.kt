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
        // Only a COMPLETE frame counts as "receiving video" (never a bare datagram).
        lastVideoNanos = System.nanoTime()
        if (consoleAddress == null) lockLearnedAddress()
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

    /** Per-stage counters so the UI can tell TCP / command / UDP / complete frames apart. */
    val diagnostics = SessionDiagnostics()

    @Volatile private var running = false
    @Volatile private var lastVideoNanos = 0L
    @Volatile private var consoleAddress: InetAddress? = null
    @Volatile private var pendingRequest: RemotePlayRequest? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    /** Socket of the TCP connection being opened or in use, closed by [stop] to unblock the control thread. */
    @Volatile private var controlSocket: Socket? = null
    /** Passive mode without IP: source we are trying to assemble frames from (locked after a complete frame). */
    @Volatile private var candidateAddress: InetAddress? = null
    private var candidateSinceNanos = 0L
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
                // No SO_REUSEADDR: with UDP it would let two sockets share the port and steal packets.
                DatagramSocket(InetSocketAddress(cfg.viewerPort))
            }
            // Bounded kernel queue: a huge buffer lets old video pile up (2 MiB ≈ 1 s at 16 Mbit/s) and
            // shows as growing lag; 384 KiB is a few JPEG frames, older ones are dropped instead.
            sock.receiveBufferSize = UDP_RECEIVE_BUFFER
            sock.soTimeout = 200
            udpSocket = sock
        } catch (e: IOException) {
            running = false
            setState(ConnectionState.ERROR,
                "No se pudo abrir el puerto UDP ${cfg.viewerPort} (¿lo usa otra app o una sesión anterior?): ${e.message}")
            return
        }
        lastVideoNanos = 0L
        candidateAddress = null
        diagnostics.reset()
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
        // Closing the sockets unblocks receive()/connect()/read() immediately, so the joins below
        // are short even when called from the Android main thread.
        udpSocket?.close()
        controlSocket?.let { try { it.close() } catch (_: IOException) {} }
        controlThread?.interrupt()
        try {
            udpThread?.join(500)
            controlThread?.join(500)
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
            val len = packet.length
            val expected = consoleAddress
            val kind = PacketClassifier.classify(buf, 0, len)
            if (expected != null && from != expected) {
                foreignPackets++
                if (kind == PacketKind.VIDEO_JPEG || kind == PacketKind.AUDIO) diagnostics.noteForeign(from.hostAddress)
                continue
            }
            if (expected == null) {
                // Passive mode without IP: only assemble from one candidate source at a time and lock to
                // it after its first COMPLETE frame (a stray datagram cannot hijack the session).
                if (kind != PacketKind.VIDEO_JPEG) continue
                val now = System.nanoTime()
                val cand = candidateAddress
                if (cand == null || (cand != from && now - candidateSinceNanos > CANDIDATE_TIMEOUT_NANOS)) {
                    candidateAddress = from
                    candidateSinceNanos = now
                    assembler.reset()
                } else if (cand != from) {
                    diagnostics.noteForeign(from.hostAddress)
                    continue
                }
            }
            diagnostics.udpPacketsFromConsole++
            when (kind) {
                PacketKind.VIDEO_JPEG -> {
                    diagnostics.videoPackets++
                    assembler.feed(buf, 0, len)
                }
                PacketKind.AUDIO -> {
                    audioPackets++
                    audioSequencer.feed(buf, 0, len) { pcm, off, silence ->
                        listener.onAudioFrame(pcm, off, silence)
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

    private fun lockLearnedAddress() {
        val cand = candidateAddress ?: return
        consoleAddress = cand
        listener.onConsoleAddressLearned(cand.hostAddress)
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
                        setState(ConnectionState.ERROR, noVideoMessage())
                        break
                    }
                    if (!sleepMs(config.backoffMs(attempt))) break
                }
                Outcome.STOPPED -> break
            }
        }
    }

    private fun noVideoMessage(): String {
        val foreign = diagnostics.lastForeignSource
        val d = diagnostics
        return when {
            foreign != null && d.videoPackets == 0L ->
                "Llegan paquetes NTR desde $foreign, pero la IP configurada es ${config.consoleIp}. ¿Es esa la IP correcta de la 3DS?"
            d.videoPackets > 0 ->
                "Llegan paquetes de vídeo UDP pero ningún frame completo (${d.videoPackets} paquetes). Wi-Fi con muchas pérdidas: baja la calidad JPEG/QoS o acerca la 3DS al router."
            else ->
                "La consola acepta la conexión TCP pero no llega vídeo UDP al puerto ${config.viewerPort}. " +
                    "Revisa aislamiento de clientes Wi-Fi / red de invitados y que la 3DS y este dispositivo estén en la misma red."
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
        controlSocket = s
        return try {
            s.tcpNoDelay = true
            s.keepAlive = true
            if (!running) throw IOException("stopped")
            s.connect(InetSocketAddress(addr, config.controlPort), config.connectTimeoutMs)
            s.soTimeout = 0
            diagnostics.tcpConnects++
            log("TCP conectado a ${addr.hostAddress}:${config.controlPort}")
            Control(s, s.getOutputStream(), s.getInputStream())
        } catch (e: IOException) {
            if (running) log("TCP: ${e.javaClass.simpleName}: ${e.message}")
            diagnostics.tcpFailures++
            diagnostics.lastTcpError = when (e) {
                is java.net.ConnectException -> "conexión rechazada (${e.message})"
                is SocketTimeoutException -> "sin respuesta en ${config.connectTimeoutMs} ms"
                else -> e.message ?: e.javaClass.simpleName
            }
            try { s.close() } catch (_: IOException) {}
            controlSocket = null
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
            val data = ByteArray(MAX_TCP_TEXT)
            try {
                while (running) {
                    readFully(c.input, header, header.size)
                    val p = NtrControlPacket.decode(header)
                    if (p.magic != NtrConstants.TCP_MAGIC) {
                        log("TCP: respuesta que no es NTR (magic 0x%08x); ¿el puerto 8000 es de otro programa?".format(p.magic))
                        diagnostics.tcpProtocolErrors++
                        break
                    }
                    diagnostics.tcpReplies++
                    // dataLen is an unsigned u32: reject absurd values instead of allocating them
                    val len = p.dataLen.toLong() and 0xFFFFFFFFL
                    if (len > MAX_TCP_PAYLOAD) {
                        log("TCP: longitud de datos inválida ($len)")
                        diagnostics.tcpProtocolErrors++
                        break
                    }
                    var remaining = len.toInt()
                    var first = true
                    while (remaining > 0) {
                        val n = minOf(remaining, data.size)
                        readFully(c.input, data, n)
                        if (first && p.cmd == NtrConstants.CMD_HEARTBEAT) {
                            val text = String(data, 0, n, Charsets.UTF_8).trim()
                            if (text.isNotEmpty()) log("NTR: ${text.take(400)}")
                        }
                        first = false
                        remaining -= n
                    }
                }
            } catch (_: IOException) {
            }
            alive.set(false)
        }, "ntr-tcp-reader").apply { isDaemon = true; start() }

        try {
            pendingRequest = null
            if (!send(c, config.request.toPacket(c.seq++))) return Outcome.LOST
            diagnostics.remotePlayCommands++
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
                        diagnostics.remotePlayCommands++
                    }
                    if (waited >= config.firstFrameTimeoutMs) return Outcome.NO_VIDEO
                }
                pendingRequest?.let {
                    pendingRequest = null
                    if (!send(c, it.toPacket(c.seq++))) return if (everStreamed) Outcome.LOST else Outcome.NO_VIDEO
                    diagnostics.remotePlayCommands++
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
            controlSocket = null
            reader.interrupt()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, count: Int) {
        var pos = 0
        while (pos < count) {
            val n = input.read(buf, pos, count - pos)
            if (n < 0) throw IOException("closed")
            pos += n
        }
    }

    companion object {
        /** NTR debug text is small; anything larger is skipped in chunks, never allocated. */
        private const val MAX_TCP_TEXT = 16 * 1024
        private const val MAX_TCP_PAYLOAD = 4L * 1024 * 1024
        const val UDP_RECEIVE_BUFFER = 384 * 1024
        private const val CANDIDATE_TIMEOUT_NANOS = 2_000_000_000L
    }
}
