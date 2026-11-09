package com.threedstwo.tv.core.ntr

/**
 * NTR-HR plain-UDP DSP audio (xzn/ntr-hr `thread_audio.rs`, ntrviewer-hr `ntr_audio.h`):
 *  hdr[0] = sequence number of the NEWEST frame in the packet (u8),
 *  hdr[1] = flags (reserved), hdr[2] = 4 (audio), hdr[3] = 0 (PCM16),
 *  payload = N consecutive frames, oldest first, each 160 stereo s16le samples @ 32728 Hz.
 * The console sends the previous and current frame in each packet (redundancy against loss).
 *
 * [AudioSequencer] removes the redundant copies and reports gaps so the player can conceal them.
 */
class AudioSequencer {
    fun interface Sink {
        /** [silenceFramesBefore] frames were lost before this one (bounded). */
        fun onAudioFrame(pcm: ByteArray, offset: Int, silenceFramesBefore: Int)
    }

    private var lastSeq = -1

    @Volatile var framesPlayed = 0L
        private set
    @Volatile var framesLost = 0L
        private set
    @Volatile var unsupportedFormat = 0L
        private set

    fun reset() {
        lastSeq = -1
    }

    fun feed(buf: ByteArray, offset: Int, length: Int, sink: Sink) {
        if (length <= NtrConstants.DATA_HEADER_SIZE) return
        if ((buf[offset + 3].toInt() and 0xFF) != NtrConstants.AUDIO_FMT_PCM16) {
            unsupportedFormat++
            return
        }
        val payload = length - NtrConstants.DATA_HEADER_SIZE
        if (payload % NtrConstants.AUDIO_FRAME_BYTES != 0) return
        val n = payload / NtrConstants.AUDIO_FRAME_BYTES
        val newest = buf[offset].toInt() and 0xFF
        for (i in 0 until n) {
            val seq = (newest - (n - 1 - i)) and 0xFF
            val frameOffset = offset + NtrConstants.DATA_HEADER_SIZE + i * NtrConstants.AUDIO_FRAME_BYTES
            if (lastSeq < 0) {
                // start with the newest frame only: lower latency
                if (i != n - 1) continue
                lastSeq = seq
                framesPlayed++
                sink.onAudioFrame(buf, frameOffset, 0)
                continue
            }
            val delta = (seq - lastSeq) and 0xFF
            if (delta == 0 || delta >= 128) continue // duplicate or older
            val lost = delta - 1
            framesLost += lost
            lastSeq = seq
            framesPlayed++
            sink.onAudioFrame(buf, frameOffset, minOf(lost, MAX_CONCEALED_FRAMES))
        }
    }

    companion object {
        /** Never insert more than ~50 ms of silence for a gap; beyond that just resync. */
        const val MAX_CONCEALED_FRAMES = 10
    }
}

/** AudioTrack buffer sizing for NTR-HR audio (PCM16 stereo = 4 bytes per frame). */
object AudioBufferSize {
    const val BYTES_PER_FRAME = NtrConstants.AUDIO_CHANNELS * 2

    /**
     * Bytes for [latencyMs] of audio, never below the platform minimum, ALWAYS a whole number of
     * frames: AudioTrack rejects other sizes with "Invalid audio buffer size" (seen on a real
     * Chromecast with Google TV, Android 14: 32728 Hz * 4 * 160 ms = 20945 bytes was not a multiple of 4).
     */
    fun bytes(latencyMs: Int, platformMinimum: Int, sampleRate: Int = NtrConstants.AUDIO_SAMPLE_RATE): Int {
        val wanted = sampleRate.toLong() * BYTES_PER_FRAME * latencyMs / 1000
        val size = maxOf(wanted, platformMinimum.toLong(), BYTES_PER_FRAME.toLong())
        val rounded = (size + BYTES_PER_FRAME - 1) / BYTES_PER_FRAME * BYTES_PER_FRAME
        return rounded.toInt()
    }
}
