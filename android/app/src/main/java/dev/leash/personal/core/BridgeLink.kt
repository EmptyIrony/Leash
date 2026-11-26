package dev.leash.personal.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

typealias TunnelRoute = suspend (host: String, port: Int) -> Int?

/** The state of the link to the bridge. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState
    /** Will retry. */
    data class Waiting(val reason: String) : LinkState
    /** Will not retry (bad token, nothing configured). */
    data class Failed(val reason: String) : LinkState
}

class BridgeError(val code: Int, override val message: String, /** -32020: who holds the conversation ("cli", "claude-desktop", …) */ val holder: String? = null) : Exception(message) {
    companion object {
        /** the conversation is open in another client (Claude Code terminal / desktop app) */
        const val LOCKED_ELSEWHERE = -32020
        /** the conversation moved on elsewhere since it was loaded here */
        const val CHANGED_ELSEWHERE = -32021
        /** bridge/unqueue: the queued message already went out */
        const val ALREADY_SENT = -32024
        /** reply: the request was already answered */
        const val ALREADY_RESOLVED = -32002
        const val CLOSED = -1
        const val TIMEOUT = -2
        const val NO_REPLY = -4

        fun notConnected() = BridgeError(CLOSED, "Not connected to the bridge")
        fun timeout() = BridgeError(TIMEOUT, "Request timed out")
    }
}

/** What the app needs from the bridge connection; the demo bridge (debug builds) implements it too. */
interface BridgeLink {
    val state: LinkState
    var onMessage: ((JsonElement) -> Unit)?
    var onState: ((LinkState) -> Unit)?

    /** Leash is on screen. The bridge holds Bark pushes back only for a client on screen, so it has to hear when it is not. */
    var visible: Boolean

    /**
     * Built-in Tailscale: the loopback port that reaches host:port through the tailnet, or null to connect to the bridge
     * directly. Asked on every attempt, because the port changes whenever the node restarts. May throw [TunnelError].
     */
    var route: TunnelRoute?
        get() = null
        set(_) {}

    fun configure(host: String, port: Int, token: String)
    fun connect()

    /** Reconnect right away if not connected (e.g. the app came to the foreground). */
    fun ensureConnected()
    fun disconnect()

    /** Drop the socket and connect again right away (the device's network changed: the old socket is dead, it just does not know yet). */
    fun reconnect()

    /** Call a bridge / ACP method. `timeoutMs` null = wait forever (session/prompt). */
    suspend fun call(method: String, params: JsonElement = JsonObject(emptyMap()), timeoutMs: Long? = 30_000): JsonElement

    /** Answer a permission / elicitation and wait for the bridge to confirm it (-32002: already resolved). */
    suspend fun replyConfirmed(key: String, result: JsonElement, timeoutMs: Long = 10_000): JsonElement

    /** Answer an agent -> client request (permission, elicitation), fire and forget; throws BridgeError when not connected. */
    fun reply(key: String, result: JsonElement)
}
