package dev.leash.personal.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Files fetched from the agent's host live here for Share, Open with and Save (ios FetchedFile.temporaryURL): one folder per
 * fetch, under the cache directory, handed to other apps through the FileProvider (`<applicationId>.files`). Old ones are
 * swept when the app starts.
 */
object FileCache {
    private const val DIR = "leash-files"

    private fun root(context: Context) = File(context.cacheDir, DIR)

    /** A copy of [data] named [name] (the same name, so "Save" and Share keep it). */
    fun write(context: Context, name: String, data: ByteArray): File {
        val dir = File(root(context), UUID.randomUUID().toString()).apply { mkdirs() }
        val f = File(dir, FileKinds.safeName(name))
        f.writeBytes(data)
        return f
    }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Deletes what is older than a day. */
    fun sweep(context: Context, nowMs: Long = System.currentTimeMillis()) {
        root(context).listFiles()?.forEach { if (nowMs - it.lastModified() > 24 * 3_600_000L) it.deleteRecursively() }
    }

    /** The system share sheet for [file]. */
    fun share(context: Context, file: File, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            val u = uri(context, file)
            putExtra(Intent.EXTRA_STREAM, u)
            clipData = ClipData.newRawUri(file.name, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    /** Hands [file] to whichever app opens its type; false when none does. */
    fun openWith(context: Context, file: File, mime: String, title: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            val u = uri(context, file)
            setDataAndType(u, mime)
            clipData = ClipData.newRawUri(file.name, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try { context.startActivity(Intent.createChooser(view, title)); true } catch (e: ActivityNotFoundException) { false }
    }

    /** Copies [file] to a document the user picked with the system's save dialog. */
    fun copyTo(context: Context, file: File, target: Uri): Boolean = try {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
    } catch (e: java.io.IOException) { false }
}
