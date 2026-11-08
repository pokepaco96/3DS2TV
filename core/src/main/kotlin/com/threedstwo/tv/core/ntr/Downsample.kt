package com.threedstwo.tv.core.ntr

/**
 * Reconstruction for NTR-HR "even/odd" downsampling (console advanced option, hdr[2] downsample = 2):
 * each frame carries every other column of the 240 px axis; two consecutive frames are interleaved.
 * Port of ntrviewer-hr `screen_process()` (MIT) operating on ARGB int pixels.
 */
object Downsample {
    /**
     * @param curr  half-width image of the current frame (width/2 x height, row-major)
     * @param prev  half-width image of the previous frame
     * @param out   full image (width x height)
     * @param evenOdd frame parity (frameId % 2 == 1)
     */
    fun mergeEvenOdd(curr: IntArray, prev: IntArray, out: IntArray, evenOdd: Boolean, width: Int, height: Int) {
        val a = if (evenOdd) prev else curr
        val b = if (evenOdd) curr else prev
        val half = width / 2
        require(curr.size >= half * height && prev.size >= half * height && out.size >= width * height)
        for (y in 0 until height) {
            val rowSrc = y * half
            val rowDst = y * width
            for (x in 0 until width) {
                out[rowDst + x] = (if (x % 2 == 0) a else b)[rowSrc + x / 2]
            }
        }
    }
}
