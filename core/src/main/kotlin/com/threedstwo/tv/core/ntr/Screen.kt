package com.threedstwo.tv.core.ntr

enum class Screen { TOP, BOTTOM;

    /** Native landscape width of the screen in pixels (400 top, 320 bottom). */
    val nativeWidth: Int get() = if (this == TOP) NtrConstants.TOP_WIDTH else NtrConstants.BOTTOM_WIDTH
    val nativeHeight: Int get() = NtrConstants.SCREEN_HEIGHT
}

/**
 * Per-frame metadata decoded from the 4-byte NTR data header.
 *
 *  hdr[0] frame id, hdr[1] bit0 = top screen, bit1 = full width (top), bit2 = both eyes, bit4 = end of frame,
 *  hdr[2] = 2 | downsample << 2 | lossless, hdr[3] = packet index (u8, wraps).
 */
data class VideoFrameInfo(
    val screen: Screen,
    val frameId: Int,
    /** 0 none, 1 checker, 2 even/odd columns, 3 quarter (NTR-HR advanced console option). */
    val downsample: Int,
    val fullWidth: Boolean,
    val bothEyes: Boolean,
) {
    /** Width of the encoded JPEG (the 240 px axis of the 3DS framebuffer, possibly halved). */
    val jpegWidth: Int get() = if (downsample == 2 || downsample == 3) 120 else 240

    /** Height of the encoded JPEG (the 400/320 px axis, 800 for full-width top, halved for quarter). */
    val jpegHeight: Int
        get() {
            val h = if (screen == Screen.TOP) (if (fullWidth) 800 else 400) else 320
            return if (downsample == 3) h / 2 else h
        }
}
