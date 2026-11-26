package dev.leash.personal.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo bridge driven through the real AppModel: the scripted frames must be ones the reducers understand. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoBridgeTest {
    private fun TestScope.model(): AppModel {
        val m = AppModel(backgroundScope, InMemorySettingsStore(Settings(useDemo = true)), FakeLink(), { DemoBridge(backgroundScope) })
        m.start()
        advanceTimeBy(1_000); runCurrent()
        return m
    }

    @Test fun connectsListsSessionsAndSurfacesPendingRequests() = runTest {
        val m = model()
        val s = m.state.value
        assertTrue(s.useDemo && s.isConnected && s.agentReady)
        assertEquals(12, s.sessions.size)
        assertEquals(setOf("demo-permission", "demo-queue", "demo-subagent-live"), s.running)
        assertEquals(3, s.pending.size)
        assertEquals(2, s.sessions.first { it.id == "demo-permission" }.pendingCount)
        assertEquals("demo-subagent", s.sessions.first { it.id == "demo-subagent" }.id)
        assertEquals("cli", s.sessions.first { it.id == "demo-locked" }.lockedBy)
        assertTrue(s.pending.values.any { it.isPermission } && s.pending.values.any { !it.isPermission })
        assertNotNull(s.quotas["agy"])
    }

    @Test fun theRefactorConversationHasThinkingToolDiffMarkdownAndStats() = runTest {
        val m = model()
        val st = m.store("demo-refactor")
        m.attach(st)
        val items = st.displayItems()
        assertEquals(listOf("User", "Activity", "Answer", "Changes", "Stats"), items.map { it::class.simpleName })
        val g = (items.first { it is DisplayItem.Activity } as DisplayItem.Activity).group
        assertTrue(g.steps.any { it is ChatEntry.Message && it.item.role == MessageItem.Role.Thought })
        assertEquals("Explored 2 files, ran 1 command, edited 1 file", g.summary)
        val answer = (items.first { it is DisplayItem.Answer } as DisplayItem.Answer).item.text
        assertTrue(answer.contains("kotlin\nfun parse") && answer.contains("![parser flow](/Users/demo"))
        assertEquals(1, (items.first { it is DisplayItem.Changes } as DisplayItem.Changes).changes.files.size)
        assertEquals(2, st.commands.size)
        assertEquals("Sonnet 5.5", st.modelOption!!.currentName)
    }

    @Test fun thePermissionConversationShowsItsRequestsAndAnsweringResolvesThem() = runTest {
        val m = model()
        val st = m.store("demo-permission")
        m.attach(st)
        assertTrue(st.running)
        assertEquals(2, m.state.value.pendingFor("demo-permission").size)
        val p = m.state.value.pendingFor("demo-permission").first { it.isPermission }
        m.answerPermission(p, "allow", "allow_once"); runCurrent()
        assertEquals(1, m.state.value.pendingFor("demo-permission").size)
        val q = m.state.value.pendingFor("demo-permission").single()
        m.answerElicitation(q, "accept", mapOf("question_0" to j("postgres"))); runCurrent()
        // what is left is the request of a subagent in another conversation
        assertEquals(listOf("demo-subagent-live"), m.state.value.pending.values.map { it.sessionId })
    }

    @Test fun theQueueConversationHasQueuedMessagesAndUndoWorks() = runTest {
        val m = model()
        val st = m.store("demo-queue")
        m.attach(st)
        assertEquals(listOf("agy:q1", "agy:q2"), st.queued.map { it.id })
        assertEquals(1, st.queued[1].imageCount)
        m.removeQueued(st, st.queued[0]); advanceTimeBy(200); runCurrent()
        assertEquals(listOf("agy:q2"), st.queued.map { it.id })
        m.undoRemoveQueued(st); advanceTimeBy(200); runCurrent()
        assertEquals(listOf("agy:q1", "agy:q2"), st.queued.map { it.id })
    }

    @Test fun theSubagentConversationHasAFinishedSubagentWithItsOwnTranscript() = runTest {
        val m = model()
        val st = m.store("demo-subagent")
        m.attach(st)
        val sub = st.subagent("demo-child")!!
        assertFalse(sub.isRunning)
        assertEquals("Explore", sub.agentType)
        assertEquals(1, sub.stepCount)
        assertEquals("List the Gradle files", (sub.entries.first() as ChatEntry.Message).item.text)
    }

    @Test fun aLockedConversationIsRefusedWithTheHolder() = runTest {
        val m = model()
        val st = m.store("demo-locked")
        m.attach(st)
        assertEquals("cli", st.lockedBy)
        assertFalse(st.attached)
    }

    @Test fun promptingStreamsATurnAndEndsIdle() = runTest {
        val m = model()
        val st = m.store("demo-empty")
        m.attach(st)
        m.send(st, "Please fix the parser")
        assertNotNull(st.pendingEcho)
        advanceTimeBy(1_000); runCurrent()
        assertNull(st.pendingEcho)   // the echo adopted the bubble
        assertTrue(st.running)
        advanceTimeBy(10_000); runCurrent()
        assertFalse(st.running)
        assertEquals("end_turn", st.lastStopReason)
        val items = st.displayItems()
        assertEquals("Please fix the parser", (items.first() as DisplayItem.User).item.text)
        assertTrue(items.any { it is DisplayItem.Answer } && items.any { it is DisplayItem.Stats })
        assertFalse("demo-empty" in m.state.value.running)
    }

    @Test fun aMidTurnMessageIsQueuedForAgyAndSteeredForClaude() = runTest {
        val m = model()
        val agy = m.store("demo-empty")
        m.attach(agy)
        m.send(agy, "first")
        advanceTimeBy(500); runCurrent()
        m.send(agy, "second")
        advanceTimeBy(100); runCurrent()
        assertEquals(1, agy.queued.size)
        assertEquals("second", agy.queued[0].text)
        advanceTimeBy(10_000); runCurrent()   // the queue goes out as the next turn
        assertTrue(agy.queued.isEmpty())
        assertFalse(agy.running)
        assertEquals(listOf("first", "second"), agy.entries.mapNotNull { (it as? ChatEntry.Message)?.item?.takeIf { x -> x.role == MessageItem.Role.User }?.text })

        val claude = m.store("demo-refactor")
        m.attach(claude)
        m.send(claude, "go")
        advanceTimeBy(500); runCurrent()
        m.send(claude, "also this")
        advanceTimeBy(100); runCurrent()
        assertTrue(claude.queued.isEmpty())
        assertTrue(claude.entries.any { it is ChatEntry.Message && it.item.text == "also this" })
        advanceTimeBy(10_000); runCurrent()
    }

    @Test fun cancelStopsTheTurn() = runTest {
        val m = model()
        val st = m.store("demo-empty")
        m.attach(st)
        m.send(st, "long task")
        advanceTimeBy(600); runCurrent()
        assertTrue(st.running)
        m.cancel(st)
        advanceTimeBy(200); runCurrent()
        assertFalse(st.running)
        assertEquals("cancelled", st.lastStopReason)
    }

    @Test fun aPermissionPromptDuringATurnWaitsForTheAnswer() = runTest {
        val m = model()
        val st = m.store("demo-empty")
        m.attach(st)
        m.send(st, "needs permission to edit")
        advanceTimeBy(1_500); runCurrent()
        val p = m.state.value.pendingFor("demo-empty").single()
        assertTrue(st.running)
        m.answerPermission(p, "allow", "allow_once")
        advanceTimeBy(10_000); runCurrent()
        assertFalse(st.running)
        assertTrue(m.state.value.pendingFor("demo-empty").isEmpty())
        assertTrue(st.displayItems().any { it is DisplayItem.Changes })
    }

    @Test fun newSessionRegistersAndReachesTheList() = runTest {
        val m = model()
        val id = m.newSession("/Users/demo/workspace/leash", "claude")
        advanceTimeBy(500); runCurrent()
        assertTrue(m.state.value.sessions.any { it.id == id })
        assertTrue(m.existingStore(id)!!.attached)
    }
}
