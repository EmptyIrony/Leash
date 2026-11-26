package dev.leash.personal.ui.files

import dev.leash.personal.core.FileLimits

/** What the viewer does with a file. */
enum class ViewerKind { Text, Image, Pdf, Other }

/** Glyph families for a file name (ios FileRef.symbol). */
enum class FileGlyph { Doc, Text, Image, Pdf, Video, Audio, Archive, Table, Slides }

/** Decisions about files by name and type, kept out of the UI so they can be tested. */
object FileKinds {
    private val images = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val video = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp")
    private val audio = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")
    private val archives = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "apk", "dmg")
    private val tables = setOf("csv", "tsv", "xls", "xlsx", "numbers", "ods")
    private val slides = setOf("ppt", "pptx", "key", "odp")
    private val textual = setOf(
        "txt", "md", "markdown", "log", "json", "yaml", "yml", "toml", "xml", "html", "htm", "svg", "css", "js", "mjs", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "java",
        "kt", "kts", "swift", "c", "h", "cpp", "hpp", "m", "mm", "sh", "zsh", "bash", "sql", "gradle", "properties", "ini", "conf", "env", "lock", "csv", "tsv",
    )

    fun extension(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun isImage(name: String, mime: String? = null) = extension(name) in images || (mime?.startsWith("image/") == true && extension(name) != "svg")

    fun glyph(name: String): FileGlyph {
        val e = extension(name)
        return when {
            e == "pdf" -> FileGlyph.Pdf
            e in images || e == "svg" -> FileGlyph.Image
            e in video -> FileGlyph.Video
            e in audio -> FileGlyph.Audio
            e in archives -> FileGlyph.Archive
            e in tables -> FileGlyph.Table
            e in slides -> FileGlyph.Slides
            e in textual -> FileGlyph.Text
            else -> FileGlyph.Doc
        }
    }

    /**
     * How to show a downloaded file. Images and PDFs render; anything else the bridge calls text (and that is small
     * enough and valid UTF-8, checked by the caller) shows with line numbers; the rest hands over to other apps.
     * SVG and HTML are text here (no native renderer, and a page is code until opened in a browser).
     */
    fun viewerKind(name: String, mime: String?, binary: Boolean, size: Int): ViewerKind {
        val e = extension(name)
        return when {
            isImage(name, mime) -> ViewerKind.Image
            e == "pdf" || mime == "application/pdf" -> ViewerKind.Pdf
            e in video || e in audio || e in archives -> ViewerKind.Other
            !binary && size <= FileLimits.MAX_TEXT_VIEW_BYTES -> ViewerKind.Text
            else -> ViewerKind.Other
        }
    }

    /** The MIME type to hand to other apps; the bridge's own when it sent one. */
    fun mimeFor(name: String, bridgeMime: String?): String {
        if (!bridgeMime.isNullOrEmpty() && bridgeMime != "application/octet-stream") return bridgeMime
        return when (extension(name)) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"; "zip" -> "application/zip"; "json" -> "application/json"; "html", "htm" -> "text/html"
            "md", "markdown" -> "text/markdown"; "csv" -> "text/csv"; "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"
            in textual -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** A file name safe on the phone's file system (the host's names can hold anything). */
    fun safeName(name: String): String {
        val n = name.substringAfterLast('/').map { if (it.isISOControl() || it in "\\:*?\"<>|") '_' else it }.joinToString("").trim().ifEmpty { "file" }
        return if (n.length <= 120) n else n.take(100) + "." + extension(n).take(10)
    }

    /** UTF-8 text, or null when the bytes are not valid UTF-8 (a binary file the host called text). */
    fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().replace("\r\n", "\n")
    } catch (e: java.nio.charset.CharacterCodingException) { null }
}
