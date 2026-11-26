package dev.leash.personal.ui.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

/** 12400 -> "12.4k", 1_800_000 -> "1.8M" */
fun compactNumber(n: Int): String {
    fun one(v: Double, unit: String): String {
        val s = String.format(java.util.Locale.US, "%.1f", v)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + unit
    }
    return when {
        n >= 1_000_000 -> one(n / 1_000_000.0, "M")
        n >= 1_000 -> one(n / 1_000.0, "k")
        else -> n.toString()
    }
}

/**
 * Decodes an image scaled down so no side is beyond [maxPx] (a camera photo or a screenshot must not be decoded at full size for
 * a tile), with its EXIF rotation applied. Null for bytes that are not an image the platform can decode.
 */
fun decodeImage(bytes: ByteArray, maxPx: Int = 1024): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > maxPx) {
            val scale = maxPx.toFloat() / longest
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // drawable on any canvas, and savable
        decoder.isMutableRequired = false
    }
} catch (e: java.io.IOException) { null } catch (e: RuntimeException) { null }

