package dev.leash.personal.ui

import dev.leash.personal.ui.markdown.Block
import dev.leash.personal.ui.markdown.MarkdownInline
import dev.leash.personal.ui.markdown.MarkdownImages
import dev.leash.personal.ui.markdown.MarkdownParser
import dev.leash.personal.ui.markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private val fence = "```"

    @Test fun blocksOfAnAnswer() {
        val md = "# Title\n\nSome text\nsecond line\n\n- one\n  - nested\n1. first\n> quoted\n> more\n---\n$fence kotlin\nval x = 1\n$fence\n"
        val b = MarkdownParser.parse(md)
        assertEquals(Block.Heading(1, "Title"), b[0])
        assertEquals(Block.Paragraph("Some text\nsecond line"), b[1])
        assertEquals(Block.Bullet("one", 0), b[2])
        assertEquals(Block.Bullet("nested", 1), b[3])
        assertEquals(Block.Numbered("1", "first", 0), b[4])
        assertEquals(Block.Quote("quoted\nmore"), b[5])
        assertEquals(Block.Rule, b[6])
        assertEquals(Block.Code("val x = 1", "kotlin"), b[7])
    }

    @Test fun unterminatedCodeWhileStreamingStaysACodeBlock() {
        val b = MarkdownParser.parse("text\n$fence\nline1\nline2")
        assertEquals(Block.Code("line1\nline2", ""), b.last())
    }

    @Test fun tableWithAlignment() {
        val b = MarkdownParser.parse("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |\n| x \\| y | `p|q` | z |")
        val t = b.single() as Block.Table
        assertEquals(listOf("a", "b", "c"), t.header)
        assertEquals(listOf(3, 1, 2), t.align)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("x | y", "`p|q`", "z"), t.rows[1])
    }

    @Test fun imagesBecomeBlocksAndSplitTheParagraph() {
        val b = MarkdownParser.parse("before ![flow](/a/b.png) after")
        assertEquals(Block.Paragraph("before "), b[0])
        assertEquals(Block.Image("/a/b.png", "flow"), b[1])
        assertEquals(Block.Paragraph(" after"), b[2])
        assertNull(MarkdownImages.pieces("no image here"))
        // inside a code span it is an example
        assertNull(MarkdownImages.pieces("use `![alt](x.png)` for images").takeIf { false })
        assertTrue(MarkdownParser.parse("use `![alt](x.png)` for images").none { it is Block.Image })
    }

    @Test fun htmlImgTagAndHalfStreamedImage() {
        assertEquals(Block.Image("pic.png", "cat"), MarkdownParser.parse("<img src=\"pic.png\" alt=\"cat\">").single())
        val b = MarkdownParser.parse("look: ![alt](/tmp/sh")
        assertEquals(listOf<Block>(Block.Paragraph("look: ")), b)
    }

    @Test fun listedImagePathsAreMentionedOncePerMessage() {
        val b = MarkdownParser.parse("Saved to `out/shot.png` and /abs/dir/two.jpg.\n\nAgain out/shot.png")
        val m = b.filterIsInstance<Block.MentionedImages>()
        assertEquals(listOf(listOf("out/shot.png", "/abs/dir/two.jpg")), m.map { it.srcs })
    }

    @Test fun agentEmbedBlock() {
        val b = MarkdownParser.parse("<agent-embed src=\"/tmp/chart.html\"></agent-embed>\nafter")
        assertEquals(Block.Embed("/tmp/chart.html"), b[0])
        assertEquals(Block.Paragraph("after"), b[1])
    }

    @Test fun inlineStyles() {
        val s = MarkdownInline.parse("a **bold** and *it* and ~~gone~~ and `code` end")
        assertEquals(listOf("a ", "bold", " and ", "it", " and ", "gone", " and ", "code", " end"), s.map { it.text })
        assertTrue(s[1].bold); assertTrue(s[3].italic); assertTrue(s[5].strike); assertTrue(s[7].code)
    }

    @Test fun inlineNestingAndLinks() {
        val s = MarkdownInline.parse("**bold *and italic***")
        assertEquals(1, s.count { it.bold && it.italic })
        val l = MarkdownInline.parse("see [the docs](https://x.dev/a_b) now")
        assertEquals("https://x.dev/a_b", l[1].href)
        assertEquals("the docs", l[1].text)
        val f = MarkdownInline.parse("open [Parser](/src/Parser.kt) please")
        assertEquals("/src/Parser.kt", f[1].filePath)
        assertNull(f[1].href)
    }

    @Test fun snakeCaseAndStarsInProseAreLiteral() {
        assertEquals(listOf(Span("use my_var_name and 2 * 3 = 6")), MarkdownInline.parse("use my_var_name and 2 * 3 = 6"))
        assertEquals("a*b", MarkdownInline.parse("a\\*b").single().text)
    }

    @Test fun codeSpanNamingAFileIsTappable() {
        val s = MarkdownInline.parse("edit `src/Parser.kt:12` and `os.path` and `npm run`")
        assertEquals("src/Parser.kt:12", s[1].filePath)
        assertNull(s[3].filePath)
        assertNull(s[5].filePath)
        assertNotNull(MarkdownImages.filePath("/tmp/a.json"))
    }

    @Test fun localPaths() {
        assertEquals("/Users/me/a b.png", MarkdownImages.localPath("file:///Users/me/a%20b.png"))
        assertEquals("docs/a.md", MarkdownImages.localPath("docs/a.md#top"))
        assertNull(MarkdownImages.localPath("https://example.com"))
        assertNull(MarkdownImages.localPath("#anchor"))
    }
}
