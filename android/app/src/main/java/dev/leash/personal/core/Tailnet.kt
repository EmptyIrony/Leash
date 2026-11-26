package dev.leash.personal.core

/** An error from the embedded Tailscale node (Go's own message; it never contains the auth key). */
class TailnetException(message: String) : Exception(message)

/**
 * Why built-in Tailscale could not give a route to the bridge. [retry] false: only the user can fix it (a new auth key), so the
 * connection stops with `Failed` instead of backing off and trying again.
 */
class TunnelError(override val message: String, val retry: Boolean, val issue: Issue? = null) : Exception(message)

/**
 * The embedded Tailscale node (the `tailnet` Go module, built into an AAR by gomobile). Calls block on the Go side, so the
 * implementation takes them off the caller's thread; the ones that change the node run in the order they were made.
 */
interface TailnetNode {
    /** Brings the node up; returns once it is initialised, not once it is on the tailnet. */
    suspend fun start(stateDir: String, authKey: String, controlUrl: String, hostname: String)
    /** Leaves the tailnet; the saved node stays. Queued behind earlier calls. */
    fun stop()
    /** Logs the node out at control and deletes its saved state. The node has to be started. */
    suspend fun logout()
    /** A port on 127.0.0.1 that reaches host:port through the tailnet (the same one for the same target until [stop]). */
    suspend fun proxyPort(host: String, port: Int): Int
    /** Waits until the node is on the tailnet; throws with the reason otherwise. */
    suspend fun waitConnected(seconds: Int)
    /** The node's status as the Go side's JSON (state, ips, name, error). */
    suspend fun status(): String
}

/** What Settings and the connection UI show about the node. */
sealed interface TailnetStatus {
    /** Switched off, or this build has no Tailscale node. */
    data object Off : TailnetStatus
    /** Switched on, not running right now (not started yet, or stopped while Leash is in the background). */
    data object Stopped : TailnetStatus
    data object Connecting : TailnetStatus
    data class Connected(val name: String, val address: String) : TailnetStatus
    /** The key was rejected or expired, or there is none and no saved node; control's reason when it gave one. */
    data class NeedsKey(val reason: String?) : TailnetStatus
    data class Failed(val message: String, val issue: Issue? = null) : TailnetStatus
}

/** Parses the Go side's `Status()` JSON. */
fun parseTailnetStatus(json: String): TailnetStatus {
    val o = runCatching { parseJson(json) }.getOrNull()?.takeIf { it.obj != null }
        ?: return TailnetStatus.Failed("Unreadable status from Tailscale", Issue.TailnetUnreadable)
    val detail = o["error"].string?.takeIf { it.isNotEmpty() }
    return when (o["state"].string) {
        "connected" -> {
            val ips = o["ips"].array?.mapNotNull { it.string } ?: emptyList()
            TailnetStatus.Connected(o["name"].string ?: "", ips.firstOrNull { it.startsWith("100.") } ?: ips.firstOrNull() ?: "")
        }
        "starting" -> TailnetStatus.Connecting
        "needs_key" -> TailnetStatus.NeedsKey(detail)
        "stopped" -> TailnetStatus.Stopped
        else -> TailnetStatus.Failed(detail ?: "Tailscale is in an unexpected state", fallbackIssue(detail, Issue.TailnetUnexpected))
    }
}

/** What survives a restart. The key lives encrypted (see [TailnetPrefs]) and is never logged. */
data class TailnetConfig(
    val enabled: Boolean = false,
    /** Tailscale auth key (tskey-auth-…), only needed until the node is registered. */
    val authKey: String = "",
    /** Empty for Tailscale's own control server; a Headscale URL otherwise. */
    val controlUrl: String = "",
    /** The node's name on the tailnet; null: [TailnetController.DEFAULT_HOSTNAME]. */
    val hostname: String? = null,
) {
    // a data class prints its fields: keep the key out of every log line
    override fun toString() = "TailnetConfig(enabled=$enabled, authKey=" + (if (authKey.isEmpty()) "unset" else "set") + ", controlUrl=$controlUrl, hostname=$hostname)"
}

interface TailnetPrefs {
    suspend fun load(): TailnetConfig
    suspend fun save(config: TailnetConfig)
}

class InMemoryTailnetPrefs(var value: TailnetConfig = TailnetConfig()) : TailnetPrefs {
    override suspend fun load() = value
    override suspend fun save(config: TailnetConfig) { value = config }
}

/** Encrypts the auth key for storage (Android Keystore on the device). */
interface SecretBox {
    fun encrypt(plain: String): String
    /** null when it cannot be decrypted (the key was lost, e.g. a restored backup): the user enters the key again. */
    fun decrypt(sealed: String): String?
}
