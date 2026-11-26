package dev.leash.personal.ui

import dev.leash.personal.ui.embed.AgentEmbed
import dev.leash.personal.ui.embed.EmbedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedHostTest {
    @Test fun theHostContractGoesRightAfterHead() {
        val out = EmbedHost.prepare("<!doctype html><html><head><title>x</title></head><body>hi</body></html>", dark = true)
        val head = out.indexOf("<head>")
        val csp = out.indexOf("Content-Security-Policy")
        val script = out.indexOf("<script>")
        val title = out.indexOf("<title>")
        assertTrue(head in 0 until csp && csp < script && script < title)
        assertTrue(out.contains("""var THEMES = {"dark":{"accent":"#4FD8EE","background":"#12141C""""))
        assertTrue(out.contains("apply(\"dark\");"))
        assertTrue(out.contains("agHostAndroid.post"))
        assertTrue(out.contains("https://www.gstatic.com/antigravity/"))
    }

    @Test fun aPageWithoutHeadGetsItAfterTheDoctypeOrInFront() {
        val a = EmbedHost.prepare("<!DOCTYPE html><div>x</div>", dark = false)
        assertTrue(a.indexOf("<!DOCTYPE html>") < a.indexOf("Content-Security-Policy") && a.indexOf("Content-Security-Policy") < a.indexOf("<div>"))
        assertTrue(a.contains("apply(\"light\");"))
        val b = EmbedHost.prepare("<div>x</div>", dark = false)
        assertTrue(b.startsWith("<meta http-equiv"))
    }

    @Test fun bothThemesCarryEveryToken() {
        val dark = EmbedHost.themes.getValue("dark").keys
        val light = EmbedHost.themes.getValue("light").keys
        assertEquals(dark, light)
        assertTrue("chart-6" in dark && "card-border" in dark && "primary-foreground" in dark)
        assertTrue(EmbedHost.themesJson().startsWith("{\"dark\":{"))
    }

    @Test fun theReportedHeightIsClampedAndRoundedUp() {
        assertEquals(124f, EmbedHost.heightFrom("""{"height":123.2}"""))
        assertEquals(4000f, EmbedHost.heightFrom("""{"height":99999}"""))
        assertEquals(1f, EmbedHost.heightFrom("""{"height":0}"""))
        assertNull(EmbedHost.heightFrom("""{"width":10}"""))
    }

    @Test fun onlyLocalFilesAreEmbeds() {
        assertEquals("/a/b/widget.html", AgentEmbed.localPath("file:///a/b/widget.html"))
        assertEquals("/a b/w.html", AgentEmbed.localPath("file:///a%20b/w.html"))
        assertEquals("/abs/w.html", AgentEmbed.localPath("/abs/w.html"))
        assertNull(AgentEmbed.localPath("https://example.com/w.html"))
        assertEquals("widget.html", AgentEmbed.title("file:///a/b/widget.html"))
        assertEquals("https://x", AgentEmbed.title("https://x"))
    }
}
