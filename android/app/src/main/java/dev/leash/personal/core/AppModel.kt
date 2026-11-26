package dev.leash.personal.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

data class AgentInfo(
    val id: String,
    val label: String,
    val state: String,
    val error: String? = null,
    val home: String? = null,
    val name: String? = null,
    val imageInput: Boolean = false,
    /** Mid-turn messages are injected into the running turn (Claude Code); otherwise the bridge queues them. */
    val steering: Boolean = false,
    /** Antigravity's "Turbo": the bridge approves every permission prompt. Claude Code has its own modes for that. */
    val turbo: Boolean = false,
    /** /btw side questions (Claude Code: a throwaway fork on its host). */
    val btw: Boolean = false,
    /** Conversations can be put in Claude Code's own /resume list on its host. */
    val cliList: Boolean = false,
) {
    val isReady: Boolean get() = state == "ready"
}

data class SessionSummary(
    val id: String,
    val agent: String = "agy",
    val cwd: String,
    val title: String?,
    val updatedAtMs: Long?,
    val running: Boolean,
    val pendingCount: Int,
    /** open in another client right now ("cli", "claude-desktop", …); such sessions can't be opened here */
    val lockedBy: String? = null,
    /** how the bridge's last turn here ended, and when */
    val lastStopReason: String? = null,
    val turnEndedAtMs: Long? = null,
) {
    /** Null for a conversation with no title yet (the UI says "Conversation" and a piece of its id, see [shortId]). */
    val displayTitle: String? get() = title?.takeIf { it.isNotEmpty() }
    val shortId: String get() = id.take(8)
    val folderName: String get() = cwd.trimEnd('/').substringAfterLast('/')
}

/** The subagent a request came from (the bridge files it under the root conversation). */
data class SubagentRef(val sessionId: String, val title: String?, val agentType: String?) {
    val label: String get() = listOfNotNull(title, agentType.takeIf { it != title }).joinToString(" · ")
}

data class PendingRequest(
    val key: String,
    val agentId: String = "agy",
    val method: String,
    val sessionId: String?,
    val params: JsonElement,
    val subagent: SubagentRef? = null,
) {
    val isPermission: Boolean get() = method == "session/request_permission"
    /** What the request is about; null when it says nothing (the UI says "Needs your input"). */
    val toolTitle: String? get() = params["toolCall"]["title"].string ?: params["message"].string
}

data class BrowseResult(val path: String, val parent: String?, val isGit: Boolean, val dirs: List<String>, val files: List<String>)

/** A conversation to bring on screen (a notification tap, a leash://session link): the root screen routes to it. */
data class OpenRequest(val sessionId: String, val id: UUID = UUID.randomUUID())

enum class Haptic { Light, Medium, Heavy, Soft, Selection, Success, Warning, Error }

enum class RemoteOutcome { Done, BridgeUnreachable, AlreadyAnswered, Locked, ChangedElsewhere, Failed }

/**
 * Seams phase 3 fills in (notifications, the foreground service, the ongoing turn notification). Everything defaults to
 * "nothing happens", which is what the demo and the unit tests want.
 */
interface AppHooks {
    /** A request arrived while Leash is off screen: alert about it; true when it did (the bridge is then told, so Bark stays quiet). */
    fun alertRequest(request: PendingRequest, agentLabel: String, conversation: String?): Boolean = false
    fun requestArrived(request: PendingRequest, alerted: Boolean) {}
    fun requestResolved(key: String) {}
    /** A turn ended while Leash is off screen; true when it alerted. */
    fun alertTurnEnd(sessionId: String, agent: String, agentLabel: String, conversation: String?, stopReason: String?, beganMs: Long?): Boolean = false
    fun turnChanged(sessionId: String, running: Boolean, stopReason: String?, startedAtMs: Long?) {}
    fun turnStarted(sessionId: String) {}
    fun turnAborted(sessionId: String) {}
    fun haptic(kind: Haptic) {}
    /** After `ready`: these are the requests still waiting (answered ones left no `resolved` frame for this client). */
    fun requestsReconciled(pendingKeys: Set<String>) {}
    /** The conversation was seen (on screen, or marked read): its "finished" notification can go. */
    fun conversationSeen(sessionId: String) {}
    /** A different bridge (or none): everything shown for the old one goes. */
    fun reset() {}
    /** Asked once, at the first turn you start; true when it was dealt with (asked, or not needed). */
    fun askNotificationPermission(): Boolean = false
}

/** Everything the screens read. Nested chat state lives in [ChatStore] (one per conversation). */
data class AppState(
    val settingsLoaded: Boolean = false,
    val host: String = "",
    val port: Int = LeashLink.DEFAULT_PORT,
    val token: String = "",
    val bridgeName: String = "",
    val useDemo: Boolean = false,
    val connection: LinkState = LinkState.Idle,
    val agentState: String = "unknown",
    val agentError: String? = null,
    val agentName: String? = null,
    val bridgeHome: String? = null,
    val imageInput: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: Problem? = null,
    val pending: Map<String, PendingRequest> = emptyMap(),
    val running: Set<String> = emptySet(),
    val lastAttention: PendingRequest? = null,
    val agents: List<AgentInfo> = emptyList(),
    val quotas: Map<String, QuotaInfo> = emptyMap(),
    val quotaErrors: Map<String, Problem> = emptyMap(),
    /** a conversation the root screen should show (consumed there) */
    val openRequest: OpenRequest? = null,
    val drafts: Map<String, String> = emptyMap(),
    val lastSeen: Map<String, Long> = emptyMap(),
    val markedUnread: Set<String> = emptySet(),
    val turboConfirmed: Boolean = false,
    val bypassConfirmed: Boolean = false,
    /** keep the connection alive in the background (foreground service with an ongoing notification) while a turn works */
    val keepAlive: Boolean = true,
    /** the notification permission was asked for (once, at the first turn) */
    val notificationsAsked: Boolean = false,
) {
    val isConfigured: Boolean get() = useDemo || (host.isNotEmpty() && token.isNotEmpty())
    val isConnected: Boolean get() = connection == LinkState.Connected
    /** at least one agent can take work */
    val agentReady: Boolean get() = isConnected && (if (agents.isEmpty()) agentState == "ready" else agents.any { it.isReady })
    fun agent(id: String): AgentInfo? = agents.firstOrNull { it.id == id }
    fun isReady(agentId: String): Boolean = isConnected && (agent(agentId)?.isReady ?: (agentId == "agy" && agentState == "ready"))
    fun steers(agentId: String) = agent(agentId)?.steering ?: false
    fun hasTurbo(agentId: String) = agent(agentId)?.turbo ?: false
    fun asksSideQuestions(agentId: String) = agent(agentId)?.btw ?: false
    fun hasCliList(agentId: String) = agent(agentId)?.cliList ?: false
    fun agentLabel(id: String): String = agent(id)?.label ?: if (id == "claude") "Claude Code" else "Antigravity"
    /** legacy single-agent accessors (Antigravity) */
    val quota: QuotaInfo? get() = quotas["agy"] ?: quotas.values.firstOrNull()
    val quotaError: Problem? get() = quotaErrors["agy"]
    /** The tightest quota pool across all agents (for the list's rings). */
    val tightestPool: QuotaPool? get() = quotas.values.mapNotNull { it.mostConstrained }.minByOrNull { it.minFraction ?: 1.0 }
    fun pendingFor(sessionId: String): List<PendingRequest> = pending.values.filter { it.sessionId == sessionId }.sortedBy { it.key }
    fun home(agent: String): String? = agent(agent)?.home ?: bridgeHome
    fun shortPath(p: String, agent: String = "agy"): String {
        val h = home(agent)
        return if (h != null && p.startsWith(h)) "~" + p.substring(h.length) else p
    }
    fun recentFolders(agent: String): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for (s in sessions) if (s.agent == agent && s.cwd.isNotEmpty() && seen.add(s.cwd)) out += s.cwd
        return out.take(12)
    }
    fun conversationTitle(sid: String?): String? = if (sid == null) null else sessions.firstOrNull { it.id == sid }?.displayTitle
}

/**
 * The app-scoped model (ios Core/AppModel.swift + AppModel+Remote.swift): connection, conversations list, pending
 * requests, one [ChatStore] per conversation. Owned by the Application so the UI and the foreground service share one
 * connection. [scope] must run on one thread (Main): everything here, and the link's callbacks, are confined to it.
 */
class AppModel(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val realLink: BridgeLink,
    /** Debug builds: the scripted demo bridge. */
    private val demoLink: (() -> BridgeLink)? = null,
    var hooks: AppHooks = object : AppHooks {},
    /** Built-in Tailscale (Settings): the bridge connection goes through the node it runs in-process. Null in tests that do not need it. */
    val tailnet: TailnetController? = null,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val s: AppState get() = _state.value
    private inline fun set(f: AppState.() -> AppState) { _state.update { it.f() } }

    var link: BridgeLink = realLink
        private set

    /** the scripted demo bridge exists in this build (debug) */
    val demoAvailable: Boolean get() = demoLink != null

    private val stores = mutableMapOf<String, ChatStore>()
    /** the conversation currently on screen (set by the chat screen) */
    var visibleSessionId: String? = null
    /** the app is in the foreground */
    var sceneActive = false
        private set
    /** when each running turn began, also for conversations without a store */
    private val turnStarts = mutableMapOf<String, Long>()
    private var unreadSinceMs = Clock.now()
    /** How long a removed queued message can be put back; longer with TalkBack on (reaching Undo takes more than a glance). */
    var queueUndoWindowMs = 4_000L
    private val persistLock = Mutex()
    /** at most this many side questions are open at once; closing one makes room */
    val maxSideQuestions = 3

    // MARK: lifecycle and settings

    /** Loads the stored settings and, when paired (or in demo), connects. */
    fun start() {
        tailnet?.start()
        scope.launch {
            val st = settings.load()
            unreadSinceMs = st.unreadSinceMs ?: Clock.now()
            set { copy(settingsLoaded = true, host = st.host, port = st.port, token = st.token, bridgeName = st.bridgeName, useDemo = st.useDemo && demoLink != null,
                drafts = st.drafts, lastSeen = st.lastSeen, markedUnread = st.markedUnread,
                turboConfirmed = st.turboConfirmed, bypassConfirmed = st.bypassConfirmed, keepAlive = st.keepAlive, notificationsAsked = st.notificationsAsked) }
            if (st.unreadSinceMs == null) persist()
            select(s.useDemo)
            if (s.isConfigured) reconfigure()
        }
    }

    /** Live state belongs to one connection (a bridge, or the demo); a different one starts clean. */
    private fun resetLive() {
        stores.clear(); turnStarts.clear(); watchers.clear()
        hooks.reset()
        set { copy(agentState = "unknown", agentError = null, agentName = null, bridgeHome = null, imageInput = false, sessions = emptyList(), sessionsLoading = false,
            sessionsError = null, pending = emptyMap(), running = emptySet(), lastAttention = null, agents = emptyList(), quotas = emptyMap(), quotaErrors = emptyMap(), openRequest = null) }
    }

    private fun persist() {
        scope.launch {
            persistLock.withLock {
                val x = s
                settings.save(Settings(x.host, x.port, x.token, x.bridgeName, x.useDemo, x.drafts, x.lastSeen, x.markedUnread, unreadSinceMs, x.turboConfirmed, x.bypassConfirmed, x.keepAlive, x.notificationsAsked))
            }
        }
    }

    private fun select(demo: Boolean) {
        val next = if (demo) demoLink?.invoke() ?: realLink else realLink
        if (next !== link) {
            link.onMessage = null; link.onState = null
            link.disconnect()
            link = next
        }
        link.onState = { st ->
            if (st is LinkState.Failed && s.connection != st) hooks.haptic(Haptic.Error)   // e.g. token rejected
            set { copy(connection = st) }
        }
        link.onMessage = { m -> handle(m) }
        link.visible = sceneActive
    }

    fun reconfigure() {
        val t = tailnet
        if (t != null) {
            t.configChanged()
            // the node runs only while Leash has a reason to be connected (on screen, or the foreground service): a connection
            // attempt is what starts it, and releaseConnection / the end of the grace stops it
            link.route = if (t.enabled) ({ host, port -> t.proxyPort(host, port) }) else null
        }
        link.configure(s.host, s.port, s.token)
        if (s.isConfigured) link.connect() else t?.warmUp()
    }

    /** Switch between the real bridge and the scripted demo bridge; live state does not carry over. */
    fun setUseDemo(on: Boolean) {
        if (demoLink == null || on == s.useDemo) return
        link.disconnect()
        resetLive()
        set { copy(useDemo = on, connection = LinkState.Idle) }
        persist()
        select(on)
        if (s.isConfigured) reconfigure()
    }

    /** Store the connection settings and connect with them. */
    fun setConnection(host: String, port: Int, token: String, name: String? = null) {
        val h = host.trim()
        link.disconnect()
        resetLive()
        set { copy(host = h, port = port, token = token.trim(), bridgeName = name?.trim()?.takeIf { it.isNotEmpty() } ?: h, useDemo = false, connection = LinkState.Idle) }
        persist()
        select(false)
        reconfigure()
    }

    // MARK: built-in Tailscale (Settings)

    fun setTailnetEnabled(on: Boolean) {
        val t = tailnet ?: return
        scope.launch { t.setEnabled(on); reconfigure() }
    }

    /** Saves the typed key (blank keeps the saved one) and control URL, and connects with them. */
    fun saveTailnet(authKey: String, controlUrl: String) {
        val t = tailnet ?: return
        scope.launch { t.saveConfig(authKey, controlUrl); reconfigure() }
    }

    fun tailnetLogout() {
        val t = tailnet ?: return
        scope.launch { t.logout(); reconfigure() }
    }

    fun pair(info: PairingInfo) = setConnection(info.host, info.port, info.token, info.name)

    /** Forget the bridge: no host, no token, disconnected. */
    fun unpair() {
        link.disconnect()
        tailnet?.stop()
        resetLive()
        set { copy(host = "", token = "", bridgeName = "", port = LeashLink.DEFAULT_PORT, useDemo = false, connection = LinkState.Idle) }
        persist()
        select(false)
    }

    /** leash://pair?… pairs; leash://session?id=…&agent=… opens that conversation; leash://open just opens the app. */
    fun open(l: LeashLink) {
        when (l) {
            is LeashLink.Pair -> pair(l.info)
            is LeashLink.Session -> {
                val st = store(l.id)
                if (l.agent != null && s.sessions.none { it.id == l.id }) st.agent = l.agent
                set { copy(openRequest = OpenRequest(l.id)) }
            }
            LeashLink.Open -> {}
        }
    }

    /** A turn this phone started: the ongoing notification follows it, and the first one asks for the notification permission. */
    private fun turnStartedHere(sessionId: String) {
        hooks.turnStarted(sessionId)
        if (!s.notificationsAsked && hooks.askNotificationPermission()) { set { copy(notificationsAsked = true) }; persist() }
    }

    fun openHandled(request: OpenRequest) { if (s.openRequest == request) set { copy(openRequest = null) } }

    /** Leash is on screen (or not): presence for the bridge, and a reconnect on the way back. */
    fun sceneChanged(active: Boolean) {
        sceneActive = active
        link.visible = active
        if (active) {
            foreground()
            visibleSessionId?.let { sid -> if (s.openRequest == null) markSeen(sid) }
        }
    }

    fun setKeepAlive(on: Boolean) {
        if (on == s.keepAlive) return
        set { copy(keepAlive = on) }
        persist()
    }

    /** The default network changed (Wi-Fi to mobile, a new Wi-Fi): the old socket is dead even if it does not know yet. */
    fun networkChanged() {
        if (!s.isConfigured || link.state == LinkState.Idle) return
        link.reconnect()
    }

    /** Nothing needs the connection any more and Leash is off screen: let go of it (the bridge then sees it gone, and Bark covers). */
    fun releaseConnection() {
        if (sceneActive || link.state == LinkState.Idle) return
        link.disconnect()
        tailnet?.stop()
        set { copy(connection = LinkState.Idle) }
    }

    /** The stored settings are loaded (a notification action can start the process before they are). */
    suspend fun awaitSettings() { _state.first { it.settingsLoaded } }

    fun foreground() {
        if (!s.isConfigured) return
        link.ensureConnected()
        if (s.isConnected) scope.launch { refreshQuota() }
    }

    // MARK: quota

    /** Asks the bridge for quota (it reuses data younger than 2 min unless forced). */
    suspend fun refreshQuota(force: Boolean = false) {
        if (!s.isConnected) return
        val ids = if (s.agents.isEmpty()) listOf("agy") else s.agents.map { it.id }
        ids.map { id -> scope.launch { refreshQuota(id, force) } }.joinAll()
    }

    suspend fun refreshQuota(agent: String, force: Boolean = false) {
        try {
            val r = link.call("bridge/quota", jsonObj("force" to force, "agent" to agent), 75_000)
            QuotaInfo.parse(r["quota"])?.let { q -> set { copy(quotas = quotas + (agent to q)) } }
            val err = r["error"].string
            set { copy(quotaErrors = if (err != null) quotaErrors + (agent to Problem(err)) else quotaErrors - agent) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            set { copy(quotaErrors = quotaErrors + (agent to e.toProblem())) }
        }
    }

    // MARK: inbound

    private fun handle(m: JsonElement) {
        when (m["op"].string) {
            "ready" -> {
                applyAgent(m["agent"])
                var agents = (m["agents"].array ?: emptyList()).mapNotNull(::agentInfo)
                var quotas = s.quotas
                for (a in m["agents"].array ?: emptyList()) {
                    val id = a["id"].string ?: continue
                    QuotaInfo.parse(a["quota"])?.let { quotas = quotas + (id to it) }
                }
                if (agents.isEmpty()) QuotaInfo.parse(m["quota"])?.let { quotas = quotas + ("agy" to it) }
                val running = (m["running"].array ?: emptyList()).mapNotNull { it.string }.toSet()
                val pend = LinkedHashMap<String, PendingRequest>()
                for (p in m["pending"].array ?: emptyList()) parsePending(p)?.let { pend[it.key] = it }
                set { copy(bridgeHome = m["bridge"]["home"].string, agents = agents, quotas = quotas, pending = pend, running = running) }
                turnStarts.keys.retainAll(running)
                hooks.requestsReconciled(pend.keys.toSet())
                refreshPendingCounts()
                scope.launch { refreshSessions() }
                scope.launch { refreshQuota() }
                for (st in stores.values.toList()) if (st.attached) scope.launch { attach(st) }
            }
            "agent" -> {
                val id = m["agentId"].string
                if (id != null) {
                    set {
                        copy(agents = agents.map { a ->
                            if (a.id != id) a else {
                                val info = m["info"]
                                a.copy(
                                    state = m["state"].string ?: a.state,
                                    error = m["error"].string,
                                    name = if (info != null && !info.isJsonNull) (info["agentInfo"]["title"].string ?: info["agentInfo"]["name"].string) else a.name,
                                    imageInput = if (info != null && !info.isJsonNull) (info["agentCapabilities"]["promptCapabilities"]["image"].bool ?: false) else a.imageInput,
                                    steering = m["steering"].bool ?: false,
                                    turbo = m["turbo"].bool ?: false,
                                    btw = m["btw"].bool ?: false,
                                    cliList = m["cliList"].bool ?: false,
                                )
                            }
                        })
                    }
                }
                if (id == null || id == "agy") applyAgent(m)
                if (s.agentState == "ready") {
                    scope.launch { refreshSessions() }
                    for (st in stores.values.toList()) if (st.attached) scope.launch { attach(st) }
                }
            }
            "update" -> {
                val sid = m["sessionId"].string ?: return
                val st = stores[sid] ?: return
                val ep = m["epoch"].string
                if (ep != null && st.epoch != null && ep != st.epoch) {
                    scope.launch { attach(st) }   // agent restarted; resync
                    return
                }
                st.ingest(m["seq"].int ?: 0, m["update"] ?: JsonNull, live = true)
            }
            "request" -> addPending(m, notify = true)
            "resolved" -> {
                m["key"].string?.let { k -> set { copy(pending = pending - k) }; hooks.requestResolved(k) }
                refreshPendingCounts()
            }
            "turn" -> handleTurn(m)
            "session_info" -> {
                val sid = m["sessionId"].string ?: return
                val t = m["title"].string
                if (t != null) {
                    set { copy(sessions = sessions.map { if (it.id == sid && it.title != t) it.copy(title = t) else it }) }
                    stores[sid]?.title = t
                }
            }
            "queue" -> m["sessionId"].string?.let { sid -> stores[sid]?.queued = QueuedMessage.list(m["items"]) }
            "shell" -> m["sessionId"].string?.let { sid -> stores[sid]?.shellRunning = m["running"].bool == true }
            "session_flags" -> m["sessionId"].string?.let { sid -> stores[sid]?.autoApprove = m["autoApprove"].bool ?: false }
            "session_locked" -> {
                // opened in Claude Code on the computer: the bridge dropped its copy; Retry reloads once it is closed there
                val sid = m["sessionId"].string ?: return
                val by = m["lockedBy"].string ?: "other"
                stores[sid]?.let { if (!it.running) { it.lockedBy = by; it.attached = false } }
                set { copy(sessions = sessions.map { if (it.id == sid && it.lockedBy != by) it.copy(lockedBy = by) else it }) }
            }
            "session_unlocked" -> {
                // closed / finished on the computer: reload the transcript if this conversation is on screen
                val sid = m["sessionId"].string ?: return
                set { copy(sessions = sessions.map { if (it.id == sid && it.lockedBy != null) it.copy(lockedBy = null) else it }) }
                val st = stores[sid]
                if (st != null && st.lockedBy != null) {
                    st.lockedBy = null
                    if (visibleSessionId == sid) scope.launch { attach(st) }
                }
            }
            "quota" -> {
                val id = m["agentId"].string ?: "agy"
                QuotaInfo.parse(m["quota"])?.let { q -> set { copy(quotas = quotas + (id to q), quotaErrors = quotaErrors - id) } }
            }
            "session_created" -> scope.launch { refreshSessions() }
            else -> {}
        }
    }

    private fun handleTurn(m: JsonElement) {
        val sid = m["sessionId"].string ?: return
        // running: a prompt is in flight; with subagents still working the turn isn't over (the list and notifications wait
        // for them), though the conversation takes a new message as usual
        val isRunning = m["running"].bool == true
        val isBusy = isRunning || (m["subagents"].int ?: 0) > 0
        val wasBusy = sid in s.running
        val began = turnStarts[sid] ?: stores[sid]?.turnStartedAtMs
        set { copy(running = if (isBusy) running + sid else running - sid) }
        if (wasBusy && !isBusy && !sceneActive) turnEndedOffScreen(sid, m["agentId"].string, m["stopReason"].string, began)
        val startedAt = m["startedAt"].long
        if (isBusy) turnStarts[sid] = startedAt ?: turnStarts[sid] ?: Clock.now() else turnStarts.remove(sid)
        hooks.turnChanged(sid, isBusy, m["stopReason"].string, startedAt)
        stores[sid]?.let { st ->
            if (startedAt != null) st.turnStartedAtMs = startedAt
            else if (isRunning && !st.running) st.turnStartedAtMs = Clock.now()
            if (isRunning && !st.running) st.lastStopReason = null
            st.running = isRunning
            if (!isRunning) st.lastStopReason = m["stopReason"].string
        }
        set {
            copy(sessions = sessions.map { row ->
                if (row.id != sid) row else {
                    var r = row.copy(running = isBusy)
                    // as session/list would say it: the unread dot appears without waiting for the next refresh
                    if (wasBusy && !isBusy) r = r.copy(turnEndedAtMs = Clock.now(), lastStopReason = m["stopReason"].string)
                    r
                }
            })
        }
        if (wasBusy && !isBusy && sceneActive && visibleSessionId == sid) markSeen(sid)
    }

    private fun agentInfo(a: JsonElement): AgentInfo? {
        val id = a["id"].string ?: return null
        val info = a["info"]
        return AgentInfo(id, a["label"].string ?: id, a["state"].string ?: "down", a["error"].string, a["home"].string,
            info["agentInfo"]["title"].string ?: info["agentInfo"]["name"].string,
            info["agentCapabilities"]["promptCapabilities"]["image"].bool ?: false,
            a["steering"].bool ?: false, a["turbo"].bool ?: false, a["btw"].bool ?: false, a["cliList"].bool ?: false)
    }

    private fun applyAgent(a: JsonElement?) {
        if (a == null) return
        val info = a["info"]
        set {
            copy(agentState = a["state"].string ?: agentState, agentError = a["error"].string,
                agentName = if (info != null && !info.isJsonNull) (info["agentInfo"]["title"].string ?: info["agentInfo"]["name"].string) else agentName,
                imageInput = if (info != null && !info.isJsonNull) (info["agentCapabilities"]["promptCapabilities"]["image"].bool ?: false) else imageInput)
        }
    }

    private fun parsePending(p: JsonElement): PendingRequest? {
        val key = p["key"].string ?: return null
        val method = p["method"].string ?: return null
        val sub = p["subagent"]
        val subId = sub["sessionId"].string
        return PendingRequest(key, p["agentId"].string ?: key.substringBefore(':'), method, p["sessionId"].string, p["params"] ?: JsonNull,
            if (subId != null) SubagentRef(subId, sub["title"].string, sub["description"].string) else null)
    }

    private fun addPending(p: JsonElement, notify: Boolean) {
        val req = parsePending(p) ?: return
        set { copy(pending = pending + (req.key to req)) }
        refreshPendingCounts()
        if (notify) {
            set { copy(lastAttention = req) }
            if (req.sessionId != visibleSessionId) hooks.haptic(Haptic.Warning)
            // off screen Leash alerts itself (and Bark stays quiet)
            val alerted = !sceneActive && hooks.alertRequest(req, s.agentLabel(req.agentId), s.conversationTitle(req.sessionId))
            if (alerted) noticed(req.key)
            hooks.requestArrived(req, alerted)
        }
    }

    /** Rows move between "Needs you" and their folder as counts change. */
    private fun refreshPendingCounts() {
        val next = withPendingCounts(s.sessions)
        if (next != s.sessions) set { copy(sessions = next) }
    }

    private fun withPendingCounts(list: List<SessionSummary>): List<SessionSummary> =
        list.map { row -> row.copy(pendingCount = s.pending.values.count { it.sessionId == row.id }) }

    // MARK: sessions

    suspend fun refreshSessions() {
        if (!s.agentReady) return
        set { copy(sessionsLoading = true) }
        try {
            val all = mutableListOf<SessionSummary>()
            var cursor: String? = null
            var pages = 0
            do {
                val params = if (cursor != null) jsonObj("cursor" to cursor) else JsonObject(emptyMap())
                val r = link.call("session/list", params)
                all += (r["sessions"].array ?: emptyList()).mapNotNull(::summary)
                cursor = r["nextCursor"].string
                pages++
            } while (cursor != null && pages < 10)
            var next = all.sortedByDescending { it.updatedAtMs ?: Long.MIN_VALUE }
            next = next.map { if (it.id in s.running) it.copy(running = true) else it }
            next = withPendingCounts(next)
            set { copy(sessions = next, sessionsError = null) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            set { copy(sessionsError = e.toProblem()) }
        } finally {
            set { copy(sessionsLoading = false) }
        }
    }

    /** Keeps [text] as the conversation's draft; blank text removes it. */
    fun setDraft(text: String, sessionId: String) {
        val next = text.takeIf { it.isNotBlank() }
        if (s.drafts[sessionId] != next) {
            set { copy(drafts = if (next == null) drafts - sessionId else drafts + (sessionId to next)) }
            persist()
        }
    }

    /** A turn the bridge ran here ended after the conversation was last on screen, or it was marked unread. */
    fun isUnread(row: SessionSummary): Boolean {
        if (row.id in s.markedUnread) return true
        val end = row.turnEndedAtMs
        if (row.running || end == null || end <= unreadSinceMs) return false
        // the bridge's clock stamps the end, the phone's the visit: a few seconds of slack
        return end - (s.lastSeen[row.id] ?: Long.MIN_VALUE / 2) > 3_000
    }

    /** The conversation is on screen (or the user marked it read). */
    fun markSeen(sessionId: String) {
        set { copy(lastSeen = lastSeen + (sessionId to Clock.now()), markedUnread = markedUnread - sessionId) }
        hooks.conversationSeen(sessionId)
        persist()
    }

    fun setUnread(sessionId: String, unread: Boolean) {
        if (unread) { set { copy(markedUnread = markedUnread + sessionId) }; persist() } else markSeen(sessionId)
    }

    /** The store for a conversation only if one exists (no side effects). */
    fun existingStore(id: String): ChatStore? = stores[id]

    fun store(id: String): ChatStore {
        stores[id]?.let { return it }
        val st = ChatStore(id)
        val sum = s.sessions.firstOrNull { it.id == id }
        if (sum != null) { st.cwd = sum.cwd; st.title = sum.title; st.agent = sum.agent }
        else s.pending.values.firstOrNull { it.sessionId == id }?.let { st.agent = it.agentId }
        st.running = id in s.running
        stores[id] = st
        return st
    }

    suspend fun attach(st: ChatStore) {
        if (!s.isReady(st.agent)) { st.attached = true; return }
        if (st.attaching) { st.reattachNeeded = true; return }
        st.attaching = true
        st.loading = st.entries.isEmpty()
        st.loadError = null   // Try Again shows progress, not the old failure
        // only a bubble that existed before this request can be judged by its result (a send during a re-attach can't)
        val echoAtStart = st.pendingEcho?.id
        try {
            val params = linkedMapOf<String, Any?>("sessionId" to st.sessionId, "sinceSeq" to st.lastSeq, "agent" to st.agent)
            st.epoch?.let { params["epoch"] = it }
            st.cwd?.let { params["cwd"] = it }
            val r = link.call("bridge/attach", j(params), 120_000)
            val events = r["events"].array ?: emptyList()
            if (r["reset"].bool == true) st.reset(events)
            else for (e in events) st.ingest(e["seq"].int ?: 0, e["update"] ?: JsonNull)   // catch-up: not live
            st.epoch = r["epoch"].string
            st.lastSeq = r["lastSeq"].int ?: st.lastSeq
            st.cwd = r["cwd"].string ?: st.cwd
            r["title"].string?.let { st.title = it }
            st.running = r["running"].bool ?: false
            if (!st.running) r["lastStopReason"].string?.let { st.lastStopReason = it }
            r["turnStartedAt"].long?.let { st.turnStartedAtMs = it }
            val busy = st.running || (r["subagents"].int ?: 0) > 0
            set { copy(running = if (busy) running + st.sessionId else running - st.sessionId) }
            st.setConfigOptions(r["configOptions"])
            st.setModes(r["modes"])
            st.setCommands(r["availableCommands"])
            st.autoApprove = r["autoApprove"].bool ?: false
            st.queued = QueuedMessage.list(r["queue"])
            for (p in r["pending"].array ?: emptyList()) addPending(p, notify = false)
            st.attached = true
            st.lockedBy = null
            // idle and the catch-up held no echo of it: the prompt never reached the bridge (connection dropped)
            if (!st.running && echoAtStart != null && st.pendingEcho?.id == echoAtStart) st.pendingEcho = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (e is BridgeError && e.code == BridgeError.LOCKED_ELSEWHERE) {
                st.lockedBy = e.holder ?: "other"
                st.attached = false
                if (echoAtStart != null && st.pendingEcho?.id == echoAtStart) st.pendingEcho = null
            } else st.loadError = e.toProblem()
        } finally {
            st.loading = false
            st.attaching = false
            val sid = st.sessionId
            val lock = st.lockedBy
            set { copy(sessions = sessions.map { if (it.id == sid && it.lockedBy != lock) it.copy(lockedBy = lock) else it }) }
            if (st.reattachNeeded) { st.reattachNeeded = false; scope.launch { attach(st) } }
        }
    }

    /** Views showing a conversation: its chat, and a subagent's transcript pushed on top of it (which hides the chat). */
    private val watchers = mutableMapOf<String, Int>()

    /** A view showing [st] appeared: it stays attached (live updates) and counts as on screen while any such view is. */
    fun watch(st: ChatStore) {
        watchers[st.sessionId] = (watchers[st.sessionId] ?: 0) + 1
        visibleSessionId = st.sessionId
        if (sceneActive) markSeen(st.sessionId)   // otherwise when the app comes forward (sceneChanged)
    }

    /**
     * The last view of [st] going away detaches it. A push from the chat to a subagent (or back) disappears one view as the
     * other appears, in either order: the short wait lets the next one claim the conversation first.
     */
    fun unwatch(st: ChatStore) {
        val n = maxOf(0, (watchers[st.sessionId] ?: 1) - 1)
        watchers[st.sessionId] = n
        if (n != 0) return
        if (sceneActive) markSeen(st.sessionId)   // a turn that ended while it was open was seen
        scope.launch {
            delay(600)
            if ((watchers[st.sessionId] ?: 0) != 0) return@launch
            if (visibleSessionId == st.sessionId) visibleSessionId = null
            detach(st)
        }
    }

    fun detach(st: ChatStore) {
        // keep the store cached (cheap) but stop live updates for sessions we left
        st.attached = false
        scope.launch { runCatching { link.call("bridge/detach", jsonObj("sessionId" to st.sessionId, "agent" to st.agent)) } }
    }

    suspend fun newSession(cwd: String, agent: String = "agy"): String {
        val r = link.call("session/new", jsonObj("cwd" to cwd, "agent" to agent), 120_000)
        val id = r["sessionId"].string ?: throw BridgeError(-3, "agent returned no session id", issue = Issue.NoSessionId)
        val st = store(id)
        st.cwd = cwd
        st.agent = agent
        st.epoch = r["epoch"].string
        st.lastSeq = r["lastSeq"].int ?: 0
        st.setConfigOptions(r["configOptions"])
        st.setModes(r["modes"])
        st.attached = true
        set { copy(sessions = listOf(SessionSummary(id, agent, cwd, null, Clock.now(), false, 0)) + sessions) }
        return id
    }

    /**
     * Mid-turn the bridge steers the message into the running turn or queues it for the turn's end; [interrupt] stops the
     * turn and sends it (with anything queued) right away. [attachments]: resource_link blocks of files already stored on
     * the agent's host (see bridge/upload).
     */
    fun send(st: ChatStore, text: String, images: List<ByteArray> = emptyList(), attachments: List<JsonElement> = emptyList(), interrupt: Boolean = false, retried: Boolean = false) {
        val blocks = mutableListOf<JsonElement>()
        if (text.isNotEmpty()) blocks += jsonObj("type" to "text", "text" to text)
        for (img in images) blocks += jsonObj("type" to "image", "mimeType" to "image/jpeg", "data" to java.util.Base64.getEncoder().encodeToString(img))
        blocks += attachments
        // what goes back into the composer if nothing was sent (attached files return as their links)
        val restore = (listOf(text) + attachments.mapNotNull(FileRef::linkText)).filter { it.isNotEmpty() }.joinToString("\n")
        if (blocks.isEmpty()) return
        val wasIdle = !st.running
        // drawn in the transcript right away; the bridge's echo of it takes its place (ChatStore)
        val echo = if (wasIdle) PendingEcho(echoItem(text, images, attachments)) else null
        val echoId = echo?.id
        if (echo != null) {
            st.pendingEcho = echo
            st.turnStartedAtMs = Clock.now()
        }
        st.running = true
        set { copy(running = running + st.sessionId) }
        if (wasIdle) turnStartedHere(st.sessionId)
        scope.launch {
            try {
                val params = linkedMapOf<String, Any?>("sessionId" to st.sessionId, "prompt" to blocks, "agent" to st.agent)
                if (interrupt) params["interrupt"] = true
                val r = link.call("session/prompt", j(params), null)
                // the bridge was mid-turn after all and queued it: the queue row shows it, not a bubble
                if (r["queued"].bool == true) dropEcho(st, echoId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val code = (e as? BridgeError)?.code
                when {
                    code == BridgeError.CLOSED -> {
                        // connection dropped; the turn keeps running on the bridge host and we resync on reconnect
                        // (the pending echo stays until the resync's echo replaces it, or attach finds the session idle)
                    }
                    code == BridgeError.LOCKED_ELSEWHERE -> {
                        // opened on the computer meanwhile: nothing was sent; the bubble goes back toward the composer
                        dropEcho(st, echoId)
                        st.running = false; set { copy(running = running - st.sessionId) }; hooks.turnAborted(st.sessionId)
                        st.lockedBy = e.holder ?: "other"
                        st.attached = false
                        st.unsentDraft = restore
                        if (images.isNotEmpty()) st.unsentImages = images
                        hooks.haptic(Haptic.Error)
                    }
                    code == BridgeError.CHANGED_ELSEWHERE && !retried -> {
                        // the conversation moved on elsewhere since we loaded it: reload the transcript, then send on top of it
                        dropEcho(st, echoId)
                        st.running = false; set { copy(running = running - st.sessionId) }
                        attach(st)
                        if (st.attached && st.lockedBy == null) {
                            send(st, text, images, attachments, interrupt, retried = true)
                        } else {
                            hooks.turnAborted(st.sessionId)
                            if (st.lockedBy != null) {
                                st.unsentDraft = restore
                                if (images.isNotEmpty()) st.unsentImages = images
                            }
                        }
                    }
                    else -> {
                        // still waiting on its echo: the bridge refused it before any turn started, so nothing was sent
                        // (after the echo, the error is the turn failing; the bridge logs that as a notice)
                        val refused = echoId == null || st.pendingEcho?.id == echoId
                        if (echoId != null && st.pendingEcho?.id == echoId) {
                            dropEcho(st, echoId)
                            if (wasIdle) { st.running = false; set { copy(running = running - st.sessionId) }; hooks.turnAborted(st.sessionId) }
                            st.unsentDraft = restore
                            if (images.isNotEmpty()) st.unsentImages = images
                        }
                        if (refused) st.composerError = e.toProblem()
                        hooks.haptic(Haptic.Error)
                    }
                }
            }
        }
    }

    /** What the bridge will echo for this prompt (text, then images, then files), drawn before it arrives. */
    private fun echoItem(text: String, images: List<ByteArray>, attachments: List<JsonElement>): MessageItem {
        val item = MessageItem(MessageItem.Role.User, text, null)
        item.synthetic = true
        item.images += images
        item.files += attachments.mapNotNull { FileRef.fromBlock(it) }
        return item
    }

    /** Removes the optimistic bubble [send] put up, unless the echo already replaced it. */
    private fun dropEcho(st: ChatStore, id: UUID?) {
        if (id != null && st.pendingEcho?.id == id) st.pendingEcho = null
    }

    /** "!" shell mode: run [command] in the conversation's folder on the agent's host (logged in Claude Code's transcript format). */
    fun runShell(st: ChatStore, command: String) {
        // phone keyboards turn quotes and dashes into typographic ones: undo that for the shell
        val cmd = command.trim().replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'').replace("—", "--")
        if (cmd.isEmpty() || st.shellRunning) return
        st.shellRunning = true
        scope.launch {
            try {
                val r = link.call("bridge/shell", jsonObj("sessionId" to st.sessionId, "command" to cmd, "agent" to st.agent), 160_000)
                val code = r["exitCode"].int
                if (code != null && code != 0) hooks.haptic(Haptic.Warning)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if ((e as? BridgeError)?.code != BridgeError.CLOSED) {   // dropped: the command still finishes on the host and shows up after the resync
                    st.composerError = e.toProblem()
                    hooks.haptic(Haptic.Error)
                }
            }
            st.shellRunning = false
        }
    }

    /** Whether Claude Code's /resume list on the computer shows this conversation (it leaves out ones started here). */
    suspend fun refreshCliListing(st: ChatStore) {
        if (!s.hasCliList(st.agent) || !s.isConnected) return
        runCatching { link.call("bridge/cliVisibility", jsonObj("sessionId" to st.sessionId, "agent" to st.agent), 25_000) }
            .getOrNull()?.let { st.listedInCli = it["visible"].bool }
    }

    /** The bridge relabels the conversation on the agent's host so Claude Code's /resume lists it. */
    suspend fun showInCli(st: ChatStore) {
        link.call("bridge/showInCli", jsonObj("sessionId" to st.sessionId, "agent" to st.agent), 25_000)
        st.listedInCli = true
    }

    /** /btw: ask about the conversation without adding to it, also mid-turn. The bridge answers only this device. */
    fun askSideQuestion(st: ChatStore, question: String) {
        val q = question.trim()
        if (q.isEmpty() || st.sideQuestions.size >= maxSideQuestions) return
        val item = SideQuestion(question = q)
        st.sideQuestions = st.sideQuestions + item
        scope.launch {
            try {
                val r = link.call("bridge/btw", jsonObj("sessionId" to st.sessionId, "question" to q, "agent" to st.agent), 190_000)
                val text = (r["text"].string ?: "").trim()
                st.resolveSideQuestion(item.id, text.ifEmpty { null }, if (text.isEmpty()) Problem("No answer came back.", Issue.NoAnswer) else null)
                hooks.haptic(if (text.isEmpty()) Haptic.Error else Haptic.Success)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                st.resolveSideQuestion(item.id, null, e.toProblem())
                hooks.haptic(Haptic.Error)
            }
        }
    }

    fun closeSideQuestion(st: ChatStore, id: UUID) { st.sideQuestions = st.sideQuestions.filter { it.id != id } }

    /** The queue's "Interrupt and Send" (mid-turn) / "Send Now" (after a Stop): send what is queued right away. */
    fun sendQueued(st: ChatStore) {
        if (st.queued.isEmpty() || !s.isReady(st.agent) || !st.attached) return
        st.forgetRemovedQueued()   // what goes out now is the queue as it stands
        val wasIdle = !st.running
        if (wasIdle) st.turnStartedAtMs = Clock.now()
        st.running = true
        set { copy(running = running + st.sessionId) }
        if (wasIdle) turnStartedHere(st.sessionId)
        scope.launch {
            try {
                link.call("session/prompt", jsonObj("sessionId" to st.sessionId, "prompt" to emptyList<Any>(), "interrupt" to true, "agent" to st.agent), null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val code = (e as? BridgeError)?.code
                when (code) {
                    BridgeError.CLOSED -> {}   // connection dropped; the bridge still sends it and we resync on reconnect
                    BridgeError.LOCKED_ELSEWHERE -> {
                        // opened on the computer meanwhile: nothing was sent, the queue stays on the bridge
                        if (wasIdle) { st.running = false; set { copy(running = running - st.sessionId) }; hooks.turnAborted(st.sessionId) }
                        st.lockedBy = e.holder ?: "other"
                        st.attached = false
                        hooks.haptic(Haptic.Error)
                    }
                    else -> {
                        // refused before any turn started (changed elsewhere, agent down, …): no turn op will clear it
                        if (wasIdle) { st.running = false; set { copy(running = running - st.sessionId) }; hooks.turnAborted(st.sessionId) }
                        // still queued: nothing went out. Once the bridge has taken the queue, the error is the turn failing,
                        // which it logs as a notice in the transcript
                        if (st.queued.isNotEmpty()) st.composerError = e.toProblem()
                        hooks.haptic(Haptic.Error)
                    }
                }
            }
        }
    }

    /** Take a queued message back from the bridge into the composer, to edit it. */
    fun unqueue(st: ChatStore, q: QueuedMessage) {
        st.queued = st.queued.filter { it.id != q.id }
        scope.launch {
            try {
                val r = link.call("bridge/unqueue", jsonObj("sessionId" to st.sessionId, "id" to q.id, "agent" to st.agent))
                val blocks = r["prompt"].array ?: emptyList()
                // attached files are already on the host: they come back as "[@name](file://…)" links (what Claude Code itself sends)
                val links = blocks.mapNotNull(FileRef::linkText)
                val text = (blocks.filter { it["type"].string == "text" }.mapNotNull { it["text"].string } + links).joinToString("\n")
                val imgs = blocks.filter { it["type"].string == "image" }.mapNotNull { it["data"].string }.mapNotNull(::decodeBase64)
                if (text.isNotEmpty()) st.unsentDraft = text
                if (imgs.isNotEmpty()) st.unsentImages = imgs
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if ((e as? BridgeError)?.code != BridgeError.ALREADY_SENT) st.composerError = e.toProblem()   // else it went out with the turn that just ended
            }
        }
    }

    /** Drop a queued message at once; until the undo window ends it can be put back where it was ([undoRemoveQueued]). */
    fun removeQueued(st: ChatStore, q: QueuedMessage) {
        val index = st.queued.indexOfFirst { it.id == q.id }.let { if (it < 0) st.queued.size else it }
        st.queued = st.queued.filter { it.id != q.id }
        val removed = RemovedQueued(q, index)
        st.removedQueued = removed
        st.removedPrompt = scope.async {
            try {
                return@async link.call("bridge/unqueue", jsonObj("sessionId" to st.sessionId, "id" to q.id, "agent" to st.agent)).get("prompt").array
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // already sent: it went out with the turn that just ended, nothing to put back
                if ((e as? BridgeError)?.code != BridgeError.ALREADY_SENT) st.composerError = e.toProblem()
            }
            if (st.removedQueued?.token == removed.token) st.forgetRemovedQueued()
            null
        }
        scope.launch {
            delay(queueUndoWindowMs)
            if (st.removedQueued?.token == removed.token) st.forgetRemovedQueued()
        }
    }

    /**
     * Undo of [removeQueued]: the bridge puts the message back at its old place under its old id, so the row returns where
     * it was. Undo can come before the removal's answer; it waits for the content.
     */
    fun undoRemoveQueued(st: ChatStore) {
        val removed = st.removedQueued ?: return
        val taken = st.removedPrompt ?: return
        st.forgetRemovedQueued()
        val q = removed.message
        if (st.queued.none { it.id == q.id }) st.queued = st.queued.toMutableList().also { it.add(minOf(removed.index, it.size), q) }
        scope.launch {
            val blocks = taken.await()
            if (blocks.isNullOrEmpty()) {
                // it had already gone out (or the removal failed): nothing to put back
                st.queued = st.queued.filter { it.id != q.id }
                return@launch
            }
            try {
                link.call("bridge/requeue", jsonObj("sessionId" to st.sessionId, "id" to q.id, "prompt" to blocks, "index" to removed.index, "agent" to st.agent))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                st.queued = st.queued.filter { it.id != q.id }
                st.composerError = e.toProblem()
                hooks.haptic(Haptic.Error)
            }
        }
    }

    fun cancel(st: ChatStore) {
        scope.launch { runCatching { link.call("session/cancel", jsonObj("sessionId" to st.sessionId, "agent" to st.agent)) } }
    }

    fun setConfig(st: ChatStore, option: ConfigOption, value: JsonElement) {
        st.setLocalConfigValue(option.id, value)
        scope.launch {
            val params = linkedMapOf<String, Any?>("sessionId" to st.sessionId, "configId" to option.id, "value" to value, "agent" to st.agent)
            if (option.type == "boolean") params["type"] = "boolean"
            try {
                val r = link.call("session/set_config_option", j(params))
                st.setConfigOptions(r["configOptions"])
            } catch (e: CancellationException) { throw e } catch (e: Exception) { st.composerError = e.toProblem() }
        }
    }

    /**
     * Pick one of the agent's own modes, or (Antigravity only) "turbo": agy's auto mode plus bridge-side auto-approval of
     * every permission prompt.
     */
    /** Whether [mode] approves everything and has not been confirmed yet: the UI asks once before it is turned on. */
    fun needsApproveAllConfirmation(mode: String): Boolean =
        approvesAll(mode) && !(if (mode == "bypassPermissions") s.bypassConfirmed else s.turboConfirmed)

    /** The user confirmed [mode] (see [needsApproveAllConfirmation]); it is not asked again. */
    fun confirmApproveAll(mode: String) {
        set { if (mode == "bypassPermissions") copy(bypassConfirmed = true) else copy(turboConfirmed = true) }
        persist()
    }

    fun choose(mode: String, st: ChatStore) {
        val turbo = mode == "turbo" && s.hasTurbo(st.agent)
        if (turbo != st.autoApprove) {
            st.autoApprove = turbo
            scope.launch { runCatching { link.call("bridge/setAutoApprove", jsonObj("sessionId" to st.sessionId, "enabled" to turbo, "agent" to st.agent)) } }
        }
        val target = if (turbo) "auto" else mode
        val opt = st.modeOption
        if (opt != null) {
            if (opt.current.string != target && opt.choices.any { it.value == target }) setConfig(st, opt, j(target))
        } else if (st.currentMode != target && st.modes.any { it.id == target }) {
            setMode(st, target)
        }
        hooks.haptic(if (turbo || approvesAll(mode)) Haptic.Heavy else Haptic.Selection)
    }

    fun setMode(st: ChatStore, mode: String) {
        scope.launch {
            try {
                link.call("session/set_mode", jsonObj("sessionId" to st.sessionId, "modeId" to mode, "agent" to st.agent))
                st.currentMode = mode
            } catch (e: CancellationException) { throw e } catch (e: Exception) { st.composerError = e.toProblem() }
        }
    }

    fun answerPermission(p: PendingRequest, optionId: String?, kind: String = "") {
        val outcome = if (optionId != null) jsonObj("outcome" to "selected", "optionId" to optionId) else jsonObj("outcome" to "cancelled")
        try {
            link.reply(p.key, jsonObj("outcome" to outcome))
            set { copy(pending = pending - p.key) }
            refreshPendingCounts()
        } catch (e: BridgeError) { hooks.haptic(Haptic.Error); return }
        hooks.haptic(when (kind) {
            "allow_once" -> Haptic.Medium
            "allow_always" -> Haptic.Heavy
            "reject_once", "reject_always" -> Haptic.Soft
            else -> Haptic.Light
        })
    }

    fun answerElicitation(p: PendingRequest, action: String, content: Map<String, JsonElement>? = null) {
        val r = linkedMapOf<String, Any?>("action" to action)
        if (content != null) r["content"] = content
        try {
            link.reply(p.key, j(r))
            set { copy(pending = pending - p.key) }
            refreshPendingCounts()
        } catch (e: BridgeError) { hooks.haptic(Haptic.Error); return }
        hooks.haptic(if (action == "accept") Haptic.Medium else Haptic.Soft)
    }

    // MARK: files

    suspend fun browse(path: String?, agent: String = "agy"): BrowseResult {
        val params = linkedMapOf<String, Any?>("agent" to agent)
        if (path != null) params["path"] = path
        val r = link.call("bridge/browse", j(params))
        return BrowseResult(r["path"].string ?: "", r["parent"].string, r["isGit"].bool ?: false,
            (r["dirs"].array ?: emptyList()).mapNotNull { it.string }, (r["files"].array ?: emptyList()).mapNotNull { it.string })
    }

    /** (text, truncated, binary) */
    suspend fun readFile(path: String, agent: String = "agy"): Triple<String?, Boolean, Boolean> {
        val r = link.call("bridge/readFile", jsonObj("path" to path, "agent" to agent), 60_000)
        return Triple(r["text"].string, r["truncated"].bool ?: false, r["binary"].bool ?: false)
    }

    fun restartAgent(id: String = "agy") {
        scope.launch { runCatching { link.call("bridge/restartAgent", jsonObj("agent" to id)) } }
    }

    // MARK: remote (notification actions, links)

    /** Connected and ready (pending requests and running turns are then current), within [timeoutMs]. */
    suspend fun awaitConnection(timeoutMs: Long = 8_000): Boolean {
        if (!s.isConfigured) return false
        if (s.isConnected) return true
        link.ensureConnected()
        var waited = 0L
        while (waited < timeoutMs) {
            delay(100); waited += 100
            if (s.isConnected) return true
            if (s.connection is LinkState.Failed) return false
        }
        return s.isConnected
    }

    /** Answer a permission request and wait for the bridge to confirm it. */
    suspend fun answer(key: String, optionId: String?): RemoteOutcome {
        if (!awaitConnection()) return RemoteOutcome.BridgeUnreachable
        if (key !in s.pending) return RemoteOutcome.AlreadyAnswered   // ready just refreshed the list
        val outcome = if (optionId != null) jsonObj("outcome" to "selected", "optionId" to optionId) else jsonObj("outcome" to "cancelled")
        return try {
            link.replyConfirmed(key, jsonObj("outcome" to outcome))
            RemoteOutcome.Done   // the bridge's resolved broadcast removes it here too
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val code = (e as? BridgeError)?.code
            when (code) {
                BridgeError.ALREADY_RESOLVED -> { set { copy(pending = pending - key) }; RemoteOutcome.AlreadyAnswered }
                BridgeError.CLOSED, BridgeError.TIMEOUT -> RemoteOutcome.BridgeUnreachable
                else -> RemoteOutcome.Failed
            }
        }
    }

    /** Tell the bridge we alerted about this request ourselves, so Bark doesn't push it too. */
    fun noticed(key: String) {
        val p = s.pending[key] ?: return
        val params = linkedMapOf<String, Any?>("key" to key, "agent" to p.agentId)
        p.sessionId?.let { params["sessionId"] = it }
        scope.launch { runCatching { link.call("bridge/noticed", j(params), 10_000) } }
    }

    /** A turn ended while Leash runs off screen: say so ourselves and tell the bridge, so Bark doesn't too. */
    private fun turnEndedOffScreen(sid: String, agent: String?, stopReason: String?, began: Long?) {
        val a = agent ?: stores[sid]?.agent ?: s.sessions.firstOrNull { it.id == sid }?.agent ?: "agy"
        if (!hooks.alertTurnEnd(sid, a, s.agentLabel(a), s.conversationTitle(sid) ?: stores[sid]?.title, stopReason, began)) return
        scope.launch { runCatching { link.call("bridge/noticed", jsonObj("agent" to a, "sessionId" to sid, "turnEnd" to true), 10_000) } }
    }

    companion object {
        /** Modes that run everything without asking: agy's Turbo, Claude Code's Bypass permissions. */
        fun approvesAll(id: String) = id == "turbo" || id == "bypassPermissions"

        fun summary(j: JsonElement): SessionSummary? {
            val id = j["sessionId"].string ?: return null
            val l = j["_leash"]
            return SessionSummary(
                id = id, agent = l["agent"].string ?: "agy", cwd = j["cwd"].string ?: "", title = j["title"].string,
                updatedAtMs = parseIsoMillis(j["updatedAt"].string),
                running = l["running"].bool == true || (l["subagents"].int ?: 0) > 0,
                pendingCount = l["pending"].int ?: 0,
                lockedBy = l["lockedBy"].string,
                lastStopReason = l["stopReason"].string,
                turnEndedAtMs = l["turnEndedAt"].long,
            )
        }
    }
}
