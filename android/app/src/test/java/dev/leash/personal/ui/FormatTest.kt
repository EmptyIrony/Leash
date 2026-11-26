package dev.leash.personal.ui

import dev.leash.personal.ui.chat.compactNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun compactNumbers() {
        assertEquals("0", compactNumber(0))
        assertEquals("999", compactNumber(999))
        assertEquals("1k", compactNumber(1000))
        assertEquals("12.4k", compactNumber(12400))
        assertEquals("1.8M", compactNumber(1_800_000))
    }
}
