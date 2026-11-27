package dev.leash.personal.ui.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import kotlin.math.roundToInt

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

/** A transcript tile's bitmap has no side beyond this. */
const val TILE_DECODE_PX = 1024

/** The pixel size [decodeImage] gives an image of [width] x [height]. */
fun decodedSize(width: Int, height: Int, maxPx: Int = TILE_DECODE_PX): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxPx) return width to height
    val scale = maxPx.toFloat() / longest
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/** [width] x [height] pixels scaled down, never up, to fit within [maxWidth] x [maxHeight] with its shape kept. */
fun fitInside(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/**
 * Decodes an image scaled down so no side is beyond [maxPx] (a camera photo or a screenshot must not be decoded at full size for
 * a tile), with its EXIF rotation applied. Null for bytes that are not an image the platform can decode.
 */
fun decodeImage(bytes: ByteArray, maxPx: Int = TILE_DECODE_PX): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val (w, h) = decodedSize(info.size.width, info.size.height, maxPx)
        if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // drawable on any canvas, and savable
        decoder.isMutableRequired = false
    }
} catch (e: java.io.IOException) { null } catch (e: RuntimeException) { null }

