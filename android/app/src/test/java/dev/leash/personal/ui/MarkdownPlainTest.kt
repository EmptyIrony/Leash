package dev.leash.personal.ui

import dev.leash.personal.ui.markdown.MarkdownPlain
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPlainTest {
    @Test fun inlineSyntaxIsDropped() {
        assertEquals("Hello bold and code and link", MarkdownPlain.text("Hello **bold** and `code` and [link](https://x.io)"))
    }

    @Test fun listItemsStayTogetherAndBlocksAreSeparated() {
        val md = "# Title\n\nIntro text\n\n- one\n- two\n  - nested\n\n1. first\n2. second"
        assertEquals("Title\n\nIntro text\n\n• one\n• two\n  • nested\n1. first\n2. second", MarkdownPlain.text(md))   // consecutive items stay together, whatever the list kind
    }

    @Test fun tablesAndCodeKeepTheirShape() {
        val md = "| a | b |\n|---|---|\n| 1 | **2** |\n\n```kotlin\nval x = 1\n```"
        assertEquals("a\tb\n1\t2\n\nval x = 1", MarkdownPlain.text(md))
    }

    @Test fun imagesAndRulesBecomeTheirAltOrNothing() {
        assertEquals("before\n\nthe flow\n\nafter", MarkdownPlain.text("before\n\n![the flow](a.png)\n\n---\n\nafter"))
    }
}
