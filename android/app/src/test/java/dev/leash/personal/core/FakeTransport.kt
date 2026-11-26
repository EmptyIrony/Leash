package dev.leash.personal.core

import kotlinx.serialization.json.JsonElement

/** A FrameTransport the test drives by hand. */
class FakeTransport : FrameTransport {
    class Sock(val endpoint: Endpoint, val listener: FrameTransport.Listener) : FrameSocket {
        val sent = mutableListOf<JsonElement>()
        var closed = false
        var accept = true
        override fun send(text: String): Boolean { if (!accept) return false; sent += parseJson(text); return true }
        override fun close() { closed = true }
        fun receive(m: JsonElement) = listener.onText(m.encoded())
        fun ready(extra: Map<String, Any?> = emptyMap()) = receive(jsonObj("op" to "ready", "agents" to emptyList<Any>(), *extra.toList().toTypedArray()))
    }

    val sockets = mutableListOf<Sock>()
    val last: Sock get() = sockets.last()
    var openError: Exception? = null

    override fun open(endpoint: Endpoint, listener: FrameTransport.Listener): FrameSocket {
        openError?.let { throw it }
        return Sock(endpoint, listener).also { sockets += it }
    }
}
