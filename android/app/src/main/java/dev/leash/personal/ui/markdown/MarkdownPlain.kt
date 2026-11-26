package dev.leash.personal.ui.markdown

/**
 * An answer as it reads on screen, without markdown syntax (ios MarkdownView.plainText): list items on consecutive lines, table
 * cells tab-separated, other blocks separated by a blank line. What the select-text sheet shows.
 */
object MarkdownPlain {
    private fun flat(s: String) = MarkdownInline.parse(s).joinToString("") { it.text }

    fun text(markdown: String): String {
        val out = StringBuilder()
        var lastWasItem = false
        for (b in MarkdownParser.parse(markdown)) {
            var isItem = false
            val piece: String? = when (b) {
                is Block.Heading -> flat(b.text)
                is Block.Paragraph -> flat(b.text)
                is Block.Quote -> flat(b.text)
                is Block.Bullet -> { isItem = true; "  ".repeat(b.indent) + "• " + flat(b.text) }
                is Block.Numbered -> { isItem = true; "  ".repeat(b.indent) + b.number + ". " + flat(b.text) }
                is Block.Code -> b.code
                is Block.Table -> (listOf(b.header) + b.rows).joinToString("\n") { row -> row.joinToString("\t") { flat(it) } }
                is Block.Image -> b.alt.ifEmpty { b.src }
                Block.Rule, is Block.Embed, is Block.MentionedImages -> null
            }
            if (piece == null) continue
            if (out.isNotEmpty()) out.append(if (isItem && lastWasItem) "\n" else "\n\n")
            out.append(piece)
            lastWasItem = isItem
        }
        return out.toString()
    }
}
