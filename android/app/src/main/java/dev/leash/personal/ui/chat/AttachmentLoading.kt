package dev.leash.personal.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import dev.leash.personal.core.FileLimits
import dev.leash.personal.core.PendingFile
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pure rules for what the composer takes in (kept apart from the Android calls so they can be tested). */
object AttachmentPolicy {
    /** At most this many photos go with one message (ios: 4). */
    const val MAX_PHOTOS = 4
    const val PHOTO_MAX_SIDE = 1600
    const val PHOTO_QUALITY = 80

    /** Photos are downscaled so no side passes [maxSide] (never enlarged), keeping the aspect ratio. */
    fun scaled(width: Int, height: Int, maxSide: Int = PHOTO_MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val f = maxSide.toDouble() / longest
        return maxOf(1, (width * f).toInt()) to maxOf(1, (height * f).toInt())
    }

    /** A file is refused by its size before its bytes are read (a missing size is read and checked after). */
    fun tooLarge(size: Long?): Boolean = size != null && size > FileLimits.MAX_UPLOAD_BYTES

    /** What picking [photos] adds to [have]: the first that fit under [MAX_PHOTOS]. */
    fun room(have: Int, picked: Int): Int = (MAX_PHOTOS - have).coerceIn(0, picked)
}

class LoadedFiles(val files: List<PendingFile>, val tooLarge: List<String>, val unreadable: List<String>)

/** Reads what the system pickers hand back: photos as JPEG (like iOS), files whole, refusing the ones over the upload cap. */
object AttachmentLoading {
    /** A photo from the Photo Picker, rotated by its EXIF, scaled to 1600 px on the long side, JPEG 80. Null when it can't be decoded. */
    suspend fun photo(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (w, h) = AttachmentPolicy.scaled(info.size.width, info.size.height)
                if (w != info.size.width) decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, AttachmentPolicy.PHOTO_QUALITY, out)
            out.toByteArray()
        } catch (e: java.io.IOException) { null } catch (e: RuntimeException) { null }
    }

    /** Files from the document picker; ones over the cap are reported by name before their bytes are read. */
    suspend fun files(context: Context, uris: List<Uri>): LoadedFiles = withContext(Dispatchers.IO) {
        val files = mutableListOf<PendingFile>()
        val tooLarge = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        for (uri in uris) {
            var name: String? = null
            var size: Long? = null
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (e: RuntimeException) {}
            val label = name ?: uri.lastPathSegment ?: "file"
            if (AttachmentPolicy.tooLarge(size)) { tooLarge += label; continue }
            val bytes = try { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } } catch (e: java.io.IOException) { null } catch (e: SecurityException) { null }
            when {
                bytes == null -> unreadable += label
                bytes.size > FileLimits.MAX_UPLOAD_BYTES -> tooLarge += label
                else -> files += PendingFile(label, bytes)
            }
        }
        LoadedFiles(files, tooLarge, unreadable)
    }

    /** Reads at most the cap plus one byte (a size the provider didn't report must not fill the heap). */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > FileLimits.MAX_UPLOAD_BYTES) break
        }
        return out.toByteArray()
    }
}
