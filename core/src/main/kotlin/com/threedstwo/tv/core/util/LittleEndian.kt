package com.threedstwo.tv.core.util

/** Little-endian helpers. Both NTR-HR (ARM11) and Luma3DS read their structs as little-endian. */
object LittleEndian {
    fun putInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
        buf[offset + 1] = (value ushr 8).toByte()
        buf[offset + 2] = (value ushr 16).toByte()
        buf[offset + 3] = (value ushr 24).toByte()
    }

    fun getInt(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    fun getShort(buf: ByteArray, offset: Int): Short =
        ((buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)).toShort()
}
