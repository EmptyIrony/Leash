package dev.leash.personal.ui.markdown

import dev.leash.personal.core.FileRef

/** Where an image of a markdown answer comes from (ios MarkdownImage): the web, a data: URI, or a path on the agent's host. */
sealed interface ImageSource {
    data class Remote(val url: String) : ImageSource
    class Data(val bytes: ByteArray) : ImageSource
    data class Local(val path: String) : ImageSource
    data object Unsupported : ImageSource

    companion object {
        fun of(src: String): ImageSource {
            val s = src.trim()
            val low = s.lowercase()
            return when {
                low.startsWith("http://") || low.startsWith("https://") -> Remote(s)
                low.startsWith("data:") -> dataBytes(s)?.let(::Data) ?: Unsupported
                s.isEmpty() -> Unsupported
                else -> Local(FileRef.pathFromUri(s) ?: percentDecode(s))
            }
        }

        /** "data:image/png;base64,AAAA" or a percent-encoded body; null when it is neither. */
        fun dataBytes(src: String): ByteArray? {
            val comma = src.indexOf(',')
            if (comma < 0) return null
            val meta = src.substring(0, comma)
            val body = src.substring(comma + 1)
            return if (meta.contains(";base64", ignoreCase = true)) runCatching { java.util.Base64.getMimeDecoder().decode(body) }.getOrNull()
            else percentDecode(body).toByteArray(Charsets.UTF_8)
        }

        /** The name a downloaded image is saved under: the last path component without query, decoded. */
        fun fallbackName(src: String): String {
            val n = src.substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { "image" }
            return percentDecode(n)
        }

        private fun percentDecode(s: String): String = try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: IllegalArgumentException) { s }
    }
}
