package dev.leash.personal.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory

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

/** Decodes an attached image, scaled down so no side is much beyond [maxPx] (a camera photo must not be decoded at full size for a thumbnail). */
fun decodeImage(bytes: ByteArray, maxPx: Int = 1024): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
