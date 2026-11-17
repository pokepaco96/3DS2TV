package com.threedstwo.tv.core.bench

import com.threedstwo.tv.core.input.ButtonMapping
import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.input.InputMapper
import com.threedstwo.tv.core.input.MappingPreset
import com.threedstwo.tv.core.input.PadControl
import com.threedstwo.tv.core.input.PadSnapshot
import com.threedstwo.tv.core.input.StickSettings
import com.threedstwo.tv.core.layout.LayoutSettings
import com.threedstwo.tv.core.layout.ScreenLayout
import com.threedstwo.tv.core.ntr.FrameMailbox
import com.threedstwo.tv.core.ntr.JpegFrameAssembler
import com.threedstwo.tv.core.ntr.JpegPacketizer
import com.threedstwo.tv.core.ntr.PacketClassifier
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import org.junit.Test
import kotlin.random.Random

/** Benchmarks of every hot-path stage that runs on the JVM. ./gradlew :core:test -Pbench --tests '*bench*' */
class PipelineBench {
    // ~30 KB JPEG at quality ~80 for the top screen -> 21 packets
    private val jpeg = Random(7).nextBytes(30_000)
    private val packets = JpegPacketizer.packetize(jpeg, Screen.TOP, 0)

    @Test fun classify() {
        Bench.assumeEnabled()
        val p = packets[3]
        Bench.run("udp.classify(video)", 1_000_000) { PacketClassifier.classify(p, 0, p.size).ordinal.toLong() }
    }

    @Test fun assemblePerFrame() {
        Bench.assumeEnabled()
        var n = 0L
        val asm = JpegFrameAssembler(listener = { _, _, len -> n += len })
        // a fresh frame id per iteration, like the live stream
        val frames = List(256) { id -> JpegPacketizer.packetize(jpeg, Screen.TOP, id) }
        Bench.run("assembler.frame(21 pkts)", 2_000) { i ->
            for (p in frames[i and 0xFF]) asm.feed(p, 0, p.size)
            n
        }
    }

    @Test fun mailboxOfferTake() {
        Bench.assumeEnabled()
        val mb = FrameMailbox()
        val info = VideoFrameInfo(Screen.TOP, 1, 0, false, false)
        Bench.run("mailbox.offer+take(30KB)", 50_000) {
            mb.offer(info, jpeg, jpeg.size)
            val f = mb.take(0)!!
            mb.release(f)
            f.length.toLong()
        }
    }

    @Test fun inputMapAndEncode() {
        Bench.assumeEnabled()
        val m = ButtonMapping.preset(MappingPreset.POSITIONAL)
        val s = StickSettings()
        val pad = PadSnapshot(held = setOf(PadControl.SOUTH, PadControl.R2), leftX = 0.7f, leftY = -0.3f, rightX = 0.2f)
        val out = ByteArray(InputFrame.PACKET_SIZE)
        Bench.run("input.map+encode", 200_000) {
            InputMapper.map(pad, m, s, s, false, false, null).frame.encode(out)[0].toLong()
        }
    }

    @Test fun layoutAndMatrix() {
        Bench.assumeEnabled()
        val ps = ScreenLayout.compute(1920, 1080, LayoutSettings())
        Bench.run("placement.jpegToSurface(2 screens)", 500_000) {
            var x = 0f
            for (p in ps) x += p.jpegToSurface(240, if (p.screen == Screen.TOP) 400 else 320).a
            x.toLong()
        }
    }

    @Test fun jpegValidationStrategies() {
        Bench.assumeEnabled()
        val real = com.threedstwo.tv.core.JpegHeaderTest.realJpeg(240, 400)
        // B: our header reader (A = no pre-check costs 0; C = inJustDecodeBounds is Android-only and
        // parses through libjpeg setup, see docs/OPTIMIZATION.md)
        Bench.run("jpeg.header(SOF parse)", 1_000_000) { com.threedstwo.tv.core.ntr.JpegHeader.dimensions(real, real.size).toLong() }
        // Reference: full decode of the same JPEG with the JVM decoder (only for scale; not BitmapFactory)
        Bench.run("jpeg.full decode (JVM ImageIO, ref)", 50, batches = 10, warmupBatches = 3) {
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(real)).width.toLong()
        }
    }
}
