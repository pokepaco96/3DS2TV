package com.threedstwo.tv.core

import com.threedstwo.tv.core.ntr.JpegFrameAssembler
import com.threedstwo.tv.core.ntr.JpegPacketizer
import com.threedstwo.tv.core.ntr.NtrConstants
import com.threedstwo.tv.core.ntr.PacketClassifier
import com.threedstwo.tv.core.ntr.PacketKind
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JpegFrameAssemblerTest {
    private val out = mutableListOf<Pair<VideoFrameInfo, ByteArray>>()
    private val asm = JpegFrameAssembler(listener = { info, data, len -> out += info to data.copyOf(len) })

    private fun payload(size: Int, seed: Int) = Random(seed).nextBytes(size)
    private fun feed(p: ByteArray) = asm.feed(p, 0, p.size)

    @Test fun reassemblesInOrder() {
        val jpeg = payload(5000, 1)
        JpegPacketizer.packetize(jpeg, Screen.TOP, 5).forEach { feed(it) }
        assertEquals(1, out.size)
        assertEquals(Screen.TOP, out[0].first.screen)
        assertEquals(5, out[0].first.frameId)
        assertArrayEquals(jpeg, out[0].second)
        assertEquals(240, out[0].first.jpegWidth)
        assertEquals(400, out[0].first.jpegHeight)
    }

    @Test fun reassemblesOutOfOrderAndIgnoresDuplicates() {
        val jpeg = payload(10000, 2)
        val packets = JpegPacketizer.packetize(jpeg, Screen.BOTTOM, 9)
        val shuffled = packets.shuffled(Random(3))
        shuffled.forEach { feed(it) }
        packets.forEach { feed(it) } // late duplicates after completion
        assertEquals(1, out.size)
        assertEquals(Screen.BOTTOM, out[0].first.screen)
        assertEquals(320, out[0].first.jpegHeight)
        assertArrayEquals(jpeg, out[0].second)
        assertTrue(asm.stats.duplicatePackets >= packets.size)
    }

    @Test fun singlePacketFrame() {
        val jpeg = payload(100, 4)
        JpegPacketizer.packetize(jpeg, Screen.TOP, 0).forEach { feed(it) }
        assertArrayEquals(jpeg, out.single().second)
    }

    @Test fun exactMultipleOfPacketSize() {
        val jpeg = payload(NtrConstants.PACKET_DATA_SIZE * 3, 5)
        val packets = JpegPacketizer.packetize(jpeg, Screen.TOP, 1)
        assertEquals(3, packets.size)
        packets.forEach { feed(it) }
        assertArrayEquals(jpeg, out.single().second)
    }

    @Test fun lostPacketDropsFrameButNextFrameWorks() {
        val a = payload(6000, 6)
        val b = payload(6000, 7)
        JpegPacketizer.packetize(a, Screen.TOP, 1).filterIndexed { i, _ -> i != 2 }.forEach { feed(it) }
        assertTrue(out.isEmpty())
        JpegPacketizer.packetize(b, Screen.TOP, 2).forEach { feed(it) }
        assertEquals(1, out.size)
        assertArrayEquals(b, out[0].second)
        assertEquals(1, asm.stats.framesIncomplete)
    }

    @Test fun interleavedScreensAreSeparated() {
        val top = JpegPacketizer.packetize(payload(4000, 8), Screen.TOP, 3)
        val bot = JpegPacketizer.packetize(payload(4000, 9), Screen.BOTTOM, 3)
        val inter = top.zip(bot).flatMap { listOf(it.first, it.second) }
        inter.forEach { feed(it) }
        assertEquals(setOf(Screen.TOP, Screen.BOTTOM), out.map { it.first.screen }.toSet())
    }

    @Test fun largeFrameWithIndexWrapAround() {
        // > 256 packets: hdr[3] wraps, assembler must unwrap like ntrviewer-hr
        val jpeg = payload(NtrConstants.PACKET_DATA_SIZE * 300 + 17, 10)
        val packets = JpegPacketizer.packetize(jpeg, Screen.TOP, 4)
        assertEquals(301, packets.size)
        packets.forEach { feed(it) }
        assertArrayEquals(jpeg, out.single().second)
    }

    @Test fun rejectsTruncatedNonFinalPacket() {
        val p = JpegPacketizer.packetize(payload(4000, 11), Screen.TOP, 1)[0]
        assertFalse(feed(p.copyOf(100)))
        assertEquals(1, asm.stats.invalidPackets)
    }

    @Test fun headerFlagsDecoded() {
        JpegPacketizer.packetize(payload(300, 12), Screen.TOP, 6, downsample = 3, fullWidth = true).forEach { feed(it) }
        val info = out.single().first
        assertEquals(3, info.downsample)
        assertTrue(info.fullWidth)
        assertEquals(120, info.jpegWidth)
        assertEquals(400, info.jpegHeight) // 800 / 2
    }

    @Test fun classifier() {
        val video = JpegPacketizer.packetize(payload(100, 13), Screen.TOP, 1)[0]
        assertEquals(PacketKind.VIDEO_JPEG, PacketClassifier.classify(video, 0, video.size))
        val audio = JpegPacketizer.audioPacket(1, listOf(ByteArray(640), ByteArray(640)))
        assertEquals(PacketKind.AUDIO, PacketClassifier.classify(audio, 0, audio.size))
        val lossless = video.copyOf().also { it[2] = 3 }
        assertEquals(PacketKind.VIDEO_LOSSLESS, PacketClassifier.classify(lossless, 0, lossless.size))
        assertEquals(PacketKind.RELIABLE_STREAM, PacketClassifier.classify(byteArrayOf(1, 2), 0, 2))
        val kcp = byteArrayOf(0x01, 0x7F, 0x55, 0x10, 0, 0, 0, 0)
        assertEquals(PacketKind.RELIABLE_STREAM, PacketClassifier.classify(kcp, 0, kcp.size))
        assertEquals(PacketKind.INVALID, PacketClassifier.classify(byteArrayOf(1), 0, 1))
    }

    @Test fun staleIncompleteFrameDroppedWhenNewerCompletes() {
        val old = JpegPacketizer.packetize(payload(5000, 14), Screen.TOP, 1)
        val new = JpegPacketizer.packetize(payload(5000, 15), Screen.TOP, 2)
        feed(old[0]); feed(old[1])
        new.forEach { feed(it) }
        // remaining old packets arrive late: must not produce an out-of-date frame after a newer one
        old.drop(2).forEach { feed(it) }
        assertEquals(listOf(2), out.map { it.first.frameId })
    }

    @Test fun wholeOlderFrameArrivingAfterNewerIsNotShown() {
        // Reordering of a complete frame: frame 9 delivered, then all of frame 8 arrives.
        JpegPacketizer.packetize(payload(3000, 20), Screen.TOP, 9).forEach { feed(it) }
        JpegPacketizer.packetize(payload(3000, 21), Screen.TOP, 8).forEach { feed(it) }
        assertEquals(listOf(9), out.map { it.first.frameId })
    }

    @Test fun frameIdWrapAroundIsNewer() {
        for (id in listOf(254, 255, 0, 1)) JpegPacketizer.packetize(payload(2000, id), Screen.TOP, id).forEach { feed(it) }
        assertEquals(listOf(254, 255, 0, 1), out.map { it.first.frameId })
    }

    @Test fun screensAreOrderedIndependently() {
        JpegPacketizer.packetize(payload(2000, 30), Screen.TOP, 50).forEach { feed(it) }
        JpegPacketizer.packetize(payload(2000, 31), Screen.BOTTOM, 10).forEach { feed(it) }
        assertEquals(2, out.size)
    }

    @Test fun consoleRestartIsAcceptedAfterSilence() {
        var now = 0L
        val got = mutableListOf<Int>()
        val a = JpegFrameAssembler(listener = { info, _, _ -> got += info.frameId }, clock = { now })
        fun send(id: Int) = JpegPacketizer.packetize(payload(2000, id), Screen.TOP, id).forEach { a.feed(it, 0, it.size) }
        send(100)
        now += 50_000_000; send(3)       // looks older, within 300 ms: dropped
        assertEquals(listOf(100), got)
        assertTrue(a.stats.stalePackets > 0)
        now += 400_000_000; send(4)      // > 300 ms without frames: counter restarted, accepted
        now += 16_000_000; send(5)
        assertEquals(listOf(100, 4, 5), got)
    }

    @Test fun counterResetMidStreamRecoversWithinThreeFrames() {
        var now = 0L
        val got = mutableListOf<Int>()
        val a = JpegFrameAssembler(listener = { info, _, _ -> got += info.frameId }, clock = { now })
        fun send(id: Int) = JpegPacketizer.packetize(payload(2000, id), Screen.TOP, id).forEach { a.feed(it, 0, it.size) }
        for (id in 60..62) { send(id); now += 16_000_000 }
        // NTR-HR restarts encoding (quality change): ids start again at 0 with no long pause
        for (id in 0..5) { send(id); now += 16_000_000 }
        assertEquals(listOf(60, 61, 62, 2, 3, 4, 5), got)
    }
}
