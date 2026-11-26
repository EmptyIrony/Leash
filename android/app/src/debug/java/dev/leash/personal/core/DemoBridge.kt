package dev.leash.personal.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * A fake bridge behind the BridgeLink interface (debug builds): answers the calls the app makes from scripted
 * conversations, streams a turn for session/prompt, and keeps a queue, permission requests and questions, so UI can be
 * checked without a reachable bridge. Nothing here touches the network.
 */
class DemoBridge(private val scope: CoroutineScope) : BridgeLink {
    override var state: LinkState = LinkState.Idle
        private set(v) { if (field != v) { field = v; onState?.invoke(v) } }
    override var onMessage: ((JsonElement) -> Unit)? = null
    override var onState: ((LinkState) -> Unit)? = null
    override var visible: Boolean = false

    private class Sess(val id: String, val agent: String, var title: String?, val updatedAt: String, val lockedBy: String? = null) {
        val events = mutableListOf<JsonElement>()
        var seq = 0
        var running = false
        var subscribed = false
        val queue = mutableListOf<JsonElement>()
        var job: Job? = null
        var nextQueue = 1
        var changedOnce = false
        var model = "sonnet"
        var effort = "medium"
        var mode = "default"
    }

    private val sessions = LinkedHashMap<String, Sess>()
    private val pending = LinkedHashMap<String, JsonElement>()
    private val answers = HashMap<String, CompletableDeferred<JsonElement>>()
    private var generation = 0
    private var nextKey = 100
    private var nextSession = 1

    init {
        fun add(id: String, agent: String, title: String?, at: String, locked: String? = null, events: List<JsonElement> = emptyList()): Sess {
            val s = Sess(id, agent, title, at, locked)
            events.forEach { s.events += it; s.seq++ }
            sessions[id] = s
            return s
        }
        add("demo-refactor", "claude", "Refactor the parser", "2026-11-26T01:00:00Z", events = DemoScript.refactorHistory())
        add("demo-permission", "claude", "Clean up the build directory", "2026-11-26T02:00:00Z", events = DemoScript.permissionHistory()).running = true
        add("demo-queue", "agy", "Migrate the database schema", "2026-11-26T02:10:00Z", events = DemoScript.queueHistory()).also { s ->
            s.running = true
            s.queue += jsonObj("id" to "agy:q1", "text" to "Also add an index on user_id", "imageCount" to 0, "fileCount" to 0)
            s.queue += jsonObj("id" to "agy:q2", "text" to "Then run the tests", "imageCount" to 1, "fileCount" to 0)
        }
        add("demo-subagent", "claude", "Audit the build scripts", "2026-11-25T20:00:00Z", events = DemoScript.subagentHistory())
        add("demo-locked", "claude", "Open in the terminal", "2026-11-25T10:00:00Z", locked = "cli")
        // a folder with more than a handful of conversations, for the list's "Show all"
        for (n in 1..5) add("demo-old-$n", "claude", "Older conversation $n", "2026-11-2" + (5 - n) + "T09:00:00Z", events = listOf(DemoScript.user("An older question $n", "u1"), DemoScript.agent("An older answer $n.", "a1")))
        add("demo-empty", "agy", null, "2026-11-24T10:00:00Z")
        add("demo-files", "claude", "Review the attached spec", "2026-11-26T01:30:00Z", events = DemoScript.filesHistory())
        add("demo-changes", "claude", "Rename the helpers", "2026-11-26T01:40:00Z", events = DemoScript.changesHistory())
        add("demo-embed", "agy", "Chart the benchmark results", "2026-11-26T01:50:00Z", events = DemoScript.embedHistory())
        add("demo-subagent-live", "claude", "Index the repository", "2026-11-26T02:20:00Z", events = DemoScript.subagentLiveHistory()).running = true
        pending["claude:3"] = DemoScript.permissionRequest("claude:3", "demo-subagent-live", "claude", subagent = true)
        pending["claude:1"] = DemoScript.permissionRequest("claude:1", "demo-permission", "claude")
        pending["claude:2"] = DemoScript.questionRequest("claude:2", "demo-permission", "claude")
    }

    private fun emit(m: JsonElement) { onMessage?.invoke(m) }

    // MARK: connection

    override fun configure(host: String, port: Int, token: String) {}

    override fun connect() {
        val gen = ++generation
        state = LinkState.Connecting
        scope.launch {
            delay(150)
            if (gen != generation) return@launch
            state = LinkState.Connected
            emit(ready())
        }
    }

    override fun ensureConnected() { if (state != LinkState.Connected && state != LinkState.Connecting) connect() }

    override fun reconnect() {}   // the device's network changing means nothing here

    override fun disconnect() {
        generation++
        sessions.values.forEach { it.subscribed = false }
        state = LinkState.Idle
    }

    private fun ready() = jsonObj(
        "op" to "ready", "bridge" to jsonObj("version" to "demo", "protocol" to 1, "hostname" to "demo-bridge", "home" to DemoScript.HOME, "multiAgent" to true),
        "agent" to jsonObj("state" to "ready"),
        "agents" to listOf(
            jsonObj("id" to "agy", "label" to "Antigravity", "state" to "ready", "home" to DemoScript.HOME, "turbo" to true, "steering" to false, "btw" to false, "cliList" to false,
                "info" to jsonObj("agentInfo" to jsonObj("title" to "Antigravity (demo)"), "agentCapabilities" to jsonObj("promptCapabilities" to jsonObj("image" to true))),
                "quota" to quota()),
            jsonObj("id" to "claude", "label" to "Claude Code", "state" to "ready", "home" to DemoScript.HOME, "steering" to true, "btw" to true, "cliList" to true, "turbo" to false,
                "info" to jsonObj("agentInfo" to jsonObj("title" to "Claude Code (demo)"), "agentCapabilities" to jsonObj("promptCapabilities" to jsonObj("image" to true))),
                "quota" to DemoScript.claudeQuota())),
        "pending" to pending.values.toList(),
        "running" to sessions.values.filter { it.running }.map { it.id },
    )

    private fun quota() = jsonObj("planTier" to "demo", "fetchedAt" to Clock.now(), "pools" to listOf(
        jsonObj("id" to "gemini", "label" to "Gemini", "buckets" to listOf(jsonObj("id" to "g5", "window" to "5h", "remainingFraction" to 0.62), jsonObj("id" to "gw", "window" to "weekly", "remainingFraction" to 0.85)))))

    // MARK: calls

    override suspend fun call(method: String, params: JsonElement, timeoutMs: Long?): JsonElement {
        if (state != LinkState.Connected) throw BridgeError.notConnected()
        delay(40)
        val s = params["sessionId"].string?.let { sessions[it] }
        return when (method) {
            "initialize" -> jsonObj("protocolVersion" to 1, "agentInfo" to jsonObj("name" to "demo", "version" to "0"), "agentCapabilities" to jsonObj("loadSession" to true))
            "session/list" -> jsonObj("sessions" to sessions.values.map { listed(it) })
            "bridge/attach" -> attach(s ?: throw BridgeError(-32004, "unknown session"), params)
            "bridge/detach" -> { s?.subscribed = false; jsonObj("ok" to true) }
            "session/new" -> newSession(params)
            "session/prompt" -> prompt(s ?: throw BridgeError(-32004, "attach to the session before prompting"), params)
            "session/cancel" -> { s?.let { cancel(it) }; jsonObj("ok" to true) }
            "session/set_config_option" -> {
                val value = params["value"].string ?: "sonnet"
                s?.let { if (params["configId"].string == "thought_level") it.effort = value else it.model = value }
                jsonObj("configOptions" to DemoScript.configOptions(s?.model ?: "sonnet", s?.effort ?: "medium"))
            }
            "session/set_mode" -> { s?.mode = params["modeId"].string ?: "default"; jsonObj() }
            "bridge/setAutoApprove" -> jsonObj("ok" to true, "autoApprove" to (params["enabled"].bool == true))
            "bridge/unqueue" -> unqueue(s ?: throw BridgeError(-32004, "unknown session"), params["id"].string)
            "bridge/requeue" -> requeue(s ?: throw BridgeError(-32004, "unknown session"), params)
            "bridge/quota" -> jsonObj("quota" to (if (params["agent"].string == "claude") DemoScript.claudeQuota() else quota()))
            "bridge/noticed", "bridge/restartAgent" -> jsonObj("ok" to true)
            "bridge/browse" -> jsonObj("path" to (params["path"].string ?: DemoScript.HOME), "parent" to DemoScript.HOME.takeIf { params["path"].string != null },
                "isGit" to true, "dirs" to listOf("workspace", "Documents"), "files" to listOf("notes.md"))
            "bridge/readFile" -> readFile(params)
            "bridge/upload" -> upload(params)
            "bridge/shell" -> shell(s ?: throw BridgeError(-32004, "unknown session"), params)
            "bridge/btw" -> btw(params)
            "bridge/cliVisibility" -> jsonObj("visible" to true)
            "bridge/showInCli" -> jsonObj("ok" to true)
            else -> throw BridgeError(-32601, "method not allowed: " + method)
        }
    }

    override suspend fun replyConfirmed(key: String, result: JsonElement, timeoutMs: Long): JsonElement {
        delay(40)
        if (!resolve(key, result)) throw BridgeError(BridgeError.ALREADY_RESOLVED, "already resolved")
        return jsonObj("ok" to true)
    }

    override fun reply(key: String, result: JsonElement) {
        if (state != LinkState.Connected) throw BridgeError.notConnected()
        resolve(key, result)
    }

    private fun resolve(key: String, result: JsonElement): Boolean {
        val req = pending.remove(key) ?: return false
        answers.remove(key)?.complete(result)
        emit(jsonObj("op" to "resolved", "key" to key, "sessionId" to req["sessionId"].string))
        return true
    }

    private fun listed(s: Sess) = jsonObj("sessionId" to s.id, "cwd" to DemoScript.PROJECT, "title" to s.title, "updatedAt" to s.updatedAt,
        "_leash" to jsonObj("agent" to s.agent, "running" to s.running, "subagents" to 0, "loaded" to s.subscribed, "lockedBy" to s.lockedBy,
            "pending" to pending.values.count { it["sessionId"].string == s.id }, "stopReason" to (if (s.running) null else "end_turn"),
            "turnEndedAt" to (if (s.running) null else Clock.now() - 3_600_000)))

    private fun attach(s: Sess, params: JsonElement): JsonElement {
        s.lockedBy?.let { throw BridgeError(BridgeError.LOCKED_ELSEWHERE, "this conversation is open in another client", it) }
        s.subscribed = true
        val since = params["sinceSeq"].int ?: 0
        val delta = params["epoch"].string == DemoScript.EPOCH && since > 0 && since <= s.seq
        return jsonObj("sessionId" to s.id, "cwd" to DemoScript.PROJECT, "epoch" to DemoScript.EPOCH, "lastSeq" to s.seq, "reset" to !delta,
            "events" to s.events.mapIndexed { i, u -> jsonObj("seq" to i + 1, "update" to u) }.filter { !delta || it["seq"].int!! > since },
            "running" to s.running, "subagents" to 0, "lastStopReason" to "end_turn", "turnStartedAt" to (if (s.running) Clock.now() - 20_000 else null),
            "configOptions" to DemoScript.configOptions(s.model, s.effort), "modes" to DemoScript.modes(s.mode), "title" to s.title, "autoApprove" to false,
            "queue" to s.queue, "availableCommands" to DemoScript.commands, "pending" to pending.values.filter { it["sessionId"].string == s.id })
    }

    private fun newSession(params: JsonElement): JsonElement {
        val id = "demo-new-" + nextSession++
        val agent = params["agent"].string ?: "agy"
        sessions[id] = Sess(id, agent, null, "2026-11-26T03:00:00Z").also { it.subscribed = true }
        emit(jsonObj("op" to "session_created", "sessionId" to id, "cwd" to params["cwd"].string))
        return jsonObj("sessionId" to id, "epoch" to DemoScript.EPOCH, "lastSeq" to 0, "configOptions" to DemoScript.configOptions(), "modes" to DemoScript.modes())
    }

    // MARK: files, shell, side questions

    private fun readFile(params: JsonElement): JsonElement {
        val path = params["path"].string ?: throw BridgeError(-32602, "path required")
        val entry = DemoFiles.find(path) ?: throw BridgeError(-32006, "ENOENT: no such file or directory, open '" + path + "'")
        val abs = DemoFiles.absolute(path)
        if (params["mode"].string == "base64") {
            if (DemoFiles.tooLargeForDownload(path) || entry.bytes.size > (params["maxBytes"].int ?: Int.MAX_VALUE)) throw BridgeError(FileLimits.FILE_TOO_LARGE, "file too large (limit 32 MB)")
            return jsonObj("path" to abs, "size" to entry.bytes.size, "mimeType" to entry.mime, "binary" to entry.binary, "data" to java.util.Base64.getEncoder().encodeToString(entry.bytes))
        }
        val n = minOf(entry.bytes.size, 512 * 1024)
        return jsonObj("path" to abs, "size" to entry.bytes.size, "truncated" to (DemoFiles.tooLargeForDownload(path) || entry.bytes.size > n), "binary" to entry.binary,
            "text" to (if (entry.binary) null else String(entry.bytes, 0, n, Charsets.UTF_8)))
    }

    /** The phone's file lands under the host's uploads folder, and can be read back from there. */
    private fun upload(params: JsonElement): JsonElement {
        val bytes = try { java.util.Base64.getDecoder().decode(params["data"].string ?: "") } catch (e: IllegalArgumentException) { throw BridgeError(-32602, "data (base64) required") }
        if (bytes.size > MAX_UPLOAD) throw BridgeError(FileLimits.FILE_TOO_LARGE, "file too large")
        val name = (params["name"].string ?: "file").replace(Regex("[\\s\\[\\]]+"), "_")
        val path = DemoScript.HOME + "/.leash/uploads/" + (params["sessionId"].string ?: "misc") + "/" + name
        val mime = DemoFiles.mimeFor(name)
        DemoFiles.store(path, bytes, mime)
        return jsonObj("path" to path, "uri" to "file://" + path, "name" to name, "size" to bytes.size, "mimeType" to mime)
    }

    /** "!" shell mode: the command and its output go into the transcript the way Claude Code writes them. */
    private suspend fun shell(s: Sess, params: JsonElement): JsonElement {
        val cmd = (params["command"].string ?: "").trim()
        if (cmd.isEmpty()) throw BridgeError(-32602, "command required")
        if (s.running) throw BridgeError(-32022, "the agent is working; run shell commands between turns")
        emit(jsonObj("op" to "shell", "sessionId" to s.id, "running" to true))
        delay(700)
        val (out, err, code) = when {
            cmd == "pwd" -> Triple(DemoScript.PROJECT + "\n", "", 0)
            cmd.startsWith("ls") -> Triple("README.md\nbuild.gradle.kts\ndocs\nsrc\n", "", 0)
            cmd.startsWith("echo ") -> Triple(cmd.removePrefix("echo ").trim('"', '\'') + "\n", "", 0)
            cmd == "false" -> Triple("", "", 1)
            else -> Triple("", "demo: " + cmd.substringBefore(' ') + ": command not found\n", 127)
        }
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        record(s, DemoScript.user("<bash-input>" + esc(cmd) + "</bash-input>", "sh-in-" + s.seq))
        record(s, DemoScript.user("<bash-stdout>" + esc(out) + "</bash-stdout><bash-stderr>" + esc(err) + "</bash-stderr>" + (if (code != 0) "<bash-exit-code>$code</bash-exit-code>" else ""), "sh-out-" + s.seq))
        emit(jsonObj("op" to "shell", "sessionId" to s.id, "running" to false))
        return jsonObj("exitCode" to code, "stdout" to out, "stderr" to err)
    }

    /** /btw: a side answer; "long" in the question makes it taller than the card, "fail" refuses. */
    private suspend fun btw(params: JsonElement): JsonElement {
        val q = (params["question"].string ?: "").lowercase()
        delay(900)
        if ("fail" in q) throw BridgeError(-32000, "The demo can't answer that")
        val text = if ("long" in q) (1..14).joinToString("\n\n") { "**Point $it.** The parser reads its input once, tokenises it and hands the tokens to the next stage, which is why blank input has to be rejected before it starts." }
        else "It is a **scripted** side answer from the demo bridge, with `inline code` and a list:\n\n- one\n- two"
        return jsonObj("text" to text, "durationMs" to 800, "model" to "demo")
    }

    // MARK: turns

    private fun record(s: Sess, update: JsonElement) {
        s.events += update
        s.seq++
        if (s.subscribed) emit(jsonObj("op" to "update", "agentId" to s.agent, "sessionId" to s.id, "epoch" to DemoScript.EPOCH, "seq" to s.seq, "update" to update))
    }

    private fun broadcastTurn(s: Sess, running: Boolean, stopReason: String? = null) =
        emit(jsonObj("op" to "turn", "agentId" to s.agent, "sessionId" to s.id, "running" to running, "subagents" to 0,
            "stopReason" to (if (running) null else stopReason), "startedAt" to (if (running) Clock.now() else null)))

    private fun broadcastQueue(s: Sess) = emit(jsonObj("op" to "queue", "agentId" to s.agent, "sessionId" to s.id, "items" to s.queue.toList()))

    private fun promptText(params: JsonElement) =
        (params["prompt"].array ?: emptyList()).filter { it["type"].string == "text" }.joinToString("\n") { it["text"].string ?: "" }

    private fun takeQueue(s: Sess): List<String> = s.queue.map { it["text"].string ?: "" }.also { s.queue.clear(); broadcastQueue(s) }

    private suspend fun prompt(s: Sess, params: JsonElement): JsonElement {
        val text = promptText(params)
        val blocks = params["prompt"].array ?: emptyList()
        // error states for UI checks: the words in the message pick what the demo answers with
        val low = text.lowercase()
        if ("locked elsewhere" in low) throw BridgeError(BridgeError.LOCKED_ELSEWHERE, "Open in another client", "cli")
        if ("changed elsewhere" in low && !s.changedOnce) { s.changedOnce = true; throw BridgeError(BridgeError.CHANGED_ELSEWHERE, "The conversation changed elsewhere") }
        if ("refuse" in low) throw BridgeError(-32000, "The demo agent refuses this message")
        if (!s.running) {
            val items = takeQueue(s) + listOfNotNull(text.takeIf { blocks.isNotEmpty() })
            if (items.isEmpty()) throw BridgeError(-32602, "prompt required")
            return runTurn(s, items.filter { it.isNotEmpty() }, blocks.filter { it["type"].string != "text" })
        }
        if (params["interrupt"].bool == true) {
            if (blocks.isNotEmpty()) s.queue += jsonObj("id" to s.agent + ":q" + s.nextQueue++, "text" to text, "imageCount" to 0, "fileCount" to 0)
            broadcastQueue(s)
            val old = s.job
            cancel(s)
            scope.launch { old?.join(); runTurn(s, takeQueue(s)) }
            return jsonObj("queued" to true, "interrupting" to true)
        }
        if (blocks.isEmpty()) throw BridgeError(-32602, "prompt required")
        if (s.agent == "claude") {   // steering: injected into the running turn
            record(s, DemoScript.user(text, "u-steer-" + s.seq))
            return jsonObj("steered" to true)
        }
        val id = s.agent + ":q" + s.nextQueue++
        s.queue += jsonObj("id" to id, "text" to text, "imageCount" to 0, "fileCount" to 0)
        broadcastQueue(s)
        return jsonObj("queued" to true, "id" to id)
    }

    /** One scripted turn; returns when it ends (the real bridge answers session/prompt then). */
    private suspend fun runTurn(s: Sess, items: List<String>, extra: List<JsonElement> = emptyList()): JsonElement {
        val done = CompletableDeferred<JsonElement>()
        s.running = true
        s.job = scope.launch {
            var stop = "end_turn"
            try {
                broadcastTurn(s, true)
                val uid = "u-" + s.seq
                record(s, DemoScript.user(items.joinToString("\n"), uid))
                extra.forEach { record(s, DemoScript.userBlock(it, uid)) }
                delay(300)
                record(s, DemoScript.thought("Let me look at "))
                delay(250)
                record(s, DemoScript.thought("what this needs."))
                delay(300)
                val rid = "t-r" + s.seq
                record(s, DemoScript.tool(rid, "Read Parser.kt", "read", "in_progress", "locations" to listOf(jsonObj("path" to DemoScript.PROJECT + "/src/Parser.kt"))))
                delay(350)
                record(s, DemoScript.toolUpdate(rid, "completed"))
                // "slow": time to leave the app before the request arrives and before the turn ends
                val slow = items.any { it.contains("slow", ignoreCase = true) }
                if (slow) delay(SLOW_MS)
                if (items.any { it.contains("questions", ignoreCase = true) }) {
                    val key = s.agent + ":" + nextKey++
                    val req = DemoScript.formRequest(key, s.id, s.agent)
                    pending[key] = req
                    val answer = CompletableDeferred<JsonElement>().also { answers[key] = it }
                    emit(req)
                    answer.await()
                }
                if (items.any { it.contains("permission", ignoreCase = true) }) {
                    val key = s.agent + ":" + nextKey++
                    val req = DemoScript.permissionRequest(key, s.id, s.agent, withDiff = items.any { it.contains("diff", ignoreCase = true) })
                    pending[key] = req
                    val answer = CompletableDeferred<JsonElement>().also { answers[key] = it }
                    emit(req)
                    val outcome = answer.await()["outcome"]
                    if (outcome["outcome"].string == "cancelled" || outcome["optionId"].string == "deny") {
                        record(s, DemoScript.agent("Okay, I skipped it.", "a-" + s.seq))
                        return@launch
                    }
                }
                if (slow) delay(SLOW_MS)
                val diff = DemoScript.diffTool
                record(s, DemoScript.tool("t-e" + s.seq, "Edit Parser.kt", "edit", "completed", "locations" to diff["locations"], "content" to diff["content"]))
                delay(300)
                val mid = "a-" + s.seq
                for (w in DemoScript.replyWords) { record(s, DemoScript.agent(w, mid)); delay(25) }
                record(s, DemoScript.stats())
            } catch (e: CancellationException) {
                stop = "cancelled"
                throw e
            } finally {
                s.running = false
                if (s.title == null) s.title = items.first().take(40)
                broadcastTurn(s, false, stop)
                done.complete(jsonObj("stopReason" to stop, "usage" to jsonObj("inputTokens" to 1000, "outputTokens" to 40)))
                // anything queued during the turn goes out as the next one
                if (stop == "end_turn" && s.queue.isNotEmpty()) {
                    val next = takeQueue(s)
                    scope.launch { runTurn(s, next) }
                }
            }
        }
        return done.await()
    }

    private fun cancel(s: Sess) {
        pending.entries.filter { it.value["sessionId"].string == s.id }.map { it.key }.forEach { resolve(it, jsonObj("outcome" to jsonObj("outcome" to "cancelled"))) }
        val j = s.job
        if (j != null) j.cancel()
        else if (s.running) { s.running = false; broadcastTurn(s, false, "cancelled") }   // a scripted "already running" conversation
    }

    private fun unqueue(s: Sess, id: String?): JsonElement {
        val i = s.queue.indexOfFirst { it["id"].string == id }
        if (i < 0) throw BridgeError(BridgeError.ALREADY_SENT, "already sent")
        val q = s.queue.removeAt(i)
        broadcastQueue(s)
        // a queued message that held a photo gives it back with its text
        val photos = (0 until (q["imageCount"].int ?: 0)).map { jsonObj("type" to "image", "mimeType" to "image/png", "data" to java.util.Base64.getEncoder().encodeToString(DemoFiles.png(160, 120, boxes = true))) }
        return jsonObj("prompt" to listOf(jsonObj("type" to "text", "text" to q["text"].string)) + photos)
    }

    private fun requeue(s: Sess, params: JsonElement): JsonElement {
        val text = (params["prompt"].array ?: emptyList()).firstOrNull { it["type"].string == "text" }["text"].string ?: ""
        s.queue.add(minOf(params["index"].int ?: s.queue.size, s.queue.size), jsonObj("id" to params["id"].string, "text" to text, "imageCount" to 0, "fileCount" to 0))
        broadcastQueue(s)
        return jsonObj("ok" to true)
    }
}

private const val SLOW_MS = 8_000L
private const val MAX_UPLOAD = 25 * 1024 * 1024
