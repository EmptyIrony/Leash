package dev.leash.personal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuotaTest {
    private val q = QuotaInfo.parse(parseJson("""
      {"planTier":"max","fetchedAt":1700000000000,"pools":[
        {"id":"gemini","label":"Gemini","buckets":[{"id":"g5","window":"5h","remainingFraction":0.8,"resetTime":"2026-11-23T05:00:00.000Z"}]},
        {"id":"3p","label":"Claude and GPT","buckets":[{"id":"t5","window":"5h","remainingFraction":0.5},{"id":"tw","window":"weekly","remainingFraction":0.2}]},
        {"id":"fable","label":"Fable","model":"Fable","buckets":[{"id":"f","window":"weekly","remainingFraction":0.1}]}
      ]}"""))!!

    @Test fun parsesPoolsBucketsAndTimes() {
        assertEquals("max", q.planTier)
        assertEquals(1700000000000L, q.fetchedAtMs)
        assertEquals(3, q.pools.size)
        assertEquals(1795410000000L, q.pools[0].buckets[0].resetAtMs)
        assertEquals("weekly", q.pools[1].weekly!!.window)
        assertEquals(0.2, q.pools[1].minFraction!!, 0.0)
    }

    @Test fun poolForModelFollowsTheProviders() {
        assertEquals("3p", q.poolForModel("claude-opus")!!.id)
        assertEquals("gemini", q.poolForModel("gemini-4-carbon")!!.id)
    }

    @Test fun limitingPicksTheTightestBucket() {
        val (pool, bucket) = q.limiting("claude-fable-5", "claude")!!
        assertEquals("fable", pool.id)
        assertEquals("f", bucket.id)
        val (p2, b2) = q.limiting("claude-opus", "claude")!!
        assertEquals("3p", p2.id)
        assertEquals("tw", b2.id)
        assertEquals("gemini", q.limiting("gemini-4", "agy")!!.first.id)
    }

    @Test fun mostConstrainedAndGarbage() {
        assertEquals("fable", q.mostConstrained!!.id)
        assertNull(QuotaInfo.parse(null))
        assertNull(QuotaInfo.parse(parseJson("{}")))
    }
}
