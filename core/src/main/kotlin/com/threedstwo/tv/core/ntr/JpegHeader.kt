package com.threedstwo.tv.core.ntr

/**
 * Minimal JPEG header reader: walks the marker segments up to the first SOFn and returns its
 * dimensions WITHOUT decoding. NTR-HR JPEGs put SOF0 a few hundred bytes in (after DQT/DHT), so
 * this touches < 1 KB and allocates nothing. Used to reject a corrupt/unexpected frame BEFORE
 * BitmapFactory sees it (no double decode, no huge allocation from a bogus header).
 *
 * Returns `(width shl 16) or height`, or [INVALID] if the data is not a parsable JPEG header.
 */
object JpegHeader {
    const val INVALID = -1

    fun dimensions(data: ByteArray, length: Int): Int {
        val end = minOf(length, data.size)
        if (end < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return INVALID
        var i = 2
        while (i + 3 < end) {
            if (data[i] != 0xFF.toByte()) return INVALID
            var marker = data[i + 1].toInt() and 0xFF
            // fill bytes 0xFF 0xFF ...
            while (marker == 0xFF && i + 2 < end) {
                i++
                marker = data[i + 1].toInt() and 0xFF
            }
            i += 2
            when {
                marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 -> continue // no length
                marker == 0xD9 || marker == 0xDA -> return INVALID // EOI / SOS before any SOF
            }
            if (i + 1 >= end) return INVALID
            val segLen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            if (segLen < 2) return INVALID
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 6 >= end || segLen < 7) return INVALID
                val h = ((data[i + 3].toInt() and 0xFF) shl 8) or (data[i + 4].toInt() and 0xFF)
                val w = ((data[i + 5].toInt() and 0xFF) shl 8) or (data[i + 6].toInt() and 0xFF)
                if (w == 0 || h == 0) return INVALID
                return (w shl 16) or h
            }
            i += segLen
        }
        return INVALID
    }

    fun width(dims: Int) = dims ushr 16
    fun height(dims: Int) = dims and 0xFFFF
}
