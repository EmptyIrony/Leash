package dev.leash.personal.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings shows. The auth key itself never leaves the controller: the UI only learns whether one is saved. */
data class TailnetState(
    /** the config is loaded */
    val loaded: Boolean = false,
    /** this build has a Tailscale node (the Tailnet AAR is linked) */
    val available: Boolean = false,
    /** the Settings switch; always false when not [available] */
    val enabled: Boolean = false,
    val keySaved: Boolean = false,
    val controlUrl: String = "",
    val status: TailnetStatus = TailnetStatus.Off,
    /** Control could not be told about a log out (the local state is gone anyway). */
    val logoutNote: Problem? = null,
)

/**
 * Built-in Tailscale: Leash joins the tailnet itself (the Go `tailnet` module, tsnet in userspace, no VpnService) and only its
 * connection to the bridge goes through it. [BridgeConnection] asks [proxyPort] on every attempt for the loopback port that
 * reaches the paired host through the tailnet; this class starts the node when that happens, and [AppModel] stops it when
 * Leash has no reason to be connected. Port of ios Core/TailnetController.swift. [scope] runs on one thread (Main).
 *
 * The auth key and control URL live in [prefs] (the key encrypted) and are never logged; the node's identity lives in the state
 * directory (no-backup storage), so the key is only needed until the node is registered.
 */
class TailnetController(
    private val scope: CoroutineScope,
    /** null: this build has no node */
    private val node: TailnetNode?,
    private val prefs: TailnetPrefs,
    /** the directory for the node's identity, created by the node */
    private val stateDir: String,
) {
    private val _state = MutableStateFlow(TailnetState(available = node != null))
    val state: StateFlow<TailnetState> = _state

    private var config = TailnetConfig()
    private val loaded = CompletableDeferred<Unit>()
    private var generation = 0
    private var desired = false
    private var running = false
    private var runningFingerprint: String? = null
    private var connecting: Deferred<Result<Unit>>? = null
    private var monitor: Job? = null

    /** the Settings switch, false in a build without the node */
    val enabled: Boolean get() = config.enabled && node != null

    private val hostname: String get() = config.hostname?.takeIf { it.isNotBlank() } ?: DEFAULT_HOSTNAME
    private val fingerprint: String get() = config.authKey.trim() + "\n" + config.controlUrl.trim() + "\n" + hostname

    fun start() {
        scope.launch {
            try {
                config = runCatching { prefs.load() }.getOrElse { TailnetConfig() }
                publish(status = if (enabled) TailnetStatus.Stopped else TailnetStatus.Off)
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    /** Suspends until [start] has finished loading the saved configuration. */
    suspend fun awaitLoaded() = loaded.await()

    private fun publish(status: TailnetStatus? = null, logoutNote: Problem? = _state.value.logoutNote) {
        _state.update {
            it.copy(loaded = true, enabled = enabled, keySaved = config.authKey.isNotBlank(), controlUrl = config.controlUrl,
                status = status ?: it.status, logoutNote = logoutNote)
        }
    }

    private val saveLock = Mutex()

    /** Saves the current config; the last save always writes the latest one, whatever order the launches run in. */
    private fun persist() {
        scope.launch { saveLock.withLock { prefs.save(config) } }
    }

    // MARK: settings

    suspend fun setEnabled(on: Boolean) {
        loaded.await()
        config = config.copy(enabled = on)
        persist()
        publish(status = if (enabled) TailnetStatus.Stopped else TailnetStatus.Off, logoutNote = if (on) null else _state.value.logoutNote)
        configChanged()
    }

    /** Saves what was typed: a blank [authKey] keeps the saved one. */
    suspend fun saveConfig(authKey: String, controlUrl: String) {
        loaded.await()
        config = config.copy(authKey = authKey.trim().ifEmpty { config.authKey }, controlUrl = controlUrl.trim())
        persist()
        publish()
        configChanged()
    }

    /** Debug builds: a different node name (the intent a test uses). */
    suspend fun setHostname(name: String?) {
        loaded.await()
        config = config.copy(hostname = name?.trim()?.ifEmpty { null })
        persist()
        configChanged()
    }

    // MARK: routing

    /**
     * The port on 127.0.0.1 that reaches [host]:[port] through the tailnet, starting the node if needed; null when built-in
     * Tailscale is off (connect directly). Throws a [TunnelError]: `retry` false when only the user can fix it.
     */
    suspend fun proxyPort(host: String, port: Int): Int? {
        loaded.await()
        val n = node
        if (!enabled || n == null) return null
        bringUp(n)
        try {
            return n.proxyPort(host, port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TunnelError(e.message ?: "Tailscale has no route to the bridge", retry = true, issue = fallbackIssue(e.message, Issue.TailnetNoRoute))
        }
    }

    /** Starts the node without a target (Settings, to show the status before a bridge is paired). */
    fun warmUp() {
        val n = node ?: return
        scope.launch {
            loaded.await()
            if (enabled && connecting == null) runCatching { bringUp(n) }
        }
    }

    /** Settings changed: stop the node when it was switched off or its key or control URL changed; the next connection attempt starts it again. */
    fun configChanged() {
        if (!loaded.isCompleted) return
        if (!enabled || runningFingerprint != fingerprint) stop()
    }

    /** Leaves the tailnet (the saved node stays). A connection attempt after this starts it again. */
    fun stop() {
        generation++
        monitor?.cancel(); monitor = null
        connecting = null
        val wasActive = desired || running
        desired = false; running = false; runningFingerprint = null
        if (wasActive) node?.stop()
        publish(status = if (enabled) TailnetStatus.Stopped else TailnetStatus.Off)
    }

    /** Logs the node out of the tailnet, deletes its saved state and key, and switches the feature off. */
    suspend fun logout() {
        loaded.await()
        stop()
        var note: Problem? = null
        val n = node
        if (n != null) {
            try {
                // Logout needs a started node; the saved identity is enough, no key.
                n.start(stateDir, "", config.controlUrl.trim(), hostname)
                n.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = Problem(e.message ?: "Logging out failed", fallbackIssue(e.message, Issue.TailnetLogoutFailed))
            }
            n.stop()
        }
        config = config.copy(authKey = "", enabled = false)
        persist()
        publish(status = TailnetStatus.Off, logoutNote = note)
    }

    // MARK: starting

    private suspend fun bringUp(n: TailnetNode) {
        connecting?.let { return it.await().getOrThrow() }
        desired = true
        // a failure is the result, not the job's: it must not take the scope down with it
        val job = scope.async { runCatching { startAndWait(n) }.onFailure { if (it is CancellationException) throw it } }
        connecting = job
        try { job.await().getOrThrow() } finally { if (connecting === job) connecting = null }
    }

    private suspend fun startAndWait(n: TailnetNode) {
        val gen = generation
        val fp = fingerprint
        if (!running || runningFingerprint != fp) {
            publish(status = TailnetStatus.Connecting)
            try {
                n.start(stateDir, config.authKey.trim(), config.controlUrl.trim(), hostname)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Tailscale did not start"
                val issue = fallbackIssue(e.message, Issue.TailnetNotStarted)
                if (gen == generation) publish(status = TailnetStatus.Failed(message, issue))
                throw TunnelError(message, retry = true, issue = issue)
            }
            if (gen != generation) throw interrupted(n)
            running = true
            runningFingerprint = fp
            startMonitor(n)
        }
        try {
            n.waitConnected(WAIT_SECONDS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generation) throw interrupted(n)
            if (refreshStatus(n, gen) is TailnetStatus.NeedsKey) {
                throw TunnelError("Built-in Tailscale needs a new auth key. Enter one in Settings.", retry = false, issue = Issue.TailnetNeedsKey)
            }
            throw TunnelError(e.message ?: "Tailscale did not connect", retry = true, issue = fallbackIssue(e.message, Issue.TailnetNotConnected))
        }
        if (gen != generation) throw interrupted(n)
        refreshStatus(n, gen)
    }

    /** A stop (or a restart with other settings) overtook an attempt. If nothing wants the node any more it must not be left running by a start that finished after the stop. */
    private fun interrupted(n: TailnetNode): TunnelError {
        if (!desired) n.stop()
        return TunnelError("Built-in Tailscale was stopped", retry = true, issue = Issue.TailnetStopped)
    }

    // MARK: status

    private fun startMonitor(n: TailnetNode) {
        monitor?.cancel()
        val gen = generation
        monitor = scope.launch {
            while (gen == generation && running) {
                val s = refreshStatus(n, gen)
                delay(if (s is TailnetStatus.Connected) CONNECTED_POLL_MS else STARTING_POLL_MS)
            }
        }
    }

    private suspend fun refreshStatus(n: TailnetNode, gen: Int): TailnetStatus {
        val s = runCatching { parseTailnetStatus(n.status()) }.getOrElse { if (it is CancellationException) throw it else TailnetStatus.Failed(it.message ?: "Tailscale status failed", fallbackIssue(it.message, Issue.TailnetStatusFailed)) }
        if (gen == generation && running) publish(status = s)
        return s
    }

    companion object {
        const val DEFAULT_HOSTNAME = "leash-android"
        const val WAIT_SECONDS = 45
        const val STARTING_POLL_MS = 1_500L
        const val CONNECTED_POLL_MS = 15_000L
    }
}
