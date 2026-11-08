package com.threedstwo.tv.mock

import com.threedstwo.tv.core.ntr.Screen
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Synthetic 3DS screens with easy-to-recognise features:
 *  - red square in the native TOP-LEFT corner, blue square in the TOP-RIGHT, green in BOTTOM-LEFT
 *    (lets you verify orientation/rotation on the TV at a glance and in automated tests);
 *  - screen name and a frame counter; a moving bar to see motion and dropped frames.
 * The native landscape image is rotated into the 3DS framebuffer orientation before JPEG encoding,
 * exactly like the console sends it.
 */
object TestPattern {
    val TOP_LEFT_COLOR = Color(255, 0, 0)
    val TOP_RIGHT_COLOR = Color(0, 0, 255)
    val BOTTOM_LEFT_COLOR = Color(0, 200, 0)
    const val MARKER = 40

    fun native(screen: Screen, frame: Int): BufferedImage {
        val w = screen.nativeWidth
        val h = screen.nativeHeight
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = if (screen == Screen.TOP) Color(30, 30, 60) else Color(50, 30, 30)
        g.fillRect(0, 0, w, h)
        // checkerboard to judge pixel-perfect scaling
        g.color = Color(70, 70, 90)
        for (y in 60 until 180 step 8) for (x in 60 until w - 60 step 8) if ((x / 8 + y / 8) % 2 == 0) g.fillRect(x, y, 4, 4)
        g.color = TOP_LEFT_COLOR; g.fillRect(0, 0, MARKER, MARKER)
        g.color = TOP_RIGHT_COLOR; g.fillRect(w - MARKER, 0, MARKER, MARKER)
        g.color = BOTTOM_LEFT_COLOR; g.fillRect(0, h - MARKER, MARKER, MARKER)
        g.color = Color.WHITE
        g.stroke = BasicStroke(2f)
        g.drawRect(1, 1, w - 3, h - 3)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        g.drawString(if (screen == Screen.TOP) "3DS2TV  TOP" else "BOTTOM", 60, 40)
        g.font = Font(Font.MONOSPACED, Font.BOLD, 22)
        g.drawString("frame %05d".format(frame), 60, h - 15)
        val barX = (frame * 4) % w
        g.color = Color.YELLOW
        g.fillRect(barX, 190, 6, 20)
        g.dispose()
        return img
    }

    /** Native landscape -> framebuffer orientation (240 wide): fb(x, y) = native(X = y, Y = 239 - x). */
    fun toFramebuffer(native: BufferedImage): BufferedImage {
        val nw = native.width
        val nh = native.height
        val fb = BufferedImage(nh, nw, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until nw) for (x in 0 until nh) fb.setRGB(x, y, native.getRGB(y, nh - 1 - x))
        return fb
    }

    fun encodeJpeg(img: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam
            p.compressionMode = ImageWriteParam.MODE_EXPLICIT
            p.compressionQuality = (quality.coerceIn(10, 100)) / 100f
            writer.write(null, IIOImage(img, null, null), p)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun frameJpeg(screen: Screen, frame: Int, quality: Int) = encodeJpeg(toFramebuffer(native(screen, frame)), quality)
}
