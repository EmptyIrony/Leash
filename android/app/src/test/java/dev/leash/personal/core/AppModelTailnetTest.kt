package dev.leash.personal.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTailnetTest {
    private class Rig(scope: TestScope, config: TailnetConfig, paired: Boolean = true) {
        val transport = FakeTransport()
        val node = FakeTailnetNode()
        val tailnet = TailnetController(scope.backgroundScope, node, InMemoryTailnetPrefs(config), "/data/tailnet")
        val model = AppModel(
            scope.backgroundScope, InMemorySettingsStore(if (paired) Settings(host = "jerrymac-mini.tail1.ts.net", port = 8787, token = "tok") else Settings()),
            BridgeConnection(scope.backgroundScope, transport), null, tailnet = tailnet,
        )
    }

    @Test fun theBridgeIsReachedThroughTheNodeWhenItIsOn() = runTest {
        val r = Rig(this, TailnetConfig(enabled = true, authKey = "tskey-auth-x"))
        r.model.start(); runCurrent()
        assertEquals("ws://127.0.0.1:40123/ws", r.transport.last.endpoint.url)
        assertEquals(listOf("jerrymac-mini.tail1.ts.net" to 8787), r.node.proxied)
    }

    @Test fun withTailscaleOffTheBridgeIsReachedDirectly() = runTest {
        val r = Rig(this, TailnetConfig(enabled = false))
        r.model.start(); runCurrent()
        assertEquals("ws://jerrymac-mini.tail1.ts.net:8787/ws", r.transport.last.endpoint.url)
        assertTrue(r.node.starts.isEmpty())
    }

    @Test fun switchingItOnReconnectsThroughTheNode() = runTest {
        val r = Rig(this, TailnetConfig(enabled = false, authKey = "tskey-auth-x"))
        r.model.start(); runCurrent()
        r.model.setTailnetEnabled(true); runCurrent()
        assertEquals("ws://127.0.0.1:40123/ws", r.transport.last.endpoint.url)
        r.model.setTailnetEnabled(false); runCurrent()
        assertEquals("ws://jerrymac-mini.tail1.ts.net:8787/ws", r.transport.last.endpoint.url)
        assertTrue(r.node.stops >= 1)
    }

    @Test fun aRejectedKeyShowsAsAFailedConnectionWithTheReason() = runTest {
        val r = Rig(this, TailnetConfig(enabled = true, authKey = "tskey-auth-bad"))
        r.node.waitError = TailnetException("tailnet: invalid authkey")
        r.node.statusJson = """{"state":"needs_key","ips":[],"error":"invalid authkey"}"""
        r.model.start(); runCurrent()
        val c = r.model.state.value.connection
        assertTrue(c.toString(), c is LinkState.Failed && "new auth key" in c.reason)
    }

    @Test fun leavingTheAppForGoodStopsTheNode() = runTest {
        val r = Rig(this, TailnetConfig(enabled = true, authKey = "tskey-auth-x"))
        r.model.start(); runCurrent()
        r.transport.last.ready(); runCurrent()
        r.model.releaseConnection()
        assertEquals(1, r.node.stops)
    }

    @Test fun unpairingStopsTheNode() = runTest {
        val r = Rig(this, TailnetConfig(enabled = true, authKey = "tskey-auth-x"))
        r.model.start(); runCurrent()
        r.model.unpair()
        assertEquals(1, r.node.stops)
    }

    @Test fun withNothingPairedTheNodeStillComesUpToShowItsStatus() = runTest {
        val r = Rig(this, TailnetConfig(enabled = false, authKey = "tskey-auth-x"), paired = false)
        r.model.start(); runCurrent()
        r.model.setTailnetEnabled(true); runCurrent()
        assertEquals(1, r.node.starts.size)
        assertEquals(TailnetStatus.Connected("leash-android", "100.64.0.9"), r.tailnet.state.value.status)
    }

    @Test fun coldStartWaitsForTailnetPrefsBeforeConnecting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val prefs = object : TailnetPrefs {
            override suspend fun load(): TailnetConfig {
                gate.await()
                return TailnetConfig(enabled = true, authKey = "tskey-auth-x")
            }
            override suspend fun save(config: TailnetConfig) {}
        }
        val transport = FakeTransport()
        val node = FakeTailnetNode()
        val tailnet = TailnetController(backgroundScope, node, prefs, "/data/tailnet")
        val model = AppModel(
            backgroundScope,
            InMemorySettingsStore(Settings(host = "jerrymac-mini.tail1.ts.net", port = 8787, token = "tok")),
            BridgeConnection(backgroundScope, transport), null, tailnet = tailnet,
        )

        model.start(); runCurrent()
        assertTrue(transport.sockets.isEmpty())
        assertTrue(node.starts.isEmpty())

        gate.complete(Unit); runCurrent()
        assertEquals("ws://127.0.0.1:40123/ws", transport.last.endpoint.url)
        assertEquals(1, node.starts.size)
        assertEquals(TailnetStatus.Connected("leash-android", "100.64.0.9"), tailnet.state.value.status)
    }

    @Test fun returningToForegroundRestartsTailscaleAfterRelease() = runTest {
        val r = Rig(this, TailnetConfig(enabled = true, authKey = "tskey-auth-x"))
        r.model.start(); runCurrent()
        r.transport.last.ready(); runCurrent()
        r.model.releaseConnection()
        assertEquals(1, r.node.stops)
        assertEquals(TailnetStatus.Stopped, r.tailnet.state.value.status)

        r.model.sceneChanged(true); runCurrent()
        assertEquals(2, r.node.starts.size)
        assertEquals("ws://127.0.0.1:40123/ws", r.transport.last.endpoint.url)
        assertEquals(TailnetStatus.Connected("leash-android", "100.64.0.9"), r.tailnet.state.value.status)
    }

    @Test fun coldStartWithoutABridgeWarmsUpEnabledTailscale() = runTest {
        val r = Rig(this, TailnetConfig(enabled = true, authKey = "tskey-auth-x"), paired = false)
        r.model.start(); runCurrent()
        assertEquals(1, r.node.starts.size)
        assertEquals(TailnetStatus.Connected("leash-android", "100.64.0.9"), r.tailnet.state.value.status)
    }

    @Test fun earlyPairBeforeTailnetLoadsStillRoutesThroughTailscale() = runTest {
        val gate = CompletableDeferred<Unit>()
        val prefs = object : TailnetPrefs {
            override suspend fun load(): TailnetConfig {
                gate.await()
                return TailnetConfig(enabled = true, authKey = "tskey-auth-x")
            }
            override suspend fun save(config: TailnetConfig) {}
        }
        val transport = FakeTransport()
        val node = FakeTailnetNode()
        val tailnet = TailnetController(backgroundScope, node, prefs, "/data/tailnet")
        val model = AppModel(
            backgroundScope, InMemorySettingsStore(Settings()),
            BridgeConnection(backgroundScope, transport), null, tailnet = tailnet,
        )

        model.start()
        model.pair(PairingInfo("jerrymac-mini.tail1.ts.net", 8787, "tok", "Mac"))
        runCurrent()
        assertTrue(transport.sockets.isEmpty())

        gate.complete(Unit); runCurrent()
        assertEquals("ws://127.0.0.1:40123/ws", transport.last.endpoint.url)
        assertEquals(1, node.starts.size)
    }
}
