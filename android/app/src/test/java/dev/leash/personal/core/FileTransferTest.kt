package dev.leash.personal.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferTest {
    private fun rig(scope: kotlinx.coroutines.CoroutineScope): Pair<AppModel, FakeLink> {
        val link = FakeLink()
        return AppModel(scope, InMemorySettingsStore(Settings(host = "h", token = "t")), link) to link
    }

    @Test fun sizeTextUsesDecimalUnits() {
        assertEquals("12 B", FileLimits.sizeText(12))
        assertEquals("1.5 KB", FileLimits.sizeText(1_500))
        assertEquals("1 MB", FileLimits.sizeText(1_000_000))
        assertEquals("25 MB", FileLimits.sizeText(25_000_000))
        assertEquals("120 KB", FileLimits.sizeText(120_000))
        assertEquals("1.2 GB", FileLimits.sizeText(1_200_000_000))
    }

    @Test fun lineSuffixIsSplitOff() {
        assertEquals("a/b.kt" to 42, splitLineSuffix("a/b.kt:42"))
        assertEquals("a/b.kt" to null, splitLineSuffix("a/b.kt"))
        assertEquals("C:/x" to null, splitLineSuffix("C:/x"))
    }

    @Test fun uploadStoresTheFileAndReturnsAResourceLink() = runTest {
        val (model, link) = rig(backgroundScope)
        link.on("bridge/upload") { p -> jsonObj("path" to "/home/u/.leash/uploads/s1/" + p["name"].string, "uri" to "file:///home/u/.leash/uploads/s1/" + p["name"].string, "name" to p["name"].string, "size" to 3, "mimeType" to "text/plain") }
        val st = model.store("s1").also { it.agent = "claude" }
        val block = model.upload(PendingFile("notes.txt", byteArrayOf(1, 2, 3)), st)
        val sent = link.callsOf("bridge/upload").single()
        assertEquals("s1", sent["sessionId"].string)
        assertEquals("claude", sent["agent"].string)
        assertEquals("notes.txt", sent["name"].string)
        assertArrayEquals(byteArrayOf(1, 2, 3), java.util.Base64.getDecoder().decode(sent["data"].string))
        assertEquals("resource_link", block["type"].string)
        assertEquals("file:///home/u/.leash/uploads/s1/notes.txt", block["uri"].string)
        assertEquals("text/plain", block["mimeType"].string)
        assertEquals("[@notes.txt](file:///home/u/.leash/uploads/s1/notes.txt)", FileRef.linkText(block))
    }

    @Test fun uploadRefusesAFileOverTheCapBeforeSendingAnything() = runTest {
        val (model, link) = rig(backgroundScope)
        val st = model.store("s1")
        try {
            model.upload(PendingFile("big.bin", ByteArray(FileLimits.MAX_UPLOAD_BYTES + 1)), st)
            fail("should refuse")
        } catch (e: BridgeError) {
            assertEquals(FileLimits.FILE_TOO_LARGE, e.code)
            assertTrue(e.message.contains("11 MB"))
        }
        assertTrue(link.callsOf("bridge/upload").isEmpty())
    }

    @Test fun theCapKeepsTheWholeFrameUnderOkHttpsQueueLimit() {
        val base64 = (FileLimits.MAX_UPLOAD_BYTES + 2) / 3 * 4
        // the JSON around the data is a few hundred bytes (a long file name, session id, ...)
        assertTrue(base64 + 4_096 < 16 * 1024 * 1024)
        // and a bridge limit of 25 MB would not fit
        assertTrue((25 * 1024 * 1024 + 2) / 3 * 4 > 16 * 1024 * 1024)
    }

    @Test fun fetchFileDecodesBase64AndAsksForTheWholeFile() = runTest {
        val (model, link) = rig(backgroundScope)
        link.on("bridge/readFile") { jsonObj("path" to "/p/a.png", "data" to java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7)), "mimeType" to "image/png", "binary" to true) }
        val f = model.fetchFile("a.png", "claude", "s1")
        val p = link.callsOf("bridge/readFile").single()
        assertEquals("base64", p["mode"].string)
        assertEquals("s1", p["sessionId"].string)
        assertEquals(FileLimits.MAX_DOWNLOAD_BYTES, p["maxBytes"].int)
        assertArrayEquals(byteArrayOf(9, 8, 7), f.data)
        assertEquals("a.png", f.name)
        assertEquals("image/png", f.mimeType)
        assertTrue(f.binary)
    }

    @Test fun fetchFileKeepsTheTooLargeCodeForTheViewer() = runTest {
        val (model, link) = rig(backgroundScope)
        link.on("bridge/readFile") { throw BridgeError(FileLimits.FILE_TOO_LARGE, "file too large") }
        try { model.fetchFile("/x", "claude", null); fail() } catch (e: BridgeError) { assertEquals(FileLimits.FILE_TOO_LARGE, e.code) }
    }

    @Test fun textPreviewReadsTheStartOfTheFile() = runTest {
        val (model, link) = rig(backgroundScope)
        link.on("bridge/readFile") { jsonObj("text" to "hello", "truncated" to true, "binary" to false) }
        val r = model.readTextPreview("/x", "claude", "s1")
        assertEquals(TextPreview("hello", true, false), r)
        assertNull(link.callsOf("bridge/readFile").single()["mode"].string)
    }
}
