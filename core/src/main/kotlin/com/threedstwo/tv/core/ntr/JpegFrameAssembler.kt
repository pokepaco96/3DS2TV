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

    fun reset() {
        slots.forEach { it.active = false }
        java.util.Arrays.fill(recentlyCompleted, -1)
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

        val slot = findOrAllocate(key, hdr0, id1, hdr2)
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

    private fun findOrAllocate(key: Int, hdr0: Int, id1: Int, hdr2: Int): Slot {
        for (s in slots) if (s.active && s.key == key) return s
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
        stats.framesCompleted++
        listener.onFrame(info, slot.data, slot.totalSize)
    }
}
