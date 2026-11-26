package dev.leash.personal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    @Test fun parsesTheBridgeLink() {
        val p = parsePairingLink("leash://pair?host=mac.example.ts.net&port=8787&token=abc_DEF-123&name=Studio")!!
        assertEquals(PairingInfo("mac.example.ts.net", 8787, "abc_DEF-123", "Studio"), p)
    }

    @Test fun decodesAndTrims() {
        val p = parsePairingLink("  leash://pair?host=h&port=9000&token=a%2Bb%3D&name=My%20Mac \n")!!
        assertEquals("a+b=", p.token)
        assertEquals("My Mac", p.name)
        assertEquals(9000, p.port)
    }

    @Test fun defaultsPortAndName() {
        val p = parsePairingLink("leash://pair?host=h&token=t")!!
        assertEquals(8787, p.port)
        assertEquals("h", p.name)
    }

    @Test fun rejectsIncompleteOrForeignLinks() {
        assertNull(parsePairingLink("leash://pair?host=h"))
        assertNull(parsePairingLink("leash://pair?token=t"))
        assertNull(parsePairingLink("leash://pair?host=h&token=t&port=99999"))
        assertNull(parsePairingLink("https://pair?host=h&token=t"))
        assertNull(parsePairingLink("not a link"))
    }

    @Test fun sessionAndOpenLinks() {
        assertEquals(LeashLink.Session("s1", "claude"), LeashLink.parse("leash://session?id=s1&agent=claude"))
        assertEquals(LeashLink.Session("s1", null), LeashLink.parse("leash://session?id=s1"))
        assertNull(LeashLink.parse("leash://session?agent=claude"))
        assertTrue(LeashLink.parse("leash://open") is LeashLink.Open)
    }
}
