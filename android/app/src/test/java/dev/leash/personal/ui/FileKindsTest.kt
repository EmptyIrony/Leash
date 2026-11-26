package dev.leash.personal.ui

import dev.leash.personal.core.FileLimits
import dev.leash.personal.ui.files.FileGlyph
import dev.leash.personal.ui.files.FileKinds
import dev.leash.personal.ui.files.ViewerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindsTest {
    @Test fun imagesAndPdfsRenderTextShowsAndTheRestHandsOver() {
        assertEquals(ViewerKind.Image, FileKinds.viewerKind("shot.PNG", "image/png", true, 10))
        assertEquals(ViewerKind.Image, FileKinds.viewerKind("x", "image/jpeg", true, 10))
        assertEquals(ViewerKind.Pdf, FileKinds.viewerKind("spec.pdf", null, true, 10))
        assertEquals(ViewerKind.Text, FileKinds.viewerKind("a.kt", "text/plain", false, 1_000))
        assertEquals(ViewerKind.Text, FileKinds.viewerKind("Makefile", null, false, 1_000))
        assertEquals(ViewerKind.Other, FileKinds.viewerKind("a.kt", null, false, FileLimits.MAX_TEXT_VIEW_BYTES + 1))
        assertEquals(ViewerKind.Other, FileKinds.viewerKind("data.bin", null, true, 10))
        assertEquals(ViewerKind.Other, FileKinds.viewerKind("clip.mp4", "video/mp4", true, 10))
        assertEquals(ViewerKind.Other, FileKinds.viewerKind("a.zip", null, false, 10))
    }

    @Test fun svgAndHtmlAreSourceNotImages() {
        assertEquals(ViewerKind.Text, FileKinds.viewerKind("logo.svg", "image/svg+xml", false, 500))
        assertEquals(ViewerKind.Text, FileKinds.viewerKind("page.html", "text/html", false, 500))
    }

    @Test fun glyphsFollowTheExtension() {
        assertEquals(FileGlyph.Pdf, FileKinds.glyph("a.pdf"))
        assertEquals(FileGlyph.Image, FileKinds.glyph("a.webp"))
        assertEquals(FileGlyph.Archive, FileKinds.glyph("a.tar"))
        assertEquals(FileGlyph.Table, FileKinds.glyph("a.xlsx"))
        assertEquals(FileGlyph.Text, FileKinds.glyph("a.kt"))
        assertEquals(FileGlyph.Doc, FileKinds.glyph("a.weird"))
        assertEquals(FileGlyph.Doc, FileKinds.glyph("noext"))
    }

    @Test fun mimePrefersTheBridgesAndFallsBackToTheExtension() {
        assertEquals("image/png", FileKinds.mimeFor("a.bin", "image/png"))
        assertEquals("image/png", FileKinds.mimeFor("a.png", "application/octet-stream"))
        assertEquals("text/plain", FileKinds.mimeFor("a.kt", null))
        assertEquals("application/octet-stream", FileKinds.mimeFor("a.weird", null))
    }

    @Test fun namesAreMadeSafeForTheCacheFolder() {
        assertEquals("file", FileKinds.safeName(""))
        assertEquals("evil.txt", FileKinds.safeName("../../evil.txt"))
        assertEquals("a_b.txt", FileKinds.safeName("a:b.txt"))
        assertTrue(FileKinds.safeName("x".repeat(300) + ".png").length <= 120)
    }

    @Test fun onlyValidUtf8IsText() {
        assertEquals("héllo\nx", FileKinds.decodeUtf8("héllo\r\nx".toByteArray()))
        assertNull(FileKinds.decodeUtf8(byteArrayOf(0x80.toByte(), 0xFF.toByte())))
    }
}
