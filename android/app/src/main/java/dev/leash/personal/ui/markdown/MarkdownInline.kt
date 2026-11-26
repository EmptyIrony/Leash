package dev.leash.personal.ui.markdown

/** One styled run of inline markdown. [href] is a web link, [filePath] a file on the agent's host (tappable, opens the viewer). */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val href: String? = null,
    val filePath: String? = null,
)

/** Inline markdown only (bold, italic, strikethrough, code, links), the way Foundation's inline parser reads it on iOS. */
object MarkdownInline {
    fun parse(src: String): List<Span> {
        val out = mutableListOf<Span>()
        parseInto(src, Span(""), out)
        return merge(out)
    }

    /** Neighbouring runs with the same style become one. */
    private fun merge(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + s.text) else out += s
        }
        return out
    }

    private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit())

    private fun parseInto(s: String, base: Span, out: MutableList<Span>) {
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out += base.copy(text = buf.toString()); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // escapes
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!~|<>") { buf.append(s[i + 1]); i += 2; continue }
            // code span (n backticks)
            if (c == '`') {
                var n = 0
                while (i + n < s.length && s[i + n] == '`') n++
                val fence = "`".repeat(n)
                val end = s.indexOf(fence, i + n)
                if (end > 0) {
                    flush()
                    val body = s.substring(i + n, end).let { if (it.length > 1 && it.startsWith(" ") && it.endsWith(" ") && it.isNotBlank()) it.substring(1, it.length - 1) else it }
                    out += base.copy(text = body, code = true, filePath = base.filePath ?: MarkdownImages.filePath(body))
                    i = end + n
                    continue
                }
                buf.append(fence); i += n; continue
            }
            // link [text](target)
            if (c == '[') {
                val close = matchingBracket(s, i)
                if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                    val pEnd = s.indexOf(')', close + 2)
                    if (pEnd > 0) {
                        val target = s.substring(close + 2, pEnd).trim().removeSurrounding("<", ">").substringBefore(' ')
                        flush()
                        val local = MarkdownImages.localPath(target)
                        val inner = base.copy(href = if (local == null) target else null, filePath = local)
                        parseInto(s.substring(i + 1, close), inner, out)
                        i = pEnd + 1
                        continue
                    }
                }
            }
            // strikethrough
            if (c == '~' && s.startsWith("~~", i)) {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) { flush(); parseInto(s.substring(i + 2, end), base.copy(strike = true), out); i = end + 2; continue }
            }
            // emphasis
            if (c == '*' || c == '_') {
                val run = if (s.startsWith("" + c + c, i)) 2 else 1
                val before = s.getOrNull(i - 1)
                val after = s.getOrNull(i + run)
                val opens = after != null && !after.isWhitespace() && (c == '*' || !isWordChar(before))
                if (opens) {
                    val marker = "" + c.toString().repeat(run)
                    var end = i + run
                    var found = -1
                    while (true) {
                        end = s.indexOf(marker, end)
                        if (end < 0) break
                        val prev = s[end - 1]
                        val next = s.getOrNull(end + run)
                        // a closer follows non-space, and for "_" is not inside a word
                        if (end > i + run && !prev.isWhitespace() && (c == '*' || !isWordChar(next))) {
                            found = end
                            // "***x***": the closer is the last two of the run, the first belongs to the inner emphasis
                            if (run == 2) { var k = end + run; while (k < s.length && s[k] == c) k++; found = end + (k - end - run) }
                            break
                        }
                        end += run
                    }
                    if (found > 0) {
                        flush()
                        parseInto(s.substring(i + run, found), if (run == 2) base.copy(bold = true) else base.copy(italic = true), out)
                        i = found + run
                        continue
                    }
                }
            }
            buf.append(c)
            i++
        }
        flush()
    }

    private fun matchingBracket(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }
}
