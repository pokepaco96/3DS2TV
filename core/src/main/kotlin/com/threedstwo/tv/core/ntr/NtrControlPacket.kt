package com.threedstwo.tv.core.ntr

import com.threedstwo.tv.core.util.LittleEndian

/** The 84-byte NS_PACKET header exchanged over TCP port 8000 (little-endian u32 fields). */
data class NtrControlPacket(
    val seq: Int,
    val type: Int,
    val cmd: Int,
    val args: IntArray = IntArray(NtrConstants.TCP_ARGS_COUNT),
    val dataLen: Int = 0,
    val magic: Int = NtrConstants.TCP_MAGIC,
) {
    init {
        require(args.size <= NtrConstants.TCP_ARGS_COUNT) { "too many args" }
    }

    fun encode(): ByteArray {
        val buf = ByteArray(NtrConstants.TCP_HEADER_SIZE)
        LittleEndian.putInt(buf, 0, magic)
        LittleEndian.putInt(buf, 4, seq)
        LittleEndian.putInt(buf, 8, type)
        LittleEndian.putInt(buf, 12, cmd)
        for (i in 0 until NtrConstants.TCP_ARGS_COUNT) {
            LittleEndian.putInt(buf, 16 + i * 4, if (i < args.size) args[i] else 0)
        }
        LittleEndian.putInt(buf, 16 + NtrConstants.TCP_ARGS_COUNT * 4, dataLen)
        return buf
    }

    override fun equals(other: Any?): Boolean =
        other is NtrControlPacket && seq == other.seq && type == other.type && cmd == other.cmd &&
            dataLen == other.dataLen && magic == other.magic &&
            normalizedArgs().contentEquals(other.normalizedArgs())

    override fun hashCode(): Int = ((seq * 31 + cmd) * 31 + dataLen) * 31 + normalizedArgs().contentHashCode()

    private fun normalizedArgs(): IntArray = IntArray(NtrConstants.TCP_ARGS_COUNT) { if (it < args.size) args[it] else 0 }

    companion object {
        fun decode(buf: ByteArray, offset: Int = 0): NtrControlPacket {
            require(buf.size - offset >= NtrConstants.TCP_HEADER_SIZE) { "short header" }
            val args = IntArray(NtrConstants.TCP_ARGS_COUNT) { LittleEndian.getInt(buf, offset + 16 + it * 4) }
            return NtrControlPacket(
                magic = LittleEndian.getInt(buf, offset),
                seq = LittleEndian.getInt(buf, offset + 4),
                type = LittleEndian.getInt(buf, offset + 8),
                cmd = LittleEndian.getInt(buf, offset + 12),
                args = args,
                dataLen = LittleEndian.getInt(buf, offset + 16 + NtrConstants.TCP_ARGS_COUNT * 4),
            )
        }

        fun heartbeat(seq: Int) = NtrControlPacket(seq = seq, type = 0, cmd = NtrConstants.CMD_HEARTBEAT)
    }
}

/**
 * Parameters of the "start/update remote play" command (cmd 901).
 *
 * Encoding verified in ntrviewer-hr `ntr_hb.c` (sender) and ntr-hr `source/ns.c` (receiver):
 *  - args[0] = (topPriority << 8) | priorityFactor
 *  - args[1] = JPEG quality
 *  - args[2] = QoS in bytes/s (Mbps * 128 * 1024)
 *  - args[3] = guarding magic 1404036572
 *  - args[4] = viewer UDP port | protocol flags (0 = JPEG over plain UDP, "JPEG Compat")
 *  - args[5] = audio enable (0 off, 1 DSP audio as plain UDP packets)
 *  - args[6] = full width (0 = left eye only, normal 400 px top screen)
 */
data class RemotePlayRequest(
    val topScreenPriority: Boolean = true,
    /** Prioritized screen gets this many frames per frame of the other. 0 = only the prioritized screen. */
    val priorityFactor: Int = 1,
    val jpegQuality: Int = 80,
    val qosMbps: Int = 16,
    val viewerPort: Int = NtrConstants.DEFAULT_VIEWER_PORT,
    val audio: Boolean = false,
) {
    fun clamped(): RemotePlayRequest = copy(
        priorityFactor = priorityFactor.coerceIn(0, 255),
        jpegQuality = jpegQuality.coerceIn(NtrConstants.JPEG_QUALITY_MIN, NtrConstants.JPEG_QUALITY_MAX),
        qosMbps = qosMbps.coerceIn(NtrConstants.QOS_MBPS_MIN, NtrConstants.QOS_MBPS_MAX),
        viewerPort = viewerPort.coerceIn(1024, 65535),
    )

    fun toArgs(): IntArray {
        val c = clamped()
        return intArrayOf(
            ((if (c.topScreenPriority) 1 else 0) shl 8) or c.priorityFactor,
            c.jpegQuality,
            c.qosMbps * 128 * 1024,
            NtrConstants.GUARDING_MAGIC,
            c.viewerPort and 0xFFFF, // no RS / delta / lossless flags: JPEG Compat over UDP
            if (c.audio) 1 else 0,
            0,
        )
    }

    fun toPacket(seq: Int) = NtrControlPacket(seq = seq, type = 0, cmd = NtrConstants.CMD_REMOTE_PLAY, args = toArgs())
}
