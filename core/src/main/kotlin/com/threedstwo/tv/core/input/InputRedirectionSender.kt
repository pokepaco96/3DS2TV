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

    fun update(frame: InputFrame) {
        synchronized(lock) {
            if (frame == current) return
            current = frame
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
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val buf = ByteArray(InputFrame.PACKET_SIZE)
        try {
            while (true) {
                val frame: InputFrame
                synchronized(lock) {
                    if (running && !dirty) lock.wait(keepAliveMs)
                    if (!running) return
                    frame = current
                    dirty = false
                }
                sendRaw(frame.encode(buf))
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
