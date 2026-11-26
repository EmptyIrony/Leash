package dev.leash.personal.core

import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

// Transcript items and the display model, ported from ios Core/ChatModels.swift. The items are mutable classes (streaming
// edits one in place); ChatStore bumps its version after every change so the UI re-reads them.

/** A second stable id from an entry's own, for a row built around it (a group, a segment), so the two never collide. */
fun UUID.derived(salt: Int): UUID = UUID(mostSignificantBits, leastSignificantBits xor salt.toLong())

enum class DurationUnit { Day, Hour, Minute, Second }

/** One unit of a duration, e.g. 3 minutes. */
data class DurationPart(val unit: DurationUnit, val value: Long)

/**
 * A duration as its largest [maxUnits] consecutive units, zero ones left out ("1m 3s", "42s", "1h 5m"; 1 h 0 m 5 s is just
 * "1h"), like iOS's narrow duration. The words and their order come from resources: see ui/chat/Format.kt.
 */
fun durationParts(seconds: Double, maxUnits: Int = 2): List<DurationPart> {
    val s = maxOf(0L, Math.round(seconds))
    val all = listOf(DurationUnit.Hour to s / 3600, DurationUnit.Minute to (s % 3600) / 60, DurationUnit.Second to s % 60)
    val first = all.indexOfFirst { it.second > 0 }.takeIf { it >= 0 } ?: return listOf(DurationPart(DurationUnit.Second, 0))
    return all.drop(first).take(maxUnits).filter { it.second > 0 }.map { DurationPart(it.first, it.second) }
}

internal fun lastPathComponent(p: String) = p.trimEnd('/').substringAfterLast('/')

private fun capitalizedWords(s: String): String =
    s.split(' ').joinToString(" ") { w -> if (w.isEmpty()) w else w.substring(0, 1).uppercase() + w.substring(1).lowercase() }

/** An attached or referenced file on the agent's host. */
data class FileRef(val name: String, val path: String, val size: Long? = null) {
    companion object {
        fun create(name: String?, path: String, size: Long? = null): FileRef {
            val n = name?.trim().orEmpty()
            return FileRef(n.ifEmpty { lastPathComponent(path) }, path, size)
        }

        /** From a resource_link / resource block ("file:///…" uri, or a plain absolute path). */
        fun fromBlock(b: JsonElement?): FileRef? {
            val uri = b["uri"].string ?: b["resource"]["uri"].string ?: ""
            val p = pathFromUri(uri) ?: return null
            return create(b["name"].string ?: b["title"].string, p, b["size"].long)
        }

        fun pathFromUri(uri: String): String? {
            if (uri.startsWith("/") || uri.startsWith("~/")) return uri
            if (!uri.lowercase().startsWith("file://")) return null
            val rest = uri.substring(7)
            val p = percentDecode(rest)
            return p.ifEmpty { null }
        }

        /** The "[@name](uri)" text Claude Code writes into its transcript (and the composer shows) for a resource_link. */
        fun linkText(block: JsonElement?): String? {
            if (block["type"].string != "resource_link") return null
            val uri = block["uri"].string ?: return null
            return "[@" + (block["name"].string ?: lastPathComponent(uri)) + "](" + uri + ")"
        }

        private val linkRegex = Regex("""\[@([^\]\n]+)\]\((file://[^)\s]+)\)""")

        /** The attachment links in a message's text, and the text without them. */
        fun extractLinks(text: String): Pair<String, List<FileRef>> {
            if (!text.contains("](file://")) return text to emptyList()
            val refs = mutableListOf<FileRef>()
            val out = StringBuilder()
            var cursor = 0
            for (m in linkRegex.findAll(text)) {
                out.append(text, cursor, m.range.first)
                cursor = m.range.last + 1
                pathFromUri(m.groupValues[2])?.let { refs += create(m.groupValues[1], it) }
            }
            out.append(text, cursor, text.length)
            return out.toString().trim() to refs
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { bytes.write(v); i += 3; continue }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}

class MessageItem(val role: Role, var text: String, var messageId: String?) {
    enum class Role { User, Agent, Thought }

    val id: UUID = UUID.randomUUID()
    val images = mutableListOf<ByteArray>()
    /** attached files (resource_link / resource blocks); Claude Code's replay writes them as "[@name](file://…)" text instead */
    val files = mutableListOf<FileRef>()
    var synthetic = false
}

sealed interface ToolContent {
    data class Text(val text: String) : ToolContent
    data class Diff(val path: String, val old: String?, val new: String) : ToolContent
    data class Terminal(val terminalId: String) : ToolContent
    class Image(val data: ByteArray) : ToolContent
}

class ChecklistStep(val index: Int, val title: String, /** pending | in_progress | completed */ val status: String) {
    companion object {
        fun normalize(s: String): String {
            val l = s.lowercase()
            if (listOf("completed", "complete", "done", "finished", "success", "succeeded").any { it in l }) return "completed"
            if (listOf("progress", "running", "active", "current", "doing", "started").any { it in l }) return "in_progress"
            return "pending"
        }
    }
}

class ToolItem(val toolCallId: String) {
    val id: UUID = UUID.randomUUID()
    var title = "Tool"
    /** [title] is the question of an ask_question call (the UI words it "Asked: …") */
    var asked = false
    var kind = "other"
    var status = ""
    var content: List<ToolContent> = emptyList()
    var locations: List<String> = emptyList()
    var rawInput: JsonElement? = null
    var rawOutput: JsonElement? = null
    var autoApproved = false
    var name: String? = null
    /** cached +/- line counts over all its diff blocks (computing diffs while drawing is expensive) */
    var diffAdds = 0
    var diffDels = 0

    val isStepsTool: Boolean
        get() {
            val n = (name ?: "").lowercase()
            return "update_steps" in n || "update_plan" in n || "todo" in n
        }

    /** Lenient parser for agy's update_steps payload: finds the list of steps and their status. */
    val checklist: List<ChecklistStep>
        get() {
            val r = rawInput ?: return emptyList()
            var arr: List<JsonElement>? = null
            for (k in listOf("steps", "Steps", "items", "Items", "todos", "Todos", "tasks", "Tasks", "plan", "Plan")) { r[k].array?.let { arr = it; break } }
            if (arr == null) arr = r.obj?.values?.firstOrNull { it.array != null }?.array
            if (arr == null) {
                val s = r["steps"].string ?: r["Steps"].string
                if (s != null) arr = runCatching { parseJson(s) }.getOrNull().array
            }
            val out = mutableListOf<ChecklistStep>()
            (arr ?: emptyList()).forEachIndexed { i, e ->
                val str = e.string
                if (str != null) { out += ChecklistStep(i, str, "pending"); return@forEachIndexed }
                var title: String? = null
                for (k in listOf("title", "Title", "name", "Name", "description", "Description", "step", "Step", "content", "Content", "text", "summary", "Summary")) {
                    val s = e[k].string
                    if (!s.isNullOrEmpty()) { title = s; break }
                }
                if (title == null) return@forEachIndexed
                var status = "pending"
                for (k in listOf("status", "Status", "state", "State")) { e[k].string?.let { status = ChecklistStep.normalize(it); break } }
                if (e["done"].bool == true || e["completed"].bool == true || e["Done"].bool == true) status = "completed"
                out += ChecklistStep(i, title, status)
            }
            return out
        }

    fun apply(u: JsonElement) {
        u["name"].string?.let { name = it }
        u["title"].string?.takeIf { it.isNotEmpty() }?.let { title = it; asked = false }
        val q = (u["args"] ?: u["rawInput"])["questions"][0]["question"].string
        val summary = u["args"]["toolSummary"].string
        if (name == "ask_question" && q != null) { title = q; asked = true }
        else if (!summary.isNullOrEmpty()) { title = summary; asked = false }
        else if (title == "Tool" && u["name"].string != null) title = capitalizedWords(u["name"].string!!.replace("_", " "))
        if (rawInput == null) u["args"]?.takeIf { !it.isJsonNull }?.let { rawInput = it }
        u["kind"].string?.let { kind = it }
        u["status"].string?.let { status = it }
        u["content"].array?.let { c ->
            content = c.mapNotNull(::parseContent)
            val blocks = diffs
            if (blocks.isNotEmpty()) {
                // Claude Code's final update sends one block per hunk, each with its context lines on both sides
                var add = 0
                var del = 0
                for (d in blocks) { val s = LineDiff.stats(LineDiff.compute(d.old, d.new)); add += s.first; del += s.second }
                diffAdds = add; diffDels = del
            }
        }
        u["locations"].array?.let { l ->
            locations = l.mapNotNull { loc ->
                val p = loc["path"].string ?: return@mapNotNull null
                val line = loc["line"].int
                if (line != null) "$p:$line" else p
            }
        }
        u["rawInput"]?.takeIf { !it.isJsonNull }?.let { rawInput = it }
        u["rawOutput"]?.takeIf { !it.isJsonNull }?.let { rawOutput = it }
    }

    val firstDiff: ToolContent.Diff? get() = content.firstOrNull { it is ToolContent.Diff } as ToolContent.Diff?
    val diffs: List<ToolContent.Diff> get() = content.filterIsInstance<ToolContent.Diff>()

    /** The file an edit / delete / move changed: its diff's, else its first location without the line. */
    val editedPath: String?
        get() {
            firstDiff?.path?.takeIf { it.isNotEmpty() }?.let { return it }
            val l = locations.firstOrNull() ?: return null
            return l.replace(Regex(""":\d+$"""), "")
        }

    val commandPreview: String?
        get() {
            val r = rawInput ?: return null
            for (k in listOf("command", "CommandLine", "cmd", "commandLine", "code", "query", "url", "path", "file_path", "AbsolutePath", "pattern")) {
                val s = r[k].string
                if (!s.isNullOrEmpty()) return s
            }
            return null
        }

    companion object {
        fun parseContent(c: JsonElement): ToolContent? = when (c["type"].string) {
            "diff" -> ToolContent.Diff(c["path"].string ?: "", c["oldText"].string, c["newText"].string ?: "")
            "terminal" -> ToolContent.Terminal(c["terminalId"].string ?: "")
            "content" -> {
                val b = c["content"]
                if (b["type"].string == "text" && b["text"].string != null) ToolContent.Text(stripCommandPreamble(b["text"].string!!))
                else if (b["type"].string == "image" && b["data"].string != null) decodeBase64(b["data"].string!!)?.let { ToolContent.Image(it) }
                else b["resource"]["text"].string?.let { ToolContent.Text(it) }
            }
            else -> null
        }

        /** agy prefixes command output with "Created At / Completed At / Command exited… / Command output:". */
        fun stripCommandPreamble(t: String): String {
            if (!t.startsWith("Created At:")) return t
            val marker = "Command output:\n"
            val at = t.indexOf(marker)
            if (at < 0) return t
            val exit = Regex("Command exited with code [0-9]+").find(t)?.value
            val body = t.substring(at + marker.length).replace("\r\n", "\n")
            if (exit != null && !exit.endsWith(" 0")) return "[" + exit + "]\n" + body
            return body
        }
    }
}

fun decodeBase64(s: String): ByteArray? = runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()

class PlanEntry(val content: String, val status: String, val priority: String) {
    val id: UUID = UUID.randomUUID()
}

class PlanItem {
    val id: UUID = UUID.randomUUID()
    var entries: List<PlanEntry> = emptyList()
    var markdown: String? = null
    var path: String? = null

    companion object {
        fun entries(arr: JsonElement?): List<PlanEntry> =
            (arr.array ?: emptyList()).map { PlanEntry(it["content"].string ?: "", it["status"].string ?: "pending", it["priority"].string ?: "medium") }
    }
}

class NoticeItem(val severity: String, val title: String, val detail: String?) {
    val id: UUID = UUID.randomUUID()

    companion object {
        fun from(u: JsonElement) = NoticeItem(u["severity"].string ?: "info", u["title"].string ?: "Notice", u["description"].string)
    }
}

/** Per-turn numbers the bridge measures: tokens, speed, duration. */
class TurnStats(u: JsonElement) {
    val id: UUID = UUID.randomUUID()
    val inputTokens = u["usage"]["inputTokens"].int
    val outputTokens = u["usage"]["outputTokens"].int
    val thoughtTokens = u["usage"]["thoughtTokens"].int
    val cachedTokens = u["usage"]["cachedReadTokens"].int
    val durationMs = u["durationMs"].int
    val ttftMs = u["ttftMs"].int
    val tokensPerSecond = u["tokensPerSecond"].double
    val model = u["model"].string
    val effort = u["effort"].string
    val stopReason = u["stopReason"].string
}

/**
 * A subagent the agent launched (Claude Code's Agent tool), run as a child session (ACP subagents RFD). The bridge files
 * the child's updates under the root conversation tagged _meta.leash.subagent; they land in [entries].
 */
enum class SummaryKind { Agents, Explored, Ran, Edited, Browsed, Tools }

data class SummaryClause(val kind: SummaryKind, val count: Int)

/** What a group of steps did, as data the UI words in the device language: up to three clauses, [more] left out, or only thinking. */
data class ActivitySummary(val clauses: List<SummaryClause>, val more: Int = 0, val thoughtOnly: Boolean = false) {
    val isEmpty: Boolean get() = clauses.isEmpty() && !thoughtOnly

    companion object { val EMPTY = ActivitySummary(emptyList()) }
}

/** What the agent is doing right now, for the live sub-line; the UI words it in the device language. */
sealed interface LiveAction {
    data class Running(val command: String) : LiveAction
    data class Reading(val file: String?) : LiveAction
    data object Searching : LiveAction
    data class Editing(val file: String?) : LiveAction
    data object Browsing : LiveAction
    /** any other tool, by its own title ([asked]: it is the question of an ask_question call) */
    data class Tool(val title: String, val asked: Boolean = false) : LiveAction
    data object Thinking : LiveAction
    /** the checklist step in progress (its title is the agent's own text) */
    data class Step(val title: String) : LiveAction
    data object NeedsApproval : LiveAction
    /** a subagent at work, [name] null for one with no title ("Agent") */
    data class Subagent(val name: String?, val action: LiveAction) : LiveAction
    /** the run so far, between two tools */
    data class Summary(val summary: ActivitySummary) : LiveAction
}

/** A subagent's second line: what it is doing while it runs, how much it did once it is done. */
data class SubagentLine(
    val agentType: String?,
    val model: String? = null,
    val action: LiveAction? = null,
    val steps: Int? = null,
    val durationSeconds: Double? = null,
    val stopped: Boolean = false,
)

class SubagentItem(val sessionId: String) {
    val id: UUID = UUID.randomUUID()
    var title: String? = null
    /** The subagent_update description: claude-agent-acp puts the agent type there ("Explore", "claude", …). */
    var agentType: String? = null
    /** running | idle | requires_action | unknown */
    var state = "unknown"
    var stopReason: String? = null
    var model: String? = null
    /** The child's own transcript: the parent's prompt (as a user message), thoughts, tools, notes, its reply, nested subagents. */
    val entries = mutableListOf<ChatEntry>()
    /** Measured only while watching live (a replay has no timestamps). */
    var startedAtMs: Long? = null
    var endedAtMs: Long? = null
    var planItem: PlanItem? = null
    /** Messages this child sent to its own children: their chunks are not part of its prompt. */
    val outgoing = mutableSetOf<String>()

    val isRunning: Boolean get() = state == "running" || state == "requires_action"
    val needsAction: Boolean get() = state == "requires_action"
    /** Null for an agent with neither (the UI says "Agent"). */
    val displayTitle: String? get() = title?.takeIf { it.isNotEmpty() } ?: agentType

    /** The child's work as one activity group (no user prompt), for the live line and the step count. */
    val activity: ActivityGroup
        get() = ActivityGroup(id, entries.filter {
            when (it) {
                is ChatEntry.Message -> it.item.role != MessageItem.Role.User
                is ChatEntry.Tool, is ChatEntry.Subagent -> true
                else -> false
            }
        }.toMutableList())

    val stepCount: Int get() { val a = activity; return a.visibleTools.size + a.subagents.size }

    /** "Explore · Fable 5.2 · Running git status…" while it runs, "Explore · 42 steps · 1m 3s" once done. */
    val secondaryLine: SubagentLine
        get() {
            val type = agentType?.takeIf { it.isNotEmpty() && it != title }
            if (isRunning) return SubagentLine(type, model, if (needsAction) LiveAction.NeedsApproval else activity.liveAction)
            val a = startedAtMs
            val b = endedAtMs
            return SubagentLine(type, null, null, stepCount, if (a != null && b != null) (b - a) / 1000.0 else null, stopReason == "cancelled")
        }

    /** This child and every subagent nested in it (their requests show in its view). */
    val lineage: Set<String>
        get() {
            val ids = mutableSetOf(sessionId)
            for (e in entries) if (e is ChatEntry.Subagent) ids += e.item.lineage
            return ids
        }

    /** subagent_update: patch semantics (an absent key leaves the value, null clears it). */
    fun applyAnnouncement(u: JsonElement, live: Boolean) {
        u["title"]?.let { title = it.string }
        u["description"]?.let { agentType = it.string }
        val st = u["state"]
        val s = st["state"].string
        if (st != null && s != null) {
            val wasRunning = isRunning
            state = s
            stopReason = st["stopReason"].string
            if (live && wasRunning && !isRunning) endedAtMs = Clock.now()
            if (live && !wasRunning && isRunning) { startedAtMs = Clock.now(); endedAtMs = null }
        }
    }

    fun appendText(role: MessageItem.Role, block: JsonElement, messageId: String?) {
        val last = (entries.lastOrNull() as? ChatEntry.Message)?.item
        if (last != null && last.role == role && last.messageId == messageId && ChatStore.merge(block, block["type"].string, last, false)) return
        ChatStore.newMessage(role, block, messageId)?.let { entries += ChatEntry.Message(it) }
    }

    /** session_message: the parent's prompt to this child becomes its first (user) message; other directions are skipped. */
    fun applySessionMessage(u: JsonElement) {
        val mid = u["messageId"].string ?: return
        val from = u["senderSessionId"].string
        if (from != null && from == sessionId) { outgoing += mid; return }
        if (!(u["recipientSessionId"].string == sessionId || u["recipientSessionId"] == null)) return
        val blocks = u["content"].array ?: return
        val text = blocks.mapNotNull { if (it["type"].string == "text") it["text"].string else null }.joinToString("\n")
        prompt(mid)?.let { it.text = text; return }
        // in order: the parent can message the same child again later
        entries += ChatEntry.Message(MessageItem(MessageItem.Role.User, text, mid))
    }

    fun applySessionMessageChunk(u: JsonElement) {
        val mid = u["messageId"].string ?: return
        val block = u["content"] ?: return
        if (mid in outgoing) return
        val m = prompt(mid)
        if (m != null) { ChatStore.merge(block, block["type"].string, m, false); return }
        ChatStore.newMessage(MessageItem.Role.User, block, mid)?.let { entries += ChatEntry.Message(it) }
    }

    private fun prompt(mid: String): MessageItem? =
        entries.firstNotNullOfOrNull { e -> (e as? ChatEntry.Message)?.item?.takeIf { it.role == MessageItem.Role.User && it.messageId == mid } }
}

sealed interface ChatEntry {
    val id: UUID

    class Message(val item: MessageItem) : ChatEntry { override val id get() = item.id }
    class Tool(val item: ToolItem) : ChatEntry { override val id get() = item.id }
    class Plan(val item: PlanItem) : ChatEntry { override val id get() = item.id }
    class Notice(val item: NoticeItem) : ChatEntry { override val id get() = item.id }
    class Stats(val item: TurnStats) : ChatEntry { override val id get() = item.id }
    class Subagent(val item: SubagentItem) : ChatEntry { override val id get() = item.id }
}

// MARK: Display model: one collapsible activity group per agent turn

class ActivityGroup(val id: UUID, val steps: MutableList<ChatEntry> = mutableListOf()) {
    /** thoughts, tools, interim agent notes (a Message with role Agent) are all in [steps] */
    var stats: TurnStats? = null
    var live = false
    /** a message streams right after this (live) group */
    var answerFollows = false
    var planEntries: List<PlanEntry> = emptyList()

    val tools: List<ToolItem> get() = steps.mapNotNull { (it as? ChatEntry.Tool)?.item }
    val subagents: List<SubagentItem> get() = steps.mapNotNull { (it as? ChatEntry.Subagent)?.item }
    val visibleTools: List<ToolItem> get() = tools.filter { !it.isStepsTool }

    val checklist: List<ChecklistStep>
        get() {
            tools.lastOrNull { it.isStepsTool }?.let { val c = it.checklist; if (c.isNotEmpty()) return c }
            return planEntries.mapIndexed { i, e -> ChecklistStep(i, e.content, ChecklistStep.normalize(e.status)) }
        }

    val failedCount: Int get() = visibleTools.count { it.status == "failed" }

    /** "Explored 12 files, ran 6 commands, edited 3 files": at most three clauses and how many more there would be. */
    val summary: ActivitySummary
        get() {
            val t = visibleTools
            val clauses = mutableListOf<SummaryClause>()
            val explored = t.count { it.kind == "read" || it.kind == "search" }
            val ran = t.count { it.kind == "execute" }
            val edits = t.filter { it.kind in listOf("edit", "delete", "move") }
            val editedPaths = edits.mapNotNull { it.firstDiff?.path ?: it.locations.firstOrNull() }.toSet()
            val edited = if (editedPaths.isEmpty()) edits.size else editedPaths.size
            val browsed = t.count { it.kind == "fetch" }
            val other = t.size - explored - ran - edits.size - browsed
            val agents = subagents.size
            if (agents > 0) clauses += SummaryClause(SummaryKind.Agents, agents)
            if (explored > 0) clauses += SummaryClause(SummaryKind.Explored, explored)
            if (ran > 0) clauses += SummaryClause(SummaryKind.Ran, ran)
            if (edited > 0) clauses += SummaryClause(SummaryKind.Edited, edited)
            if (browsed > 0) clauses += SummaryClause(SummaryKind.Browsed, browsed)
            if (other > 0) clauses += SummaryClause(SummaryKind.Tools, other)
            if (clauses.isEmpty()) return if (steps.isEmpty()) ActivitySummary.EMPTY else ActivitySummary(emptyList(), 0, thoughtOnly = true)
            return ActivitySummary(clauses.take(3), (clauses.size - 3).coerceAtLeast(0))
        }

    /** What the agent is doing right now (for the live sub-line). */
    val liveAction: LiveAction
        get() {
            val last = steps.lastOrNull()
            if (last != null) {
                when {
                    last is ChatEntry.Tool && (last.item.status == "in_progress" || last.item.status == "pending" || last.item.status.isEmpty()) -> {
                        val t = last.item
                        return when (t.kind) {
                            "execute" -> LiveAction.Running(t.commandPreview ?: t.title)
                            "read" -> LiveAction.Reading(t.locations.firstOrNull()?.let(::lastPathComponent))
                            "search" -> LiveAction.Searching
                            "edit", "delete", "move" -> LiveAction.Editing((t.firstDiff?.path ?: t.locations.firstOrNull())?.let(::lastPathComponent))
                            "fetch" -> LiveAction.Browsing
                            else -> LiveAction.Tool(t.title, t.asked)
                        }
                    }
                    last is ChatEntry.Message && last.item.role == MessageItem.Role.Thought -> return LiveAction.Thinking
                    last is ChatEntry.Subagent && last.item.isRunning ->
                        return LiveAction.Subagent(last.item.displayTitle, if (last.item.needsAction) LiveAction.NeedsApproval else last.item.activity.liveAction)
                }
            }
            checklist.firstOrNull { it.status == "in_progress" }?.let { return LiveAction.Step(it.title) }
            return LiveAction.Thinking
        }
}

/**
 * What a finished turn did to files: its edit, delete and move tools (its subagents' too) by file, newest change last.
 * Counts add up the edits' own diffs, so a line written and then rewritten counts twice; a change made through a shell
 * command isn't seen.
 */
class TurnChanges private constructor(val id: UUID, val files: List<File>) {
    class File(val path: String, val created: Boolean) {
        val tools = mutableListOf<ToolItem>()
        var adds = 0
        var dels = 0
        /** its last change deleted it */
        var deleted = false

        fun relative(cwd: String?): String {
            if (cwd.isNullOrEmpty()) return path
            val base = if (cwd.endsWith("/")) cwd else "$cwd/"
            return if (path.startsWith(base)) path.substring(base.length) else path
        }
    }

    val adds: Int = files.sumOf { it.adds }
    val dels: Int = files.sumOf { it.dels }
    /** The newest turn that changed files, with no commit run since: it can ask the agent to commit them. */
    var canCommit = false

    companion object {
        fun from(g: ActivityGroup): TurnChanges? {
            val order = mutableListOf<String>()
            val byPath = mutableMapOf<String, File>()
            // a failed, refused or never-finished edit changed nothing
            for (t in tools(g.steps)) {
                if (t.kind !in listOf("edit", "delete", "move") || t.status in listOf("failed", "pending", "in_progress")) continue
                val p = t.editedPath ?: continue
                val f = byPath.getOrPut(p) {
                    order += p
                    val blocks = t.diffs
                    File(p, t.kind == "edit" && blocks.isNotEmpty() && blocks.all { (it.old ?: "").isEmpty() })
                }
                f.tools += t
                f.adds += t.diffAdds
                f.dels += t.diffDels
                f.deleted = t.kind == "delete"
            }
            if (order.isEmpty()) return null
            return TurnChanges(g.id.derived(3), order.mapNotNull { byPath[it] })
        }

        /** Every tool of these steps, a subagent's (and its subagents') included. */
        fun tools(steps: List<ChatEntry>): List<ToolItem> = steps.flatMap { e ->
            when (e) {
                is ChatEntry.Tool -> listOf(e.item)
                is ChatEntry.Subagent -> tools(e.item.entries)
                else -> emptyList()
            }
        }

        // "git commit", "git -C repo commit", "git -c k=v commit"; not "git commit-tree"
        private val commitRegex = Regex("""\bgit(\s+-[cC]\s+\S+|\s+--?[a-z][\w-]*(=\S+)?)*\s+commit(\s|$)""")

        /** The steps ran a git commit (the agent's own, or one it was asked for). */
        fun ranCommit(steps: List<ChatEntry>): Boolean =
            tools(steps).any { t -> t.kind == "execute" && commitRegex.containsMatchIn(t.commandPreview ?: t.title) }
    }
}

/** A live turn's run of tools and thoughts between two of the agent's messages ("Explored 3 files, ran 2 commands"). */
class StepSegment(val id: UUID, val steps: List<ChatEntry>, /** the current action, or the run so far, on the run still going */ val liveLine: LiveAction? = null) {
    val summary: ActivitySummary get() = ActivityGroup(id, steps.toMutableList()).summary
}

/** One row of an expanded step list: consecutive successful reads fold into one row. */
class StepRowModel(val id: UUID, val kind: Kind) {
    sealed interface Kind {
        class Thought(val item: MessageItem) : Kind
        class Note(val item: MessageItem) : Kind
        class Tool(val item: ToolItem) : Kind
        class Reads(val items: List<ToolItem>) : Kind
        class Subagent(val item: SubagentItem) : Kind
    }

    companion object {
        fun build(steps: List<ChatEntry>): List<StepRowModel> {
            val out = mutableListOf<StepRowModel>()
            var reads = mutableListOf<ToolItem>()
            fun flushReads() {
                if (reads.size == 1) out += StepRowModel(reads[0].id, Kind.Tool(reads[0]))
                else if (reads.size > 1) out += StepRowModel(reads[0].id, Kind.Reads(reads))
                reads = mutableListOf()
            }
            for (s in steps) {
                when (s) {
                    is ChatEntry.Tool -> {
                        val t = s.item
                        if (t.isStepsTool) continue
                        if (t.kind == "read" && t.status != "failed") { reads += t; continue }
                        flushReads(); out += StepRowModel(t.id, Kind.Tool(t))
                    }
                    is ChatEntry.Message -> {
                        flushReads()
                        out += StepRowModel(s.item.id, if (s.item.role == MessageItem.Role.Thought) Kind.Thought(s.item) else Kind.Note(s.item))
                    }
                    is ChatEntry.Subagent -> { flushReads(); out += StepRowModel(s.item.id, Kind.Subagent(s.item)) }
                    else -> {}
                }
            }
            flushReads()
            return out
        }
    }
}

sealed interface DisplayItem {
    val id: UUID

    class User(val item: MessageItem) : DisplayItem { override val id get() = item.id }
    class Activity(val group: ActivityGroup) : DisplayItem { override val id get() = group.id }
    class Segment(val segment: StepSegment) : DisplayItem { override val id get() = segment.id }
    class Answer(val item: MessageItem) : DisplayItem { override val id get() = item.id }
    class Stats(val stats: TurnStats) : DisplayItem { override val id get() = stats.id }
    class Notice(val notice: NoticeItem) : DisplayItem { override val id get() = notice.id }
    class PlanDoc(val plan: PlanItem) : DisplayItem { override val id get() = plan.id }
    class Changes(val changes: TurnChanges) : DisplayItem { override val id get() = changes.id }
}

// MARK: Session config (model / thinking level ...)

data class ConfigChoice(val value: String, val name: String, val description: String?, val group: String?)

data class ConfigOption(
    val id: String,
    val name: String,
    val category: String?,
    val type: String,
    val current: JsonElement,
    val choices: List<ConfigChoice>,
) {
    fun withCurrent(v: JsonElement) = copy(current = v)

    val currentName: String
        get() {
            val v = current.string ?: ""
            return choices.firstOrNull { it.value == v }?.name ?: v
        }

    companion object {
        fun parse(j: JsonElement): ConfigOption? {
            val id = j["id"].string ?: return null
            val out = mutableListOf<ConfigChoice>()
            for (o in j["options"].array ?: emptyList()) {
                val group = o["group"].string ?: o["name"].string
                val sub = o["options"].array
                if (group != null && sub != null) {
                    for (s in sub) {
                        val v = s["value"].string ?: continue
                        out += ConfigChoice(v, s["name"].string ?: v, s["description"].string, group)
                    }
                } else {
                    val v = o["value"].string ?: continue
                    out += ConfigChoice(v, o["name"].string ?: v, o["description"].string, null)
                }
            }
            return ConfigOption(id, j["name"].string ?: id, j["category"].string, j["type"].string ?: "select", j["currentValue"] ?: JsonNull, out)
        }
    }
}

data class SessionMode(val id: String, val name: String)

/** The user's message drawn in the transcript the moment it is sent, until the bridge's echo takes its place. */
class PendingEcho(val item: MessageItem) {
    val id: UUID = UUID.randomUUID()
    val sentAtMs: Long = Clock.now()
}

/** A message waiting on the bridge until the running turn ends; not in the transcript yet. */
data class QueuedMessage(val id: String, val text: String, val imageCount: Int, val fileCount: Int = 0) {
    companion object {
        fun list(j: JsonElement?): List<QueuedMessage> = (j.array ?: emptyList()).mapNotNull { q ->
            val id = q["id"].string ?: return@mapNotNull null
            QueuedMessage(id, q["text"].string ?: "", q["imageCount"].int ?: 0, q["fileCount"].int ?: 0)
        }
    }
}

/** A queued message just removed from the queue tray: for a few seconds its row offers Undo (AppModel.removeQueued). */
class RemovedQueued(val message: QueuedMessage, /** where it was in the queue */ val index: Int) {
    /** tells a later removal of the same message apart */
    val token: UUID = UUID.randomUUID()
}

/** A slash command the agent advertises (available_commands_update), or one Leash runs itself (/btw). */
data class AgentCommand(
    /** without the slash */
    val name: String,
    val description: String,
    /** what it takes after the name ("pr-number"), when the agent says */
    val hint: String?,
)

/** A /btw side question: answered from the conversation's context by a throwaway fork; never part of the transcript. */
data class SideQuestion(val id: UUID = UUID.randomUUID(), val question: String, val answer: String? = null, val error: Problem? = null) {
    val pending: Boolean get() = answer == null && error == null
}
