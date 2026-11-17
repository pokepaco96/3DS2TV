package com.threedstwo.tv.core.input

import com.threedstwo.tv.core.util.Ipv4
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends [InputFrame]s to Luma3DS Rosalina (UDP 4950 of the CONSOLE ip only).
 *
 * - A change is sent immediately (the sender thread is woken up), so added latency is ~0.
 * - The current state is re-sent every [keepAliveMs] (default 16 ms ≈ 60 Hz) because UDP may drop a
 *   packet and Rosalina keeps the last state it received: without repetition a lost "release"
 *   would leave a button stuck. 20 bytes * 60/s ≈ 1.2 KB/s: negligible.
 * - [stop] (and [neutralize]) sends the neutral frame several times before closing.
 */
class InputRedirectionSender(
    consoleIp: String,
    private val port: Int = InputFrame.PORT,
    private val keepAliveMs: Long = 16,
) {
    private val address: InetAddress = InetAddress.getByName(
        requireNotNull(consoleIp.takeIf { Ipv4.parse(it) != null }) { "invalid console ip" },
    )
    private val lock = Object()
    private var current: InputFrame = InputFrame.NEUTRAL
    private var dirty = true
    /** System.nanoTime() of the input event that produced [current] (0 = unknown). */
    private var currentEventNanos = 0L

    /** Input event → datagram written to the socket, in ms (p50/p95/p99). Measures only this device. */
    val eventToSendMs = com.threedstwo.tv.core.session.WindowStats(256)
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile var packetsSent = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    val consoleAddress: String get() = address.hostAddress

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            dirty = true
        }
        socket = DatagramSocket()
        thread = Thread({ loop() }, "input-redirection").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    /**
     * New state from the input layer. A change wakes the sender thread at once (no waiting for the
     * keep-alive tick). [eventNanos] = System.nanoTime() of the originating input event, if known.
     */
    fun update(frame: InputFrame, eventNanos: Long = 0L) {
        synchronized(lock) {
            if (frame == current) return
            current = frame
            currentEventNanos = eventNanos
            dirty = true
            lock.notifyAll()
        }
    }

    fun neutralize() = update(InputFrame.NEUTRAL)

    fun currentFrame(): InputFrame = synchronized(lock) { current }

    /**
     * Stops sending. The sender thread itself transmits the neutral frame 3 times and closes the socket,
     * so this is safe to call from the Android main thread (no network I/O here).
     */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            current = InputFrame.NEUTRAL
            lock.notifyAll()
        }
        // No join: the sender thread itself sends the neutral frames and closes the socket, so
        // this returns immediately (safe on the Android main thread).
        thread = null
    }

    private fun loop() {
        val buf = ByteArray(InputFrame.PACKET_SIZE)
        try {
            while (true) {
                val frame: InputFrame
                val eventNanos: Long
                synchronized(lock) {
                    if (running && !dirty) lock.wait(keepAliveMs)
                    if (!running) return
                    frame = current
                    eventNanos = if (dirty) currentEventNanos else 0L
                    dirty = false
                }
                sendRaw(frame.encode(buf))
                if (eventNanos != 0L) eventToSendMs.add((System.nanoTime() - eventNanos) / 1e6)
            }
        } finally {
            // Make sure the console ends up with every button released.
            val neutral = InputFrame.NEUTRAL.encode()
            repeat(3) { sendRaw(neutral) }
            socket?.close()
            socket = null
        }
    }

    private fun sendRaw(bytes: ByteArray) {
        val s = socket ?: return
        try {
            s.send(DatagramPacket(bytes, bytes.size, address, port))
            packetsSent++
            lastError = null
        } catch (e: IOException) {
            lastError = e.message ?: e.javaClass.simpleName
        }
    }
}
