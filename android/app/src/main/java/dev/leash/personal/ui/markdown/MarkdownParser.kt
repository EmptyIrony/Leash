package dev.leash.personal.ui.markdown

/**
 * Block-level markdown, ported from ios Views/MarkdownView.swift (headings, lists, quotes, fenced code, GFM tables, rules,
 * agent embeds, images). Pure Kotlin so it is unit-testable; [MarkdownInline] does the inline styling.
 */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String, val indent: Int) : Block
    data class Numbered(val number: String, val text: String, val indent: Int) : Block
    data class Quote(val text: String) : Block
    data class Code(val code: String, val lang: String) : Block
    /** align: 0 unspecified, 1 center, 2 trailing, 3 explicit leading */
    data class Table(val header: List<String>, val rows: List<List<String>>, val align: List<Int>) : Block
    data object Rule : Block
    /** &lt;agent-embed src="..."&gt; (Antigravity's generative-UI widget) */
    data class Embed(val src: String) : Block
    data class Image(val src: String, val alt: String) : Block
    /** image paths the text above lists: shown only when they load */
    data class MentionedImages(val srcs: List<String>) : Block
}

object MarkdownParser {
    /** "| a | b |" -> ["a", "b"]; honours \| escapes. */
    fun tableCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var prev = ' '
        var inCode = false
        for (ch in s) {
            if (ch == '`') inCode = !inCode
            if (ch == '|' && prev != '\\' && !inCode) { cells += cur.toString(); cur.clear() } else cur.append(ch)
            prev = ch
        }
        cells += cur.toString()
        return cells.map { it.replace("\\|", "|").trim() }
    }

    fun isTableSeparator(line: String): Boolean {
        val cells = tableCells(line)
        if (cells.isEmpty() || !line.contains("-")) return false
        return cells.all { c ->
            val t = c.trim()
            t.isNotEmpty() && t.all { it == '-' || it == ':' } && t.contains('-')
        }
    }

    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val para = mutableListOf<String>()
        var code: MutableList<String>? = null
        var lang = ""
        fun flush() { if (para.isNotEmpty()) { blocks += Block.Paragraph(para.joinToString("\n")); para.clear() } }
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i++
            val trimmed = line.trim()
            // GFM table: header row + separator row, then body rows starting with "|"
            if (code == null && trimmed.startsWith("|") && i < lines.size && isTableSeparator(lines[i])) {
                flush()
                val header = tableCells(trimmed)
                val align = tableCells(lines[i]).map { c ->
                    val t = c.trim()
                    when {
                        t.startsWith(":") && t.endsWith(":") -> 1
                        t.startsWith(":") -> 3
                        t.endsWith(":") -> 2
                        else -> 0
                    }
                }
                i++
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += tableCells(lines[i]); i++ }
                blocks += Block.Table(header, rows, align)
                continue
            }
            if (code != null) {
                if (trimmed.startsWith("```")) { blocks += Block.Code(code.joinToString("\n"), lang); code = null } else code.add(line)
                continue
            }
            if (trimmed.startsWith("```")) { flush(); code = mutableListOf(); lang = trimmed.substring(3).trim(); continue }
            MarkdownImages.pieces(line)?.let { pieces ->
                for (p in pieces) when (p) {
                    is MarkdownImages.Piece.Text -> para += p.text
                    is MarkdownImages.Piece.Img -> { flush(); blocks += Block.Image(p.src, p.alt) }
                }
                continue
            }
            AgentEmbed.pieces(line)?.let { pieces ->
                for (p in pieces) when (p) {
                    is AgentEmbed.Piece.Text -> para += p.text
                    is AgentEmbed.Piece.Embed -> { flush(); blocks += Block.Embed(p.src) }
                }
                continue
            }
            if (trimmed.isEmpty()) { flush(); continue }
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") { flush(); blocks += Block.Rule; continue }
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length
                if (level <= 6 && trimmed.getOrNull(level) == ' ') { flush(); blocks += Block.Heading(level, trimmed.substring(level + 1)); continue }
            }
            val indent = line.takeWhile { it == ' ' }.length / 2
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                flush(); blocks += Block.Bullet(trimmed.substring(2), indent); continue
            }
            val dot = trimmed.indexOfFirst { it == '.' || it == ')' }
            if (dot > 0 && trimmed.substring(0, dot).all { it.isDigit() } && trimmed.substring(dot + 1).startsWith(" ")) {
                flush(); blocks += Block.Numbered(trimmed.substring(0, dot), trimmed.substring(dot + 2), indent); continue
            }
            if (trimmed.startsWith(">")) {
                flush()
                val q = trimmed.substring(1).trim()
                val prev = blocks.lastOrNull()
                if (prev is Block.Quote) blocks[blocks.size - 1] = Block.Quote(prev.text + "\n" + q) else blocks += Block.Quote(q)
                continue
            }
            para += line
        }
        code?.let { blocks += Block.Code(it.joinToString("\n"), lang) }   // unterminated while streaming
        flush()
        return MarkdownImages.addMentions(blocks)
    }
}

/** Images in agent markdown: `![alt](src)` (also linked, and HTML &lt;img src&gt;) become image blocks; paths an answer only lists are shown if they load. */
object MarkdownImages {
    sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Img(val src: String, val alt: String) : Piece
    }

    private val imageTag = Regex(
        """\[?!\[([^\]\n]*)\]\(\s*<?([^)\s>]+)>?(?:\s+["'][^"'\n]*["'])?\s*\)(?:\]\([^)\n]*\))?|<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>""",
        RegexOption.IGNORE_CASE)
    private val altAttr = Regex("""\balt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    /** half-streamed "![alt](sr" / "<img src=..." at the end of a line */
    private val partial = Regex("""(?:!\[[^\]\n]*(?:\]\([^)\n]*)?|<img\b[^>]*)$""", RegexOption.IGNORE_CASE)
    private val listMarker = Regex("""^\s*(?:[-*+]|\d+[.)])?\s*$""")

    /** Splits one markdown line into text and images; null when the line has no image syntax. */
    fun pieces(line: String): List<Piece>? {
        if (!line.contains("![") && !line.contains("<img", ignoreCase = true)) return null
        val out = mutableListOf<Piece>()
        var cursor = 0
        var used = 0
        fun text(s: String) { if (!listMarker.matches(s)) out += Piece.Text(s) }
        for (m in imageTag.findAll(line)) {
            // inside a code span (odd number of backticks before it) it is an example, not an image
            if (line.substring(0, m.range.first).count { it == '`' } % 2 == 1) continue
            used++
            text(line.substring(cursor, m.range.first))
            cursor = m.range.last + 1
            val md = m.groups[2]
            val tag = m.groups[3]
            if (md != null) out += Piece.Img(md.value, m.groupValues[1])
            else if (tag != null) out += Piece.Img(tag.value, altAttr.find(m.value)?.groupValues?.get(1) ?: "")
        }
        var tail = line.substring(cursor)
        partial.find(tail)?.let { tail = tail.substring(0, it.range.first) }
        if (used == 0 && tail == line) return null
        if (tail.isNotEmpty()) text(tail)
        return out
    }

    /** `out/shot.png`, [shot](out/shot.png), file:///.../shot.png, /abs/shot.png, ~/shot.png */
    private val mention: Regex = run {
        val ext = """\.(?:png|jpe?g|gif|webp|heic|bmp|tiff?)"""
        val code = "`([^`\\n]+?" + ext + ")`"
        val link = """\]\(\s*<?([^)\s>]+?""" + ext + """)>?\s*\)"""
        val end = """(?!\w)(?!\.\w)"""   // "a.png." ends a sentence; "a.png.bak" is not an image
        val uri = """(file://[^\s)<>"'`]+?""" + ext + ")" + end
        val bare = """(?:^|(?<=[\s:：，,(（"']))((?:~/|/(?!/))[^\s<>()\[\]"'，。`]+?""" + ext + ")" + end
        Regex(listOf(code, link, uri, bare).joinToString("|"), RegexOption.IGNORE_CASE)
    }

    fun mentions(text: String): List<String> {
        if (!text.contains(".")) return emptyList()
        val out = mutableListOf<String>()
        for (m in mention.findAll(text)) {
            for (g in 1..4) {
                val s = m.groups[g]?.value?.trim() ?: continue
                if ('*' in s || '{' in s || '$' in s || ' ' in s || s.lowercase().startsWith("http")) continue
                out += s
            }
        }
        return out
    }

    /** After each text block, the images its text mentions (each image once per message, at most 8). */
    fun addMentions(blocks: List<Block>): List<Block> {
        val seen = mutableSetOf<String>()
        for (b in blocks) if (b is Block.Image) seen += b.src
        val out = mutableListOf<Block>()
        var shown = 0
        for (b in blocks) {
            out += b
            val text = when (b) {
                is Block.Paragraph -> b.text
                is Block.Quote -> b.text
                is Block.Heading -> b.text
                is Block.Bullet -> b.text
                is Block.Numbered -> b.text
                is Block.Table -> (b.header + b.rows.flatten()).joinToString("\n")
                else -> continue
            }
            val found = mutableListOf<String>()
            for (s in mentions(text)) if (s !in seen && shown + found.size < 8) { seen += s; found += s }
            if (found.isNotEmpty()) { shown += found.size; out += Block.MentionedImages(found) }
        }
        return out
    }

    private val pathLike = Regex("""^(?:~/|/|\./|\.\./)?(?:[^\s/:*?"<>|`]+/)*[^\s/:*?"<>|`]+\.([A-Za-z0-9]{1,8})(?::\d+)?$""")
    private val fileExts = setOf(
        "swift", "m", "h", "c", "cc", "cpp", "hpp", "rs", "go", "py", "rb", "js", "mjs", "cjs", "ts", "tsx", "jsx", "java", "kt", "kts",
        "cs", "php", "lua", "sh", "zsh", "bash", "ps1", "sql", "md", "txt", "log", "json", "jsonl", "yaml", "yml", "toml", "ini",
        "xml", "plist", "html", "htm", "css", "scss", "csv", "tsv", "pdf", "zip", "gz", "tgz", "tar", "7z", "rar", "dmg", "ipa",
        "apk", "png", "jpg", "jpeg", "gif", "webp", "heic", "svg", "ico", "mp3", "m4a", "wav", "mp4", "mov", "webm", "docx",
        "doc", "xlsx", "xls", "pptx", "key", "pages", "numbers", "ipynb", "gd", "tscn", "tres", "godot", "unity", "prefab",
        "xcstrings", "strings", "pbxproj", "lock", "env", "cfg", "conf", "patch", "diff", "wasm", "bin", "dat", "db", "sqlite", "gradle", "mjs",
    )

    /** The path a code span names, when it looks like a file (a known extension, optionally ":line"); null otherwise. */
    fun filePath(span: String): String? {
        val t = span.trim()
        if (t.length > 300 || t.contains("://")) return null
        val m = pathLike.find(t) ?: return null
        return if (m.groupValues[1].lowercase() in fileExts) t else null
    }

    /** A link target that is a file on the agent's host (file://, an absolute / home / relative path); null for web links. */
    fun localPath(target: String): String? {
        val raw = target.trim()
        if (raw.isEmpty() || raw.startsWith("#")) return null
        val scheme = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""").find(raw)?.groupValues?.get(1)?.lowercase()
        if (scheme != null) {
            if (scheme != "file") return null
            val p = percentDecode(raw.substring(5).removePrefix("//"))
            return p.ifEmpty { null }
        }
        val path = raw.substringBefore('#')
        return percentDecode(path).ifEmpty { null }
    }

    private fun percentDecode(s: String): String = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}

/** Antigravity's generative-UI widget: `<agent-embed src="...">`. Drawn by [dev.leash.personal.ui.embed.AgentEmbedView]. */
object AgentEmbed {
    sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Embed(val src: String) : Piece
    }

    private val tag = Regex("""<agent-embed\b[^>]*?\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>(?:\s*</agent-embed\s*>)?""", RegexOption.IGNORE_CASE)
    private val stray = Regex("""</?agent-embed\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun pieces(line: String): List<Piece>? {
        if (!line.contains("<agent-embed", ignoreCase = true) && !line.contains("</agent-embed", ignoreCase = true)) return null
        val out = mutableListOf<Piece>()
        var cursor = 0
        for (m in tag.findAll(line)) {
            val before = line.substring(cursor, m.range.first)
            if (before.isNotBlank()) out += Piece.Text(before)
            cursor = m.range.last + 1
            out += Piece.Embed(m.groups[1]?.value ?: m.groups[2]?.value ?: "")
        }
        val tail = stray.replace(line.substring(cursor), "")
        if (tail.isNotBlank()) out += Piece.Text(tail)
        return out
    }
}
