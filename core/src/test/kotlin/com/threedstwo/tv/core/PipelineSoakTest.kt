package com.threedstwo.tv.core

import com.threedstwo.tv.core.ntr.FrameMailbox
import com.threedstwo.tv.core.ntr.JpegFrameAssembler
import com.threedstwo.tv.core.ntr.JpegPacketizer
import com.threedstwo.tv.core.ntr.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Reproducible soak/throughput check of the receive path (assembler + mailbox) on the JVM:
 * ~20 000 frames (≈ 3 min of 120 fps) with 2 % loss, reordering and duplicates, a consumer that
 * is sometimes slow. Verifies bounded state (no buffer leak, no stale frames) and reports throughput.
 * Not a device benchmark: Chromecast numbers must come from the HUD (docs/FIRST_REAL_DEVICE_TEST.md).
 */
class PipelineSoakTest {
    @Test fun twentyThousandFramesStayBounded() {
        val rnd = Random(1234)
        val mailbox = FrameMailbox(64 * 1024)
        var delivered = 0L
        val asm = JpegFrameAssembler(listener = { info, data, len -> mailbox.offer(info, data, len) })
        val jpegs = List(8) { Random(it).nextBytes(8_000 + it * 3_000) } // 6-21 packets per frame
        var consumed = 0L
        var lastId = IntArray(2) { -1 }
        val t0 = System.nanoTime()
        var packetsSent = 0L
        for (frame in 0 until 20_000) {
            val screen = if (frame % 2 == 0) Screen.TOP else Screen.BOTTOM
            var packets = JpegPacketizer.packetize(jpegs[frame % jpegs.size], screen, frame and 0xFF)
            if (rnd.nextInt(10) == 0) packets = packets.shuffled(rnd)
            for (p in packets) {
                if (rnd.nextDouble() < 0.02) continue
                asm.feed(p, 0, p.size)
                packetsSent++
                if (rnd.nextInt(50) == 0) asm.feed(p, 0, p.size)
            }
            // consumer: usually keeps up, sometimes skips a turn (slow decode)
            if (rnd.nextInt(4) != 0) {
                while (true) {
                    val f = mailbox.take(0) ?: break
                    consumed++
                    lastId[f.info.screen.ordinal] = f.info.frameId
                    mailbox.release(f)
                }
            }
            delivered = asm.stats.framesCompleted
        }
        val secs = (System.nanoTime() - t0) / 1e9
        println("soak: frames=20000 completed=$delivered consumed=$consumed droppedBeforeDecode=${mailbox.droppedBeforeDecode} " +
            "incomplete=${asm.stats.framesIncomplete} packets=$packetsSent in %.2fs (%.0f frames/s)".format(secs, 20_000 / secs))
        // Every completed frame was either consumed or explicitly dropped as stale: nothing lost silently, nothing queued.
        val pendingLeft = generateSequence { mailbox.take(0)?.also { mailbox.release(it) } }.count()
        assertEquals(delivered, consumed + mailbox.droppedBeforeDecode + pendingLeft)
        assertTrue("at most one pending per screen", pendingLeft <= 2)
        assertTrue("most frames survive 2% loss", delivered > 20_000 * 0.5)
        // Pool not exhausted after the soak: we can still offer/take on both screens.
        mailbox.offer(com.threedstwo.tv.core.ntr.VideoFrameInfo(Screen.TOP, 1, 0, false, false), ByteArray(4), 4)
        mailbox.offer(com.threedstwo.tv.core.ntr.VideoFrameInfo(Screen.BOTTOM, 1, 0, false, false), ByteArray(4), 4)
        assertTrue(mailbox.take(0) != null && mailbox.take(0) != null)
        assertTrue("throughput far above 120 fps on a JVM", 20_000 / secs > 1000)
    }
}
