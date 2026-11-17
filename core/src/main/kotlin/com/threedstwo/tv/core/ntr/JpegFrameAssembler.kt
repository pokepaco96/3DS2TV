package com.threedstwo.tv.core.ntr

/**
 * Reassembles NTR "JPEG Compat" frames from UDP datagrams.
 *
 * Behaviour mirrors the reference receiver (ntrviewer-hr `handle_recv`):
 *  - every non-final packet carries exactly [NtrConstants.PACKET_DATA_SIZE] bytes of JPEG data;
 *  - the packet index (hdr[3]) is a u8 that is unwrapped against the highest index seen;
 *  - a frame is complete when the end packet arrived and every index before it is present.
 * Additionally it tolerates reordering between up to [slotCount] frames in flight and never
 * waits for stale frames: when a newer frame of the same screen completes, older incomplete
 * frames of that screen are dropped (latency over completeness).
 *
 * Not thread-safe: call from the single network receive thread.
 */
class JpegFrameAssembler(
    private val slotCount: Int = 4,
    private val listener: Listener,
    /** Monotonic clock (ns); injectable for tests. */
    private val clock: () -> Long = System::nanoTime,
) {
    fun interface Listener {
        /** [data] is only valid during the call; copy what you need. */
        fun onFrame(info: VideoFrameInfo, data: ByteArray, length: Int)
    }

    class Stats {
        @Volatile var packets = 0L
        @Volatile var bytes = 0L
        @Volatile var duplicatePackets = 0L
        @Volatile var invalidPackets = 0L
        @Volatile var framesCompleted = 0L
        /** Frames abandoned because packets were lost or a newer frame overtook them. */
        @Volatile var framesIncomplete = 0L
        /** Datagrams of frames older than the last frame delivered for that screen (dropped unseen). */
        @Volatile var stalePackets = 0L
    }

    val stats = Stats()

    private class Slot {
        val data = ByteArray(NtrConstants.MAX_PACKETS_PER_FRAME * NtrConstants.PACKET_DATA_SIZE)
        val received = BooleanArray(NtrConstants.MAX_PACKETS_PER_FRAME)
        var active = false
        var key = -1
        var order = 0L
        var receivedCount = 0
        var maxPacket = 0
        var endPacket = -1
        var totalSize = 0
        var info: VideoFrameInfo? = null

        fun reset(newKey: Int, newOrder: Long, newInfo: VideoFrameInfo) {
            java.util.Arrays.fill(received, false)
            active = true
            key = newKey
            order = newOrder
            receivedCount = 0
            maxPacket = 0
            endPacket = -1
            totalSize = 0
            info = newInfo
        }
    }

    private val slots = Array(slotCount) { Slot() }
    private var orderCounter = 0L
    private val recentlyCompleted = IntArray(8) { -1 }
    private var recentIndex = 0
    /** Per screen: id and time of the last frame handed to the listener ("latest frame wins"). */
    private val lastDeliveredId = IntArray(Screen.entries.size) { -1 }
    private val lastDeliveredAt = LongArray(Screen.entries.size)
    /** Consecutive "stale" frames with increasing ids = the console restarted its counter. */
    private val staleRunId = IntArray(Screen.entries.size) { -1 }
    private val staleRun = IntArray(Screen.entries.size)

    fun reset() {
        slots.forEach { it.active = false }
        java.util.Arrays.fill(recentlyCompleted, -1)
        java.util.Arrays.fill(lastDeliveredId, -1)
        java.util.Arrays.fill(staleRunId, -1)
        java.util.Arrays.fill(staleRun, 0)
    }

    /**
     * True if [frameId] is older than (or equal to) the last frame delivered for [screen].
     * Frame ids are u8 and wrap: "older" means 0..127 steps behind. If nothing was delivered for
     * [RESTART_WINDOW_NANOS] the console probably restarted its counter, so everything is accepted.
     */
    private fun isStale(screenIdx: Int, frameId: Int, now: Long): Boolean {
        val last = lastDeliveredId[screenIdx]
        if (last < 0) return false
        val ahead = (frameId - last) and 0xFF
        if (ahead in 1..127) return false
        if (now - lastDeliveredAt[screenIdx] >= RESTART_WINDOW_NANOS) return false
        // NTR-HR resets CURRENT_FRAME_IDS to 0 when it restarts encoding (e.g. after a quality change):
        // a run of "old" ids that keep increasing is a new stream, not reordering.
        if (frameId == staleRunId[screenIdx]) return true // another packet of the same stale frame
        if (staleRunId[screenIdx] >= 0 && frameId == ((staleRunId[screenIdx] + 1) and 0xFF)) staleRun[screenIdx]++ else staleRun[screenIdx] = 1
        staleRunId[screenIdx] = frameId
        if (staleRun[screenIdx] >= RESTART_RUN) {
            lastDeliveredId[screenIdx] = -1
            staleRun[screenIdx] = 0
            staleRunId[screenIdx] = -1
            return false
        }
        return true
    }

    /** Feed one datagram already classified as [PacketKind.VIDEO_JPEG]. Returns false if it was rejected. */
    fun feed(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (length < NtrConstants.DATA_HEADER_SIZE) return reject()
        val hdr0 = buf[offset].toInt() and 0xFF
        val hdr1 = buf[offset + 1].toInt() and 0xFF
        val hdr2 = buf[offset + 2].toInt() and 0xFF
        val hdr3 = buf[offset + 3].toInt() and 0xFF
        if ((hdr2 and (NtrConstants.HDR2_DOWNSAMPLE_MASK or NtrConstants.HDR2_LOSSLESS_BIT).inv()) != NtrConstants.HDR2_VIDEO_TYPE) {
            return reject()
        }
        val payload = length - NtrConstants.DATA_HEADER_SIZE
        val isEnd = hdr1 and NtrConstants.HDR1_END_FLAG != 0
        if (!isEnd && payload != NtrConstants.PACKET_DATA_SIZE) return reject()
        if (payload > NtrConstants.PACKET_DATA_SIZE) return reject()
        val id1 = hdr1 and NtrConstants.HDR1_END_FLAG.inv()
        val key = hdr0 or (id1 shl 8) or (hdr2 shl 16)

        stats.packets++
        stats.bytes += length.toLong()

        if (recentlyCompleted.contains(key)) {
            stats.duplicatePackets++
            return true
        }

        var slot: Slot? = null
        for (sl in slots) if (sl.active && sl.key == key) { slot = sl; break }
        if (slot == null) {
            // New frame: drop it right away if it is older than what is already on screen.
            if (isStale(if (id1 and 0x01 != 0) Screen.TOP.ordinal else Screen.BOTTOM.ordinal, hdr0, clock())) {
                stats.stalePackets++
                return true
            }
            slot = allocate(key, hdr0, id1, hdr2)
        }
        var index = hdr3
        if (index + 128 < slot.maxPacket) index += 256
        if (index >= NtrConstants.MAX_PACKETS_PER_FRAME) return reject()
        if (slot.received[index]) {
            stats.duplicatePackets++
            return true
        }
        System.arraycopy(buf, offset + NtrConstants.DATA_HEADER_SIZE, slot.data, index * NtrConstants.PACKET_DATA_SIZE, payload)
        slot.received[index] = true
        slot.receivedCount++
        if (index > slot.maxPacket) slot.maxPacket = index
        if (isEnd) {
            slot.endPacket = index
            slot.totalSize = index * NtrConstants.PACKET_DATA_SIZE + payload
        }
        if (slot.endPacket >= 0 && slot.receivedCount == slot.endPacket + 1 && slot.maxPacket == slot.endPacket) {
            complete(slot)
        }
        return true
    }

    private fun reject(): Boolean {
        stats.invalidPackets++
        return false
    }

    private fun allocate(key: Int, hdr0: Int, id1: Int, hdr2: Int): Slot {
        var target: Slot? = slots.firstOrNull { !it.active }
        if (target == null) {
            target = slots.minByOrNull { it.order }!!
            stats.framesIncomplete++
        }
        val screen = if (id1 and 0x01 != 0) Screen.TOP else Screen.BOTTOM
        val fullWidth = screen == Screen.TOP && (id1 and 0x02 != 0)
        val info = VideoFrameInfo(
            screen = screen,
            frameId = hdr0,
            downsample = (hdr2 and NtrConstants.HDR2_DOWNSAMPLE_MASK) shr 2,
            fullWidth = fullWidth,
            bothEyes = fullWidth && (id1 and 0x04 != 0),
        )
        target.reset(key, ++orderCounter, info)
        return target
    }

    private fun complete(slot: Slot) {
        val info = slot.info!!
        slot.active = false
        recentlyCompleted[recentIndex] = slot.key
        recentIndex = (recentIndex + 1) % recentlyCompleted.size
        // Drop older, still-incomplete frames of the same screen: they would only add latency.
        for (s in slots) {
            if (s.active && s.order < slot.order && s.info?.screen == info.screen) {
                s.active = false
                stats.framesIncomplete++
            }
        }
        val idx = info.screen.ordinal
        lastDeliveredId[idx] = info.frameId
        lastDeliveredAt[idx] = clock()
        stats.framesCompleted++
        listener.onFrame(info, slot.data, slot.totalSize)
    }

    companion object {
        /** No frame for this long on a screen = treat the next ids as a new stream (console restart). */
        const val RESTART_WINDOW_NANOS = 300_000_000L
        /** This many consecutive increasing "old" frame ids are accepted as a counter restart. */
        const val RESTART_RUN = 3
    }
}
