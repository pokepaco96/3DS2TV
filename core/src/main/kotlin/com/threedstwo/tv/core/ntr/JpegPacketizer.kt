package com.threedstwo.tv.core.ntr

/**
 * Encoder side of NTR "JPEG Compat" (what the console does): splits one JPEG into datagrams with
 * the 4-byte header. Used by the mock sender and by tests; never by the app in normal use.
 */
object JpegPacketizer {
    fun packetize(
        jpeg: ByteArray,
        screen: Screen,
        frameId: Int,
        downsample: Int = 0,
        fullWidth: Boolean = false,
    ): List<ByteArray> {
        require(jpeg.isNotEmpty())
        val count = (jpeg.size + NtrConstants.PACKET_DATA_SIZE - 1) / NtrConstants.PACKET_DATA_SIZE
        require(count <= NtrConstants.MAX_PACKETS_PER_FRAME) { "frame too large" }
        val hdr1Base = (if (screen == Screen.TOP) 1 else 0) or (if (fullWidth && screen == Screen.TOP) 2 else 0)
        return List(count) { i ->
            val start = i * NtrConstants.PACKET_DATA_SIZE
            val len = minOf(NtrConstants.PACKET_DATA_SIZE, jpeg.size - start)
            val p = ByteArray(NtrConstants.DATA_HEADER_SIZE + len)
            p[0] = frameId.toByte()
            p[1] = (hdr1Base or if (i == count - 1) NtrConstants.HDR1_END_FLAG else 0).toByte()
            p[2] = (NtrConstants.HDR2_VIDEO_TYPE or ((downsample and 3) shl 2)).toByte()
            p[3] = i.toByte()
            System.arraycopy(jpeg, start, p, NtrConstants.DATA_HEADER_SIZE, len)
            p
        }
    }

    fun audioPacket(newestSeq: Int, frames: List<ByteArray>): ByteArray {
        val p = ByteArray(NtrConstants.DATA_HEADER_SIZE + frames.size * NtrConstants.AUDIO_FRAME_BYTES)
        p[0] = newestSeq.toByte()
        p[1] = 0
        p[2] = NtrConstants.AUDIO_TYPE.toByte()
        p[3] = NtrConstants.AUDIO_FMT_PCM16.toByte()
        frames.forEachIndexed { i, f ->
            require(f.size == NtrConstants.AUDIO_FRAME_BYTES)
            System.arraycopy(f, 0, p, NtrConstants.DATA_HEADER_SIZE + i * NtrConstants.AUDIO_FRAME_BYTES, f.size)
        }
        return p
    }
}
