package dev.leash.personal.core

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageDownloadsTest {
    private val bytes = ByteArray(10) { it.toByte() }
    private val base64 = java.util.Base64.getEncoder().encodeToString(bytes)   // 16 chars

    /** A bridge whose every chunk waits until the test answers it. */
    private inner class Bridge {
        inner class Asked(val ref: String, val sessionId: String, val agent: String, val offset: Int) {
            val answer = CompletableDeferred<JsonElement>()
            fun slice(n: Int = 8) = answer.complete(jsonObj("ref" to ref, "offset" to offset, "total" to base64.length,
                "data" to base64.substring(offset, minOf(offset + n, base64.length))))
        }
        val asked = mutableListOf<Asked>()
        val log get() = asked.map { it.ref + "@" + it.offset }
        fun downloads(scope: TestScope) = ImageDownloads(scope.backgroundScope, { ref, sid, agent, offset ->
            Asked(ref, sid, agent, offset).also { asked += it }.answer.await()
        }, decodeOn = EmptyCoroutineContext)
    }

    @Test fun slicesAreAskedForInTurnAndDecodedOnce() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        val got = async { images.load("a", "s1", "claude") }
        runCurrent()
        assertEquals(listOf("a@0"), bridge.log)   // one chunk in flight
        assertEquals("s1" to "claude", bridge.asked[0].sessionId to bridge.asked[0].agent)
        bridge.asked[0].slice(5); runCurrent()   // a slice that isn't a multiple of four characters
        bridge.asked[1].slice(5); runCurrent()
        bridge.asked[2].slice(99); runCurrent()
        assertEquals(listOf("a@0", "a@5", "a@10"), bridge.log)
        assertArrayEquals(bytes, got.await())
    }

    @Test fun anEmptySliceBeforeTheEndFailsInsteadOfLooping() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        val got = async { runCatching { images.load("a", "s", "claude") } }
        runCurrent()
        bridge.asked[0].slice(); runCurrent()
        bridge.asked[1].answer.complete(jsonObj("total" to base64.length, "data" to "")); runCurrent()
        assertEquals(2, bridge.asked.size)
        assertEquals(Issue.NoFileData, (got.await().exceptionOrNull() as BridgeError).issue)
    }

    @Test fun twoDownloadAtOnceAndTheRestWaitTheirTurn() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        val all = listOf("a", "b", "c", "d").map { ref -> async { images.load(ref, "s", "claude") } }
        runCurrent()
        assertEquals(listOf("a@0", "b@0"), bridge.log)
        bridge.asked[0].slice(99); runCurrent()
        assertEquals(listOf("a@0", "b@0", "c@0"), bridge.log)
        bridge.asked[1].slice(99); runCurrent()
        bridge.asked[2].slice(99); runCurrent()
        bridge.asked[3].slice(99); runCurrent()
        all.forEach { assertArrayEquals(bytes, it.await()) }
    }

    @Test fun theSameRefIsDownloadedOnce() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        val one = async { images.load("a", "s", "claude") }
        val two = async { images.load("a", "s", "claude") }
        runCurrent()
        assertEquals(listOf("a@0"), bridge.log)
        bridge.asked[0].slice(99); runCurrent()
        assertArrayEquals(bytes, one.await())
        assertArrayEquals(bytes, two.await())
        assertEquals(1, bridge.asked.size)
    }

    @Test fun aFailureReachesEveryCallerAndIsNotKept() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        val one = async { runCatching { images.load("a", "s", "claude") } }
        val two = async { runCatching { images.load("a", "s", "claude") } }
        runCurrent()
        bridge.asked[0].answer.completeExceptionally(BridgeError.timeout()); runCurrent()
        assertTrue(one.await().isFailure && two.await().isFailure)
        val again = async { images.load("a", "s", "claude") }
        runCurrent()
        assertEquals(listOf("a@0", "a@0"), bridge.log)
        bridge.asked[1].slice(99); runCurrent()
        assertArrayEquals(bytes, again.await())
    }

    @Test fun aWaitingDownloadNobodyWantsIsNeverAskedFor() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        launch { images.load("a", "s", "claude") }
        launch { images.load("b", "s", "claude") }
        val gone = launch { images.load("c", "s", "claude") }
        launch { images.load("d", "s", "claude") }
        runCurrent()
        gone.cancel(); runCurrent()
        bridge.asked[0].slice(99); runCurrent()
        assertEquals(listOf("a@0", "b@0", "d@0"), bridge.log)
        bridge.asked[1].slice(99); bridge.asked[2].slice(99); runCurrent()
    }

    @Test fun aRunningDownloadNobodyWantsStopsAfterTheChunkInFlight() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        val a = launch { images.load("a", "s", "claude") }
        val b = launch { images.load("b", "s", "claude") }
        val c = async { images.load("c", "s", "claude") }
        runCurrent()
        a.cancel(); runCurrent()
        assertEquals(listOf("a@0", "b@0"), bridge.log)   // its answer is still on the way: the slot stays taken
        bridge.asked[0].slice(); runCurrent()
        assertEquals(listOf("a@0", "b@0", "c@0"), bridge.log)   // no a@8
        bridge.asked[2].slice(99); runCurrent()
        assertArrayEquals(bytes, c.await())
        b.cancel(); bridge.asked[1].slice(); runCurrent()
        assertEquals(3, bridge.asked.size)
        // asked for again later: from the start
        val again = async { images.load("a", "s", "claude") }; runCurrent()
        assertEquals("a@0", bridge.log.last())
        bridge.asked.last().slice(99); runCurrent()
        assertArrayEquals(bytes, again.await())
    }

    @Test fun oneOfTwoCallersLeavingKeepsTheDownloadAndSoDoesComingBackInTime() = runTest {
        val bridge = Bridge()
        val images = bridge.downloads(this)
        val first = launch { images.load("a", "s", "claude") }
        val second = launch { images.load("a", "s", "claude") }
        runCurrent()
        first.cancel(); runCurrent()
        bridge.asked[0].slice(4); runCurrent()
        assertEquals(listOf("a@0", "a@4"), bridge.log)
        // the last one leaves and a new one arrives before the chunk does: what was downloaded counts
        second.cancel(); runCurrent()
        val back = async { images.load("a", "s", "claude") }
        runCurrent()
        bridge.asked[1].slice(4); runCurrent()
        bridge.asked[2].slice(99); runCurrent()
        assertEquals(listOf("a@0", "a@4", "a@8"), bridge.log)
        assertArrayEquals(bytes, back.await())
    }
}
