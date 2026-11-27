package dev.leash.personal.ui

import dev.leash.personal.ui.chat.compactNumber
import dev.leash.personal.ui.chat.decodedSize
import dev.leash.personal.ui.chat.fitInside
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

    @Test fun anImageTileIsLaidOutFromItsDecodedPixels() {
        assertEquals(460 to 1024, decodedSize(1080, 2400))
        assertEquals(96 to 64, decodedSize(96, 64))
        assertEquals(1024 to 1, decodedSize(5000, 1))
        // never scaled up; scaled down to the tighter bound, shape kept
        assertEquals(96 to 64, fitInside(96, 64, 660, 660))
        assertEquals(296 to 660, fitInside(460, 1024, 660, 660))
        assertEquals(960 to 540, fitInside(1024, 576, 960, Int.MAX_VALUE))
        assertEquals(460 to 1024, fitInside(460, 1024, 960, Int.MAX_VALUE))
    }
}
