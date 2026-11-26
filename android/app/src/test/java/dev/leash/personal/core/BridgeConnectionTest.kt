package dev.leash.personal.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeConnectionTest {
    private class Rig(scope: TestScope) {
        val transport = FakeTransport()
        val link = BridgeConnection(scope.backgroundScope, transport)
        val messages = mutableListOf<JsonElement>()
        val states = mutableListOf<LinkState>()
        init {
            link.onMessage = { messages += it }
            link.onState = { states += it }
            link.configure("bridge.example.ts.net", 8787, "tok-123")
        }
    }

    private fun TestScope.rig() = Rig(this)

    @Test fun connectsWithTheAuthHeaderEndpointAndReachesConnectedOnReady() = runTest {
        val r = rig()
        r.link.connect()
        assertEquals(LinkState.Connecting, r.link.state)
        assertEquals(Endpoint("ws://bridge.example.ts.net:8787/ws", "tok-123"), r.transport.last.endpoint)
        r.transport.last.ready()
        runCurrent()
        assertEquals(LinkState.Connected, r.link.state)
        assertEquals("ready", r.messages.single()["op"].string)
    }

    @Test fun presenceIsSentOnReadyAndWhenItChanges() = runTest {
        val r = rig()
        r.link.visible = true
        r.link.connect(); r.transport.last.ready(); runCurrent()
        assertEquals(listOf("presence" to true), r.transport.last.sent.map { it["op"].string to it["visible"].bool })
        r.link.visible = false
        assertEquals(false, r.transport.last.sent.last()["visible"].bool)
        // no change, no frame
        r.link.visible = false
        assertEquals(2, r.transport.last.sent.size)
    }

    @Test fun callsAreCorrelatedByIdAndResolveInAnyOrder() = runTest {
        val r = rig()
        r.link.connect(); r.transport.last.ready(); runCurrent()
        val a = async { r.link.call("session/list") }
        val b = async { r.link.call("bridge/browse", jsonObj("path" to "/x")) }
        runCurrent()
        val calls = r.transport.last.sent.filter { it["op"].string == "call" }
        assertEquals(listOf("session/list", "bridge/browse"), calls.map { it["method"].string })
        assertEquals("/x", calls[1]["params"]["path"].string)
        val (ida, idb) = calls.map { it["id"].int!! }
        r.transport.last.receive(jsonObj("op" to "result", "id" to idb, "result" to jsonObj("n" to 2)))
        r.transport.last.receive(jsonObj("op" to "result", "id" to ida, "result" to jsonObj("n" to 1)))
        assertEquals(1, a.await()["n"].int)
        assertEquals(2, b.await()["n"].int)
        // results are consumed, not forwarded
        assertEquals(listOf("ready"), r.messages.map { it["op"].string })
    }

    @Test fun errorsMapToBridgeErrorWithTheHolder() = runTest {
        val r = rig()
        r.link.connect(); r.transport.last.ready(); runCurrent()
        val d = async { runCatching { r.link.call("bridge/attach") } }
        runCurrent()
        val id = r.transport.last.sent.last()["id"].int!!
        r.transport.last.receive(jsonObj("op" to "result", "id" to id,
            "error" to jsonObj("code" to -32020, "message" to "open elsewhere", "data" to jsonObj("holders" to listOf(jsonObj("entrypoint" to "cli"))))))
        val e = d.await().exceptionOrNull() as BridgeError
        assertEquals(BridgeError.LOCKED_ELSEWHERE, e.code)
        assertEquals("cli", e.holder)
        assertEquals("open elsewhere", e.message)
    }

    @Test fun aCallTimesOutAndALateResultIsIgnored() = runTest {
        val r = rig()
        r.link.connect(); r.transport.last.ready(); runCurrent()
        val d = async { runCatching { r.link.call("session/list", timeoutMs = 30_000) } }
        runCurrent()
        val id = r.transport.last.sent.last()["id"].int!!
        advanceTimeBy(30_001)
        assertEquals(BridgeError.TIMEOUT, (d.await().exceptionOrNull() as BridgeError).code)
        r.transport.last.receive(jsonObj("op" to "result", "id" to id, "result" to jsonObj()))
        runCurrent()
        // not consumed by a pending call any more: it reaches onMessage as an unknown frame, which the app ignores
        assertEquals(2, r.messages.size)
    }

    @Test fun aCallWithoutTimeoutWaitsAndFailsWhenTheLinkDrops() = runTest {
        val r = rig()
        r.link.connect(); r.transport.last.ready(); runCurrent()
        val d = async { runCatching { r.link.call("session/prompt", timeoutMs = null) } }
        advanceTimeBy(10 * 60_000L)
        assertFalse(d.isCompleted)
        r.transport.last.listener.onFailure(java.io.IOException("reset"), null)
        runCurrent()
        val e = d.await().exceptionOrNull() as BridgeError
        assertEquals(BridgeError.CLOSED, e.code)
        assertTrue(r.link.state is LinkState.Waiting)
    }

    @Test fun callsFailFastWhenNotConnected() = runTest {
        val r = rig()
        assertEquals(BridgeError.CLOSED, assertThrows(BridgeError::class.java) { kotlinx.coroutines.runBlocking { r.link.call("x") } }.code)
        assertThrows(BridgeError::class.java) { r.link.reply("k", jsonObj()) }
    }

    @Test fun replySendsTheFrameAndReplyConfirmedWaitsForTheResult() = runTest {
        val r = rig()
        r.link.connect(); r.transport.last.ready(); runCurrent()
        r.link.reply("agy:1", jsonObj("outcome" to jsonObj("outcome" to "cancelled")))
        val f = r.transport.last.sent.last()
        assertEquals("reply", f["op"].string)
        assertEquals("agy:1", f["key"].string)
        assertNull(f["id"])
        val d = async { runCatching { r.link.replyConfirmed("agy:2", jsonObj()) } }
        runCurrent()
        val id = r.transport.last.sent.last()["id"].int!!
        r.transport.last.receive(jsonObj("op" to "result", "id" to id, "error" to jsonObj("code" to -32002, "message" to "already resolved")))
        assertEquals(BridgeError.ALREADY_RESOLVED, (d.await().exceptionOrNull() as BridgeError).code)
    }

    @Test fun a401IsFinalAndNeverRetried() = runTest {
        val r = rig()
        r.link.connect()
        r.transport.last.listener.onFailure(java.net.ProtocolException("Expected HTTP 101 response but was '401 Unauthorized'"), 401)
        runCurrent()
        assertTrue((r.link.state as LinkState.Failed).reason.contains("401"))
        assertEquals(Issue.TokenRejected, (r.link.state as LinkState.Failed).issue)
        advanceTimeBy(120_000)
        assertEquals(1, r.transport.sockets.size)
        // a later foreground still tries (the user may have re-paired)
        r.link.ensureConnected()
        assertEquals(2, r.transport.sockets.size)
    }

    @Test fun reconnectsWithDoublingBackoffCappedAt15sAndTheDelayResetsOnReady() = runTest {
        val r = rig()
        r.link.connect()
        val waits = mutableListOf<Long>()
        repeat(6) {
            val before = r.transport.sockets.size
            r.transport.last.listener.onFailure(java.io.IOException("down"), null)
            runCurrent()
            assertTrue(r.link.state is LinkState.Waiting)
            var t = 0L
            while (r.transport.sockets.size == before) { advanceTimeBy(500); runCurrent(); t += 500 }
            waits += t
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), waits)
        r.transport.last.ready(); runCurrent()
        r.transport.last.listener.onFailure(java.io.IOException("down"), null)
        runCurrent()
        val n = r.transport.sockets.size
        advanceTimeBy(999); assertEquals(n, r.transport.sockets.size)
        advanceTimeBy(2); assertEquals(n + 1, r.transport.sockets.size)
    }

    @Test fun aFailureThenACloseOfTheSameSocketSchedulesOneRetry() = runTest {
        val r = rig()
        r.link.connect()
        val s = r.transport.last
        s.listener.onFailure(java.io.IOException("a"), null)
        s.listener.onClosed("b")
        advanceTimeBy(1_500)
        assertEquals(2, r.transport.sockets.size)
        assertTrue(s.closed)
    }

    @Test fun noReadyWithin10sIsAFailureAndRetries() = runTest {
        val r = rig()
        r.link.connect()
        advanceTimeBy(9_999)
        assertEquals(LinkState.Connecting, r.link.state)
        advanceTimeBy(2)
        assertTrue((r.link.state as LinkState.Waiting).reason.contains("did not answer"))
        assertEquals(Issue.NoReply, (r.link.state as LinkState.Waiting).issue)
        advanceTimeBy(1_001)
        assertEquals(2, r.transport.sockets.size)
    }

    @Test fun eventsFromAnOlderSocketAreIgnored() = runTest {
        val r = rig()
        r.link.connect()
        val old = r.transport.last
        r.link.disconnect()
        r.link.connect()
        old.receive(jsonObj("op" to "update"))
        old.listener.onFailure(java.io.IOException("late"), null)
        runCurrent()
        assertTrue(r.messages.isEmpty())
        assertEquals(LinkState.Connecting, r.link.state)
        assertTrue(old.closed)
    }

    @Test fun disconnectGoesIdleAndStopsRetrying() = runTest {
        val r = rig()
        r.link.connect(); r.transport.last.ready(); runCurrent()
        r.link.disconnect()
        assertEquals(LinkState.Idle, r.link.state)
        advanceTimeBy(60_000)
        assertEquals(1, r.transport.sockets.size)
    }

    @Test fun ensureConnectedOnlyActsWhenNotConnected() = runTest {
        val r = rig()
        r.link.ensureConnected()
        r.link.ensureConnected()
        assertEquals(1, r.transport.sockets.size)
        r.transport.last.ready(); runCurrent()
        r.link.ensureConnected()
        assertEquals(1, r.transport.sockets.size)
    }

    @Test fun configureRejectsMissingOrBadSettings() = runTest {
        val r = rig()
        r.link.configure("", 8787, "t")
        assertEquals(Issue.SetAddressAndToken, (r.link.state as LinkState.Failed).issue)
        r.link.connect()
        assertTrue(r.transport.sockets.isEmpty())
        r.link.configure("bad host/x", 8787, "t")
        assertEquals(Issue.InvalidAddress, (r.link.state as LinkState.Failed).issue)
        r.link.configure("h", 8787, "to ken")
        assertEquals(Issue.InvalidToken, (r.link.state as LinkState.Failed).issue)
        r.link.configure("fe80::1", 8787, "t")
        r.link.connect()
        assertEquals("ws://[fe80::1]:8787/ws", r.transport.last.endpoint.url)
    }

    @Test fun anOpenThatThrowsBecomesAWaitingState() = runTest {
        val r = rig()
        r.transport.openError = IllegalStateException("boom")
        r.link.connect()
        assertEquals(LinkState.Waiting("boom"), r.link.state)
        advanceUntilIdle().also { r.transport.openError = null }
    }

    // MARK: built-in Tailscale

    @Test fun withoutARouteItConnectsDirectly() = runTest {
        val r = rig()
        r.link.connect()
        assertEquals("ws://bridge.example.ts.net:8787/ws", r.transport.last.endpoint.url)
    }

    @Test fun aRouteSendsTheConnectionThroughTheLoopbackPortWithTheBridgesToken() = runTest {
        val r = rig()
        val asked = mutableListOf<Pair<String, Int>>()
        r.link.route = { h, p -> asked += h to p; 40123 }
        r.link.connect()
        assertEquals(LinkState.Connecting, r.link.state)
        runCurrent()
        assertEquals(listOf("bridge.example.ts.net" to 8787), asked)
        assertEquals(Endpoint("ws://127.0.0.1:40123/ws", "tok-123"), r.transport.last.endpoint)
        r.transport.last.ready(); runCurrent()
        assertEquals(LinkState.Connected, r.link.state)
    }

    @Test fun aRouteThatReturnsNothingConnectsDirectly() = runTest {
        val r = rig()
        r.link.route = { _, _ -> null }
        r.link.connect(); runCurrent()
        assertEquals("ws://bridge.example.ts.net:8787/ws", r.transport.last.endpoint.url)
    }

    @Test fun anIpv6BridgeIsAskedWithoutBrackets() = runTest {
        val r = rig()
        r.link.configure("fd7a:115c:a1e0::1", 8787, "tok-123")
        var host = ""
        r.link.route = { h, _ -> host = h; 5000 }
        r.link.connect(); runCurrent()
        assertEquals("fd7a:115c:a1e0::1", host)
    }

    @Test fun aRouteThatNeedsTheUserStopsTheRetries() = runTest {
        val r = rig()
        r.link.route = { _, _ -> throw TunnelError("Built-in Tailscale needs a new auth key. Enter one in Settings.", retry = false, issue = Issue.TailnetNeedsKey) }
        r.link.connect(); runCurrent()
        assertEquals(LinkState.Failed("Built-in Tailscale needs a new auth key. Enter one in Settings.", Issue.TailnetNeedsKey), r.link.state)
        advanceTimeBy(60_000)
        assertTrue(r.transport.sockets.isEmpty())
    }

    @Test fun aRouteThatFailsLaterTriesAgainWithBackoff() = runTest {
        val r = rig()
        var attempts = 0
        r.link.route = { _, _ -> if (++attempts < 3) throw TunnelError("Tailscale did not connect", retry = true) else 4000 }
        r.link.connect(); runCurrent()
        assertEquals(LinkState.Waiting("Tailscale did not connect"), r.link.state)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, attempts)
        assertEquals("ws://127.0.0.1:4000/ws", r.transport.last.endpoint.url)
    }

    @Test fun disconnectingWhileTheNodeStartsOpensNoSocket() = runTest {
        val r = rig()
        val gate = kotlinx.coroutines.CompletableDeferred<Int?>()
        r.link.route = { _, _ -> gate.await() }
        r.link.connect(); runCurrent()
        r.link.disconnect()
        gate.complete(4000); runCurrent()
        assertTrue(r.transport.sockets.isEmpty())
        assertEquals(LinkState.Idle, r.link.state)
    }

    @Test fun aSecondConnectWhileTheNodeStartsOnlyOpensOneSocket() = runTest {
        val r = rig()
        val gate = kotlinx.coroutines.CompletableDeferred<Int?>()
        r.link.route = { _, _ -> gate.await() }
        r.link.connect(); runCurrent()
        r.link.connect(); runCurrent()
        gate.complete(4000); runCurrent()
        assertEquals(1, r.transport.sockets.size)
    }
}
