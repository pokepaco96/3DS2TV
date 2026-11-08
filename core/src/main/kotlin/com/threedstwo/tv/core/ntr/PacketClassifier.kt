package com.threedstwo.tv.core.ntr

import com.threedstwo.tv.core.util.LittleEndian

enum class PacketKind {
    /** JPEG Compat video packet (supported). */
    VIDEO_JPEG,
    /** NTR-HR "Uncompressed"/lossless over UDP (not supported by this version). */
    VIDEO_LOSSLESS,
    /** NTR-HR plain-UDP DSP audio packet. */
    AUDIO,
    /** NTR-HR Reliable Stream (modified KCP) datagram (not supported by this version). */
    RELIABLE_STREAM,
    INVALID,
}

object PacketClassifier {
    fun classify(buf: ByteArray, offset: Int, length: Int): PacketKind {
        if (length == 2) return PacketKind.RELIABLE_STREAM // ntrviewer-hr: 2-byte datagram = KCP probe
        if (length < NtrConstants.DATA_HEADER_SIZE) return PacketKind.INVALID
        val hdr2 = buf[offset + 2].toInt() and 0xFF
        if (hdr2 == NtrConstants.AUDIO_TYPE && length > NtrConstants.DATA_HEADER_SIZE &&
            (length - NtrConstants.DATA_HEADER_SIZE) % NtrConstants.AUDIO_FRAME_BYTES == 0
        ) {
            return PacketKind.AUDIO
        }
        if ((hdr2 and (NtrConstants.HDR2_DOWNSAMPLE_MASK or NtrConstants.HDR2_LOSSLESS_BIT).inv()) == NtrConstants.HDR2_VIDEO_TYPE) {
            return if (hdr2 and NtrConstants.HDR2_LOSSLESS_BIT != 0) PacketKind.VIDEO_LOSSLESS else PacketKind.VIDEO_JPEG
        }
        // Same heuristic as ntrviewer-hr test_kcp_magic(): anything that is not a plain data header.
        val magic = LittleEndian.getInt(buf, offset)
        val plain = (magic and (0x00001700.inv() and 0x0000ff00)) == 0 && (magic and 0x00f20000) == 0x00020000
        return if (!plain) PacketKind.RELIABLE_STREAM else PacketKind.INVALID
    }
}
