package dev.leash.personal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyTest {
    private val now = 1_000_000L

    @Test fun busyRunsInTheForegroundToo() {
        assertTrue(BackgroundPolicy.decide(true, sceneActive = true, busy = true, leftAtMs = null, nowMs = now).run)
        assertTrue(BackgroundPolicy.decide(true, sceneActive = false, busy = true, leftAtMs = null, nowMs = now).run)
    }

    @Test fun idleInTheForegroundDoesNotRun() {
        assertFalse(BackgroundPolicy.decide(true, sceneActive = true, busy = false, leftAtMs = now - 10, nowMs = now).run)
    }

    @Test fun leavingStartsAGraceThatEnds() {
        val d = BackgroundPolicy.decide(true, sceneActive = false, busy = false, leftAtMs = now - 1_000, nowMs = now)
        assertTrue(d.run)
        assertEquals(now - 1_000 + BackgroundPolicy.GRACE_MS, d.recheckAtMs)
        val over = BackgroundPolicy.decide(true, sceneActive = false, busy = false, leftAtMs = now - BackgroundPolicy.GRACE_MS - 1, nowMs = now)
        assertFalse(over.run)
        assertNull(over.recheckAtMs)
    }

    @Test fun startedInTheBackgroundHasNoGrace() {
        // a notification action that started the process: do the answer, then let go
        assertFalse(BackgroundPolicy.decide(true, sceneActive = false, busy = false, leftAtMs = null, nowMs = now).run)
    }

    @Test fun theSettingTurnsEverythingOff() {
        assertFalse(BackgroundPolicy.decide(false, sceneActive = false, busy = true, leftAtMs = now, nowMs = now).run)
    }

    private fun turn(id: String, at: Long, agent: String = "claude") = TrackedTurn(id, agent, "Title $id", at)
    private fun req(key: String, sid: String?, permission: Boolean = true) =
        PendingRequest(key, "claude", if (permission) "session/request_permission" else "elicitation/create", sid, jsonObj("toolCall" to jsonObj("title" to "Bash")))
    private fun pick(turns: List<TrackedTurn>, attention: List<PendingRequest> = emptyList()) =
        OngoingFocus.pick(turns, attention, { if (it == "claude") "Claude Code" else "Antigravity" }, { sid -> if (sid == "a") "Alpha" else null })

    @Test fun nothingTrackedNothingShown() = assertNull(pick(emptyList()))

    @Test fun theNewestTurnIsInFocus() {
        val c = pick(listOf(turn("a", 100), turn("b", 300), turn("c", 200)))!!
        assertEquals("b", c.sessionId)
        assertEquals(OngoingPhase.Working, c.phase)
        assertEquals("Claude Code", c.agentLabel)
        assertEquals("Title b", c.text)   // no live title known: the one from when it started
        assertEquals(300L, c.sinceMs)
        assertEquals(2, c.others)
    }

    @Test fun theBridgesTitleWinsOverTheOneFromTheStart() {
        assertEquals("Alpha", pick(listOf(turn("a", 100)))!!.text)
    }

    @Test fun aWaitingRequestTakesTheFocusFromANewerTurn() {
        val c = pick(listOf(turn("a", 100), turn("b", 300)), listOf(req("claude:2", "a"), req("claude:1", "a", permission = false)))!!
        assertEquals("a", c.sessionId)
        assertEquals(OngoingPhase.NeedsAnswer, c.phase)   // claude:1 is the oldest
        assertEquals("Claude Code", c.agentLabel)
        assertEquals(100L, c.sinceMs)
        assertEquals(1, c.others)
        assertEquals("leash://session?id=a&agent=claude", c.url)
    }

    @Test fun aRequestOnAConversationNobodyTrackedStillShows() {
        val c = pick(emptyList(), listOf(req("claude:1", "z")))!!
        assertEquals(OngoingPhase.NeedsApproval, c.phase)
        assertEquals(null, c.sinceMs)
        assertEquals(0, c.others)
    }

    @Test fun theTrackerFollowsTheBridgesRunningSet() {
        val t = TurnTracker()
        t.started(turn("a", 1)); t.started(turn("b", 2))
        t.reconcile(setOf("b", "z"))
        assertEquals(listOf("b"), t.all.map { it.sessionId })
        t.started(turn("b", 5))   // restarted: replaces, one row
        assertEquals(listOf(5L), t.all.map { it.startedAtMs })
        t.ended("b")
        assertTrue(t.isEmpty)
    }
}
