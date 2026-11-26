package dev.leash.personal.core

import kotlinx.serialization.json.JsonElement

/** A BridgeLink the test drives: records calls, answers them from handlers, emits frames. */
class FakeLink : BridgeLink {
    override var state: LinkState = LinkState.Idle
    override var onMessage: ((JsonElement) -> Unit)? = null
    override var onState: ((LinkState) -> Unit)? = null
    override var visible: Boolean = false
    val configured = mutableListOf<Triple<String, Int, String>>()
    var connects = 0
    var disconnects = 0
    var ensures = 0
    val calls = mutableListOf<Pair<String, JsonElement>>()
    val replies = mutableListOf<Pair<String, JsonElement>>()
    val handlers = mutableMapOf<String, suspend (JsonElement) -> JsonElement>()
    var defaultResult: JsonElement = jsonObj()
    var replyFails = false

    fun on(method: String, h: suspend (JsonElement) -> JsonElement) { handlers[method] = h }
    fun callsOf(method: String) = calls.filter { it.first == method }.map { it.second }

    override fun configure(host: String, port: Int, token: String) { configured += Triple(host, port, token) }
    override fun connect() { connects++ }
    override fun ensureConnected() { ensures++ }
    override fun disconnect() { disconnects++; set(LinkState.Idle) }

    override suspend fun call(method: String, params: JsonElement, timeoutMs: Long?): JsonElement {
        calls += method to params
        return handlers[method]?.invoke(params) ?: defaultResult
    }

    override suspend fun replyConfirmed(key: String, result: JsonElement, timeoutMs: Long): JsonElement {
        calls += "reply!" to jsonObj("key" to key, "result" to result)
        return handlers["reply!"]?.invoke(jsonObj("key" to key)) ?: jsonObj("ok" to true)
    }

    override fun reply(key: String, result: JsonElement) {
        if (replyFails) throw BridgeError.notConnected()
        replies += key to result
    }

    fun set(s: LinkState) { state = s; onState?.invoke(s) }
    fun emit(m: JsonElement) { onMessage?.invoke(m) }
}
