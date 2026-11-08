package com.threedstwo.tv.core

import com.threedstwo.tv.core.ntr.Downsample
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DownsampleTest {
    @Test fun interleavesColumns() {
        val curr = intArrayOf(1, 2, 3, 4) // 2x2 half image
        val prev = intArrayOf(10, 20, 30, 40)
        val out = IntArray(8)
        Downsample.mergeEvenOdd(curr, prev, out, evenOdd = false, width = 4, height = 2)
        assertArrayEquals(intArrayOf(1, 10, 2, 20, 3, 30, 4, 40), out)
        Downsample.mergeEvenOdd(curr, prev, out, evenOdd = true, width = 4, height = 2)
        assertArrayEquals(intArrayOf(10, 1, 20, 2, 30, 3, 40, 4), out)
    }
}
