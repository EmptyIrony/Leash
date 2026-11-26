package dev.leash.personal.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * OkHttp WebSocket. The 15 s ping doubles as the dead-link detector (no pong: onFailure). Incoming frames have no size
 * limit (iOS allows 64 MB; attach snapshots are the big ones). NOTE: OkHttp refuses to queue more than 16 MiB of outgoing
 * frames at once, so a base64 upload over about 12 MB of file needs chunking or another route (phase 4).
 */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : FrameTransport {
    override fun open(endpoint: Endpoint, listener: FrameTransport.Listener): FrameSocket {
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Authorization", "Bearer " + endpoint.token)
            .header("X-Leash-Client", "android")
            .build()
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onText(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                listener.onClosed(if (reason.isEmpty()) "code $code" else reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t, response?.code)
        })
        return object : FrameSocket {
            override fun send(text: String): Boolean = ws.send(text)
            override fun close() {
                if (opened.get()) ws.close(1001, "going away") else ws.cancel()
            }
        }
    }
}
