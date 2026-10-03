package com.whitedevil.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The prompt writer (POST /api/ltx/assist) wants a small JPEG as a data URL, at most 4,000,000 characters;
 * the original picture can be many megabytes. Scales to [maxSide] on the long edge, re-encodes as JPEG, and
 * steps the size down until it fits. Returns null when the bytes are not an image Java can read.
 */
fun shrinkToJpegDataUrl(bytes: ByteArray, maxSide: Int = 1024, maxChars: Int = 3_800_000): String? {
    val src = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    var side = maxSide
    while (side >= 128) {
        val scale = minOf(1.0, side.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt()); val h = maxOf(1, (src.height * scale).toInt())
        // JPEG has no alpha: draw onto white so a transparent PNG does not become black.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h)
        g.drawImage(src, 0, 0, w, h, null); g.dispose()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
        val buf = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(buf).use { ios -> writer.output = ios; writer.write(null, IIOImage(out, null, null), param) }
        writer.dispose()
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(buf.toByteArray())
        if (url.length <= maxChars) return url
        side = side * 3 / 4
    }
    return null
}
