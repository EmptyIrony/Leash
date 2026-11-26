package dev.leash.personal.core

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** Size limits of files moving between this phone and the agent's host (ios AppModel.maxUpload / maxDownload). */
object FileLimits {
    /**
     * Phone to host. The bridge takes 25 MB in one `bridge/upload` call (base64, no chunking), but OkHttp refuses to queue
     * more than 16 MiB of outgoing frames and closes the socket when one frame would pass it. Base64 adds a third plus the
     * JSON around it, so the whole file goes out only up to 11 MiB (14.7 MiB on the wire). Larger files need a chunked
     * upload in the bridge.
     */
    const val MAX_UPLOAD_BYTES = 11 * 1024 * 1024
    const val MAX_UPLOAD_TEXT = "11 MB"

    /** Host to phone: the bridge's own cap (its error is [FILE_TOO_LARGE]). */
    const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024

    /** Inline images are fetched up to this size. */
    const val MAX_INLINE_IMAGE_BYTES = 16 * 1024 * 1024

    /** A text file is shown with line numbers up to this size; anything bigger goes to an external viewer. */
    const val MAX_TEXT_VIEW_BYTES = 2 * 1024 * 1024

    /** bridge/readFile and bridge/upload: the file is over the bridge's limit. */
    const val FILE_TOO_LARGE = -32007

    /** 1_500_000 -> "1.5 MB" (decimal units, like the iOS file size style). */
    fun sizeText(bytes: Long): String {
        if (bytes < 1000) return "$bytes bytes"
        val units = listOf("KB", "MB", "GB")
        var v = bytes / 1000.0
        var u = 0
        while (v >= 1000 && u < units.lastIndex) { v /= 1000; u++ }
        val s = if (v >= 100 || u == 0 && v >= 10) String.format(java.util.Locale.US, "%.0f", v) else String.format(java.util.Locale.US, "%.1f", v)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + " " + units[u]
    }
}

/** A whole file fetched from the agent's host. */
class FetchedFile(val path: String, val data: ByteArray, val mimeType: String?, val binary: Boolean) {
    val name: String get() = lastPathComponent(path)
}

/** The start of a text file, for files too large to download whole. */
data class TextPreview(val text: String?, val truncated: Boolean, val binary: Boolean)

/** A file picked in the composer, not sent yet. */
class PendingFile(val name: String, val data: ByteArray, val id: UUID = UUID.randomUUID()) {
    val size: Int get() = data.size
}

/** "path/to/file.swift:42" -> ("path/to/file.swift", 42) */
fun splitLineSuffix(path: String): Pair<String, Int?> {
    val m = Regex(""":(\d+)$""").find(path) ?: return path to null
    return path.substring(0, m.range.first) to m.groupValues[1].toIntOrNull()
}

/**
 * Stores a picked file on the agent's host and returns the prompt block that points at it (a `resource_link`).
 * Fails at once, before anything is sent, for a file over [FileLimits.MAX_UPLOAD_BYTES].
 */
suspend fun AppModel.upload(f: PendingFile, st: ChatStore): JsonElement {
    if (f.size > FileLimits.MAX_UPLOAD_BYTES) {
        throw BridgeError(FileLimits.FILE_TOO_LARGE, f.name + " is larger than " + FileLimits.MAX_UPLOAD_TEXT)
    }
    val data = withContext(Dispatchers.Default) { java.util.Base64.getEncoder().encodeToString(f.data) }
    val r = link.call("bridge/upload", jsonObj("sessionId" to st.sessionId, "name" to f.name, "data" to data, "agent" to st.agent), 300_000)
    val uri = r["uri"].string ?: throw BridgeError(-3, "Upload failed")
    val block = linkedMapOf<String, Any?>("type" to "resource_link", "uri" to uri, "name" to (r["name"].string ?: f.name), "size" to f.size)
    r["mimeType"].string?.let { block["mimeType"] = it }
    return j(block)
}

/** The whole file (bridge/readFile in base64 mode); a relative path is taken from the conversation's folder. */
suspend fun AppModel.fetchFile(path: String, agent: String, sessionId: String?, maxBytes: Int = FileLimits.MAX_DOWNLOAD_BYTES): FetchedFile {
    val p = linkedMapOf<String, Any?>("path" to path, "agent" to agent, "mode" to "base64", "maxBytes" to maxBytes)
    if (sessionId != null) p["sessionId"] = sessionId
    val r = link.call("bridge/readFile", j(p), 180_000)
    val b64 = r["data"].string ?: throw BridgeError(-3, "The bridge sent no file data")
    val data = withContext(Dispatchers.Default) { java.util.Base64.getMimeDecoder().decode(b64) }
    return FetchedFile(r["path"].string ?: path, data, r["mimeType"].string, r["binary"].bool ?: true)
}

/** The start of a text file (the bridge's first 512 KB). */
suspend fun AppModel.readTextPreview(path: String, agent: String, sessionId: String?): TextPreview {
    val p = linkedMapOf<String, Any?>("path" to path, "agent" to agent)
    if (sessionId != null) p["sessionId"] = sessionId
    val r = link.call("bridge/readFile", j(p), 60_000)
    return TextPreview(r["text"].string, r["truncated"].bool ?: false, r["binary"].bool ?: false)
}
