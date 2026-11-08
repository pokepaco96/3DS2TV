package com.threedstwo.tv.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.threedstwo.tv.AppLog
import com.threedstwo.tv.core.ntr.NtrConstants

/**
 * Plays NTR-HR DSP audio (PCM16 stereo @ 32728 Hz) with a bounded latency.
 *
 * Called from the network thread: writes are NON-BLOCKING so audio can never stall video.
 * If more than [maxLatencyMs] are queued (clock drift, burst) incoming frames are dropped to catch up.
 */
class AudioPlayer(private val maxLatencyMs: Int = 120, private val primeMs: Int = 40) {
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var playing = false
    private val silence = ByteArray(NtrConstants.AUDIO_FRAME_BYTES)

    @Volatile var droppedFrames = 0L
        private set
    @Volatile var playedFrames = 0L
        private set
    @Volatile var error: String? = null
        private set

    private val samplesPerMs = NtrConstants.AUDIO_SAMPLE_RATE / 1000.0

    @Synchronized fun start(volume: Float) {
        if (track != null) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                NtrConstants.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val wanted = (NtrConstants.AUDIO_SAMPLE_RATE * 4 * (maxLatencyMs + 40) / 1000)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(NtrConstants.AUDIO_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, wanted))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            track = builder.build().also { it.setVolume(volume) }
            framesWritten = 0
            playing = false
            error = null
            AppLog.i("Audio: AudioTrack ${NtrConstants.AUDIO_SAMPLE_RATE} Hz listo")
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            AppLog.e("Audio: no se pudo crear AudioTrack", e)
            track = null
        }
    }

    @Synchronized fun setVolume(v: Float) {
        track?.setVolume(v.coerceIn(0f, 1f))
    }

    private fun queuedMs(t: AudioTrack): Double {
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queued = framesWritten - head
        return queued / samplesPerMs
    }

    @Synchronized fun write(pcm: ByteArray, offset: Int, silenceFramesBefore: Int) {
        val t = track ?: return
        if (playing && queuedMs(t) > maxLatencyMs) {
            droppedFrames++
            return
        }
        repeat(silenceFramesBefore) { writeFrame(t, silence, 0) }
        writeFrame(t, pcm, offset)
        playedFrames++
        if (!playing && framesWritten / samplesPerMs >= primeMs) {
            t.play()
            playing = true
        }
    }

    private fun writeFrame(t: AudioTrack, buf: ByteArray, off: Int) {
        val n = t.write(buf, off, NtrConstants.AUDIO_FRAME_BYTES, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) framesWritten += n / 4 else if (n == 0) droppedFrames++
    }

    @Synchronized fun stop() {
        track?.let {
            try {
                it.pause()
                it.flush()
                it.release()
            } catch (_: Exception) {
            }
        }
        track = null
        playing = false
    }

    val isActive: Boolean get() = track != null
}
