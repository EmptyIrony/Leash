package dev.leash.personal.ui

import dev.leash.personal.core.FileLimits
import dev.leash.personal.ui.chat.AttachmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPolicyTest {
    @Test fun photosAreScaledDownNeverUp() {
        assertEquals(1000 to 500, AttachmentPolicy.scaled(1000, 500))
        assertEquals(1600 to 800, AttachmentPolicy.scaled(4000, 2000))
        assertEquals(800 to 1600, AttachmentPolicy.scaled(3000, 6000))
        assertEquals(1600 to 1, AttachmentPolicy.scaled(9000, 1))
    }

    @Test fun aFileOverTheCapIsRefusedByItsReportedSize() {
        assertFalse(AttachmentPolicy.tooLarge(null))
        assertFalse(AttachmentPolicy.tooLarge(FileLimits.MAX_UPLOAD_BYTES.toLong()))
        assertTrue(AttachmentPolicy.tooLarge(FileLimits.MAX_UPLOAD_BYTES + 1L))
    }

    @Test fun onlyFourPhotosGoWithAMessage() {
        assertEquals(4, AttachmentPolicy.room(0, 6))
        assertEquals(1, AttachmentPolicy.room(3, 6))
        assertEquals(0, AttachmentPolicy.room(4, 2))
        assertEquals(2, AttachmentPolicy.room(1, 2))
    }
}
