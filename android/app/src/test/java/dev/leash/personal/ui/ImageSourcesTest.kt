package dev.leash.personal.ui

import dev.leash.personal.ui.markdown.ImageSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSourcesTest {
    @Test fun webAddressesAreRemote() {
        assertEquals(ImageSource.Remote("https://x.io/a.png?w=2"), ImageSource.of("https://x.io/a.png?w=2"))
        assertEquals(ImageSource.Remote("http://x.io/a.png"), ImageSource.of(" http://x.io/a.png "))
    }

    @Test fun dataUrisDecodeBothWays() {
        assertArrayEquals(byteArrayOf(1, 2, 3), (ImageSource.of("data:image/png;base64,AQID") as ImageSource.Data).bytes)
        assertArrayEquals("hi there".toByteArray(), (ImageSource.of("data:text/plain,hi%20there") as ImageSource.Data).bytes)
        assertEquals(ImageSource.Unsupported, ImageSource.of("data:nothing"))
    }

    @Test fun everythingElseIsAPathOnTheHost() {
        assertEquals(ImageSource.Local("/Users/me/shot.png"), ImageSource.of("file:///Users/me/shot.png"))
        assertEquals(ImageSource.Local("/Users/me/a b.png"), ImageSource.of("file:///Users/me/a%20b.png"))
        assertEquals(ImageSource.Local("/Users/me/a%20b.png"), ImageSource.of("/Users/me/a%20b.png"))   // an absolute path is taken as written
        assertEquals(ImageSource.Local("out/a b.png"), ImageSource.of("out/a%20b.png"))
        assertEquals(ImageSource.Local("out/a.png"), ImageSource.of("out/a.png"))
        assertEquals(ImageSource.Local("~/a.png"), ImageSource.of("~/a.png"))
        assertEquals(ImageSource.Unsupported, ImageSource.of(""))
    }

    @Test fun downloadedImagesKeepTheirName() {
        assertEquals("flow.png", ImageSource.fallbackName("https://x.io/docs/flow.png?v=3"))
        assertEquals("my pic.png", ImageSource.fallbackName("/a/my%20pic.png"))
        assertEquals("a", ImageSource.fallbackName("https://x.io/a/"))
        assertEquals("image", ImageSource.fallbackName(""))
        assertTrue(ImageSource.fallbackName("a+b.png").contains("+"))
    }
}
