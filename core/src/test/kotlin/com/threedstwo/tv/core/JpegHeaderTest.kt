package com.threedstwo.tv.core

import com.threedstwo.tv.core.ntr.JpegHeader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class JpegHeaderTest {
    companion object {
        fun realJpeg(w: Int, h: Int, seed: Int = 1): ByteArray {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val r = Random(seed)
            for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, if ((x / 8 + y / 8) % 2 == 0) r.nextInt() else 0x305080)
            return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
        }
    }

    @Test fun readsDimensionsOfRealJpegs() {
        for ((w, h) in listOf(240 to 400, 240 to 320, 120 to 200, 240 to 800, 1 to 1)) {
            val d = JpegHeader.dimensions(realJpeg(w, h), Int.MAX_VALUE)
            assertEquals("$w x $h", w, JpegHeader.width(d))
            assertEquals("$w x $h", h, JpegHeader.height(d))
        }
    }

    @Test fun rejectsGarbageTruncatedAndCorrupt() {
        val ok = realJpeg(240, 400)
        assertEquals(JpegHeader.INVALID, JpegHeader.dimensions(ByteArray(0), 0))
        assertEquals(JpegHeader.INVALID, JpegHeader.dimensions(byteArrayOf(0, 1, 2, 3), 4))
        assertEquals(JpegHeader.INVALID, JpegHeader.dimensions(ok, 10)) // truncated before SOF
        val badLen = ok.copyOf().also { it[4] = 0; it[5] = 0 } // first segment length 0
        assertEquals(JpegHeader.INVALID, JpegHeader.dimensions(badLen, badLen.size))
        val noMarker = ok.copyOf().also { it[2] = 0x12 }
        assertEquals(JpegHeader.INVALID, JpegHeader.dimensions(noMarker, noMarker.size))
        // fuzz: random corruption must never throw
        val rnd = Random(3)
        repeat(20_000) {
            val c = ok.copyOf(rnd.nextInt(2, ok.size))
            repeat(3) { c[rnd.nextInt(c.size)] = rnd.nextInt().toByte() }
            JpegHeader.dimensions(c, c.size)
        }
    }

    @Test fun hugeDeclaredDimensionsAreReportedNotAllocated() {
        val ok = realJpeg(240, 400)
        val d0 = JpegHeader.dimensions(ok, ok.size)
        // find SOF0 and patch dimensions to 65535 x 65535: the caller must reject it before decoding
        val i = (0 until ok.size - 1).first { ok[it] == 0xFF.toByte() && ok[it + 1] == 0xC0.toByte() }
        val evil = ok.copyOf().also { for (k in 5..8) it[i + k] = 0xFF.toByte() }
        val d = JpegHeader.dimensions(evil, evil.size)
        assertEquals(65535, JpegHeader.width(d)); assertEquals(65535, JpegHeader.height(d))
        assertEquals(240, JpegHeader.width(d0))
    }
}
