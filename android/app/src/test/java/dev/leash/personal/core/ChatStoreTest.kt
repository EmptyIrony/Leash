package dev.leash.personal.core

import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames shaped like the ones the bridge records (bridge/test/mock-agent.mjs, hub.mjs #echo / #recordTurnStats). */
class ChatStoreTest {
    private fun text(t: String) = jsonObj("type" to "text", "text" to t)
    private fun chunk(kind: String, t: String, mid: String? = null, meta: JsonElement? = null) =
        jsonObj("sessionUpdate" to kind, "content" to text(t), "messageId" to mid, "_meta" to meta)

    private fun ChatStore.feed(vararg updates: JsonElement) = updates.forEach { ingest(lastSeq + 1, it) }

    @Test fun textChunksOfOneMessageMergeAndANewIdStartsANewRow() {
        val s = ChatStore("s1")
        s.feed(chunk("agent_message_chunk", "Hi! ", "m1"), chunk("agent_message_chunk", "How can I help?", "m1"), chunk("agent_message_chunk", "Second", "m2"))
        assertEquals(listOf("Hi! How can I help?", "Second"), s.entries.map { (it as ChatEntry.Message).item.text })
    }

    @Test fun ingestDropsAlreadySeenSequenceNumbers() {
        val s = ChatStore("s1")
        s.ingest(1, chunk("agent_message_chunk", "a"))
        s.ingest(1, chunk("agent_message_chunk", "dup"))
        s.ingest(3, chunk("agent_message_chunk", "b"))
        s.ingest(2, chunk("agent_message_chunk", "late"))
        assertEquals("ab", (s.entries.single() as ChatEntry.Message).item.text)
        assertEquals(3, s.lastSeq)
    }

    @Test fun versionBumpsOnEveryChange() {
        val s = ChatStore("s1")
        val v0 = s.version.value
        s.ingest(1, chunk("agent_message_chunk", "a"))
        val v1 = s.version.value
        s.running = true
        assertTrue(v1 > v0)
        assertTrue(s.version.value > v1)
        val v2 = s.version.value
        s.running = true   // unchanged
        assertEquals(v2, s.version.value)
    }

    @Test fun toolCallThenUpdateKeepsOneRowAndParsesTheDiff() {
        val s = ChatStore("s1")
        s.feed(
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "t1", "title" to "Edit README.md", "kind" to "edit", "status" to "pending",
                "locations" to listOf(jsonObj("path" to "/tmp/proj/README.md", "line" to 3)),
                "content" to listOf(jsonObj("type" to "diff", "path" to "/tmp/proj/README.md", "oldText" to "a\nb\n", "newText" to "a\nc\n"))),
            jsonObj("sessionUpdate" to "tool_call_update", "toolCallId" to "t1", "status" to "completed"),
        )
        val t = (s.entries.single() as ChatEntry.Tool).item
        assertEquals("completed", t.status)
        assertEquals("Edit README.md", t.title)
        assertEquals(listOf("/tmp/proj/README.md:3"), t.locations)
        assertEquals(1, t.diffAdds)
        assertEquals(1, t.diffDels)
        assertEquals("/tmp/proj/README.md", t.editedPath)
    }

    @Test fun anUpdateForAnUnknownToolCreatesTheRow() {
        val s = ChatStore("s1")
        s.feed(jsonObj("sessionUpdate" to "tool_call_update", "toolCallId" to "x", "status" to "failed", "title" to "Bash"))
        assertEquals("failed", (s.entries.single() as ChatEntry.Tool).item.status)
    }

    @Test fun agyStepsToolBecomesAChecklistAndStaysOutOfTheVisibleTools() {
        val s = ChatStore("s1")
        s.feed(jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "p", "name" to "update_steps", "args" to
            jsonObj("steps" to listOf(jsonObj("title" to "Read", "status" to "done"), jsonObj("title" to "Write", "status" to "in progress"), "Test"))))
        val t = (s.entries.single() as ChatEntry.Tool).item
        assertTrue(t.isStepsTool)
        assertEquals(listOf("completed", "in_progress", "pending"), t.checklist.map { it.status })
        val g = (s.displayItems().single() as DisplayItem.Activity).group
        assertTrue(g.visibleTools.isEmpty())
        assertEquals("Write", g.checklist.first { it.status == "in_progress" }.title)
    }

    @Test fun terminalPreambleOfAgyCommandsIsStripped() {
        val body = "Created At: x\nCompleted At: y\nCommand exited with code 2\nCommand output:\nboom\n"
        assertEquals("[Command exited with code 2]\nboom\n", ToolItem.stripCommandPreamble(body))
        assertEquals("ok\n", ToolItem.stripCommandPreamble("Created At: x\nCommand exited with code 0\nCommand output:\nok\n"))
        assertEquals("plain", ToolItem.stripCommandPreamble("plain"))
    }

    @Test fun displayGroupsATurnIntoUserActivityAnswerStats() {
        val s = ChatStore("s1")
        s.feed(
            chunk("user_message_chunk", "fix it", "u1", jsonObj("leash" to jsonObj("synthetic" to true))),
            chunk("agent_thought_chunk", "Thinking about it"),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "t1", "title" to "Read a.kt", "kind" to "read", "status" to "completed", "locations" to listOf(jsonObj("path" to "/p/a.kt"))),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "t2", "title" to "ls", "kind" to "execute", "status" to "completed", "rawInput" to jsonObj("command" to "ls")),
            chunk("agent_message_chunk", "Done."),
            jsonObj("sessionUpdate" to "leash_turn_stats", "usage" to jsonObj("inputTokens" to 1000, "outputTokens" to 40), "durationMs" to 4200, "model" to "m", "stopReason" to "end_turn"),
        )
        val items = s.displayItems()
        assertEquals(listOf("User", "Activity", "Answer", "Stats"), items.map { it::class.simpleName })
        val g = (items[1] as DisplayItem.Activity).group
        assertEquals("Explored 1 file, ran 1 command", g.summary)
        assertEquals(40, g.stats!!.outputTokens)
        assertEquals("Done.", (items[2] as DisplayItem.Answer).item.text)
    }

    @Test fun anInterimAgentMessageFoldsIntoTheActivityWhenATool_followsIt() {
        val s = ChatStore("s1")
        s.feed(chunk("user_message_chunk", "go", "u"), chunk("agent_message_chunk", "Let me look.", "a1"),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "t", "title" to "grep", "kind" to "search", "status" to "completed"),
            chunk("agent_message_chunk", "Found it.", "a2"))
        val items = s.displayItems()
        assertEquals(listOf("User", "Activity", "Answer"), items.map { it::class.simpleName })
        assertEquals(2, (items[1] as DisplayItem.Activity).group.steps.size)
        assertEquals("Found it.", (items[2] as DisplayItem.Answer).item.text)
    }

    @Test fun aRunningTurnShowsALiveTimeline() {
        val s = ChatStore("s1")
        s.running = true
        s.feed(chunk("user_message_chunk", "go", "u"), chunk("agent_thought_chunk", "hmm"),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "t", "title" to "npm test", "kind" to "execute", "status" to "in_progress", "rawInput" to jsonObj("command" to "npm test")))
        val items = s.displayItems()
        assertEquals(listOf("User", "Activity", "Segment"), items.map { it::class.simpleName })
        assertTrue((items[1] as DisplayItem.Activity).group.live)
        assertEquals("Running npm test…", (items[2] as DisplayItem.Segment).segment.liveLine)
        // expanded: no timeline, just the header
        s.expandedGroups = setOf(items[1].id)
        assertEquals(listOf("User", "Activity"), s.displayItems().map { it::class.simpleName })
    }

    @Test fun aRunningTurnWithNoOutputYetStillHasALiveGroupAfterTheUserMessage() {
        val s = ChatStore("s1")
        s.running = true
        s.feed(chunk("user_message_chunk", "go", "u"))
        val items = s.displayItems()
        assertEquals(listOf("User", "Activity"), items.map { it::class.simpleName })
        assertEquals("Thinking…", (items[1] as DisplayItem.Activity).group.liveAction)
    }

    @Test fun theOptimisticBubbleIsAdoptedByTheEchoInPlace() {
        val s = ChatStore("s1")
        val bubble = MessageItem(MessageItem.Role.User, "hello", null).also { it.synthetic = true }
        s.pendingEcho = PendingEcho(bubble)
        s.running = true
        // before the echo: the bubble is the last user row, and no live group yet
        assertEquals(listOf("User"), s.displayItems().map { it::class.simpleName })
        s.feed(chunk("user_message_chunk", "hello", "srv-1", jsonObj("leash" to jsonObj("synthetic" to true))))
        assertNull(s.pendingEcho)
        val row = (s.entries.single() as ChatEntry.Message).item
        assertSame(bubble, row)
        assertEquals("srv-1", row.messageId)
        assertEquals("hello", row.text)
        assertEquals(listOf("User", "Activity"), s.displayItems().map { it::class.simpleName })
    }

    @Test fun anEchoWithoutABubbleIsAnOrdinaryUserRowAndShellEntriesNeverAdoptABubble() {
        val s = ChatStore("s1")
        s.feed(chunk("user_message_chunk", "from another device", "u9", jsonObj("leash" to jsonObj("synthetic" to true))))
        assertEquals("from another device", (s.entries.single() as ChatEntry.Message).item.text)
        val b = MessageItem(MessageItem.Role.User, "typed", null)
        s.pendingEcho = PendingEcho(b)
        s.feed(chunk("user_message_chunk", "<bash-input>ls</bash-input>", "sh1", jsonObj("leash" to jsonObj("synthetic" to true, "shell" to true))))
        assertNotNull(s.pendingEcho)
        assertEquals(2, s.entries.size)
    }

    @Test fun syntheticUserBlocksOfOnePromptJoinWithANewline() {
        val s = ChatStore("s1")
        val meta = jsonObj("leash" to jsonObj("synthetic" to true))
        s.feed(chunk("user_message_chunk", "ctx", "u", meta), chunk("user_message_chunk", "ask", "u", meta))
        assertEquals("ctx\nask", (s.entries.single() as ChatEntry.Message).item.text)
    }

    @Test fun imageAndResourceBlocksAttachToTheMessage() {
        val s = ChatStore("s1")
        val png = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        s.feed(
            jsonObj("sessionUpdate" to "user_message_chunk", "messageId" to "u", "content" to jsonObj("type" to "text", "text" to "see"), "_meta" to jsonObj("leash" to jsonObj("synthetic" to true))),
            jsonObj("sessionUpdate" to "user_message_chunk", "messageId" to "u", "content" to jsonObj("type" to "image", "mimeType" to "image/jpeg", "data" to png), "_meta" to jsonObj("leash" to jsonObj("synthetic" to true))),
            jsonObj("sessionUpdate" to "user_message_chunk", "messageId" to "u", "content" to jsonObj("type" to "resource_link", "uri" to "file:///Users/me/.leash/uploads/s/My%20File.txt", "name" to "My File.txt"), "_meta" to jsonObj("leash" to jsonObj("synthetic" to true))),
        )
        val m = (s.entries.single() as ChatEntry.Message).item
        assertEquals(1, m.images.size)
        assertEquals(listOf(FileRef("My File.txt", "/Users/me/.leash/uploads/s/My File.txt")), m.files)
    }

    @Test fun planPlanUpdateAndRemoval() {
        val s = ChatStore("s1")
        s.feed(jsonObj("sessionUpdate" to "plan", "entries" to listOf(jsonObj("content" to "a", "status" to "completed"), jsonObj("content" to "b", "status" to "pending", "priority" to "high"))))
        val p = (s.entries.single() as ChatEntry.Plan).item
        assertEquals(listOf("a", "b"), p.entries.map { it.content })
        assertEquals("medium", p.entries[0].priority)
        s.feed(jsonObj("sessionUpdate" to "plan_update", "plan" to jsonObj("type" to "markdown", "content" to "# Plan", "path" to "/p/plan.md")))
        assertEquals("# Plan", p.markdown)
        assertTrue(s.displayItems().any { it is DisplayItem.PlanDoc })
        s.feed(jsonObj("sessionUpdate" to "plan_removed"))
        assertTrue(s.entries.isEmpty())
    }

    @Test fun sessionLevelUpdatesFeedTheStoreFields() {
        val s = ChatStore("s1")
        s.feed(
            jsonObj("sessionUpdate" to "session_info_update", "title" to "Chat: hello"),
            jsonObj("sessionUpdate" to "current_mode_update", "currentModeId" to "plan"),
            jsonObj("sessionUpdate" to "usage_update", "used" to 1200, "size" to 200000),
            jsonObj("sessionUpdate" to "available_commands_update", "availableCommands" to listOf(
                jsonObj("name" to "/review", "description" to "Review a PR", "input" to jsonObj("hint" to "pr-number")), jsonObj("name" to "review", "description" to "dup"), jsonObj("name" to "init"))),
            jsonObj("sessionUpdate" to "config_option_update", "configOptions" to listOf(
                jsonObj("id" to "model", "name" to "Model", "category" to "model", "type" to "select", "currentValue" to "b",
                    "options" to listOf(jsonObj("value" to "a", "name" to "A"), jsonObj("value" to "b", "name" to "B"))),
                jsonObj("id" to "mode", "name" to "Mode", "category" to "mode", "type" to "select", "currentValue" to "default",
                    "options" to listOf(jsonObj("group" to "Safe", "options" to listOf(jsonObj("value" to "default", "name" to "Default"))))))),
            jsonObj("sessionUpdate" to "notice", "severity" to "error", "title" to "Prompt failed", "description" to "boom"),
        )
        assertEquals("Chat: hello", s.title)
        assertEquals("plan", s.currentMode)
        assertEquals(1200 to 200000, s.usage)
        assertEquals(listOf("review", "init"), s.commands.map { it.name })
        assertEquals("pr-number", s.commands[0].hint)
        assertEquals("B", s.modelOption!!.currentName)
        assertEquals("Safe", s.modeOption!!.choices.single().group)
        assertEquals("Prompt failed", (s.entries.last() as ChatEntry.Notice).item.title)
        s.setLocalConfigValue("model", j("a"))
        assertEquals("A", s.modelOption!!.currentName)
    }

    @Test fun modesComeFromAttachResults() {
        val s = ChatStore("s1")
        s.setModes(parseJson("""{"currentModeId":"auto","availableModes":[{"id":"auto","name":"Auto"},{"id":"plan"}]}"""))
        assertEquals(listOf(SessionMode("auto", "Auto"), SessionMode("plan", "plan")), s.modes)
        assertEquals("auto", s.currentMode)
        s.setModes(null)
        assertEquals(2, s.modes.size)
    }

    @Test fun subagentUpdatesRouteIntoTheChildAndNeverTouchTheRoot() {
        val s = ChatStore("root")
        val child = jsonObj("leash" to jsonObj("subagent" to "c1"))
        s.feed(
            jsonObj("sessionUpdate" to "subagent_update", "sessionId" to "c1", "title" to "Explore the repo", "description" to "Explore", "state" to jsonObj("state" to "running")),
            jsonObj("sessionUpdate" to "session_message", "messageId" to "m-in", "senderSessionId" to "root", "recipientSessionId" to "c1", "content" to listOf(text("look around")), "_meta" to child),
            jsonObj("sessionUpdate" to "config_option_update", "_meta" to child, "configOptions" to listOf(jsonObj("id" to "model", "category" to "model", "currentValue" to "fable", "options" to listOf(jsonObj("value" to "fable", "name" to "Fable 5.2"))))),
            jsonObj("sessionUpdate" to "agent_message_chunk", "content" to text("child looks"), "_meta" to child),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "ct1", "title" to "Edit a.txt", "kind" to "edit", "status" to "pending", "_meta" to child),
            jsonObj("sessionUpdate" to "subagent_update", "sessionId" to "g1", "title" to "Nested", "state" to jsonObj("state" to "running"), "_meta" to child),
            jsonObj("sessionUpdate" to "session_info_update", "title" to "not the root title", "_meta" to jsonObj("leash" to jsonObj("subagent" to "g1"))),
        )
        assertNull(s.title)
        val sub = s.subagent("c1")!!
        assertEquals(1, s.entries.size)
        assertTrue(sub.isRunning)
        assertEquals("Explore", sub.agentType)
        assertEquals("Fable 5.2", sub.model)
        assertEquals(listOf("user", "agent", "tool", "subagent"), sub.entries.map { e ->
            when (e) { is ChatEntry.Message -> if (e.item.role == MessageItem.Role.User) "user" else "agent"; is ChatEntry.Tool -> "tool"; is ChatEntry.Subagent -> "subagent"; else -> "?" }
        })
        assertEquals("look around", (sub.entries[0] as ChatEntry.Message).item.text)
        assertEquals(setOf("c1", "g1"), sub.lineage)
        assertNotNull(s.subagent("g1"))   // nested ones are indexed too
        assertEquals(listOf("c1"), s.runningSubagents.map { it.sessionId })
        assertTrue(sub.secondaryLine.startsWith("Explore · Fable 5.2 · "))
        // the tool inside the child still counts as a root-level tool lookup (permission requests find it)
        s.feed(jsonObj("sessionUpdate" to "tool_call_update", "toolCallId" to "ct1", "status" to "completed", "_meta" to child))
        assertEquals("completed", sub.entries.filterIsInstance<ChatEntry.Tool>().single().item.status)
        s.feed(jsonObj("sessionUpdate" to "subagent_update", "sessionId" to "c1", "state" to jsonObj("state" to "idle", "stopReason" to "end_turn")))
        assertFalse(sub.isRunning)
        assertEquals("end_turn", sub.stopReason)
        assertEquals("Explore the repo", sub.title)   // patch: an absent title leaves it
        assertTrue(s.runningSubagents.isEmpty())
    }

    @Test fun aChildUpdateBeforeItsAnnouncementPlacesTheSubagent() {
        val s = ChatStore("root")
        s.feed(jsonObj("sessionUpdate" to "agent_message_chunk", "content" to text("x"), "_meta" to jsonObj("leash" to jsonObj("subagent" to "late"))))
        assertEquals(1, s.entries.size)
        assertNotNull(s.subagent("late"))
    }

    @Test fun resetReplaysEventsFromScratch() {
        val s = ChatStore("s1")
        s.feed(chunk("agent_message_chunk", "old"))
        s.reset(listOf(jsonObj("seq" to 1, "update" to chunk("agent_message_chunk", "fresh")), jsonObj("seq" to 2, "update" to chunk("agent_message_chunk", " text"))))
        assertEquals("fresh text", (s.entries.single() as ChatEntry.Message).item.text)
    }

    @Test fun turnChangesGroupEditsByFileAndDetectACommit() {
        val s = ChatStore("s1")
        s.feed(chunk("user_message_chunk", "edit", "u"),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "e1", "title" to "Write new.txt", "kind" to "edit", "status" to "completed",
                "content" to listOf(jsonObj("type" to "diff", "path" to "/p/new.txt", "newText" to "one\ntwo"))),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "e2", "title" to "Edit a.txt", "kind" to "edit", "status" to "completed",
                "content" to listOf(jsonObj("type" to "diff", "path" to "/p/a.txt", "oldText" to "x\ny", "newText" to "x\nz"))),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "e3", "title" to "Edit a.txt", "kind" to "edit", "status" to "failed"),
            chunk("agent_message_chunk", "ok"))
        val c = s.displayItems().filterIsInstance<DisplayItem.Changes>().single().changes
        assertEquals(listOf("/p/new.txt", "/p/a.txt"), c.files.map { it.path })
        assertTrue(c.files[0].created)
        assertFalse(c.files[1].created)
        assertEquals(3, c.adds)
        assertEquals(1, c.dels)
        assertEquals("new.txt", c.files[0].relative("/p"))
        assertTrue(c.canCommit)
        s.feed(jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "g", "title" to "git commit", "kind" to "execute", "status" to "completed", "rawInput" to jsonObj("command" to "git -C /p commit -m x")))
        assertFalse(s.displayItems().filterIsInstance<DisplayItem.Changes>().single().changes.canCommit)
    }

    @Test fun commitDetectionIgnoresCommitTree() {
        fun ran(cmd: String): Boolean {
            val t = ToolItem("x").apply { kind = "execute"; rawInput = jsonObj("command" to cmd) }
            return TurnChanges.ranCommit(listOf(ChatEntry.Tool(t)))
        }
        assertTrue(ran("git commit -m hi"))
        assertTrue(ran("git -c user.name=x commit -m hi"))
        assertFalse(ran("git commit-tree abc"))
        assertTrue(ran("echo git commit"))   // a substring match, like iOS
    }

    @Test fun lineDiffKeepsContextAndCollapsesTheRest() {
        val old = (1..20).joinToString("\n")
        val new = old.replace("\n10\n", "\nten\n")
        val d = LineDiff.compute(old, new)
        assertEquals(1 to 1, LineDiff.stats(d))
        val h = LineDiff.hunks(d)
        assertTrue(h.first() is DiffLine.Gap)
        assertTrue(h.last() is DiffLine.Gap)
        assertEquals(DiffLine.Del("10"), h.first { it is DiffLine.Del })
        assertEquals(20 + 1, d.size)
        assertEquals(listOf(DiffLine.Add("a"), DiffLine.Add("b")), LineDiff.compute(null, "a\nb"))
    }

    @Test fun durationsAndPercentDecoding() {
        assertEquals("1m 3s", durationText(63.0))
        assertEquals("42s", durationText(42.4))
        assertEquals("1h 5m", durationText(3900.0))
        assertEquals("/a b/ü", FileRef.pathFromUri("file:///a%20b/%C3%BC"))
        assertEquals("/plain", FileRef.pathFromUri("/plain"))
        assertNull(FileRef.pathFromUri("https://x"))
        val (t, refs) = FileRef.extractLinks("look [@a.txt](file:///x/a.txt) here")
        assertEquals("look  here", t)
        assertEquals(listOf(FileRef("a.txt", "/x/a.txt")), refs)
    }

    @Test fun queueAndConfigParsing() {
        val q = QueuedMessage.list(parseJson("""[{"id":"agy:q1","text":"later","imageCount":1},{"text":"no id"}]"""))
        assertEquals(listOf(QueuedMessage("agy:q1", "later", 1, 0)), q)
        assertNull(ConfigOption.parse(parseJson("{}")))
    }
}
