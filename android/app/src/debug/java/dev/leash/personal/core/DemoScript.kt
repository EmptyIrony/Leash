package dev.leash.personal.core

import kotlinx.serialization.json.JsonElement

/** Scripted conversations of the demo bridge: update frames shaped like the ones the real bridge records. */
object DemoScript {
    const val EPOCH = "demo-epoch"
    const val HOME = "/Users/demo"
    const val PROJECT = "/Users/demo/workspace/leash"

    private fun text(t: String) = jsonObj("type" to "text", "text" to t)
    private val synthetic = jsonObj("leash" to jsonObj("synthetic" to true))

    fun user(t: String, id: String) = jsonObj("sessionUpdate" to "user_message_chunk", "content" to text(t), "messageId" to id, "_meta" to synthetic)
    /** A non-text block of a user message (an image or an attached file), echoed like the real bridge does. */
    fun userBlock(block: JsonElement, id: String) = jsonObj("sessionUpdate" to "user_message_chunk", "content" to block, "messageId" to id, "_meta" to synthetic)
    fun thought(t: String) = jsonObj("sessionUpdate" to "agent_thought_chunk", "content" to text(t))
    fun agent(t: String, id: String) = jsonObj("sessionUpdate" to "agent_message_chunk", "content" to text(t), "messageId" to id)
    fun tool(id: String, title: String, kind: String, status: String, vararg extra: Pair<String, Any?>) =
        jsonObj("sessionUpdate" to "tool_call", "toolCallId" to id, "title" to title, "kind" to kind, "status" to status, *extra)
    fun toolUpdate(id: String, status: String) = jsonObj("sessionUpdate" to "tool_call_update", "toolCallId" to id, "status" to status)
    fun stats(model: String = "demo-model") = jsonObj("sessionUpdate" to "leash_turn_stats", "usage" to jsonObj("inputTokens" to 12400, "outputTokens" to 860, "thoughtTokens" to 240, "cachedReadTokens" to 9000),
        "durationMs" to 18400, "ttftMs" to 900, "tokensPerSecond" to 46.7, "model" to model, "effort" to "medium", "stopReason" to "end_turn")

    val diffTool = tool("t-edit", "Edit Parser.kt", "edit", "completed",
        "locations" to listOf(jsonObj("path" to PROJECT + "/src/Parser.kt", "line" to 12)),
        "content" to listOf(jsonObj("type" to "diff", "path" to PROJECT + "/src/Parser.kt",
            "oldText" to "fun parse(s: String): Node {\n    return Node(s)\n}\n",
            "newText" to "fun parse(s: String): Node {\n    require(s.isNotBlank()) { \"empty input\" }\n    return Node(s.trim())\n}\n")))

    private val BT = 96.toChar().toString()
    private val FENCE = BT + BT + BT
    val ANSWER: String = "I tightened " + BT + "parse" + BT + " so blank input fails early:\n\n" + FENCE + "kotlin\n" +
        "fun parse(s: String): Node {\n    require(s.isNotBlank()) { \"empty input\" }\n    return Node(s.trim())\n}\n" + FENCE +
        "\n\nThe flow, for reference:\n\n![parser flow](/Users/demo/workspace/leash/docs/flow.png)\n\n- the **lexer** is untouched\n- tests pass"

    fun refactorHistory(): List<JsonElement> = listOf(
        user("Refactor the parser so blank input is rejected", "u1"),
        thought("The parser accepts anything today. "), thought("Check callers before tightening it."),
        tool("t-read", "Read Parser.kt", "read", "completed", "locations" to listOf(jsonObj("path" to PROJECT + "/src/Parser.kt"))),
        tool("t-grep", "Search parse(", "search", "completed"),
        diffTool,
        tool("t-test", "./gradlew test", "execute", "completed", "rawInput" to jsonObj("command" to "./gradlew test")),
        agent(ANSWER, "a1"), stats(),
    )

    private val tinyPng = DemoFiles.png(96, 64, boxes = false)

    /** A tool result's image, as the agent reports it. */
    private fun screenshot(width: Int, height: Int) = jsonObj("type" to "content",
        "content" to jsonObj("type" to "image", "mimeType" to "image/png", "data" to java.util.Base64.getEncoder().encodeToString(DemoFiles.png(width, height, boxes = true))))

    /** Files in and out of a conversation: an attachment and a photo in the prompt, paths, images and a "!" run in the reply. */
    fun filesHistory(): List<JsonElement> = listOf(
        user("Review the attached spec and this screenshot", "u1"),
        userBlock(jsonObj("type" to "resource_link", "uri" to "file://" + PROJECT + "/docs/spec.pdf", "name" to "spec.pdf", "size" to 1_284_000), "u1"),
        userBlock(jsonObj("type" to "image", "mimeType" to "image/png", "data" to java.util.Base64.getEncoder().encodeToString(tinyPng)), "u1"),
        user("<bash-input>ls docs</bash-input>", "u2"),
        user("<bash-stdout>big-shot.png\nflow.png\nspec.pdf\n</bash-stdout><bash-stderr></bash-stderr>", "u3"),
        tool("t-read2", "Read spec.pdf", "read", "completed", "locations" to listOf(jsonObj("path" to PROJECT + "/docs/spec.pdf"))),
        tool("t-read3", "Read flow.png", "read", "completed", "locations" to listOf(jsonObj("path" to PROJECT + "/docs/flow.png")),
            "content" to listOf(screenshot(480, 280))),
        tool("t-shot", "Take a screenshot", "other", "completed",
            "content" to listOf(jsonObj("type" to "content", "content" to text("Captured the running app.")), screenshot(540, 1200))),
        agent(
            "I went through [the spec](" + PROJECT + "/docs/spec.pdf). The parser entry point is in " + BT + "src/Parser.kt:12" + BT + ".\n\n" +
                "The flow is in " + BT + "docs/flow.png" + BT + " and a taller capture is in " + BT + "docs/big-shot.png" + BT + ".\n\n" +
                "Things I could not open: " + BT + "build/archive.zip" + BT + ", " + BT + "logs/huge.log" + BT + " and " + BT + "data/large.bin" + BT + ", and " + BT + "missing/none.pdf" + BT + ".\n\n" +
                "![parser flow](docs/flow.png)\n\n![gone](docs/gone.png)",
            "a1",
        ),
        stats(),
    )

    /** Three files changed in one turn: one edited, one created, one deleted. */
    fun changesHistory(): List<JsonElement> {
        fun edit(id: String, name: String, path: String, old: String, new: String) = tool(id, "Edit $name", "edit", "completed",
            "locations" to listOf(jsonObj("path" to path)),
            "content" to listOf(jsonObj("type" to "diff", "path" to path, "oldText" to old, "newText" to new)))
        return listOf(
            user("Rename the helpers and drop the old file", "u1"),
            edit("c1", "Parser.kt", PROJECT + "/src/Parser.kt", "fun parseAll(s: String) = s\n\nfun helper() = 1\n", "fun parseInput(s: String) = s.trim()\n\nfun helper() = 2\n"),
            edit("c2", "Lexer.kt", PROJECT + "/src/Lexer.kt", "", "package demo\n\nfun lex(s: String) = s.split(' ')\n"),
            tool("c3", "Delete Old.kt", "delete", "completed", "locations" to listOf(jsonObj("path" to PROJECT + "/src/Old.kt"))),
            agent("Renamed " + BT + "parseAll" + BT + " to " + BT + "parseInput" + BT + ", added the lexer and removed " + BT + "Old.kt" + BT + ".", "a1"),
            stats(),
        )
    }

    /** An Antigravity answer with a widget (generative UI). */
    fun embedHistory(): List<JsonElement> = listOf(
        user("Chart the benchmark results", "u1"),
        thought("A bar chart will do."),
        agent("Here is how the three parsers compare:\n\n<agent-embed src=\"file://" + DemoFiles.WIDGET + "\"></agent-embed>\n\nThe first one wins by a margin.", "a1"),
    )

    fun permissionHistory(): List<JsonElement> = listOf(
        user("Clean up the build directory", "u1"),
        thought("I need to delete generated files."),
        tool("t-rm", "rm -rf build", "execute", "pending", "rawInput" to jsonObj("command" to "rm -rf build")),
    )

    fun queueHistory(): List<JsonElement> = listOf(
        user("Migrate the database schema", "u1"),
        thought("Planning the migration order."),
        tool("t-mig", "npm run migrate", "execute", "in_progress", "rawInput" to jsonObj("command" to "npm run migrate")),
    )

    fun subagentHistory(): List<JsonElement> {
        val child = jsonObj("leash" to jsonObj("subagent" to "demo-child"))
        return listOf(
            user("Audit the build scripts", "u1"),
            jsonObj("sessionUpdate" to "subagent_update", "sessionId" to "demo-child", "title" to "Read every Gradle file", "description" to "Explore", "state" to jsonObj("state" to "running")),
            jsonObj("sessionUpdate" to "session_message", "messageId" to "m-in", "senderSessionId" to "demo-subagent", "recipientSessionId" to "demo-child", "content" to listOf(text("List the Gradle files")), "_meta" to child),
            jsonObj("sessionUpdate" to "agent_message_chunk", "content" to text("Found 3 build files."), "_meta" to child),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "c-read", "title" to "Read build.gradle.kts", "kind" to "read", "status" to "completed", "_meta" to child),
            jsonObj("sessionUpdate" to "subagent_update", "sessionId" to "demo-child", "state" to jsonObj("state" to "idle", "stopReason" to "end_turn")),
            agent("The scripts look fine; one plugin is outdated.", "a1"), stats(),
        )
    }

    /** A parent still waiting on its subagent, which has asked for permission. */
    fun subagentLiveHistory(): List<JsonElement> {
        val child = jsonObj("leash" to jsonObj("subagent" to "demo-child"))
        return listOf(
            user("Index the repository", "u1"),
            jsonObj("sessionUpdate" to "subagent_update", "sessionId" to "demo-child", "title" to "Count the source files", "description" to "Explore", "state" to jsonObj("state" to "requires_action")),
            jsonObj("sessionUpdate" to "session_message", "messageId" to "m-in", "senderSessionId" to "demo-live", "recipientSessionId" to "demo-child", "content" to listOf(text("Count the source files")), "_meta" to child),
            jsonObj("sessionUpdate" to "tool_call", "toolCallId" to "c-find", "title" to "find . -name '*.kt'", "kind" to "execute", "status" to "pending", "_meta" to child),
        )
    }

    fun permissionRequest(key: String, sessionId: String, agent: String, withDiff: Boolean = false, subagent: Boolean = false): JsonElement {
        val toolCall = if (withDiff) jsonObj("toolCallId" to "t-edit-ask", "title" to "Edit Parser.kt", "kind" to "edit", "locations" to diffTool["locations"], "content" to diffTool["content"])
        else jsonObj("toolCallId" to "t-rm", "title" to "rm -rf build", "kind" to "execute", "rawInput" to jsonObj("command" to "rm -rf build"))
        val options = listOf(jsonObj("optionId" to "allow", "name" to "Allow", "kind" to "allow_once"), jsonObj("optionId" to "always", "name" to "Always allow", "kind" to "allow_always"), jsonObj("optionId" to "deny", "name" to "Deny", "kind" to "reject_once"))
        val fields = linkedMapOf<String, Any?>("op" to "request", "agentId" to agent, "key" to key, "method" to "session/request_permission", "sessionId" to sessionId, "params" to jsonObj("toolCall" to toolCall, "options" to options))
        if (subagent) fields["subagent"] = jsonObj("sessionId" to "demo-child", "title" to "Count the source files", "description" to "Explore")
        return j(fields)
    }

    fun questionRequest(key: String, sessionId: String, agent: String) = jsonObj("op" to "request", "agentId" to agent, "key" to key, "method" to "elicitation/create", "sessionId" to sessionId,
        "params" to jsonObj("message" to "Which database should the migration target?",
            "requestedSchema" to jsonObj("type" to "object", "required" to listOf("question_0"), "properties" to jsonObj("question_0" to jsonObj("type" to "string", "title" to "Target database",
                "oneOf" to listOf(jsonObj("const" to "postgres", "title" to "PostgreSQL", "description" to "Recommended"), jsonObj("const" to "sqlite", "title" to "SQLite")))))))

    /** A form with several questions: it opens the question sheet instead of fitting in the tray. */
    fun formRequest(key: String, sessionId: String, agent: String) = jsonObj("op" to "request", "agentId" to agent, "key" to key, "method" to "elicitation/create", "sessionId" to sessionId,
        "params" to jsonObj("message" to "A few questions before I start.",
            "requestedSchema" to jsonObj("type" to "object", "required" to listOf("question_0"), "properties" to jsonObj(
                "question_0" to jsonObj("type" to "string", "title" to "Target database", "oneOf" to listOf("postgres", "sqlite", "mysql", "mongo", "redis").map { jsonObj("const" to it, "title" to it) }),
                "question_0_custom" to jsonObj("type" to "string", "title" to "Other"),
                "question_1" to jsonObj("type" to "string", "title" to "Notes", "description" to "Anything else I should know?"),
                "question_2" to jsonObj("type" to "boolean", "title" to "Run the tests afterwards", "default" to true)))))

    fun configOptions(model: String = "sonnet", effort: String = "medium") = listOf(
        jsonObj("id" to "model", "name" to "Model", "category" to "model", "type" to "select", "currentValue" to model,
            "options" to listOf(jsonObj("value" to "sonnet", "name" to "Sonnet 5.5"), jsonObj("value" to "opus", "name" to "Opus 5.5"))),
        jsonObj("id" to "thought_level", "name" to "Thinking", "category" to "thought_level", "type" to "select", "currentValue" to effort,
            "options" to listOf(jsonObj("value" to "low", "name" to "Low"), jsonObj("value" to "medium", "name" to "Medium"), jsonObj("value" to "high", "name" to "High"))))

    fun modes(current: String = "default") = jsonObj("currentModeId" to current, "availableModes" to listOf(jsonObj("id" to "default", "name" to "Manual"), jsonObj("id" to "acceptEdits", "name" to "Accept edits"), jsonObj("id" to "plan", "name" to "Plan")))
    val commands = listOf(jsonObj("name" to "review", "description" to "Review a pull request", "input" to jsonObj("hint" to "pr-number")), jsonObj("name" to "init", "description" to "Create CLAUDE.md"))

    /** Claude Code's usage: a general pool running low and a model-scoped one (the model chip picks the limiting bucket). */
    fun claudeQuota(): JsonElement {
        fun iso(afterMs: Long) = java.time.Instant.ofEpochMilli(Clock.now() + afterMs).toString()
        return jsonObj("planTier" to "Max", "fetchedAt" to Clock.now(), "pools" to listOf(
            jsonObj("id" to "claude", "label" to "Claude", "buckets" to listOf(
                jsonObj("id" to "c5", "window" to "5h", "remainingFraction" to 0.38, "resetTime" to iso(2 * 3_600_000L + 600_000L)),
                jsonObj("id" to "cw", "window" to "weekly", "remainingFraction" to 0.11, "resetTime" to iso(3 * 86_400_000L)))),
            jsonObj("id" to "fable", "label" to "Fable", "model" to "Fable", "buckets" to listOf(
                jsonObj("id" to "f5", "window" to "5h", "remainingFraction" to 0.9, "resetTime" to iso(4 * 3_600_000L)),
                jsonObj("id" to "fw", "window" to "weekly", "remainingFraction" to 0.64, "resetTime" to iso(5 * 86_400_000L))))))
    }

    /** The reply to a prompt, streamed word by word. */
    val replyWords: List<String> = ANSWER.split(" ").map { it + " " }
}
