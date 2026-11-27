package dev.leash.personal.core

import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Per-session transcript store (ios Core/ChatModels.swift ChatStore). Plain fields; every change bumps [version], so the
 * UI collects it and re-reads whatever it shows. Touch it from one thread (Main), like the rest of the model.
 */
class ChatStore(val sessionId: String) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /** Call after changing a nested item (a message streaming into its row, a tool's status). */
    fun touch() { _version.value = _version.value + 1 }

    private class Tracked<T>(private var value: T, private val changed: () -> Unit) : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (this.value != value) { this.value = value; changed() }
        }
    }

    private fun <T> tracked(initial: T) = Tracked(initial, ::touch)

    var agent by tracked("agy")
    var cwd: String? by tracked(null)
    var title: String? by tracked(null)
    val entries = mutableListOf<ChatEntry>()
    var running by tracked(false)
    /** A "!" shell command from any device is running on the agent's host. */
    var shellRunning by tracked(false)
    var lastStopReason: String? by tracked(null)
    var configOptions: List<ConfigOption> by tracked(emptyList())
    var modes: List<SessionMode> by tracked(emptyList())
    var currentMode: String? by tracked(null)
    var commands: List<AgentCommand> by tracked(emptyList())
    /** (used, size) of the context window */
    var usage: Pair<Int, Int>? by tracked(null)
    var autoApprove by tracked(false)
    var turnStartedAtMs: Long? by tracked(null)
    var attached by tracked(false)
    var loading by tracked(false)
    /**
     * What went wrong with what is in the composer: a send refused before any turn started (the draft is back), a file that
     * couldn't be attached or uploaded, a change the agent refused. Shown until dismissed or the next send; a turn that
     * fails once running is a transcript notice instead.
     */
    var composerError: Problem? by tracked(null)
    /** The conversation couldn't be loaded; shown in place of the transcript (or under it) with Try Again. */
    var loadError: Problem? by tracked(null)
    /** Set while another client (Claude Code terminal / desktop app) has this conversation open: "cli", "claude-desktop", … */
    var lockedBy: String? by tracked(null)
    /** Text the bridge refused to send (conversation locked) or a queued message taken back; the composer puts it back. */
    var unsentDraft: String? by tracked(null)
    var unsentImages: List<ByteArray>? by tracked(null)
    /** Messages sent mid-turn that wait on the bridge for the turn to end (agents without steering, or "interrupt and send"). */
    var queued: List<QueuedMessage> by tracked(emptyList())
    /** The queued message just removed, offered back until the undo window ends or the queue moves on. */
    var removedQueued: RemovedQueued? by tracked(null)
    /** Its content, from the bridge's answer to the removal (null: it had already gone out, or the removal failed). */
    var removedPrompt: Deferred<List<JsonElement>?>? = null
    /** /btw questions asked on this device, oldest first; kept until closed (not synced to other devices). */
    var sideQuestions: List<SideQuestion> by tracked(emptyList())
    /** Claude Code's /resume list on the computer shows this conversation (null: not known yet). */
    var listedInCli: Boolean? by tracked(null)
    /** optimistic user bubble shown until the bridge echoes the user message */
    var pendingEcho: PendingEcho? by tracked(null)
    var expandedGroups: Set<UUID> by tracked(emptySet())
    var expandedSteps: Set<UUID> by tracked(emptySet())

    fun forgetRemovedQueued() {
        removedQueued = null
        removedPrompt = null
    }

    fun resolveSideQuestion(id: UUID, answer: String?, error: Problem?) {
        sideQuestions = sideQuestions.map { if (it.id == id) it.copy(answer = answer, error = error) else it }
    }

    // not shown anywhere
    var epoch: String? = null
    var attaching = false
    var reattachNeeded = false
    var lastSeq = 0
    private val toolIndex = mutableMapOf<String, ToolItem>()
    private var planItem: PlanItem? = null
    /** Every subagent of this conversation by child session id, nested ones included. */
    private val subIndex = mutableMapOf<String, SubagentItem>()
    private val liveGroupId: UUID = UUID.randomUUID()
    /** The optimistic bubble's item after the echo adopted it, and how many of the echo's image blocks it already shows. */
    private var adoptedEcho: MessageItem? = null
    private var echoImagesToSkip = 0

    fun subagent(sessionId: String): SubagentItem? = subIndex[sessionId]

    /** Subagents the main agent launched that still work, in launch order (nested ones show in their parent's view). */
    val runningSubagents: List<SubagentItem> get() = entries.mapNotNull { (it as? ChatEntry.Subagent)?.item?.takeIf { s -> s.isRunning } }

    val modelOption: ConfigOption? get() = configOptions.firstOrNull { it.category == "model" || it.id == "model" }
    val effortOption: ConfigOption? get() = configOptions.firstOrNull { it.category == "thought_level" || it.id == "thought_level" }
    val modeOption: ConfigOption? get() = configOptions.firstOrNull { it.category == "mode" || it.id == "mode" }

    /** Optimistically reflect a choice before the agent confirms it. */
    fun setLocalConfigValue(id: String, value: JsonElement) {
        configOptions = configOptions.map { if (it.id == id) it.withCurrent(value) else it }
    }

    fun setConfigOptions(j: JsonElement?) {
        val arr = j.array ?: return
        configOptions = arr.mapNotNull(ConfigOption::parse)
    }

    /** available_commands_update's list (also in bridge/attach's result, which outlives the event log's trim). */
    fun setCommands(j: JsonElement?) {
        val arr = j.array ?: return
        val seen = mutableSetOf<String>()   // a name listed twice (project and user command) keeps its first
        commands = arr.mapNotNull { c ->
            val raw = c["name"].string ?: return@mapNotNull null
            val n = if (raw.startsWith("/")) raw.substring(1) else raw
            if (n.isEmpty() || !seen.add(n)) return@mapNotNull null
            AgentCommand(n, c["description"].string ?: "", c["input"]["hint"].string)
        }
    }

    fun setModes(j: JsonElement?) {
        if (j == null || j.isJsonNull) return
        modes = (j["availableModes"].array ?: emptyList()).mapNotNull { m ->
            val id = m["id"].string ?: return@mapNotNull null
            SessionMode(id, m["name"].string ?: id)
        }
        currentMode = j["currentModeId"].string
    }

    fun reset(events: List<JsonElement>) {
        entries.clear()
        toolIndex.clear()
        planItem = null
        subIndex.clear()
        for (e in events) apply(e["update"] ?: kotlinx.serialization.json.JsonNull)
        pendingEcho = null
        adoptedEcho = null; echoImagesToSkip = 0
        touch()
    }

    /** [live]: just arrived over the socket (not a reattach catch-up). Updates at or below the last seen sequence are dropped. */
    fun ingest(seq: Int, update: JsonElement, live: Boolean = false) {
        if (seq <= lastSeq) return
        lastSeq = seq
        apply(update, live)
        touch()
    }

    // MARK: display

    /** Fold the flat transcript into: user message -> activity group -> final answer -> stats. */
    fun displayItems(): List<DisplayItem> {
        val out = mutableListOf<DisplayItem>()
        var group: ActivityGroup? = null
        var answer: MessageItem? = null
        // the newest turn's changes (by index in out), and whether a commit ran in or after that turn
        var latestChanges: Int? = null
        var committedSince = false

        fun demoteAnswer() {
            val a = answer ?: return
            val g = group ?: ActivityGroup(a.id.derived(1)).also { group = it }   // the message keeps its own id
            g.steps += ChatEntry.Message(a)
            answer = null
        }

        fun flush(stats: TurnStats? = null) {
            var changes: TurnChanges? = null
            group?.let { g ->
                g.stats = stats ?: g.stats
                g.answerFollows = answer != null
                if (g.live && g.id !in expandedGroups) out += liveTimeline(g) else out += DisplayItem.Activity(g)
                if (!g.live) {
                    changes = TurnChanges.from(g)
                    val committed = TurnChanges.ranCommit(g.steps)
                    if (changes != null) committedSince = committed else if (committed) committedSince = true
                }
            }
            answer?.let { out += DisplayItem.Answer(it) }
            // answer, then what changed, then the stats line
            changes?.let { latestChanges = out.size; out += DisplayItem.Changes(it) }
            group = null; answer = null
        }

        for (e in entries) {
            when (e) {
                is ChatEntry.Message -> {
                    val m = e.item
                    when (m.role) {
                        MessageItem.Role.User -> { flush(); out += DisplayItem.User(m) }
                        MessageItem.Role.Agent -> { demoteAnswer(); answer = m }
                        MessageItem.Role.Thought -> {
                            demoteAnswer()
                            val g = group ?: ActivityGroup(m.id).also { group = it }
                            g.steps += e
                        }
                    }
                }
                is ChatEntry.Tool -> {
                    demoteAnswer()
                    val g = group ?: ActivityGroup(e.item.id).also { group = it }
                    g.steps += e
                }
                is ChatEntry.Subagent -> {
                    demoteAnswer()
                    val g = group ?: ActivityGroup(e.item.id).also { group = it }
                    g.steps += e
                }
                is ChatEntry.Plan -> {
                    val g = group ?: ActivityGroup(e.item.id).also { group = it }
                    g.planEntries = e.item.entries
                    if (e.item.markdown != null) out += DisplayItem.PlanDoc(e.item)
                }
                is ChatEntry.Stats -> {
                    flush(stats = e.item)
                    out += DisplayItem.Stats(e.item)
                }
                is ChatEntry.Notice -> { flush(); out += DisplayItem.Notice(e.item) }
            }
        }
        // While the optimistic bubble waits for its echo, nothing in entries belongs to the new turn yet: a live group now
        // would sit above the bubble and then jump below it when the echo lands.
        if (running && pendingEcho == null) {
            // the trailing group (or a fresh one) is live; an answer streaming at the end stays visible
            if (group == null) {
                val lastIsUser = out.lastOrNull() is DisplayItem.User
                if (lastIsUser || out.isEmpty() || answer != null) group = ActivityGroup(liveGroupId)
            }
            group?.live = true
        }
        flush()
        latestChanges?.let { i -> if (!committedSince) (out[i] as? DisplayItem.Changes)?.changes?.canCommit = true }
        // the optimistic bubble is drawn as the transcript's last user row, so the echo can adopt it in place
        pendingEcho?.let { out += DisplayItem.User(it.item) }
        return out
    }

    /**
     * A live turn reads as it happens: the header, then the agent's messages in full with each run of tools between them
     * folded to one line. A message keeps its id from streaming at the end to sitting above the next run, so it stays in
     * place; once the turn ends it all folds back into the header, leaving the final answer.
     */
    private fun liveTimeline(g: ActivityGroup): List<DisplayItem> {
        val items = mutableListOf<DisplayItem>(DisplayItem.Activity(g))
        var run = mutableListOf<ChatEntry>()
        fun flushRun(last: Boolean) {
            val first = run.firstOrNull() ?: return
            val active = last && !g.answerFollows
            val listed = StepRowModel.build(run).isNotEmpty()
            // a run with nothing to list (only the steps tool) shows only while it is the current one
            if (!active && !listed) { run = mutableListOf(); return }
            // the current action while a tool runs or the agent thinks; between tools the run so far, not "Thinking…"
            val busy = when (val l = run.last()) {
                is ChatEntry.Tool -> l.item.status == "in_progress" || l.item.status == "pending" || l.item.status.isEmpty()
                is ChatEntry.Message -> l.item.role == MessageItem.Role.Thought
                else -> false
            }
            val id = first.id.derived(2)
            val steps = run.toList()
            val liveLine = if (active) (if (busy || !listed) g.liveAction else LiveAction.Summary(StepSegment(id, steps).summary)) else null
            items += DisplayItem.Segment(StepSegment(id, steps, liveLine))
            run = mutableListOf()
        }
        for (s in g.steps) {
            if (s is ChatEntry.Message && s.item.role == MessageItem.Role.Agent) { flushRun(false); items += DisplayItem.Answer(s.item) } else run += s
        }
        flushRun(true)
        return items
    }

    // MARK: updates

    /** Adds one more block of the same message to [last]; false when the block starts a row of its own. */
    companion object {
        fun merge(block: JsonElement, type: String?, last: MessageItem, synthetic: Boolean): Boolean {
            val text = block["text"].string
            if (type == "text" && text != null) {
                // Synthetic user blocks are separate prompt parts: join with a newline.
                if (synthetic && last.role == MessageItem.Role.User && last.text.isNotEmpty()) last.text += "\n" + text else last.text += text
                return true
            }
            if (type == "image") {
                val image = ChatImage.parse(block)
                if (image != null) { last.images += image; return true }
            }
            if (type == "resource_link" || type == "resource") {
                val f = FileRef.fromBlock(block)
                if (f != null) { if (f !in last.files) last.files += f; return true }
            }
            return false
        }

        /** A row for a block that starts a message; null for a block type a message can't show. */
        fun newMessage(role: MessageItem.Role, block: JsonElement, messageId: String?): MessageItem? {
            val type = block["type"].string
            val item = MessageItem(role, "", messageId)
            if (type == "text") {
                item.text = block["text"].string ?: ""
            } else if (type == "image") {
                item.images += ChatImage.parse(block) ?: return null
            } else if (type == "resource_link" || type == "resource") {
                val f = FileRef.fromBlock(block)
                if (f != null) item.files += f
                else if (type == "resource") item.text = block["resource"]["text"].string ?: ""   // embedded text without a file uri
                else return null
            } else return null
            return item
        }
    }

    /**
     * [echo]: a user message the bridge recorded (not a "!" shell entry) - it replaces [pendingEcho].
     */
    private fun appendText(role: MessageItem.Role, block: JsonElement, messageId: String?, synthetic: Boolean, echo: Boolean = false) {
        val type = block["type"].string
        // The echo's first block adopts the optimistic bubble (same MessageItem, so the same row identity).
        val pe = pendingEcho
        if (echo && pe != null) {
            adopt(pe.item, block, type, messageId, synthetic)
            return
        }
        val last = (entries.lastOrNull() as? ChatEntry.Message)?.item
        if (last != null && last.role == role && last.messageId == messageId) {
            // later image blocks of an adopted echo are already on screen
            if (type == "image" && last === adoptedEcho && echoImagesToSkip > 0) { echoImagesToSkip -= 1; return }
            if (merge(block, type, last, synthetic)) return
        }
        val item = newMessage(role, block, messageId) ?: return
        item.synthetic = synthetic
        entries += ChatEntry.Message(item)
    }

    /**
     * The optimistic bubble's item becomes the transcript row; the echo's text replaces its text (identical unless messages
     * held on the bridge went out ahead of it), images it already shows are skipped, files dedupe.
     */
    private fun adopt(item: MessageItem, block: JsonElement, type: String?, messageId: String?, synthetic: Boolean) {
        item.messageId = messageId
        item.synthetic = synthetic
        echoImagesToSkip = item.images.size
        val text = block["text"].string
        if (type == "text" && text != null) item.text = text
        // the bubble keeps showing its own bytes for the echo's (referenced) copy; a bubble without photos takes the echo's
        else if (type == "image" && echoImagesToSkip > 0) echoImagesToSkip -= 1
        else if (type == "resource" && FileRef.fromBlock(block) == null) item.text = block["resource"]["text"].string ?: item.text
        else merge(block, type, item, synthetic)
        adoptedEcho = item
        pendingEcho = null
        entries += ChatEntry.Message(item)
    }

    fun apply(u: JsonElement, live: Boolean = false) {
        val child = u["_meta"]["leash"]["subagent"].string
        if (child != null) { applyChild(child, u, live); return }
        val synthetic = u["_meta"]["leash"]["synthetic"].bool == true
        when (u["sessionUpdate"].string) {
            "subagent_update" -> announce(u, null, live)
            "user_message_chunk" -> {
                // The bridge records its own echo of every prompt as synthetic (hub.mjs #echo); only "!" shell entries
                // (synthetic + shell) are not the user's message.
                val shell = u["_meta"]["leash"]["shell"].bool == true
                appendText(MessageItem.Role.User, u["content"] ?: kotlinx.serialization.json.JsonNull, u["messageId"].string, synthetic, echo = !shell)
            }
            "agent_message_chunk" -> appendText(MessageItem.Role.Agent, u["content"] ?: kotlinx.serialization.json.JsonNull, u["messageId"].string, false)
            "agent_thought_chunk" -> appendText(MessageItem.Role.Thought, u["content"] ?: kotlinx.serialization.json.JsonNull, u["messageId"].string, false)
            "tool_call" -> {
                val id = u["toolCallId"].string ?: return
                toolIndex[id]?.let { it.apply(u); return }
                val t = ToolItem(id)
                t.apply(u)
                toolIndex[id] = t
                entries += ChatEntry.Tool(t)
            }
            "tool_call_update" -> {
                val id = u["toolCallId"].string ?: return
                val t = toolIndex[id]
                if (t != null) t.apply(u) else {
                    val n = ToolItem(id); n.apply(u); toolIndex[id] = n; entries += ChatEntry.Tool(n)
                }
            }
            "plan" -> (planItem ?: newPlan()).entries = PlanItem.entries(u["entries"])
            "plan_update" -> {
                val p = planItem ?: newPlan()
                val plan = u["plan"]
                if (plan["type"].string == "markdown") { p.markdown = plan["content"].string; p.path = plan["path"].string }
                else p.entries = PlanItem.entries(plan["entries"])
            }
            "plan_removed" -> planItem?.let { p -> entries.removeAll { it is ChatEntry.Plan && it.item === p }; planItem = null }
            "config_option_update" -> setConfigOptions(u["configOptions"])
            "current_mode_update" -> currentMode = u["currentModeId"].string
            "session_info_update" -> u["title"].string?.let { title = it }
            "available_commands_update" -> setCommands(u["availableCommands"])
            "usage_update" -> {
                val used = u["used"].int
                val size = u["size"].int
                if (used != null && size != null) usage = used to size
            }
            "leash_turn_stats" -> entries += ChatEntry.Stats(TurnStats(u))
            "leash_auto_approved" -> u["toolCallId"].string?.let { toolIndex[it]?.autoApproved = true }
            "notice" -> entries += ChatEntry.Notice(NoticeItem.from(u))
            else -> {}
        }
    }

    /** subagent_update on the root ([parent] null) or on a child: announces a subagent where it was launched, or updates it. */
    private fun announce(u: JsonElement, parent: SubagentItem?, live: Boolean) {
        val cid = u["sessionId"].string ?: return
        val s = subIndex[cid] ?: SubagentItem(cid).also {
            subIndex[cid] = it
            if (parent != null) parent.entries += ChatEntry.Subagent(it) else entries += ChatEntry.Subagent(it)
        }
        s.applyAnnouncement(u, live)
    }

    /** An update from a subagent's own session goes into its transcript, never the root's state. */
    private fun applyChild(cid: String, u: JsonElement, live: Boolean) {
        val s = subIndex[cid] ?: SubagentItem(cid).also {
            // a trimmed log can start after the announcement: the first update places it here
            subIndex[cid] = it
            entries += ChatEntry.Subagent(it)
        }
        val nul = kotlinx.serialization.json.JsonNull
        when (u["sessionUpdate"].string) {
            "agent_message_chunk" -> s.appendText(MessageItem.Role.Agent, u["content"] ?: nul, u["messageId"].string)
            "agent_thought_chunk" -> s.appendText(MessageItem.Role.Thought, u["content"] ?: nul, u["messageId"].string)
            "user_message_chunk" -> s.appendText(MessageItem.Role.User, u["content"] ?: nul, u["messageId"].string)
            "session_message" -> s.applySessionMessage(u)
            "session_message_chunk" -> s.applySessionMessageChunk(u)
            "tool_call", "tool_call_update" -> {
                val id = u["toolCallId"].string ?: return
                toolIndex[id]?.let { it.apply(u); return }
                val t = ToolItem(id)
                t.apply(u)
                toolIndex[id] = t
                s.entries += ChatEntry.Tool(t)
            }
            "subagent_update" -> announce(u, s, live)
            "config_option_update" ->
                (u["configOptions"].array ?: emptyList()).mapNotNull(ConfigOption::parse).firstOrNull { it.category == "model" || it.id == "model" }?.let { s.model = it.currentName }
            "plan" -> {
                val p = s.planItem ?: PlanItem().also { s.planItem = it; s.entries += ChatEntry.Plan(it) }
                p.entries = PlanItem.entries(u["entries"])
            }
            "notice" -> s.entries += ChatEntry.Notice(NoticeItem.from(u))
            else -> {}
        }
    }

    private fun newPlan(): PlanItem {
        val p = PlanItem()
        planItem = p
        entries += ChatEntry.Plan(p)
        return p
    }
}
