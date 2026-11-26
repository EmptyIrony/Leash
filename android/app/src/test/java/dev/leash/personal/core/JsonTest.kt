package dev.leash.personal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    private val m = parseJson("""{"s":"x","n":3,"d":1.5,"big":1700000000000,"b":true,"z":null,"a":[1,{"k":"v"}],"o":{"p":{"q":"deep"}}}""")

    @Test fun readsLeniently() {
        assertEquals("x", m["s"].string)
        assertEquals(3, m["n"].int)
        assertEquals(1.5, m["d"].double!!, 0.0)
        assertEquals(1700000000000L, m["big"].long)
        assertEquals(true, m["b"].bool)
        assertEquals("deep", m["o"]["p"]["q"].string)
        assertEquals("v", m["a"][1]["k"].string)
    }

    @Test fun mismatchedAndMissingAreNull() {
        assertNull(m["s"].int)
        assertNull(m["n"].string)
        assertNull(m["missing"]["x"].string)
        assertNull(m["a"][5])
        assertNull(m["s"]["x"])
        assertNull(m["b"].string)
    }

    @Test fun jsonNullIsDistinguishedFromMissing() {
        assertTrue(m["z"].isJsonNull)
        assertFalse(m["missing"].isJsonNull)
        assertNull(m["z"].orNull)
        assertNull(m["z"].string)
    }

    @Test fun builderRoundTrips() {
        val o = jsonObj("op" to "call", "id" to 7, "params" to jsonObj("force" to true, "list" to listOf("a", 1)))
        val back = parseJson(o.encoded())
        assertEquals(o, back)
        assertEquals(7, back["id"].int)
    }

    @Test fun prettyRendersStringsRawAndObjectsSorted() {
        assertEquals("hello", j("hello").pretty())
        assertEquals("", null.pretty())
        val p = jsonObj("b" to 1, "a" to jsonObj("d" to 2, "c" to 3)).pretty()
        assertTrue(p.indexOf("\"a\"") < p.indexOf("\"b\""))
        assertTrue(p.indexOf("\"c\"") < p.indexOf("\"d\""))
    }
}
