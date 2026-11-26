package dev.leash.personal.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TailnetControllerTest {
    private class Rig(scope: TestScope, config: TailnetConfig = TailnetConfig(enabled = true, authKey = "tskey-auth-SECRET", controlUrl = "")) {
        val node = FakeTailnetNode()
        val prefs = InMemoryTailnetPrefs(config)
        val tailnet = TailnetController(scope.backgroundScope, node, prefs, "/data/no_backup/tailnet").also { it.start() }
    }

    @Test fun offMeansNoRouteAndNoNode() = runTest {
        val r = Rig(this, TailnetConfig(enabled = false, authKey = "tskey-auth-x"))
        runCurrent()
        assertNull(r.tailnet.proxyPort("bridge.ts.net", 8787))
        assertTrue(r.node.starts.isEmpty())
        assertEquals(TailnetStatus.Off, r.tailnet.state.value.status)
    }

    @Test fun aBuildWithoutTheNodeCannotBeSwitchedOn() = runTest {
        val tailnet = TailnetController(backgroundScope, null, InMemoryTailnetPrefs(TailnetConfig(enabled = true)), "/x").also { it.start() }
        runCurrent()
        assertFalse(tailnet.state.value.available)
        assertFalse(tailnet.state.value.enabled)
        assertNull(tailnet.proxyPort("h", 1))
    }

    @Test fun startsTheNodeWithTheSavedConfigAndReturnsTheLoopbackPort() = runTest {
        val r = Rig(this, TailnetConfig(enabled = true, authKey = "  tskey-auth-SECRET  ", controlUrl = " https://hs.example "))
        val port = r.tailnet.proxyPort("jerrymac-mini.tail1.ts.net", 8787)
        assertEquals(40123, port)
        assertEquals(listOf(FakeTailnetNode.Started("/data/no_backup/tailnet", "tskey-auth-SECRET", "https://hs.example", "leash-android")), r.node.starts)
        assertEquals(listOf("jerrymac-mini.tail1.ts.net" to 8787), r.node.proxied)
        assertEquals(TailnetStatus.Connected("leash-android", "100.64.0.9"), r.tailnet.state.value.status)
        // the node keeps running for the next attempt
        r.tailnet.proxyPort("jerrymac-mini.tail1.ts.net", 8787)
        assertEquals(1, r.node.starts.size)
    }

    @Test fun concurrentAttemptsShareOneStart() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>().also { r.node.startGate = it }
        val a = async { r.tailnet.proxyPort("h", 1) }
        val b = async { r.tailnet.proxyPort("h", 1) }
        runCurrent()
        assertEquals(TailnetStatus.Connecting, r.tailnet.state.value.status)
        gate.complete(Unit)
        assertEquals(40123, a.await()); assertEquals(40123, b.await())
        assertEquals(1, r.node.starts.size)
    }

    @Test fun aRejectedKeyIsNotRetried() = runTest {
        val r = Rig(this)
        r.node.waitError = TailnetException("tailnet: invalid authkey")
        r.node.statusJson = """{"state":"needs_key","ips":[],"error":"invalid authkey"}"""
        val e = runCatching { r.tailnet.proxyPort("h", 1) }.exceptionOrNull() as TunnelError
        assertFalse(e.retry)
        assertTrue(e.message, "new auth key" in e.message)
        assertEquals(TailnetStatus.NeedsKey("invalid authkey"), r.tailnet.state.value.status)
    }

    @Test fun otherFailuresAreRetried() = runTest {
        val r = Rig(this)
        r.node.waitError = TailnetException("tailnet: timed out while connecting")
        r.node.statusJson = """{"state":"starting","ips":[]}"""
        val e = runCatching { r.tailnet.proxyPort("h", 1) }.exceptionOrNull() as TunnelError
        assertTrue(e.retry)
        assertEquals("tailnet: timed out while connecting", e.message)
    }

    @Test fun aNodeThatDoesNotStartIsAnErrorStatus() = runTest {
        val r = Rig(this)
        r.node.startError = TailnetException("tailnet: start: boom")
        val e = runCatching { r.tailnet.proxyPort("h", 1) }.exceptionOrNull() as TunnelError
        assertTrue(e.retry)
        assertEquals(TailnetStatus.Failed("tailnet: start: boom"), r.tailnet.state.value.status)
    }

    @Test fun aRouteThatCannotBeMadeIsRetried() = runTest {
        val r = Rig(this)
        r.tailnet.proxyPort("h", 1)
        r.node.stops = 0
        val broken = object : TailnetNode by r.node { override suspend fun proxyPort(host: String, port: Int): Int = throw TailnetException("no route") }
        val t2 = TailnetController(backgroundScope, broken, InMemoryTailnetPrefs(TailnetConfig(enabled = true, authKey = "k")), "/x").also { it.start() }
        val e = runCatching { t2.proxyPort("h", 1) }.exceptionOrNull() as TunnelError
        assertTrue(e.retry); assertEquals("no route", e.message)
    }

    @Test fun aNewKeyRestartsTheNodeOnTheNextAttempt() = runTest {
        val r = Rig(this)
        r.tailnet.proxyPort("h", 1)
        r.tailnet.saveConfig("tskey-auth-NEW", "")
        assertEquals(1, r.node.stops)
        assertEquals(TailnetStatus.Stopped, r.tailnet.state.value.status)
        r.tailnet.proxyPort("h", 1)
        assertEquals(listOf("tskey-auth-SECRET", "tskey-auth-NEW"), r.node.starts.map { it.authKey })
        runCurrent()
        assertEquals("tskey-auth-NEW", r.prefs.value.authKey)
    }

    @Test fun aBlankKeyKeepsTheSavedOne() = runTest {
        val r = Rig(this)
        r.tailnet.saveConfig("  ", "https://hs.example")
        runCurrent()
        assertEquals("tskey-auth-SECRET", r.prefs.value.authKey)
        assertEquals("https://hs.example", r.prefs.value.controlUrl)
        assertTrue(r.tailnet.state.value.keySaved)
    }

    @Test fun savingTheSameConfigDoesNotRestartTheNode() = runTest {
        val r = Rig(this)
        r.tailnet.proxyPort("h", 1)
        r.tailnet.saveConfig("", "")
        assertEquals(0, r.node.stops)
        assertEquals(1, r.node.starts.size)
    }

    @Test fun switchingOffStopsTheNodeAndPersists() = runTest {
        val r = Rig(this)
        r.tailnet.proxyPort("h", 1)
        r.tailnet.setEnabled(false)
        runCurrent()
        assertEquals(1, r.node.stops)
        assertEquals(TailnetStatus.Off, r.tailnet.state.value.status)
        assertFalse(r.prefs.value.enabled)
        assertNull(r.tailnet.proxyPort("h", 1))
    }

    @Test fun aStopDuringAStartLeavesNothingRunning() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>().also { r.node.startGate = it }
        val a = async { runCatching { r.tailnet.proxyPort("h", 1) } }
        runCurrent()
        r.tailnet.stop()
        gate.complete(Unit)
        val e = a.await().exceptionOrNull() as TunnelError
        assertTrue(e.retry)
        // the stop that ran before the start finished, and the one that cleans up after it
        assertEquals(2, r.node.stops)
        assertTrue(r.tailnet.state.value.status is TailnetStatus.Stopped)
    }

    @Test fun logoutStartsFromTheSavedIdentityWithoutAKeyThenForgetsEverything() = runTest {
        val r = Rig(this)
        r.tailnet.logout()
        runCurrent()
        assertEquals("", r.node.starts.single().authKey)
        assertEquals(1, r.node.logouts)
        assertEquals("", r.prefs.value.authKey)
        assertFalse(r.prefs.value.enabled)
        assertEquals(TailnetStatus.Off, r.tailnet.state.value.status)
        assertFalse(r.tailnet.state.value.keySaved)
        assertNull(r.tailnet.state.value.logoutNote)
    }

    @Test fun aLogoutControlCouldNotHearAboutIsSaidButStillForgets() = runTest {
        val r = Rig(this)
        r.node.logoutError = TailnetException("tailnet: control logout failed, local state removed: offline")
        r.tailnet.logout()
        runCurrent()
        assertEquals("tailnet: control logout failed, local state removed: offline", r.tailnet.state.value.logoutNote)
        assertEquals("", r.prefs.value.authKey)
        assertEquals(TailnetStatus.Off, r.tailnet.state.value.status)
    }

    @Test fun theStatusFollowsTheNodeWhileItRuns() = runTest {
        val r = Rig(this)
        r.node.statusJson = """{"state":"starting","ips":[]}"""
        r.tailnet.proxyPort("h", 1)
        // the attempt's own refresh saw "starting" ...
        assertEquals(TailnetStatus.Connecting, r.tailnet.state.value.status)
        r.node.statusJson = """{"state":"connected","ips":["100.64.0.9"],"name":"n"}"""
        advanceTimeBy(TailnetController.STARTING_POLL_MS + 1)
        assertEquals(TailnetStatus.Connected("n", "100.64.0.9"), r.tailnet.state.value.status)
    }

    @Test fun theAuthKeyIsNotInWhatIsPrintedOrShown() = runTest {
        val r = Rig(this)
        r.tailnet.proxyPort("h", 1)
        runCurrent()
        assertFalse("SECRET" in r.tailnet.state.value.toString())
        assertFalse("SECRET" in r.prefs.value.toString())
        assertTrue(r.tailnet.state.value.keySaved)
    }

    @Test fun theHostnameCanBeOverriddenAndIsKeptWithTheConfig() = runTest {
        val r = Rig(this)
        r.tailnet.setHostname("leash-android-test")
        r.tailnet.proxyPort("h", 1)
        runCurrent()
        assertEquals("leash-android-test", r.node.starts.single().hostname)
        assertEquals("leash-android-test", r.prefs.value.hostname)
    }

    @Test fun statusJsonIsParsed() {
        assertEquals(TailnetStatus.Connected("leash-android", "100.64.0.9"), parseTailnetStatus("""{"state":"connected","ips":["fd7a::1","100.64.0.9"],"name":"leash-android"}"""))
        assertEquals(TailnetStatus.Connected("n", "fd7a::1"), parseTailnetStatus("""{"state":"connected","ips":["fd7a::1"],"name":"n"}"""))
        assertEquals(TailnetStatus.Connecting, parseTailnetStatus("""{"state":"starting","ips":[]}"""))
        assertEquals(TailnetStatus.NeedsKey("invalid authkey"), parseTailnetStatus("""{"state":"needs_key","error":"invalid authkey","ips":[]}"""))
        assertEquals(TailnetStatus.NeedsKey(null), parseTailnetStatus("""{"state":"needs_key","ips":[]}"""))
        assertEquals(TailnetStatus.Stopped, parseTailnetStatus("""{"state":"stopped","ips":[]}"""))
        assertEquals(TailnetStatus.Failed("boom"), parseTailnetStatus("""{"state":"error","error":"boom","ips":[]}"""))
        assertEquals(TailnetStatus.Failed("Tailscale is in an unexpected state"), parseTailnetStatus("""{"state":"???"}"""))
        assertEquals(TailnetStatus.Failed("Unreadable status from Tailscale"), parseTailnetStatus("not json"))
        assertEquals(TailnetStatus.Failed("Unreadable status from Tailscale"), parseTailnetStatus(""))
    }

    @Test fun configPrintsWithoutTheKey() {
        assertFalse("tskey" in TailnetConfig(enabled = true, authKey = "tskey-auth-abc").toString())
    }
}
