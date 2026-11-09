package com.threedstwo.tv.core

import com.threedstwo.tv.core.ntr.AudioSequencer
import com.threedstwo.tv.core.ntr.JpegPacketizer
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSequencerTest {
    private fun frame(tag: Int) = ByteArray(640) { tag.toByte() }
    private val played = mutableListOf<Pair<Int, Int>>()
    private val seq = AudioSequencer()
    private fun send(newest: Int) {
        val p = JpegPacketizer.audioPacket(newest, listOf(frame(newest - 1), frame(newest)))
        seq.feed(p, 0, p.size) { pcm, off, silence -> played += pcm[off].toInt() to silence }
    }

    @Test fun redundantFramesAreDeduplicated() {
        for (s in 1..5) send(s)
        assertEquals(listOf(1, 2, 3, 4, 5), played.map { it.first })
        assertEquals(0L, seq.framesLost)
    }

    @Test fun singleLostPacketIsRecoveredFromRedundancy() {
        send(1); send(2); /* 3 lost */ send(4)
        assertEquals(listOf(1, 2, 3, 4), played.map { it.first })
    }

    @Test fun biggerGapReportsSilence() {
        send(1); send(10)
        assertEquals(listOf(1 to 0, 9 to 7, 10 to 0), played)
        assertEquals(7L, seq.framesLost)
    }

    @Test fun wrapAroundAndOldPacketsIgnored() {
        send(254); send(255); send(0); send(255); send(1)
        assertEquals(listOf(254, 255, 0, 1).map { it.toByte().toInt() }, played.map { it.first })
    }

    @Test fun audioTrackBufferIsWholeFrames() {
        // Regression from the first real-device test: 20945 bytes -> "Invalid audio buffer size"
        val b = com.threedstwo.tv.core.ntr.AudioBufferSize.bytes(160, 0)
        assertEquals(0, b % 4)
        assertEquals(20948, b)
        assertEquals(0, com.threedstwo.tv.core.ntr.AudioBufferSize.bytes(160, 30001) % 4)
        assertEquals(30004, com.threedstwo.tv.core.ntr.AudioBufferSize.bytes(160, 30001))
        // getMinBufferSize() may return a negative error code
        assertEquals(20948, com.threedstwo.tv.core.ntr.AudioBufferSize.bytes(160, -2))
        for (ms in 1..500) assertEquals(0, com.threedstwo.tv.core.ntr.AudioBufferSize.bytes(ms, 0) % 4)
    }
}
