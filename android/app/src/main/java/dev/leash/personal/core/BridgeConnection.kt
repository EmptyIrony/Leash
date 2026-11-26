package dev.leash.personal.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

/**
 * Link to the bridge running on the Mac: auth header, `/ws`, reconnect with backoff, call/result correlation with
 * timeouts, presence. Port of ios Core/BridgeConnection.swift. `scope` should run on one thread (Main): socket
 * callbacks hop onto it, so state is only touched there.
 */
class BridgeConnection(
    private val scope: CoroutineScope,
    private val transport: FrameTransport,
) : BridgeLink {
    override var onMessage: ((JsonElement) -> Unit)? = null
    override var onState: ((LinkState) -> Unit)? = null

    @Volatile override var state: LinkState = LinkState.Idle
        private set(value) {
            if (field != value) { field = value; onState?.invoke(value) }
        }

    override var visible: Boolean = false
        set(value) { if (field != value) { field = value; sendPresence() } }

    private var socket: FrameSocket? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicInteger(1)
    private var generation = 0
    private var retryDelayMs = INITIAL_RETRY_MS
    private var retryJob: Job? = null
    private var watchdogJob: Job? = null
    private var endpoint: Endpoint? = null

    override fun configure(host: String, port: Int, token: String) {
        val h = host.trim()
        val t = token.trim()
        if (h.isEmpty() || t.isEmpty()) {
            endpoint = null
            disconnect()
            state = LinkState.Failed("Set the Mac address and token in Settings")
            return
        }
        if (!HOST.matches(h) || port !in 1..65535) {
            endpoint = null
            disconnect()
            state = LinkState.Failed("The Mac address or port is not valid")
            return
        }
        if (!TOKEN.matches(t)) {
            endpoint = null
            disconnect()
            state = LinkState.Failed("The token has characters a token cannot have")
            return
        }
        val bracketed = if (':' in h && !h.startsWith("[")) "[$h]" else h
        endpoint = Endpoint("ws://$bracketed:$port/ws", t)
    }

    override fun connect() {
        val ep = endpoint ?: run { state = LinkState.Failed("Set the Mac address and token in Settings"); return }
        retryJob?.cancel(); retryJob = null
        teardown(null)
        val gen = ++generation
        state = LinkState.Connecting
        val s = try {
            transport.open(ep, Listener(gen))
        } catch (e: Exception) {
            fail(e, null)
            return
        }
        socket = s
        // Watchdog: the bridge sends "ready" right after the upgrade.
        watchdogJob = scope.launch {
            delay(READY_TIMEOUT_MS)
            if (gen == generation && state == LinkState.Connecting && socket === s) {
                handleFailure(gen, s, BridgeError(BridgeError.NO_REPLY, "Mac did not answer (is the bridge running and Tailscale up?)"), null)
            }
        }
    }

    override fun ensureConnected() {
        when (state) {
            LinkState.Connected, LinkState.Connecting -> return
            is LinkState.Failed -> if (endpoint == null) return
            else -> {}
        }
        retryDelayMs = INITIAL_RETRY_MS
        connect()
    }

    override fun disconnect() {
        retryJob?.cancel(); retryJob = null
        generation++
        teardown(null)
        state = LinkState.Idle
    }

    private fun teardown(reason: String?) {
        watchdogJob?.cancel(); watchdogJob = null
        socket?.close()
        socket = null
        val err = BridgeError(BridgeError.CLOSED, reason ?: "Connection closed")
        val dropped = pending.values.toList()
        pending.clear()
        for (c in dropped) c.completeExceptionally(err)
    }

    private inner class Listener(val gen: Int) : FrameTransport.Listener {
        // the socket this listener belongs to is only known after open() returns; compare by generation
        override fun onOpen() {}
        override fun onText(text: String) { scope.launch { if (gen == generation) receive(text) } }
        override fun onFailure(error: Throwable, httpStatus: Int?) { scope.launch { handleFailure(gen, null, error, httpStatus) } }
        override fun onClosed(reason: String) {
            scope.launch { handleFailure(gen, null, BridgeError(BridgeError.CLOSED, "Connection closed by the Mac" + (if (reason.isBlank()) "" else " ($reason)")), null) }
        }
    }

    private fun receive(text: String) {
        val m = runCatching { parseJson(text) }.getOrNull() ?: return
        handle(m)
    }

    /** A stale generation, or a socket already torn down (a failure and a close can both report), is ignored. */
    private fun handleFailure(gen: Int, expected: FrameSocket?, error: Throwable, httpStatus: Int?) {
        if (gen != generation || socket == null) return
        if (expected != null && expected !== socket) return
        fail(error, httpStatus)
    }

    private fun fail(error: Throwable, httpStatus: Int?) {
        teardown(error.message)
        if (httpStatus == 401) {
            state = LinkState.Failed("Token rejected by the Mac (401). Re-pair in Settings.")
            return
        }
        val delayMs = retryDelayMs
        retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_MS)
        state = LinkState.Waiting(error.message ?: error.toString())
        retryJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun handle(m: JsonElement) {
        val op = m["op"].string
        if (op == "ready") {
            state = LinkState.Connected
            watchdogJob?.cancel(); watchdogJob = null
            retryDelayMs = INITIAL_RETRY_MS
            sendPresence()
        }
        if (op == "result") {
            val id = m["id"].int
            val c = if (id != null) pending.remove(id) else null
            if (c != null) {
                val e = m["error"]
                if (e != null && !e.isJsonNull) {
                    c.completeExceptionally(BridgeError(e["code"].int ?: -32603, e["message"].string ?: "error", e["data"]["holders"][0]["entrypoint"].string))
                } else {
                    c.complete(m["result"] ?: kotlinx.serialization.json.JsonNull)
                }
                return
            }
        }
        onMessage?.invoke(m)
    }

    private fun sendPresence() {
        if (state != LinkState.Connected) return
        runCatching { sendRaw(jsonObj("op" to "presence", "visible" to visible)) }
    }

    private fun sendRaw(obj: JsonElement) {
        val s = socket
        if (s == null || state != LinkState.Connected) throw BridgeError.notConnected()
        if (!s.send(obj.encoded())) throw BridgeError(BridgeError.CLOSED, "Connection closed")
    }

    override suspend fun call(method: String, params: JsonElement, timeoutMs: Long?): JsonElement {
        val id = nextId.getAndIncrement()
        return exchange(jsonObj("op" to "call", "id" to id, "method" to method, "params" to params), id, timeoutMs)
    }

    override suspend fun replyConfirmed(key: String, result: JsonElement, timeoutMs: Long): JsonElement {
        val id = nextId.getAndIncrement()
        return exchange(jsonObj("op" to "reply", "id" to id, "key" to key, "result" to result), id, timeoutMs)
    }

    /** Send `msg` and resume with the bridge's `result` frame for `id`. */
    private suspend fun exchange(msg: JsonElement, id: Int, timeoutMs: Long?): JsonElement {
        val d = CompletableDeferred<JsonElement>()
        pending[id] = d
        try {
            sendRaw(msg)
            return if (timeoutMs != null) withTimeout(timeoutMs) { d.await() } else d.await()
        } catch (e: TimeoutCancellationException) {
            throw BridgeError.timeout()
        } finally {
            pending.remove(id)
        }
    }

    override fun reply(key: String, result: JsonElement) = sendRaw(jsonObj("op" to "reply", "key" to key, "result" to result))

    private companion object {
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15_000L
        const val READY_TIMEOUT_MS = 10_000L
        val HOST = Regex("""[A-Za-z0-9._:\[\]-]+""")
        val TOKEN = Regex("""[\x21-\x7E]+""")
    }
}
