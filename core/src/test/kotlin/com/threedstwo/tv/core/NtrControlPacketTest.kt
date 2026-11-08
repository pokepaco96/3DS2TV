package com.threedstwo.tv.core

import com.threedstwo.tv.core.ntr.NtrConstants
import com.threedstwo.tv.core.ntr.NtrControlPacket
import com.threedstwo.tv.core.ntr.RemotePlayRequest
import com.threedstwo.tv.core.util.LittleEndian
import org.junit.Assert.assertEquals
import org.junit.Test

class NtrControlPacketTest {
    @Test fun headerIs84BytesLittleEndian() {
        val bytes = NtrControlPacket.heartbeat(7).encode()
        assertEquals(84, bytes.size)
        assertEquals(0x78.toByte(), bytes[0]); assertEquals(0x56.toByte(), bytes[1])
        assertEquals(0x34.toByte(), bytes[2]); assertEquals(0x12.toByte(), bytes[3])
        assertEquals(7, LittleEndian.getInt(bytes, 4))
        assertEquals(0, LittleEndian.getInt(bytes, 12))
        assertEquals(0, LittleEndian.getInt(bytes, 80))
    }

    @Test fun remotePlayArgsMatchNtrViewerHr() {
        val req = RemotePlayRequest(topScreenPriority = true, priorityFactor = 2, jpegQuality = 75, qosMbps = 16, viewerPort = 8001, audio = true)
        val p = NtrControlPacket.decode(req.toPacket(3).encode())
        assertEquals(NtrConstants.CMD_REMOTE_PLAY, p.cmd)
        assertEquals(3, p.seq)
        assertEquals(0x0102, p.args[0])
        assertEquals(75, p.args[1])
        assertEquals(16 * 128 * 1024, p.args[2])
        assertEquals(1404036572, p.args[3])
        assertEquals(8001, p.args[4]) // no RS / delta / lossless flags => JPEG Compat
        assertEquals(1, p.args[5])
        assertEquals(0, p.args[6])
        for (i in 7 until 16) assertEquals(0, p.args[i])
    }

    @Test fun requestIsClamped() {
        val a = RemotePlayRequest(jpegQuality = 500, qosMbps = 1, priorityFactor = -3).toArgs()
        assertEquals(100, a[1])
        assertEquals(4 * 128 * 1024, a[2])
        assertEquals(0x100, a[0])
        val bottom = RemotePlayRequest(topScreenPriority = false, priorityFactor = 0).toArgs()
        assertEquals(0, bottom[0])
    }

    @Test fun roundTrip() {
        val p = NtrControlPacket(seq = 9, type = 1, cmd = 901, args = intArrayOf(1, 2, 3), dataLen = 5)
        assertEquals(p, NtrControlPacket.decode(p.encode()))
    }
}
