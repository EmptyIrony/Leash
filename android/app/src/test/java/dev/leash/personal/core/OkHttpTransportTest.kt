package dev.leash.personal.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real socket against a local MockWebServer: headers, a text round trip, a 401. */
class OkHttpTransportTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() { runCatching { server.shutdown() } }

    private class Events : FrameTransport.Listener {
        val texts = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Pair<Throwable, Int?>>()
        val opened = CountDownLatch(1)
        val gotText = CountDownLatch(1)
        val failed = CountDownLatch(1)
        override fun onOpen() = opened.countDown()
        override fun onText(text: String) { texts += text; gotText.countDown() }
        override fun onFailure(error: Throwable, httpStatus: Int?) { failures += error to httpStatus; failed.countDown() }
        override fun onClosed(reason: String) {}
    }

    @Test fun sendsAuthAndClientHeadersAndRoundTripsFrames() {
        val echoed = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"op":"ready"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("echo:" + text); echoed.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        val ev = Events()
        val sock = OkHttpTransport().open(Endpoint("ws://${server.hostName}:${server.port}/ws", "secret-token"), ev)
        assertTrue(ev.opened.await(5, TimeUnit.SECONDS))
        assertTrue(ev.gotText.await(5, TimeUnit.SECONDS))
        assertEquals("""{"op":"ready"}""", ev.texts[0])
        assertTrue(sock.send("""{"op":"presence"}"""))
        assertTrue(echoed.await(5, TimeUnit.SECONDS))
        val req = server.takeRequest()
        assertEquals("/ws", req.path)
        assertEquals("Bearer secret-token", req.getHeader("Authorization"))
        assertEquals("android", req.getHeader("X-Leash-Client"))
        sock.close()
    }

    @Test fun aMaximumUploadFitsOkHttpsOutgoingQueueAndOneMoreMegabyteClosesTheSocket() {
        val received = java.util.concurrent.atomic.AtomicInteger(0)
        val got = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"op":"ready"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) { received.set(text.length); got.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        val ev = Events()
        val sock = OkHttpTransport().open(Endpoint("ws://${server.hostName}:${server.port}/ws", "t"), ev)
        assertTrue(ev.gotText.await(5, TimeUnit.SECONDS))
        // the largest allowed file, base64 inside the call JSON the bridge expects
        val data = java.util.Base64.getEncoder().encodeToString(ByteArray(FileLimits.MAX_UPLOAD_BYTES))
        val frame = jsonObj("op" to "call", "id" to 1, "method" to "bridge/upload", "params" to jsonObj("sessionId" to "s", "name" to "big.bin", "data" to data, "agent" to "claude")).encoded()
        assertTrue(frame.length < 16 * 1024 * 1024)
        assertTrue(sock.send(frame))
        assertTrue(got.await(30, TimeUnit.SECONDS))
        assertEquals(frame.length, received.get())
        // a frame past the queue limit is refused (and OkHttp closes the connection): why the phone checks the size first
        assertTrue(!sock.send("x".repeat(16 * 1024 * 1024 + 1)))
        sock.close()
    }

    @Test fun aRejectedTokenReportsTheHttpStatus() {
        server.enqueue(MockResponse().setResponseCode(401))
        val ev = Events()
        OkHttpTransport().open(Endpoint("ws://${server.hostName}:${server.port}/ws", "bad"), ev)
        assertTrue(ev.failed.await(5, TimeUnit.SECONDS))
        assertEquals(401, ev.failures[0].second)
        assertNotNull(ev.failures[0].first)
    }

    @Test fun fullStackThroughBridgeConnection() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"op":"ready","agents":[]}""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = parseJson(text)
                if (m["op"].string == "call") webSocket.send(jsonObj("op" to "result", "id" to m["id"].int, "result" to jsonObj("echo" to m["method"].string)).encoded())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val scope = kotlinx.coroutines.CoroutineScope(executor.asCoroutineDispatcher() + kotlinx.coroutines.SupervisorJob())
        try {
            val link = BridgeConnection(scope, OkHttpTransport())
            link.configure(server.hostName, server.port, "t")
            val connected = CountDownLatch(1)
            link.onState = { if (it == LinkState.Connected) connected.countDown() }
            link.connect()
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val r = kotlinx.coroutines.runBlocking { link.call("session/list") }
            assertEquals("session/list", r["echo"].string)
            link.disconnect()
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }
}
