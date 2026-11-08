package com.threedstwo.tv.core.ntr

/**
 * Bounded hand-off between the network thread and the decoder thread.
 *
 * Holds at most ONE pending frame per screen: offering a new frame replaces (and counts as dropped)
 * an older frame that was not yet taken. This bounds latency — we never queue stale frames.
 * Buffers are recycled, so steady-state streaming does not allocate.
 */
class FrameMailbox(private val bufferCapacity: Int = NtrConstants.MAX_PACKETS_PER_FRAME * NtrConstants.PACKET_DATA_SIZE) {
    class Frame internal constructor(capacity: Int) {
        val data = ByteArray(capacity)
        var length = 0
            internal set
        lateinit var info: VideoFrameInfo
            internal set
        /** System.nanoTime() when the last packet of the frame was received. */
        var receivedAtNanos = 0L
            internal set
    }

    private val lock = Object()
    private val free = ArrayDeque<Frame>()
    private val pending = arrayOfNulls<Frame>(Screen.entries.size)
    private var pendingSeq = LongArray(Screen.entries.size)
    private var seq = 0L
    private var closed = false

    @Volatile var droppedBeforeDecode = 0L
        private set

    init {
        // per screen: one pending + one being decoded + one being filled
        repeat(Screen.entries.size * 3) { free.addLast(Frame(bufferCapacity)) }
    }

    fun offer(info: VideoFrameInfo, data: ByteArray, length: Int, nowNanos: Long = System.nanoTime()) {
        if (length > bufferCapacity) return
        synchronized(lock) {
            if (closed) return
            val target = free.removeFirstOrNull() ?: pending[info.screen.ordinal]?.also {
                pending[info.screen.ordinal] = null
                droppedBeforeDecode++
            } ?: return
            System.arraycopy(data, 0, target.data, 0, length)
            target.length = length
            target.info = info
            target.receivedAtNanos = nowNanos
            val idx = info.screen.ordinal
            pending[idx]?.let {
                free.addLast(it)
                droppedBeforeDecode++
            }
            pending[idx] = target
            pendingSeq[idx] = ++seq
            lock.notifyAll()
        }
    }

    /** Takes the oldest pending frame, waiting up to [timeoutMs]. Must be returned via [release]. */
    fun take(timeoutMs: Long): Frame? {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed) {
                var best = -1
                for (i in pending.indices) {
                    if (pending[i] != null && (best < 0 || pendingSeq[i] < pendingSeq[best])) best = i
                }
                if (best >= 0) {
                    val f = pending[best]!!
                    pending[best] = null
                    return f
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
            return null
        }
    }

    fun release(frame: Frame) {
        synchronized(lock) { free.addLast(frame) }
    }

    fun clear() {
        synchronized(lock) {
            for (i in pending.indices) {
                pending[i]?.let { free.addLast(it) }
                pending[i] = null
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}
