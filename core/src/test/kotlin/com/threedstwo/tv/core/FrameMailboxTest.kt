package com.threedstwo.tv.core

import com.threedstwo.tv.core.ntr.FrameMailbox
import com.threedstwo.tv.core.ntr.Screen
import com.threedstwo.tv.core.ntr.VideoFrameInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameMailboxTest {
    private fun info(s: Screen, id: Int) = VideoFrameInfo(s, id, 0, false, false)

    @Test fun keepsOnlyLatestPerScreen() {
        val mb = FrameMailbox(1024)
        mb.offer(info(Screen.TOP, 1), ByteArray(10) { 1 }, 10)
        mb.offer(info(Screen.TOP, 2), ByteArray(10) { 2 }, 10)
        mb.offer(info(Screen.TOP, 3), ByteArray(10) { 3 }, 10)
        val f = mb.take(10)!!
        assertEquals(3, f.info.frameId)
        assertEquals(3.toByte(), f.data[0])
        assertEquals(2, mb.droppedBeforeDecode)
        mb.release(f)
        assertNull(mb.take(10))
    }

    @Test fun oldestScreenFirstAndNoAllocationsExhaustPool() {
        val mb = FrameMailbox(64)
        repeat(1000) { i ->
            mb.offer(info(Screen.TOP, i), ByteArray(4), 4)
            mb.offer(info(Screen.BOTTOM, i), ByteArray(4), 4)
            val a = mb.take(10)!!
            assertEquals(Screen.TOP, a.info.screen)
            val b = mb.take(10)!!
            assertEquals(Screen.BOTTOM, b.info.screen)
            mb.release(a); mb.release(b)
        }
    }

    @Test fun pendingReplacedWhileConsumerHoldsFrame() {
        val mb = FrameMailbox(64)
        mb.offer(info(Screen.TOP, 1), ByteArray(4), 4)
        val held = mb.take(10)!!
        repeat(10) { mb.offer(info(Screen.TOP, 2 + it), ByteArray(4), 4) }
        assertEquals(11, mb.take(10)!!.info.frameId)
        mb.release(held)
    }
}
