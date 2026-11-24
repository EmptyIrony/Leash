import Foundation
import Observation
import SwiftUI

// MARK: - Transcript items (reference types so streaming only re-renders one row)

@Observable
final class MessageItem: Identifiable {
    enum Role { case user, agent, thought }
    let id = UUID()
    let role: Role
    var text: String
    var images: [Data] = []
    /// attached files (resource_link / resource blocks); Claude Code's replay writes them as "[@name](file://…)" text instead
    var files: [FileRef] = []
    var messageId: String?
    var synthetic = false
    init(role: Role, text: String, messageId: String?) {
        self.role = role; self.text = text; self.messageId = messageId
    }
}

enum ToolContent: Identifiable {
    case text(String)
    case diff(path: String, old: String?, new: String)
    case terminal(String)
    case image(Data)
    var id: String {
        switch self {
        case .text(let s): return "t\(s.hashValue)"
        case .diff(let p, let o, let n): return "d\(p)\(o?.hashValue ?? 0)\(n.hashValue)"
        case .terminal(let t): return "x\(t)"
        case .image(let d): return "i\(d.hashValue)"
        }
    }
}

@Observable
final class ToolItem: Identifiable {
    let id = UUID()
    let toolCallId: String
    var title: String = "Tool"
    var kind: String = "other"
    var status: String = ""
    var content: [ToolContent] = []
    var locations: [String] = []
    var rawInput: JSON?
    var rawOutput: JSON?
    var autoApproved = false
    var name: String?
    /// cached +/- line counts of the first diff (computing diffs in view bodies is expensive)
    var diffAdds = 0
    var diffDels = 0
    init(toolCallId: String) { self.toolCallId = toolCallId }

    var isStepsTool: Bool { (name ?? "").lowercased().contains("update_steps") || (name ?? "").lowercased().contains("update_plan") || (name ?? "").lowercased().contains("todo") }

    /// Lenient parser for agy's update_steps payload: finds the list of steps and their status.
    var checklist: [ChecklistStep] {
        guard let r = rawInput else { return [] }
        var arr: [JSON]? = nil
        for k in ["steps", "Steps", "items", "Items", "todos", "Todos", "tasks", "Tasks", "plan", "Plan"] { if let a = r[k]?.array { arr = a; break } }
        if arr == nil, let o = r.object { arr = o.values.first(where: { $0.array != nil })?.array }
        if arr == nil, let s = r["steps"]?.string ?? r["Steps"]?.string, let d = s.data(using: .utf8), let j = try? JSON.decode(d) { arr = j.array }
        return (arr ?? []).enumerated().compactMap { i, e in
            if let s = e.string { return ChecklistStep(index: i, title: s, status: "pending") }
            var title: String? = nil
            for k in ["title", "Title", "name", "Name", "description", "Description", "step", "Step", "content", "Content", "text", "summary", "Summary"] {
                if let s = e[k]?.string, !s.isEmpty { title = s; break }
            }
            guard let title else { return nil }
            var status = "pending"
            for k in ["status", "Status", "state", "State"] { if let s = e[k]?.string { status = ChecklistStep.normalize(s); break } }
            if e["done"]?.bool == true || e["completed"]?.bool == true || e["Done"]?.bool == true { status = "completed" }
            return ChecklistStep(index: i, title: title, status: status)
        }
    }

    func apply(_ u: JSON) {
        if let n = u["name"]?.string { name = n }
        if let t = u["title"]?.string, !t.isEmpty { title = t }
        if name == "ask_question", let q = (u["args"] ?? u["rawInput"])?["questions"]?[0]?["question"]?.string { title = String(localized: "Asked: \(q)") }
        else if let t = u["args"]?["toolSummary"]?.string, !t.isEmpty { title = t }
        else if title == "Tool", let n = u["name"]?.string { title = n.replacingOccurrences(of: "_", with: " ").capitalized }
        if rawInput == nil, let a = u["args"], !a.isNull { rawInput = a }
        if let k = u["kind"]?.string { kind = k }
        if let s = u["status"]?.string { status = s }
        if let c = u["content"]?.array {
            content = c.compactMap(ToolItem.parseContent)
            if let d = firstDiff { let s = LineDiff.stats(LineDiff.compute(old: d.old, new: d.new)); diffAdds = s.add; diffDels = s.del }
        }
        if let l = u["locations"]?.array {
            locations = l.compactMap { loc in
                guard let p = loc["path"]?.string else { return nil }
                if let line = loc["line"]?.int { return "\(p):\(line)" }
                return p
            }
        }
        if let r = u["rawInput"], !r.isNull { rawInput = r }
        if let r = u["rawOutput"], !r.isNull { rawOutput = r }
    }

    static func parseContent(_ c: JSON) -> ToolContent? {
        switch c["type"]?.string {
        case "diff":
            return .diff(path: c["path"]?.string ?? "", old: c["oldText"]?.string, new: c["newText"]?.string ?? "")
        case "terminal":
            return .terminal(c["terminalId"]?.string ?? "")
        case "content":
            let b = c["content"]
            if b?["type"]?.string == "text", let t = b?["text"]?.string { return .text(ToolItem.stripCommandPreamble(t)) }
            if b?["type"]?.string == "image", let d = b?["data"]?.string, let data = Data(base64Encoded: d) { return .image(data) }
            if let t = b?["resource"]?["text"]?.string { return .text(t) }
            return nil
        default: return nil
        }
    }

    /// agy prefixes command output with "Created At / Completed At / Command exited… / Command output:".
    static func stripCommandPreamble(_ t: String) -> String {
        guard t.hasPrefix("Created At:"), let r = t.range(of: "Command output:\n") else { return t }
        let exit = t.range(of: "Command exited with code [0-9]+", options: .regularExpression).map { String(t[$0]) }
        let body = String(t[r.upperBound...]).replacingOccurrences(of: "\r\n", with: "\n")
        if let exit, !exit.hasSuffix(" 0") { return "[" + exit + "]\n" + body }
        return body
    }

    var firstDiff: (path: String, old: String?, new: String)? {
        for c in content { if case .diff(let p, let o, let n) = c { return (p, o, n) } }
        return nil
    }

    var commandPreview: String? {
        guard let r = rawInput else { return nil }
        for k in ["command", "CommandLine", "cmd", "commandLine", "code", "query", "url", "path", "file_path", "AbsolutePath", "pattern"] {
            if let s = r[k]?.string, !s.isEmpty { return s }
        }
        return nil
    }
}

struct ChecklistStep: Identifiable, Hashable {
    var id: Int { index }
    let index: Int
    let title: String
    let status: String   // pending | in_progress | completed
    static func normalize(_ s: String) -> String {
        let l = s.lowercased()
        if ["completed", "complete", "done", "finished", "success", "succeeded"].contains(where: { l.contains($0) }) { return "completed" }
        if ["progress", "running", "active", "current", "doing", "started"].contains(where: { l.contains($0) }) { return "in_progress" }
        return "pending"
    }
}

struct PlanEntry: Identifiable, Hashable {
    let id = UUID()
    let content: String
    let status: String
    let priority: String
}

@Observable
final class PlanItem: Identifiable {
    let id = UUID()
    var entries: [PlanEntry] = []
    var markdown: String?
    var path: String?
}

@Observable
final class NoticeItem: Identifiable {
    let id = UUID()
    let severity: String
    let title: String
    let detail: String?
    init(severity: String, title: String, detail: String?) { self.severity = severity; self.title = title; self.detail = detail }
}

/// Per-turn numbers the bridge measures: tokens, speed, duration.
struct TurnStats: Identifiable {
    let id = UUID()
    let inputTokens: Int?
    let outputTokens: Int?
    let thoughtTokens: Int?
    let cachedTokens: Int?
    let durationMs: Int?
    let ttftMs: Int?
    let tokensPerSecond: Double?
    let model: String?
    let effort: String?
    let stopReason: String?

    init(_ u: JSON) {
        let us = u["usage"]
        inputTokens = us?["inputTokens"]?.int
        outputTokens = us?["outputTokens"]?.int
        thoughtTokens = us?["thoughtTokens"]?.int
        cachedTokens = us?["cachedReadTokens"]?.int
        durationMs = u["durationMs"]?.int
        ttftMs = u["ttftMs"]?.int
        tokensPerSecond = u["tokensPerSecond"]?.double
        model = u["model"]?.string
        effort = u["effort"]?.string
        stopReason = u["stopReason"]?.string
    }
}

enum ChatEntry: Identifiable {
    case message(MessageItem)
    case tool(ToolItem)
    case plan(PlanItem)
    case notice(NoticeItem)
    case stats(TurnStats)
    var id: UUID {
        switch self {
        case .message(let m): return m.id
        case .tool(let t): return t.id
        case .plan(let p): return p.id
        case .notice(let n): return n.id
        case .stats(let s): return s.id
        }
    }
}

// MARK: - Display model: one collapsible activity group per agent turn

struct ActivityGroup: Identifiable {
    let id: UUID
    var steps: [ChatEntry] = []          // thoughts, tools, interim agent notes (.message with role .agent)
    var stats: TurnStats?
    var live = false
    var answerFollows = false            // a message streams right after this (live) group
    var planEntries: [PlanEntry] = []

    var tools: [ToolItem] { steps.compactMap { if case .tool(let t) = $0 { return t }; return nil } }
    var visibleTools: [ToolItem] { tools.filter { !$0.isStepsTool } }

    var checklist: [ChecklistStep] {
        if let t = tools.last(where: { $0.isStepsTool }) { let c = t.checklist; if !c.isEmpty { return c } }
        return planEntries.enumerated().map { ChecklistStep(index: $0.offset, title: $0.element.content, status: ChecklistStep.normalize($0.element.status)) }
    }

    var failedCount: Int { visibleTools.filter { $0.status == "failed" }.count }

    /// "Explored 12 files, ran 6 commands, edited 3 files"
    var summary: String {
        let t = visibleTools
        var clauses: [String] = []
        let explored = t.filter { $0.kind == "read" || $0.kind == "search" }.count
        let ran = t.filter { $0.kind == "execute" }.count
        let edits = t.filter { ["edit", "delete", "move"].contains($0.kind) }
        let editedPaths = Set(edits.compactMap { $0.firstDiff?.path ?? $0.locations.first })
        let edited = editedPaths.isEmpty ? edits.count : editedPaths.count
        let browsed = t.filter { $0.kind == "fetch" }.count
        let other = t.count - explored - ran - edits.count - browsed
        if explored > 0 { clauses.append(explored == 1 ? String(localized: "explored 1 file") : String(localized: "explored \(explored) files")) }
        if ran > 0 { clauses.append(ran == 1 ? String(localized: "ran 1 command") : String(localized: "ran \(ran) commands")) }
        if edited > 0 { clauses.append(edited == 1 ? String(localized: "edited 1 file") : String(localized: "edited \(edited) files")) }
        if browsed > 0 { clauses.append(browsed == 1 ? String(localized: "browsed 1 page") : String(localized: "browsed \(browsed) pages")) }
        if other > 0 { clauses.append(other == 1 ? String(localized: "used 1 tool") : String(localized: "used \(other) tools")) }
        if clauses.isEmpty { return steps.isEmpty ? "" : String(localized: "Thought it through") }
        let sep = String(localized: "summary.separator", defaultValue: ", ")
        let more = clauses.count - 3
        let shown = clauses.prefix(3).joined(separator: sep) + (more > 0 ? String(localized: " +\(more) more") : "")
        return shown.prefix(1).uppercased() + shown.dropFirst()
    }

    /// What the agent is doing right now (for the live sub-line).
    var liveAction: String {
        if let last = steps.last {
            switch last {
            case .tool(let t) where t.status == "in_progress" || t.status == "pending" || t.status.isEmpty:
                switch t.kind {
                case "execute":
                    let cmd = t.commandPreview ?? t.title
                    return String(localized: "Running \(cmd)…")
                case "read":
                    let f = t.locations.first.map { ($0 as NSString).lastPathComponent } ?? String(localized: "file")
                    return String(localized: "Reading \(f)…")
                case "search": return String(localized: "Searching…")
                case "edit", "delete", "move":
                    let f = (t.firstDiff?.path ?? t.locations.first).map { ($0 as NSString).lastPathComponent } ?? String(localized: "file")
                    return String(localized: "Editing \(f)…")
                case "fetch": return String(localized: "Browsing…")
                default: return t.title + "…"
                }
            case .message(let m) where m.role == .thought:
                return String(localized: "Thinking…")
            default: break
            }
        }
        if let cur = checklist.first(where: { $0.status == "in_progress" }) { return cur.title }
        return String(localized: "Thinking…")
    }
}

/// A live turn's run of tools and thoughts between two of the agent's messages ("Explored 3 files, ran 2 commands").
struct StepSegment: Identifiable {
    let id: UUID
    let steps: [ChatEntry]
    var liveLine: String?                // the current action, on the run still going

    var summary: String { ActivityGroup(id: id, steps: steps).summary }
}

extension UUID {
    /// A second stable id from an entry's own, for a row built around it (a group, a segment), so the two never
    /// collide in one ForEach.
    func derived(_ salt: UInt8) -> UUID {
        var u = uuid
        u.15 ^= salt
        return UUID(uuid: u)
    }
}

enum DisplayItem: Identifiable {
    case user(MessageItem)
    case activity(ActivityGroup)
    case segment(StepSegment)
    case answer(MessageItem)
    case stats(TurnStats)
    case notice(NoticeItem)
    case planDoc(PlanItem)
    var id: UUID {
        switch self {
        case .user(let m), .answer(let m): return m.id
        case .activity(let g): return g.id
        case .segment(let s): return s.id
        case .stats(let s): return s.id
        case .notice(let n): return n.id
        case .planDoc(let p): return p.id
        }
    }
}

// MARK: - Session config (model / thinking level ...)

struct ConfigChoice: Identifiable, Hashable {
    var id: String { value }
    let value: String
    let name: String
    let description: String?
    let group: String?
}

struct ConfigOption: Identifiable, Hashable {
    let id: String
    let name: String
    let category: String?
    let type: String
    let current: JSON
    let choices: [ConfigChoice]

    init?(_ j: JSON) {
        guard let id = j["id"]?.string else { return nil }
        self.id = id
        name = j["name"]?.string ?? id
        category = j["category"]?.string
        type = j["type"]?.string ?? "select"
        current = j["currentValue"] ?? .null
        var out: [ConfigChoice] = []
        for o in j["options"]?.array ?? [] {
            if let group = o["group"]?.string ?? o["name"]?.string, let sub = o["options"]?.array {
                for s in sub {
                    if let v = s["value"]?.string { out.append(ConfigChoice(value: v, name: s["name"]?.string ?? v, description: s["description"]?.string, group: group)) }
                }
            } else if let v = o["value"]?.string {
                out.append(ConfigChoice(value: v, name: o["name"]?.string ?? v, description: o["description"]?.string, group: nil))
            }
        }
        choices = out
    }

    func with(current v: JSON) -> ConfigOption {
        ConfigOption(id: id, name: name, category: category, type: type, current: v, choices: choices)
    }

    init(id: String, name: String, category: String?, type: String, current: JSON, choices: [ConfigChoice]) {
        self.id = id; self.name = name; self.category = category; self.type = type; self.current = current; self.choices = choices
    }

    var currentName: String {
        let v = current.string ?? ""
        return choices.first { $0.value == v }?.name ?? v
    }
}

struct SessionMode: Identifiable, Hashable {
    let id: String
    let name: String
}

/// The user's message drawn in the transcript the moment it is sent, until the bridge's echo of it arrives
/// and takes its place (same text, images and files, so the swap is invisible).
struct PendingEcho: Identifiable {
    let id = UUID()
    let item: MessageItem
    let sentAt = Date()
}

/// A message waiting on the bridge until the running turn ends; not in the transcript yet.
struct QueuedMessage: Identifiable, Hashable {
    let id: String
    let text: String
    let imageCount: Int
    var fileCount: Int = 0

    static func list(_ j: JSON?) -> [QueuedMessage] {
        (j?.array ?? []).compactMap { q in
            guard let id = q["id"]?.string else { return nil }
            return QueuedMessage(id: id, text: q["text"]?.string ?? "", imageCount: q["imageCount"]?.int ?? 0, fileCount: q["fileCount"]?.int ?? 0)
        }
    }
}

/// A /btw side question: answered from the conversation's context by a throwaway fork; never part of the transcript.
struct SideQuestion: Identifiable, Equatable {
    let id = UUID()
    let question: String
    var answer: String?
    var error: String?
    var pending: Bool { answer == nil && error == nil }
}

// MARK: - Per-session transcript store

@Observable
@MainActor
final class ChatStore {
    let sessionId: String
    var agent: String = "agy"
    var cwd: String?
    var title: String?
    var entries: [ChatEntry] = []
    var running = false
    /// A "!" shell command from any device is running on the agent's host.
    var shellRunning = false
    var lastStopReason: String?
    var configOptions: [ConfigOption] = []
    var modes: [SessionMode] = []
    var currentMode: String?
    var commands: [(name: String, description: String)] = []
    var usage: (used: Int, size: Int)?
    var autoApprove = false
    var turnStartedAt: Date?
    var attached = false
    var loading = false
    var error: String?
    /// Set while another client (Claude Code terminal / desktop app) has this conversation open: "cli", "claude-desktop", …
    var lockedBy: String?
    /// Text the bridge refused to send (conversation locked) or a queued message taken back; the composer puts it back.
    var unsentDraft: String?
    var unsentImages: [Data]?
    /// Messages sent mid-turn that wait on the bridge for the turn to end (agents without steering, or "interrupt and send").
    var queued: [QueuedMessage] = []
    /// /btw questions asked on this phone, oldest first; kept until closed (not synced to other devices).
    var sideQuestions: [SideQuestion] = []
    func resolveSideQuestion(_ id: UUID, answer: String?, error: String?) {
        guard let i = sideQuestions.firstIndex(where: { $0.id == id }) else { return }
        sideQuestions[i].answer = answer
        sideQuestions[i].error = error
    }
    /// optimistic user bubble shown until the bridge echoes the user message
    var pendingEcho: PendingEcho?
    /// bumps on every change so the chat view can auto-scroll
    var revision = 0

    var expandedGroups: Set<UUID> = []
    var expandedSteps: Set<UUID> = []
    @ObservationIgnored let liveGroupID = UUID()
    @ObservationIgnored var epoch: String?
    @ObservationIgnored var attaching = false
    @ObservationIgnored var reattachNeeded = false
    @ObservationIgnored var lastSeq = 0
    @ObservationIgnored private var toolIndex: [String: ToolItem] = [:]
    @ObservationIgnored private var planItem: PlanItem?
    /// The optimistic bubble's item after the echo adopted it, and how many of the echo's image blocks it already shows.
    @ObservationIgnored private weak var adoptedEcho: MessageItem?
    @ObservationIgnored private var echoImagesToSkip = 0

    init(sessionId: String) { self.sessionId = sessionId }

    /// Fold the flat transcript into: user message → activity group → final answer → stats.
    func displayItems() -> [DisplayItem] {
        var out: [DisplayItem] = []
        var group: ActivityGroup? = nil
        var answer: MessageItem? = nil

        func demoteAnswer() {
            if let a = answer {
                if group == nil { group = ActivityGroup(id: a.id.derived(1)) }   // the message keeps its own id
                group!.steps.append(.message(a))
                answer = nil
            }
        }
        func flush(stats: TurnStats? = nil) {
            if var g = group {
                g.stats = stats ?? g.stats
                g.answerFollows = answer != nil
                if g.live && !expandedGroups.contains(g.id) { out.append(contentsOf: liveTimeline(g)) } else { out.append(.activity(g)) }
            }
            if let a = answer { out.append(.answer(a)) }
            group = nil; answer = nil
        }

        for e in entries {
            switch e {
            case .message(let m):
                switch m.role {
                case .user: flush(); out.append(.user(m))
                case .agent: demoteAnswer(); answer = m
                case .thought:
                    demoteAnswer()
                    if group == nil { group = ActivityGroup(id: m.id) }
                    group!.steps.append(e)
                }
            case .tool(let t):
                demoteAnswer()
                if group == nil { group = ActivityGroup(id: t.id) }
                group!.steps.append(e)
            case .plan(let p):
                if group == nil { group = ActivityGroup(id: p.id) }
                group!.planEntries = p.entries
                if p.markdown != nil { out.append(.planDoc(p)) }
            case .stats(let s):
                let st = s
                flush(stats: st)
                out.append(.stats(s))
            case .notice(let n):
                flush(); out.append(.notice(n))
            }
        }
        // While the optimistic bubble waits for its echo, nothing in `entries` belongs to the new turn yet: a live
        // group now would sit above the bubble and then jump below it when the echo lands.
        if running && pendingEcho == nil {
            // the trailing group (or a fresh one) is live; an answer streaming at the end stays visible
            if group == nil {
                let lastIsUser: Bool = { if case .user? = out.last { return true }; return false }()
                if lastIsUser || out.isEmpty || answer != nil { group = ActivityGroup(id: liveGroupID) }
            }
            group?.live = true
        }
        flush()
        // the optimistic bubble is drawn as the transcript's last user row, so the echo can adopt it in place
        if let p = pendingEcho { out.append(.user(p.item)) }
        return out
    }

    /// A live turn reads as it happens: the header, then the agent's messages in full with each run of tools between
    /// them folded to one line. A message keeps its id from streaming at the end to sitting above the next run, so
    /// it stays in place; once the turn ends it all folds back into the header, leaving the final answer.
    private func liveTimeline(_ g: ActivityGroup) -> [DisplayItem] {
        var items: [DisplayItem] = [.activity(g)]
        var run: [ChatEntry] = []
        func flushRun(last: Bool) {
            guard let first = run.first else { return }
            let active = last && !g.answerFollows
            let listed = !StepRowModel.build(run).isEmpty
            // a run with nothing to list (only the steps tool) shows only while it is the current one
            guard active || listed else { run = []; return }
            // the current action while a tool runs or the agent thinks; between tools the run so far, not "Thinking…"
            let busy: Bool = {
                switch run.last {
                case .tool(let t)?: return t.status == "in_progress" || t.status == "pending" || t.status.isEmpty
                case .message(let m)?: return m.role == .thought
                default: return false
                }
            }()
            let seg = StepSegment(id: first.id.derived(2), steps: run)
            items.append(.segment(StepSegment(id: seg.id, steps: run, liveLine: active ? (busy || !listed ? g.liveAction : seg.summary) : nil)))
            run = []
        }
        for s in g.steps {
            if case .message(let m) = s, m.role == .agent { flushRun(last: false); items.append(.answer(m)) } else { run.append(s) }
        }
        flushRun(last: true)
        return items
    }

    func reset(events: [JSON]) {
        entries = []
        toolIndex = [:]
        planItem = nil
        for e in events { apply(e["update"] ?? .null) }
        if pendingEcho != nil { withoutAnimation { pendingEcho = nil } }
        adoptedEcho = nil; echoImagesToSkip = 0
        revision += 1
    }

    /// `live`: just arrived over the socket (not a reattach catch-up), so a new user message animates in.
    func ingest(seq: Int, update: JSON, live: Bool = false) {
        guard seq > lastSeq else { return }
        lastSeq = seq
        apply(update, live: live)
        revision += 1
    }

    private func withoutAnimation(_ body: () -> Void) {
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t, body)
    }

    var modelOption: ConfigOption? { configOptions.first { $0.category == "model" || $0.id == "model" } }
    var effortOption: ConfigOption? { configOptions.first { $0.category == "thought_level" || $0.id == "thought_level" } }
    var modeOption: ConfigOption? { configOptions.first { $0.category == "mode" || $0.id == "mode" } }

    /// Optimistically reflect a choice before the agent confirms it.
    func setLocalConfigValue(_ id: String, _ value: JSON) {
        configOptions = configOptions.map { o in o.id == id ? o.with(current: value) : o }
    }

    func setConfigOptions(_ j: JSON?) {
        guard let arr = j?.array else { return }
        configOptions = arr.compactMap(ConfigOption.init)
    }

    func setModes(_ j: JSON?) {
        guard let j, !j.isNull else { return }
        modes = (j["availableModes"]?.array ?? []).compactMap { m in
            guard let id = m["id"]?.string else { return nil }
            return SessionMode(id: id, name: m["name"]?.string ?? id)
        }
        currentMode = j["currentModeId"]?.string
    }

    /// Adds one more block of the same message to `last`; false when the block starts a row of its own.
    private static func merge(_ block: JSON, type: String?, into last: MessageItem, synthetic: Bool) -> Bool {
        if type == "text", let t = block["text"]?.string {
            // Synthetic user blocks are separate prompt parts: join with a newline.
            if synthetic && last.role == .user && !last.text.isEmpty { last.text += "\n" + t } else { last.text += t }
            return true
        }
        if type == "image", let d = block["data"]?.string, let data = Data(base64Encoded: d) {
            last.images.append(data); return true
        }
        if type == "resource_link" || type == "resource", let f = FileRef(block: block) {
            if !last.files.contains(f) { last.files.append(f) }
            return true
        }
        return false
    }

    /// `echo`: a user message the bridge recorded (not a "!" shell entry) — it replaces `pendingEcho`;
    /// `live`: a new row created by it animates in (Motion.sent on the row).
    private func appendText(role: MessageItem.Role, block: JSON, messageId: String?, synthetic: Bool, echo: Bool = false, live: Bool = false) {
        let type = block["type"]?.string
        // The echo's first block adopts the optimistic bubble (same MessageItem, so the same row identity: a rise
        // still in flight just finishes). The echo usually lands within that rise, since the bridge records it on receipt.
        if echo, let pending = pendingEcho {
            adopt(pending.item, block: block, type: type, messageId: messageId, synthetic: synthetic)
            return
        }
        if case .message(let last)? = entries.last, last.role == role, last.messageId == messageId {
            // later image blocks of an adopted echo are already on screen
            if type == "image", last === adoptedEcho, echoImagesToSkip > 0 { echoImagesToSkip -= 1; return }
            if Self.merge(block, type: type, into: last, synthetic: synthetic) { return }
        }
        let item = MessageItem(role: role, text: "", messageId: messageId)
        item.synthetic = synthetic
        if type == "text" { item.text = block["text"]?.string ?? "" }
        else if type == "image", let d = block["data"]?.string, let data = Data(base64Encoded: d) { item.images.append(data) }
        else if type == "resource_link" || type == "resource", let f = FileRef(block: block) { item.files.append(f) }
        else if type == "resource" { item.text = block["resource"]?["text"]?.string ?? "" }   // embedded text without a file uri
        else { return }
        if echo && live {
            // a message nobody here was waiting for (sent at turn end from the queue, or from another device)
            withAnimation(Motion.layout) { entries.append(.message(item)) }
        } else {
            entries.append(.message(item))
        }
    }

    /// The optimistic bubble's item becomes the transcript row; the echo's text replaces its text (identical unless
    /// messages held on the bridge went out ahead of it), images it already shows are skipped, files dedupe.
    private func adopt(_ item: MessageItem, block: JSON, type: String?, messageId: String?, synthetic: Bool) {
        item.messageId = messageId
        item.synthetic = synthetic
        echoImagesToSkip = item.images.count
        if type == "text", let t = block["text"]?.string { item.text = t }
        else if type == "image" { echoImagesToSkip = max(0, echoImagesToSkip - 1) }
        else if type == "resource" && FileRef(block: block) == nil { item.text = block["resource"]?["text"]?.string ?? item.text }
        else { _ = Self.merge(block, type: type, into: item, synthetic: synthetic) }
        adoptedEcho = item
        // the bubble itself doesn't change; only the turn's live row, now below it, fades in
        withAnimation(Motion.layout) {
            pendingEcho = nil
            entries.append(.message(item))
        }
    }

    func apply(_ u: JSON, live: Bool = false) {
        let synthetic = u["_meta"]?["leash"]?["synthetic"]?.bool == true
        switch u["sessionUpdate"]?.string {
        case "user_message_chunk":
            // The bridge records its own echo of every prompt as synthetic (hub.mjs #echo); only "!" shell entries
            // (synthetic + shell) are not the user's message.
            let shell = u["_meta"]?["leash"]?["shell"]?.bool == true
            appendText(role: .user, block: u["content"] ?? .null, messageId: u["messageId"]?.string, synthetic: synthetic, echo: !shell, live: live)
        case "agent_message_chunk":
            appendText(role: .agent, block: u["content"] ?? .null, messageId: u["messageId"]?.string, synthetic: false)
        case "agent_thought_chunk":
            appendText(role: .thought, block: u["content"] ?? .null, messageId: u["messageId"]?.string, synthetic: false)
        case "tool_call":
            guard let id = u["toolCallId"]?.string else { return }
            if let existing = toolIndex[id] { existing.apply(u); return }
            let t = ToolItem(toolCallId: id)
            t.apply(u)
            toolIndex[id] = t
            entries.append(.tool(t))
        case "tool_call_update":
            guard let id = u["toolCallId"]?.string else { return }
            if let t = toolIndex[id] { t.apply(u) } else {
                let t = ToolItem(toolCallId: id); t.apply(u); toolIndex[id] = t; entries.append(.tool(t))
            }
        case "plan":
            let p = planItem ?? newPlan()
            p.entries = (u["entries"]?.array ?? []).map { PlanEntry(content: $0["content"]?.string ?? "", status: $0["status"]?.string ?? "pending", priority: $0["priority"]?.string ?? "medium") }
        case "plan_update":
            let p = planItem ?? newPlan()
            let plan = u["plan"]
            if plan?["type"]?.string == "markdown" {
                p.markdown = plan?["content"]?.string; p.path = plan?["path"]?.string
            } else {
                p.entries = (plan?["entries"]?.array ?? []).map { PlanEntry(content: $0["content"]?.string ?? "", status: $0["status"]?.string ?? "pending", priority: $0["priority"]?.string ?? "medium") }
            }
        case "plan_removed":
            if let p = planItem { entries.removeAll { if case .plan(let x) = $0 { return x === p }; return false }; planItem = nil }
        case "config_option_update":
            setConfigOptions(u["configOptions"])
        case "current_mode_update":
            currentMode = u["currentModeId"]?.string
        case "session_info_update":
            if let t = u["title"]?.string { title = t }
        case "available_commands_update":
            commands = (u["availableCommands"]?.array ?? []).compactMap { c in
                guard let n = c["name"]?.string else { return nil }
                return (n, c["description"]?.string ?? "")
            }
        case "usage_update":
            if let used = u["used"]?.int, let size = u["size"]?.int { usage = (used, size) }
        case "leash_turn_stats":
            entries.append(.stats(TurnStats(u)))
        case "leash_auto_approved":
            if let id = u["toolCallId"]?.string, let t = toolIndex[id] { t.autoApproved = true }
        case "notice":
            entries.append(.notice(NoticeItem(severity: u["severity"]?.string ?? "info", title: u["title"]?.string ?? String(localized: "Notice"), detail: u["description"]?.string)))
        default:
            break
        }
    }

    private func newPlan() -> PlanItem {
        let p = PlanItem()
        planItem = p
        entries.append(.plan(p))
        return p
    }
}
