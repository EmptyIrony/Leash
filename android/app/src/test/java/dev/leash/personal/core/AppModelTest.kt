package dev.leash.personal.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTest {
    private class Hooks : AppHooks {
        val haptics = mutableListOf<Haptic>()
        val requests = mutableListOf<Pair<String, Boolean>>()
        val resolved = mutableListOf<String>()
        val turns = mutableListOf<Triple<String, Boolean, String?>>()
        val started = mutableListOf<String>()
        val aborted = mutableListOf<String>()
        var alertRequests = false
        var alertTurnEnds = false
        val turnAlerts = mutableListOf<String>()
        override fun alertRequest(request: PendingRequest, agentLabel: String, conversation: String?) = alertRequests
        override fun requestArrived(request: PendingRequest, alerted: Boolean) { requests += request.key to alerted }
        override fun requestResolved(key: String) { resolved += key }
        override fun alertTurnEnd(sessionId: String, agent: String, agentLabel: String, conversation: String?, stopReason: String?, beganMs: Long?): Boolean {
            turnAlerts += sessionId; return alertTurnEnds
        }
        override fun turnChanged(sessionId: String, running: Boolean, stopReason: String?, startedAtMs: Long?) { turns += Triple(sessionId, running, stopReason) }
        override fun turnStarted(sessionId: String) { started += sessionId }
        override fun turnAborted(sessionId: String) { aborted += sessionId }
        override fun haptic(kind: Haptic) { haptics += kind }
        val reconciled = mutableListOf<Set<String>>()
        val seen = mutableListOf<String>()
        var resets = 0
        var asks = 0
        var askDealtWith = false
        override fun requestsReconciled(pendingKeys: Set<String>) { reconciled += pendingKeys }
        override fun conversationSeen(sessionId: String) { seen += sessionId }
        override fun reset() { resets++ }
        override fun askNotificationPermission(): Boolean { asks++; return askDealtWith }
    }

    private class Rig(scope: TestScope, val settings: InMemorySettingsStore = InMemorySettingsStore(Settings(host = "bridge.ts.net", token = "tok")), demo: FakeLink? = null) {
        val link = FakeLink()
        val hooks = Hooks()
        val model = AppModel(scope.backgroundScope, settings, link, demo?.let { d -> { d } }, hooks)
        val state get() = model.state.value
        init {
            link.on("session/list") { jsonObj("sessions" to emptyList<Any>()) }
            link.on("bridge/quota") { jsonObj("quota" to null) }
        }
    }

    @Before fun pinClock() { Clock.now = { 1_000_000L } }
    @After fun unpinClock() { Clock.now = { System.currentTimeMillis() } }

    private fun readyFrame(pending: List<JsonElement> = emptyList(), running: List<String> = emptyList()) = jsonObj(
        "op" to "ready", "bridge" to jsonObj("home" to "/Users/me", "version" to "1"), "agent" to jsonObj("state" to "ready"),
        "agents" to listOf(
            jsonObj("id" to "agy", "label" to "Antigravity", "state" to "ready", "home" to "/Users/me", "turbo" to true,
                "info" to jsonObj("agentInfo" to jsonObj("title" to "Antigravity CLI"), "agentCapabilities" to jsonObj("promptCapabilities" to jsonObj("image" to true))),
                "quota" to jsonObj("pools" to listOf(jsonObj("id" to "gemini", "label" to "Gemini", "buckets" to listOf(jsonObj("id" to "b", "window" to "5h", "remainingFraction" to 0.4)))))),
            jsonObj("id" to "claude", "label" to "Claude Code", "state" to "ready", "steering" to true, "btw" to true, "cliList" to true, "home" to "/home/me")),
        "pending" to pending, "running" to running,
    )

    private fun permission(key: String, sid: String, subagent: JsonElement? = null) = jsonObj(
        "op" to "request", "agentId" to "claude", "key" to key, "method" to "session/request_permission", "sessionId" to sid, "subagent" to subagent,
        "params" to jsonObj("toolCall" to jsonObj("toolCallId" to "t1", "title" to "Edit README.md"),
            "options" to listOf(jsonObj("optionId" to "allow", "name" to "Allow", "kind" to "allow_once"), jsonObj("optionId" to "deny", "name" to "Deny", "kind" to "reject_once"))))

    private suspend fun TestScope.connect(r: Rig, ready: JsonElement = readyFrame()) {
        r.model.start(); runCurrent()
        r.link.set(LinkState.Connected)
        r.link.emit(ready); runCurrent()
    }

    private fun session(id: String, agent: String = "claude", title: String? = "T $id", updatedAt: String = "2026-11-23T01:00:00Z", extra: Map<String, Any?> = emptyMap()) =
        jsonObj("sessionId" to id, "cwd" to "/Users/me/proj", "title" to title, "updatedAt" to updatedAt,
            "_leash" to jsonObj("agent" to agent, "running" to false, "subagents" to 0, "pending" to 0, "lockedBy" to null, "stopReason" to null, "turnEndedAt" to null, *extra.toList().toTypedArray()))

    // MARK: settings, links, lifecycle

    @Test fun startLoadsStoredSettingsAndConnectsWhenPaired() = runTest {
        val r = Rig(this)
        r.model.start(); runCurrent()
        assertTrue(r.state.settingsLoaded)
        assertEquals("bridge.ts.net", r.state.host)
        assertEquals(listOf(Triple("bridge.ts.net", 8787, "tok")), r.link.configured)
        assertEquals(1, r.link.connects)
        // a first launch stamps the unread baseline
        assertNotNull(r.settings.value.unreadSinceMs)
    }

    @Test fun startUnpairedDoesNotConnectAndSaysSo() = runTest {
        val r = Rig(this, InMemorySettingsStore())
        r.model.start(); runCurrent()
        assertEquals(0, r.link.connects)
        assertEquals(LinkState.Idle, r.state.connection)
        assertFalse(r.state.isConfigured)
    }

    @Test fun pairingStoresAndConnects() = runTest {
        val r = Rig(this, InMemorySettingsStore())
        r.model.start(); runCurrent()
        r.model.open(LeashLink.parse("leash://pair?host=h.ts.net&port=9001&token=abc&name=Studio")!!)
        runCurrent()
        assertEquals(listOf(Triple("h.ts.net", 9001, "abc")), r.link.configured)
        assertEquals(1, r.link.connects)
        assertEquals("Studio", r.state.bridgeName)
        assertEquals("abc", r.settings.value.token)
        assertEquals(9001, r.settings.value.port)
        r.model.unpair(); runCurrent()
        assertEquals("", r.settings.value.token)
        assertFalse(r.state.isConfigured)
    }

    @Test fun pairingAnotherBridgeDropsTheOldLiveState() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { jsonObj("sessions" to listOf(session("a"))) }
        connect(r, readyFrame(pending = listOf(permission("claude:1", "a")), running = listOf("a")))
        r.model.store("a")
        assertEquals(1, r.state.sessions.size)
        r.model.pair(PairingInfo("other.ts.net", 8787, "t2", "Other"))
        assertTrue(r.state.sessions.isEmpty() && r.state.pending.isEmpty() && r.state.running.isEmpty() && r.state.agents.isEmpty())
        assertNull(r.model.existingStore("a"))
    }

    @Test fun sessionLinkCreatesTheStoreAndAnOpenRequest() = runTest {
        val r = Rig(this)
        r.model.start(); runCurrent()
        r.model.open(LeashLink.Session("abc", "claude"))
        val req = r.state.openRequest!!
        assertEquals("abc", req.sessionId)
        assertEquals("claude", r.model.existingStore("abc")!!.agent)
        r.model.openHandled(req)
        assertNull(r.state.openRequest)
    }

    @Test fun presenceFollowsTheScene() = runTest {
        val r = Rig(this)
        r.model.start(); runCurrent()
        r.model.sceneChanged(true)
        assertTrue(r.link.visible)
        assertEquals(1, r.link.ensures)
        r.model.sceneChanged(false)
        assertFalse(r.link.visible)
    }

    @Test fun demoSwitchSwapsTheLinkAndResetsLiveState() = runTest {
        val demo = FakeLink()
        demo.on("session/list") { jsonObj("sessions" to emptyList<Any>()) }
        val r = Rig(this, demo = demo)
        connect(r, readyFrame(running = listOf("x")))
        assertEquals(setOf("x"), r.state.running)
        r.model.setUseDemo(true); runCurrent()
        assertTrue(r.state.useDemo)
        assertTrue(r.state.running.isEmpty())
        assertTrue(r.link.disconnects >= 1)
        assertEquals(1, demo.connects)
        assertTrue(r.settings.value.useDemo)
        // frames now come from the demo link only
        demo.set(LinkState.Connected)
        demo.emit(readyFrame(running = listOf("y"))); runCurrent()
        assertEquals(setOf("y"), r.state.running)
        r.link.emit(readyFrame(running = listOf("stale"))); runCurrent()
        assertEquals(setOf("y"), r.state.running)
        r.model.setUseDemo(false); runCurrent()
        assertEquals(2, r.link.connects)
    }

    // MARK: ready, sessions, requests, turns

    @Test fun readyBuildsAgentsQuotaPendingAndRunningThenRefreshes() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { jsonObj("sessions" to listOf(session("a", updatedAt = "2026-11-22T00:00:00Z"), session("b", agent = "agy", updatedAt = "2026-11-23T00:00:00Z", title = null), session("c", updatedAt = "2026-11-21T00:00:00Z"))) }
        connect(r, readyFrame(pending = listOf(permission("claude:1", "a")), running = listOf("c")))
        val s = r.state
        assertEquals("/Users/me", s.bridgeHome)
        assertEquals(listOf("agy", "claude"), s.agents.map { it.id })
        assertEquals("Antigravity CLI", s.agents[0].name)
        assertTrue(s.agents[0].imageInput && s.agents[0].turbo)
        assertTrue(s.steers("claude") && s.asksSideQuestions("claude") && s.hasCliList("claude"))
        assertEquals(0.4, s.quotas["agy"]!!.pools[0].buckets[0].fraction!!, 0.0)
        assertTrue(s.agentReady)
        assertEquals(listOf("b", "a", "c"), s.sessions.map { it.id })   // newest first
        assertNull(s.sessions[0].displayTitle)   // no title yet: the UI says "Conversation b"
        assertEquals("b", s.sessions[0].shortId)
        assertEquals(1, s.sessions.first { it.id == "a" }.pendingCount)
        assertTrue(s.sessions.first { it.id == "c" }.running)
        assertEquals("proj", s.sessions[0].folderName)
        assertEquals(setOf("agy", "claude"), r.link.callsOf("bridge/quota").map { it["agent"].string }.toSet())
        assertEquals("~/proj", s.shortPath("/Users/me/proj"))
        assertEquals(listOf("/Users/me/proj"), s.recentFolders("claude"))
    }

    @Test fun listSessionsFollowsCursorPages() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { p -> if (p["cursor"].string == null) jsonObj("sessions" to listOf(session("a")), "nextCursor" to "c2") else jsonObj("sessions" to listOf(session("b", updatedAt = "2026-11-24T00:00:00Z"))) }
        connect(r)
        assertEquals(listOf("b", "a"), r.state.sessions.map { it.id })
        assertEquals(listOf(null, "c2"), r.link.callsOf("session/list").map { it["cursor"].string })
    }

    @Test fun aListFailureIsRecordedNotThrown() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { throw BridgeError(-32603, "boom") }
        connect(r)
        assertEquals(Problem("boom"), r.state.sessionsError)
        assertFalse(r.state.sessionsLoading)
    }

    @Test fun requestsAreTrackedCountedAndResolved() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { jsonObj("sessions" to listOf(session("a"))) }
        connect(r)
        r.model.sceneChanged(true); runCurrent()
        r.link.emit(permission("claude:7", "a", jsonObj("sessionId" to "child", "title" to "Explore", "description" to "Explore")))
        val p = r.state.pending["claude:7"]!!
        assertTrue(p.isPermission)
        assertEquals("Edit README.md", p.toolTitle)
        assertEquals("claude", p.agentId)
        assertEquals("Explore", p.subagent!!.label)
        assertEquals(1, r.state.sessions[0].pendingCount)
        assertEquals(listOf("claude:7" to false), r.hooks.requests)
        r.link.emit(jsonObj("op" to "resolved", "key" to "claude:7", "sessionId" to "a"))
        assertTrue(r.state.pending.isEmpty())
        assertEquals(0, r.state.sessions[0].pendingCount)
        assertEquals(listOf("claude:7"), r.hooks.resolved)
    }

    @Test fun anAgentIdIsDerivedFromTheKeyWhenAbsent() = runTest {
        val r = Rig(this)
        connect(r)
        r.link.emit(jsonObj("op" to "request", "key" to "agy:3", "method" to "elicitation/create", "sessionId" to "z", "params" to jsonObj("message" to "Which?")))
        val p = r.state.pending["agy:3"]!!
        assertEquals("agy", p.agentId)
        assertFalse(p.isPermission)
        assertEquals("Which?", p.toolTitle)
    }

    @Test fun anOffScreenRequestAlertsAndTellsTheBridgeSoBarkStaysQuiet() = runTest {
        val r = Rig(this)
        r.hooks.alertRequests = true
        connect(r)
        r.link.emit(permission("claude:9", "a")); runCurrent()
        assertEquals(listOf("claude:9" to true), r.hooks.requests)
        val n = r.link.callsOf("bridge/noticed").single()
        assertEquals("claude:9", n["key"].string)
        assertEquals("a", n["sessionId"].string)
        assertEquals("claude", n["agent"].string)
        assertTrue(Haptic.Warning in r.hooks.haptics)
    }

    @Test fun anOnScreenRequestIsNotAlertedAndTheConversationOnScreenIsQuiet() = runTest {
        val r = Rig(this)
        r.hooks.alertRequests = true
        connect(r)
        r.model.sceneChanged(true); runCurrent()
        r.model.visibleSessionId = "a"
        r.link.emit(permission("claude:9", "a")); runCurrent()
        assertEquals(listOf("claude:9" to false), r.hooks.requests)
        assertTrue(r.link.callsOf("bridge/noticed").isEmpty())
        assertFalse(Haptic.Warning in r.hooks.haptics)
    }

    @Test fun turnFramesMoveRunningStateAndTheListRow() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { jsonObj("sessions" to listOf(session("a"))) }
        connect(r)
        val st = r.model.store("a")
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to true, "subagents" to 0, "startedAt" to 5000))
        assertTrue("a" in r.state.running && st.running)
        assertEquals(5000L, st.turnStartedAtMs)
        assertTrue(r.state.sessions[0].running)
        // idle but a background subagent still works: the turn is not over for the list, the conversation takes input
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to false, "subagents" to 1))
        assertTrue("a" in r.state.running)
        assertFalse(st.running)
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to false, "subagents" to 0, "stopReason" to "end_turn"))
        assertFalse("a" in r.state.running)
        assertEquals("end_turn", st.lastStopReason)
        assertEquals("end_turn", r.state.sessions[0].lastStopReason)
        assertEquals(1_000_000L, r.state.sessions[0].turnEndedAtMs)
        assertEquals(listOf(true, true, false), r.hooks.turns.map { it.second })
    }

    @Test fun aTurnEndingOffScreenAlertsAndNoticesTheBridge() = runTest {
        val r = Rig(this)
        r.hooks.alertTurnEnds = true
        connect(r)
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to true))
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to false, "stopReason" to "end_turn", "agentId" to "claude")); runCurrent()
        assertEquals(listOf("a"), r.hooks.turnAlerts)
        val n = r.link.callsOf("bridge/noticed").single()
        assertEquals(true, n["turnEnd"].bool)
        assertEquals("claude", n["agent"].string)
        // on screen: no alert
        r.model.sceneChanged(true)
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to true))
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to false, "stopReason" to "end_turn")); runCurrent()
        assertEquals(1, r.hooks.turnAlerts.size)
    }

    @Test fun queueShellFlagsTitleAndLockFramesReachTheStoreAndList() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { jsonObj("sessions" to listOf(session("a"))) }
        connect(r)
        val st = r.model.store("a")
        st.attached = true
        r.link.emit(jsonObj("op" to "queue", "sessionId" to "a", "items" to listOf(jsonObj("id" to "claude:q1", "text" to "next", "imageCount" to 0))))
        assertEquals(listOf(QueuedMessage("claude:q1", "next", 0)), st.queued)
        r.link.emit(jsonObj("op" to "shell", "sessionId" to "a", "running" to true))
        assertTrue(st.shellRunning)
        r.link.emit(jsonObj("op" to "session_flags", "sessionId" to "a", "autoApprove" to true))
        assertTrue(st.autoApprove)
        r.link.emit(jsonObj("op" to "session_info", "sessionId" to "a", "title" to "Renamed"))
        assertEquals("Renamed", st.title)
        assertEquals("Renamed", r.state.sessions[0].title)
        r.link.emit(jsonObj("op" to "session_locked", "sessionId" to "a", "lockedBy" to "cli"))
        assertEquals("cli", st.lockedBy)
        assertFalse(st.attached)
        assertEquals("cli", r.state.sessions[0].lockedBy)
        // unlocked while on screen: reloads
        r.link.on("bridge/attach") { jsonObj("epoch" to "e1", "lastSeq" to 0, "reset" to true, "events" to emptyList<Any>()) }
        r.model.visibleSessionId = "a"
        r.link.emit(jsonObj("op" to "session_unlocked", "sessionId" to "a")); runCurrent()
        assertNull(st.lockedBy)
        assertNull(r.state.sessions[0].lockedBy)
        assertEquals(1, r.link.callsOf("bridge/attach").size)
    }

    @Test fun quotaFramesAndAgentFramesUpdateState() = runTest {
        val r = Rig(this)
        connect(r)
        r.link.emit(jsonObj("op" to "quota", "agentId" to "claude", "quota" to jsonObj("pools" to listOf(jsonObj("id" to "3p", "buckets" to listOf(jsonObj("id" to "x", "window" to "weekly", "remainingFraction" to 0.1)))))))
        assertEquals("3p", r.state.tightestPool!!.id)
        r.link.emit(jsonObj("op" to "agent", "agentId" to "claude", "state" to "down", "error" to "ssh failed"))
        assertEquals("down", r.state.agent("claude")!!.state)
        assertEquals("ssh failed", r.state.agent("claude")!!.error)
        assertFalse(r.state.isReady("claude"))
        assertTrue(r.state.isReady("agy"))
    }

    @Test fun unreadFollowsTurnEndsAndVisits() = runTest {
        val r = Rig(this, InMemorySettingsStore(Settings(host = "h", token = "t", unreadSinceMs = 500_000)))
        r.link.on("session/list") { jsonObj("sessions" to listOf(session("a", extra = mapOf("turnEndedAt" to 900_000L)), session("b", extra = mapOf("turnEndedAt" to 100_000L)))) }
        connect(r)
        val a = r.state.sessions.first { it.id == "a" }
        val b = r.state.sessions.first { it.id == "b" }
        assertTrue(r.model.isUnread(a))
        assertFalse(r.model.isUnread(b))   // ended before the first launch
        r.model.markSeen("a")
        assertFalse(r.model.isUnread(a))
        r.model.setUnread("b", true)
        assertTrue(r.model.isUnread(b))
        runCurrent()
        assertEquals(setOf("b"), r.settings.value.markedUnread)
        r.model.setDraft("hello", "a"); runCurrent()
        assertEquals("hello", r.settings.value.drafts["a"])
        r.model.setDraft("  ", "a"); runCurrent()
        assertTrue(r.settings.value.drafts.isEmpty())
    }

    // MARK: attach

    private fun attachResult(events: List<JsonElement> = emptyList(), reset: Boolean = true, running: Boolean = false, extra: Map<String, Any?> = emptyMap()) = jsonObj(
        "sessionId" to "a", "cwd" to "/Users/me/proj", "epoch" to "e1", "lastSeq" to events.size, "reset" to reset, "events" to events,
        "running" to running, "subagents" to 0, "lastStopReason" to "end_turn", "title" to "Attached", "autoApprove" to false,
        "queue" to listOf(jsonObj("id" to "claude:q1", "text" to "queued", "imageCount" to 0, "fileCount" to 1)),
        "configOptions" to listOf(jsonObj("id" to "model", "category" to "model", "name" to "Model", "currentValue" to "m1", "options" to listOf(jsonObj("value" to "m1", "name" to "M1")))),
        "modes" to jsonObj("currentModeId" to "default", "availableModes" to listOf(jsonObj("id" to "default", "name" to "Manual"))),
        "availableCommands" to listOf(jsonObj("name" to "review", "description" to "r")),
        "pending" to listOf(permission("claude:5", "a")),
        *extra.toList().toTypedArray())

    @Test fun attachLoadsTheTranscriptAndSessionState() = runTest {
        val r = Rig(this)
        r.link.on("bridge/attach") { attachResult(listOf(
            jsonObj("seq" to 1, "update" to jsonObj("sessionUpdate" to "user_message_chunk", "content" to jsonObj("type" to "text", "text" to "hi"), "messageId" to "u1", "_meta" to jsonObj("leash" to jsonObj("synthetic" to true)))),
            jsonObj("seq" to 2, "update" to jsonObj("sessionUpdate" to "agent_message_chunk", "content" to jsonObj("type" to "text", "text" to "hello"), "messageId" to "m1")))) }
        connect(r)
        val st = r.model.store("a")
        st.cwd = "/Users/me/proj"
        r.model.attach(st)
        assertEquals(2, st.entries.size)
        assertEquals("e1", st.epoch)
        assertEquals(2, st.lastSeq)
        assertEquals("Attached", st.title)
        assertTrue(st.attached)
        assertFalse(st.loading)
        assertEquals("M1", st.modelOption!!.currentName)
        assertEquals("default", st.currentMode)
        assertEquals(listOf("review"), st.commands.map { it.name })
        assertEquals(listOf(QueuedMessage("claude:q1", "queued", 0, 1)), st.queued)
        assertTrue("claude:5" in r.state.pending)
        val sent = r.link.callsOf("bridge/attach").single()
        assertEquals(0, sent["sinceSeq"].int)
        assertEquals("agy", sent["agent"].string)
        assertEquals("/Users/me/proj", sent["cwd"].string)
    }

    @Test fun reattachAsksOnlyForMissedEventsAndAppliesThemWithoutReset() = runTest {
        val r = Rig(this)
        var n = 0
        r.link.on("bridge/attach") {
            if (n++ == 0) attachResult(listOf(jsonObj("seq" to 1, "update" to jsonObj("sessionUpdate" to "agent_message_chunk", "content" to jsonObj("type" to "text", "text" to "a"), "messageId" to "m"))))
            else attachResult(listOf(jsonObj("seq" to 2, "update" to jsonObj("sessionUpdate" to "agent_message_chunk", "content" to jsonObj("type" to "text", "text" to "b"), "messageId" to "m"))), reset = false, extra = mapOf("lastSeq" to 2))
        }
        connect(r)
        val st = r.model.store("a")
        r.model.attach(st)
        r.model.attach(st)
        assertEquals("ab", (st.entries.single() as ChatEntry.Message).item.text)
        val second = r.link.callsOf("bridge/attach")[1]
        assertEquals(1, second["sinceSeq"].int)
        assertEquals("e1", second["epoch"].string)
    }

    @Test fun anAttachLockedElsewhereRecordsTheHolder() = runTest {
        val r = Rig(this)
        r.link.on("session/list") { jsonObj("sessions" to listOf(session("a"))) }
        r.link.on("bridge/attach") { throw BridgeError(BridgeError.LOCKED_ELSEWHERE, "open elsewhere", "claude-desktop") }
        connect(r)
        val st = r.model.store("a")
        r.model.attach(st)
        assertEquals("claude-desktop", st.lockedBy)
        assertFalse(st.attached)
        assertNull(st.loadError)
        assertEquals("claude-desktop", r.state.sessions[0].lockedBy)
    }

    @Test fun anAttachFailureSetsLoadError() = runTest {
        val r = Rig(this)
        r.link.on("bridge/attach") { throw BridgeError(-32603, "agent cannot load") }
        connect(r)
        val st = r.model.store("a")
        r.model.attach(st)
        assertEquals(Problem("agent cannot load"), st.loadError)
        assertFalse(st.loading)
        assertFalse(st.attaching)
    }

    @Test fun updatesIngestLiveAndAnEpochChangeTriggersAResync() = runTest {
        val r = Rig(this)
        r.link.on("bridge/attach") { attachResult() }
        connect(r)
        val st = r.model.store("a")
        r.model.attach(st)
        r.link.emit(jsonObj("op" to "update", "sessionId" to "a", "epoch" to "e1", "seq" to 1, "update" to jsonObj("sessionUpdate" to "agent_message_chunk", "content" to jsonObj("type" to "text", "text" to "live"))))
        assertEquals("live", (st.entries.single() as ChatEntry.Message).item.text)
        r.link.emit(jsonObj("op" to "update", "sessionId" to "other", "seq" to 1, "update" to jsonObj()))   // not watched: ignored
        val before = r.link.callsOf("bridge/attach").size
        r.link.emit(jsonObj("op" to "update", "sessionId" to "a", "epoch" to "e2", "seq" to 9, "update" to jsonObj("sessionUpdate" to "agent_message_chunk", "content" to jsonObj("type" to "text", "text" to "x"))))
        runCurrent()
        assertEquals(before + 1, r.link.callsOf("bridge/attach").size)
        assertEquals("e1", r.link.callsOf("bridge/attach").last()["epoch"].string)   // the bridge decides: a different epoch means reset
    }

    @Test fun readyReattachesWhatWasAttached() = runTest {
        val r = Rig(this)
        r.link.on("bridge/attach") { attachResult() }
        connect(r)
        val st = r.model.store("a")
        r.model.attach(st)
        r.link.emit(readyFrame()); runCurrent()
        assertEquals(2, r.link.callsOf("bridge/attach").size)
    }

    @Test fun anAttachWhileAttachingIsCoalescedIntoOneFollowUp() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>()
        r.link.on("bridge/attach") { gate.await(); attachResult() }
        connect(r)
        val st = r.model.store("a")
        val a = kotlinx.coroutines.CoroutineScope(coroutineContext).launch { r.model.attach(st) }
        runCurrent()
        r.model.attach(st)
        assertTrue(st.reattachNeeded)
        gate.complete(Unit)
        runCurrent()
        a.join()
        assertEquals(2, r.link.callsOf("bridge/attach").size)
    }

    @Test fun watchersKeepTheConversationAttachedUntilTheLastViewLeaves() = runTest {
        val r = Rig(this)
        r.link.on("bridge/attach") { attachResult() }
        r.link.on("bridge/detach") { jsonObj("ok" to true) }
        connect(r)
        val st = r.model.store("a")
        r.model.attach(st)
        r.model.watch(st)
        assertEquals("a", r.model.visibleSessionId)
        r.model.unwatch(st)
        r.model.watch(st)   // a push to a subagent view and back: no detach
        advanceTimeBy(700); runCurrent()
        assertTrue(st.attached)
        r.model.unwatch(st)
        advanceTimeBy(700); runCurrent()
        assertFalse(st.attached)
        assertNull(r.model.visibleSessionId)
        assertEquals("a", r.link.callsOf("bridge/detach").single()["sessionId"].string)
    }

    // MARK: sending

    private fun promptParams(r: Rig) = r.link.callsOf("session/prompt").single()

    @Test fun sendingWhileIdleDrawsAnEchoAndStartsATurn() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        st.agent = "claude"
        r.link.on("session/prompt") { jsonObj("stopReason" to "end_turn") }
        r.model.send(st, "hello", listOf(byteArrayOf(1, 2)))
        assertTrue(st.running)
        assertEquals("hello", st.pendingEcho!!.item.text)
        assertEquals(1, st.pendingEcho!!.item.images.size)
        assertTrue("a" in r.state.running)
        assertEquals(listOf("a"), r.hooks.started)
        runCurrent()
        val p = promptParams(r)
        assertEquals("claude", p["agent"].string)
        assertEquals(listOf("text", "image"), p["prompt"].array!!.map { it["type"].string })
        assertEquals("AQI=", p["prompt"][1]["data"].string)
        assertNull(p["interrupt"])
    }

    @Test fun aMessageThatTheBridgeQueuedShowsNoBubble() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        r.link.on("session/prompt") { jsonObj("queued" to true, "id" to "q1") }
        r.model.send(st, "hello")
        runCurrent()
        assertNull(st.pendingEcho)
    }

    @Test fun midTurnSendHasNoBubbleAndInterruptIsPassedThrough() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        st.running = true
        r.link.on("session/prompt") { jsonObj("steered" to true) }
        r.model.send(st, "more", interrupt = true)
        assertNull(st.pendingEcho)
        runCurrent()
        assertEquals(true, promptParams(r)["interrupt"].bool)
        assertTrue(r.hooks.started.isEmpty())
    }

    @Test fun lockedElsewhereReturnsTheDraftAndStopsTheTurn() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        r.link.on("session/prompt") { throw BridgeError(BridgeError.LOCKED_ELSEWHERE, "locked", "cli") }
        r.model.send(st, "hello", attachments = listOf(jsonObj("type" to "resource_link", "uri" to "file:///x/a.txt", "name" to "a.txt")))
        runCurrent()
        assertNull(st.pendingEcho)
        assertFalse(st.running)
        assertFalse("a" in r.state.running)
        assertEquals("cli", st.lockedBy)
        assertEquals("hello\n[@a.txt](file:///x/a.txt)", st.unsentDraft)
        assertEquals(listOf("a"), r.hooks.aborted)
    }

    @Test fun changedElsewhereReloadsThenResendsOnce() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        var tries = 0
        r.link.on("session/prompt") { if (tries++ == 0) throw BridgeError(BridgeError.CHANGED_ELSEWHERE, "changed") else jsonObj("stopReason" to "end_turn") }
        r.link.on("bridge/attach") { attachResult() }
        r.model.send(st, "hello")
        runCurrent()
        assertEquals(2, r.link.callsOf("session/prompt").size)
        assertEquals(1, r.link.callsOf("bridge/attach").size)
        assertTrue(st.attached)
        assertNotNull(st.pendingEcho)   // the resend drew its bubble again
        assertTrue(st.running)
    }

    @Test fun aSecondChangedElsewhereDoesNotLoop() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        r.link.on("session/prompt") { throw BridgeError(BridgeError.CHANGED_ELSEWHERE, "changed") }
        r.link.on("bridge/attach") { attachResult() }
        r.model.send(st, "hello")
        runCurrent()
        assertEquals(2, r.link.callsOf("session/prompt").size)
        assertEquals(Problem("changed"), st.composerError)
        assertEquals("hello", st.unsentDraft)
    }

    @Test fun aRefusedSendPutsTheDraftBackWithTheError() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        r.link.on("session/prompt") { throw BridgeError(-32000, "agent is down") }
        r.model.send(st, "hello")
        runCurrent()
        assertNull(st.pendingEcho)
        assertFalse(st.running)
        assertEquals("hello", st.unsentDraft)
        assertEquals(Problem("agent is down"), st.composerError)
        assertTrue(Haptic.Error in r.hooks.haptics)
    }

    @Test fun aDroppedConnectionKeepsTheBubbleAndTheTurn() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        r.link.on("session/prompt") { throw BridgeError(BridgeError.CLOSED, "Connection closed") }
        r.model.send(st, "hello")
        runCurrent()
        assertNotNull(st.pendingEcho)
        assertTrue(st.running)
        assertNull(st.composerError)
    }

    @Test fun emptySendsAreIgnored() = runTest {
        val r = Rig(this)
        connect(r)
        r.model.send(r.model.store("a"), "")
        runCurrent()
        assertTrue(r.link.callsOf("session/prompt").isEmpty())
    }

    @Test fun sendQueuedInterruptsWithAnEmptyPrompt() = runTest {
        val r = Rig(this)
        connect(r, readyFrame())
        val st = r.model.store("a")
        st.agent = "claude"; st.attached = true
        st.queued = listOf(QueuedMessage("q1", "x", 0))
        r.model.sendQueued(st)
        runCurrent()
        val p = promptParams(r)
        assertEquals(0, p["prompt"].array!!.size)
        assertEquals(true, p["interrupt"].bool)
        assertTrue(st.running)
    }

    // MARK: queue, undo

    @Test fun unqueueReturnsTheTextImagesAndFileLinksToTheComposer() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        st.queued = listOf(QueuedMessage("q1", "x", 1))
        r.link.on("bridge/unqueue") { jsonObj("prompt" to listOf(jsonObj("type" to "text", "text" to "later"),
            jsonObj("type" to "image", "data" to "AQI="), jsonObj("type" to "resource_link", "uri" to "file:///x/a.txt", "name" to "a.txt"))) }
        r.model.unqueue(st, st.queued[0])
        assertTrue(st.queued.isEmpty())
        runCurrent()
        assertEquals("later\n[@a.txt](file:///x/a.txt)", st.unsentDraft)
        assertEquals(1, st.unsentImages!!.size)
    }

    @Test fun anUnqueueOfAMessageThatAlreadyWentOutIsQuiet() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        st.queued = listOf(QueuedMessage("q1", "x", 0))
        r.link.on("bridge/unqueue") { throw BridgeError(BridgeError.ALREADY_SENT, "sent") }
        r.model.unqueue(st, st.queued[0]); runCurrent()
        assertNull(st.composerError)
        assertNull(st.unsentDraft)
    }

    @Test fun removeThenUndoPutsTheMessageBackWhereItWas() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        val q = listOf(QueuedMessage("q1", "one", 0), QueuedMessage("q2", "two", 0), QueuedMessage("q3", "three", 0))
        st.queued = q
        val blocks = listOf(jsonObj("type" to "text", "text" to "two"))
        r.link.on("bridge/unqueue") { jsonObj("prompt" to blocks) }
        r.link.on("bridge/requeue") { jsonObj("ok" to true) }
        r.model.removeQueued(st, q[1])
        assertEquals(listOf("q1", "q3"), st.queued.map { it.id })
        assertEquals(q[1], st.removedQueued!!.message)
        r.model.undoRemoveQueued(st)   // before the removal's answer even arrived
        assertEquals(listOf("q1", "q2", "q3"), st.queued.map { it.id })
        assertNull(st.removedQueued)
        runCurrent()
        val rq = r.link.callsOf("bridge/requeue").single()
        assertEquals("q2", rq["id"].string)
        assertEquals(1, rq["index"].int)
        assertEquals(blocks, rq["prompt"].array)
        assertEquals(listOf("q1", "q2", "q3"), st.queued.map { it.id })
    }

    @Test fun undoOfAMessageThatAlreadyWentOutDropsTheRow() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        val q = listOf(QueuedMessage("q1", "one", 0))
        st.queued = q
        r.link.on("bridge/unqueue") { throw BridgeError(BridgeError.ALREADY_SENT, "sent") }
        r.model.removeQueued(st, q[0]); runCurrent()
        assertNull(st.removedQueued)   // nothing to undo
        r.model.undoRemoveQueued(st)
        assertTrue(st.queued.isEmpty())
    }

    @Test fun theUndoWindowEnds() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        st.queued = listOf(QueuedMessage("q1", "one", 0))
        r.link.on("bridge/unqueue") { jsonObj("prompt" to listOf(jsonObj("type" to "text", "text" to "one"))) }
        r.model.removeQueued(st, st.queued[0]); runCurrent()
        assertNotNull(st.removedQueued)
        advanceTimeBy(4_100); runCurrent()
        assertNull(st.removedQueued)
    }

    // MARK: answering, config, misc calls

    @Test fun permissionAndElicitationAnswersUseTheBridgeShapes() = runTest {
        val r = Rig(this)
        connect(r, readyFrame(pending = listOf(permission("claude:1", "a"), permission("claude:2", "a"))))
        val p1 = r.state.pending["claude:1"]!!
        r.model.answerPermission(p1, "allow", "allow_once")
        assertEquals("selected", r.link.replies[0].second["outcome"]["outcome"].string)
        assertEquals("allow", r.link.replies[0].second["outcome"]["optionId"].string)
        assertFalse("claude:1" in r.state.pending)
        r.model.answerPermission(r.state.pending["claude:2"]!!, null)
        assertEquals("cancelled", r.link.replies[1].second["outcome"]["outcome"].string)
        r.link.emit(jsonObj("op" to "request", "key" to "claude:3", "method" to "elicitation/create", "sessionId" to "a", "params" to jsonObj("message" to "Which?")))
        r.model.answerElicitation(r.state.pending["claude:3"]!!, "accept", mapOf("answer" to j("A")))
        assertEquals("accept", r.link.replies[2].second["action"].string)
        assertEquals("A", r.link.replies[2].second["content"]["answer"].string)
        assertTrue(r.state.pending.isEmpty())
    }

    @Test fun aReplyThatCannotBeSentKeepsTheRequest() = runTest {
        val r = Rig(this)
        connect(r, readyFrame(pending = listOf(permission("claude:1", "a"))))
        r.link.replyFails = true
        r.model.answerPermission(r.state.pending["claude:1"]!!, "allow")
        assertTrue("claude:1" in r.state.pending)
        assertTrue(Haptic.Error in r.hooks.haptics)
    }

    @Test fun confirmedAnswersMapTheirOutcomes() = runTest {
        val r = Rig(this)
        connect(r, readyFrame(pending = listOf(permission("claude:1", "a"), permission("claude:2", "a"), permission("claude:3", "a"))))
        assertEquals(RemoteOutcome.Done, r.model.answer("claude:1", "allow"))
        assertEquals(RemoteOutcome.AlreadyAnswered, r.model.answer("claude:404", "allow"))
        r.link.on("reply!") { throw BridgeError(BridgeError.ALREADY_RESOLVED, "resolved") }
        assertEquals(RemoteOutcome.AlreadyAnswered, r.model.answer("claude:2", "allow"))
        assertFalse("claude:2" in r.state.pending)
        r.link.on("reply!") { throw BridgeError(BridgeError.TIMEOUT, "timeout") }
        assertEquals(RemoteOutcome.BridgeUnreachable, r.model.answer("claude:3", "allow"))
    }

    @Test fun answerWhileDisconnectedReportsUnreachable() = runTest {
        val r = Rig(this)
        r.model.start(); runCurrent()
        assertEquals(RemoteOutcome.BridgeUnreachable, r.model.answer("k", "x"))
        assertTrue(r.link.ensures > 0)
    }

    @Test fun approveAllConfirmationIsAskedOnceAndRemembered() = runTest {
        val r = Rig(this)
        r.model.start(); runCurrent()
        assertTrue(r.model.needsApproveAllConfirmation("turbo"))
        assertTrue(r.model.needsApproveAllConfirmation("bypassPermissions"))
        assertFalse(r.model.needsApproveAllConfirmation("plan"))
        r.model.confirmApproveAll("turbo"); runCurrent()
        assertFalse(r.model.needsApproveAllConfirmation("turbo"))
        assertTrue(r.model.needsApproveAllConfirmation("bypassPermissions"))
        assertTrue(r.settings.value.turboConfirmed)
        assertFalse(r.settings.value.bypassConfirmed)
    }

    @Test fun configModeAndAutoApproveCalls() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        st.agent = "agy"
        st.setConfigOptions(parseJson("""[{"id":"mode","category":"mode","name":"Mode","currentValue":"default","options":[{"value":"default","name":"Default"},{"value":"auto","name":"Auto"}]}]"""))
        r.link.on("session/set_config_option") { jsonObj("configOptions" to listOf(jsonObj("id" to "mode", "category" to "mode", "currentValue" to "auto", "options" to listOf(jsonObj("value" to "auto", "name" to "Auto"))))) }
        r.model.choose("turbo", st)   // agy: auto mode plus bridge-side auto approval
        assertTrue(st.autoApprove)
        runCurrent()
        assertEquals(true, r.link.callsOf("bridge/setAutoApprove").single()["enabled"].bool)
        val c = r.link.callsOf("session/set_config_option").single()
        assertEquals("mode", c["configId"].string)
        assertEquals("auto", c["value"].string)
        assertEquals("auto", st.modeOption!!.current.string)
        assertTrue(Haptic.Heavy in r.hooks.haptics)
        // claude has no turbo: the mode id goes through as it is
        val cl = r.model.store("c")
        cl.agent = "claude"
        cl.setModes(parseJson("""{"currentModeId":"default","availableModes":[{"id":"default","name":"Manual"},{"id":"plan","name":"Plan"}]}"""))
        r.link.on("session/set_mode") { jsonObj() }
        r.model.choose("plan", cl); runCurrent()
        assertEquals("plan", r.link.callsOf("session/set_mode").single()["modeId"].string)
        assertEquals("plan", cl.currentMode)
        assertTrue(AppModel.approvesAll("bypassPermissions"))
    }

    @Test fun newSessionRegistersTheStoreAndTheRow() = runTest {
        val r = Rig(this)
        connect(r)
        r.link.on("session/new") { jsonObj("sessionId" to "n1", "epoch" to "e9", "lastSeq" to 0, "configOptions" to emptyList<Any>()) }
        val id = r.model.newSession("/Users/me/proj", "claude")
        assertEquals("n1", id)
        val st = r.model.existingStore("n1")!!
        assertTrue(st.attached)
        assertEquals("claude", st.agent)
        assertEquals("e9", st.epoch)
        assertEquals("n1", r.state.sessions[0].id)
    }

    @Test fun shellRunsOnceAndReportsFailures() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        r.link.on("bridge/shell") { jsonObj("exitCode" to 2) }
        r.model.runShell(st, "  echo “hi” — x ")
        assertTrue(st.shellRunning)
        r.model.runShell(st, "ls")   // already running
        runCurrent()
        assertEquals(1, r.link.callsOf("bridge/shell").size)
        assertEquals("echo \"hi\" -- x", r.link.callsOf("bridge/shell")[0]["command"].string)
        assertFalse(st.shellRunning)
        assertTrue(Haptic.Warning in r.hooks.haptics)
    }

    @Test fun sideQuestionsAreCappedAndResolve() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        r.link.on("bridge/btw") { p -> if (p["question"].string == "q2") jsonObj("text" to "") else jsonObj("text" to " answer ") }
        r.model.askSideQuestion(st, "q1")
        r.model.askSideQuestion(st, "q2")
        r.model.askSideQuestion(st, "q3")
        r.model.askSideQuestion(st, "q4")   // over the cap
        assertEquals(3, st.sideQuestions.size)
        assertTrue(st.sideQuestions.all { it.pending })
        runCurrent()
        assertEquals("answer", st.sideQuestions[0].answer)
        assertEquals(Problem("No answer came back.", Issue.NoAnswer), st.sideQuestions[1].error)
        r.model.closeSideQuestion(st, st.sideQuestions[0].id)
        assertEquals(2, st.sideQuestions.size)
    }

    @Test fun browseAndReadFile() = runTest {
        val r = Rig(this)
        connect(r)
        r.link.on("bridge/browse") { jsonObj("path" to "/Users/me", "parent" to "/Users", "isGit" to true, "dirs" to listOf("a", "b"), "files" to listOf("x.md")) }
        r.link.on("bridge/readFile") { jsonObj("text" to "hello", "truncated" to true) }
        val b = r.model.browse(null, "claude")
        assertEquals(BrowseResult("/Users/me", "/Users", true, listOf("a", "b"), listOf("x.md")), b)
        assertEquals("claude", r.link.callsOf("bridge/browse")[0]["agent"].string)
        assertNull(r.link.callsOf("bridge/browse")[0]["path"])
        assertEquals(Triple("hello", true, false), r.model.readFile("/x"))
    }

    @Test fun cliListingAndShowInCli() = runTest {
        val r = Rig(this)
        connect(r)
        val st = r.model.store("a")
        st.agent = "claude"
        r.link.on("bridge/cliVisibility") { jsonObj("visible" to false) }
        r.link.on("bridge/showInCli") { jsonObj("ok" to true) }
        r.model.refreshCliListing(st)
        assertEquals(false, st.listedInCli)
        r.model.showInCli(st)
        assertEquals(true, st.listedInCli)
    }

    // MARK: phase 3: notifications, background, network

    @Test fun anOffScreenRequestTheNotifierDidNotAlertAboutIsNotReportedAsNoticed() = runTest {
        val r = Rig(this)
        r.hooks.alertRequests = false   // notifications off or the channel blocked: Bark must stay in charge
        connect(r)
        r.link.emit(permission("claude:9", "a")); runCurrent()
        assertEquals(listOf("claude:9" to false), r.hooks.requests)
        assertTrue(r.link.callsOf("bridge/noticed").isEmpty())
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to true))
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to false, "stopReason" to "end_turn")); runCurrent()
        assertTrue(r.link.callsOf("bridge/noticed").isEmpty())
    }

    @Test fun readyTellsTheHooksWhichRequestsStillWait() = runTest {
        val r = Rig(this)
        connect(r, readyFrame(pending = listOf(permission("claude:1", "a"))))
        connect(r, readyFrame())
        assertEquals(listOf(setOf("claude:1"), emptySet<String>()), r.hooks.reconciled)
    }

    @Test fun seeingAConversationAndForgettingTheBridgeReachTheHooks() = runTest {
        val r = Rig(this)
        connect(r)
        r.model.markSeen("a")
        assertEquals(listOf("a"), r.hooks.seen)
        val before = r.hooks.resets
        r.model.unpair(); runCurrent()
        assertEquals(before + 1, r.hooks.resets)
    }

    @Test fun theNotificationPermissionIsAskedAtTheFirstTurnOnlyUntilItWasDealtWith() = runTest {
        val r = Rig(this)
        connect(r)
        r.link.on("session/prompt") { jsonObj("stopReason" to "end_turn") }
        r.hooks.askDealtWith = false
        r.model.send(r.model.store("a"), "one"); runCurrent()
        assertEquals(1, r.hooks.asks)
        assertFalse(r.state.notificationsAsked)
        r.hooks.askDealtWith = true
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to false, "stopReason" to "end_turn")); runCurrent()
        r.model.send(r.model.store("a"), "two"); runCurrent()
        assertEquals(2, r.hooks.asks)
        assertTrue(r.state.notificationsAsked)
        assertTrue(r.settings.value.notificationsAsked)
        r.link.emit(jsonObj("op" to "turn", "sessionId" to "a", "running" to false, "stopReason" to "end_turn")); runCurrent()
        r.model.send(r.model.store("a"), "three"); runCurrent()
        assertEquals(2, r.hooks.asks)
    }

    @Test fun keepAliveIsStored() = runTest {
        val r = Rig(this)
        connect(r)
        assertTrue(r.state.keepAlive)
        r.model.setKeepAlive(false); runCurrent()
        assertFalse(r.settings.value.keepAlive)
    }

    @Test fun aNetworkChangeReconnectsOnlyAConnectionThatExists() = runTest {
        val r = Rig(this)
        connect(r)
        r.model.networkChanged()
        assertEquals(1, r.link.reconnects)
        r.link.set(LinkState.Idle)
        r.model.networkChanged()
        assertEquals(1, r.link.reconnects)
        val unpaired = Rig(this, InMemorySettingsStore())
        unpaired.model.start(); runCurrent()
        unpaired.model.networkChanged()
        assertEquals(0, unpaired.link.reconnects)
    }

    @Test fun releasingTheConnectionNeedsAnOffScreenApp() = runTest {
        val r = Rig(this)
        connect(r)
        r.model.sceneChanged(true)
        r.model.releaseConnection()
        assertEquals(LinkState.Connected, r.state.connection)
        r.model.sceneChanged(false)
        r.model.releaseConnection()
        assertEquals(LinkState.Idle, r.state.connection)
        // coming back reconnects
        val ensures = r.link.ensures
        r.model.sceneChanged(true)
        assertEquals(ensures + 1, r.link.ensures)
    }

    @Test fun anAnswerFromANotificationOnAColdProcessWaitsForTheSettingsAndTheReadyFrame() = runTest {
        val r = Rig(this)
        var outcome: RemoteOutcome? = null
        launch { r.model.awaitSettings(); outcome = r.model.answer("claude:1", "allow") }
        runCurrent()
        assertNull(outcome)
        r.model.start(); runCurrent()
        assertEquals(1, r.link.connects)
        r.link.set(LinkState.Connected)
        r.link.emit(readyFrame(pending = listOf(permission("claude:1", "a"))))
        advanceTimeBy(300); runCurrent()
        assertEquals(RemoteOutcome.Done, outcome)
        val reply = r.link.callsOf("reply!").single()
        assertEquals("claude:1", reply["key"].string)
        assertEquals("allow", reply["result"]["outcome"]["optionId"].string)
    }

    @Test fun anAnswerForARequestTheNewReadyFrameDoesNotListIsAlreadyAnswered() = runTest {
        val r = Rig(this)
        connect(r)
        assertEquals(RemoteOutcome.AlreadyAnswered, r.model.answer("claude:42", "allow"))
        assertTrue(r.link.callsOf("reply!").isEmpty())
    }

    @Test fun anActionThatStartedTheProcessWaitsForTheStoredSettings() = runTest {
        val r = Rig(this)
        var settled = false
        launch { r.model.awaitSettings(); settled = true }
        runCurrent()
        assertFalse(settled)
        r.model.start(); runCurrent()
        assertTrue(settled)
    }
}
