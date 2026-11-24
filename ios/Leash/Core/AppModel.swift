import Foundation
import Observation
import SwiftUI
import UIKit
import Security

struct AgentInfo: Identifiable, Hashable {
    let id: String
    var label: String
    var state: String
    var error: String?
    var home: String?
    var name: String?
    var imageInput: Bool
    /// Mid-turn messages are injected into the running turn (Claude Code); otherwise the bridge queues them.
    var steering = false
    /// Antigravity's "Turbo": the bridge approves every permission prompt. Claude Code has its own modes for that.
    var turbo = false
    /// /btw side questions (Claude Code: a throwaway fork on its host).
    var btw = false
    var isReady: Bool { state == "ready" }
}

struct SessionSummary: Identifiable, Hashable {
    var id: String
    var agent: String = "agy"
    var cwd: String
    var title: String?
    var updatedAt: Date?
    var running: Bool
    var pendingCount: Int
    /// open in another client right now ("cli", "claude-desktop", …); such sessions can't be opened here
    var lockedBy: String? = nil

    var displayTitle: String {
        if let t = title, !t.isEmpty { return t }
        return String(localized: "Conversation \(String(id.prefix(8)))")
    }
    var folderName: String { (cwd as NSString).lastPathComponent }
}

/// The subagent a request came from (the bridge files it under the root conversation).
struct SubagentRef: Hashable {
    let sessionId: String
    let title: String?
    let agentType: String?
    var label: String { [title, agentType == title ? nil : agentType].compactMap { $0 }.joined(separator: " · ") }
}

struct PendingRequest: Identifiable, Hashable {
    let key: String
    var agentId: String = "agy"
    let method: String
    let sessionId: String?
    let params: JSON
    var subagent: SubagentRef?
    var id: String { key }
    var isPermission: Bool { method == "session/request_permission" }
    var toolTitle: String { params["toolCall"]?["title"]?.string ?? params["message"]?.string ?? String(localized: "Needs your input") }
}

struct BrowseResult {
    let path: String
    let parent: String?
    let isGit: Bool
    let dirs: [String]
    let files: [String]
}

@Observable
@MainActor
final class AppModel {
    // persisted connection settings
    var host: String = "" { didSet { UserDefaults.standard.set(host, forKey: "host") } }
    var port: Int = 8787 { didSet { UserDefaults.standard.set(port, forKey: "port") } }
    var token: String = "" { didSet { Keychain.set("token", token) } }
    var macName: String = "" { didSet { UserDefaults.standard.set(macName, forKey: "macName") } }

    // live state
    var connection: BridgeConnection.State = .idle
    var agentState: String = "unknown"
    var agentError: String?
    var agentName: String?
    var bridgeHome: String?
    var imageInput = false
    var sessions: [SessionSummary] = []
    var sessionsLoading = false
    var sessionsError: String?
    var pending: [String: PendingRequest] = [:]
    var running: Set<String> = []
    var lastAttention: PendingRequest?
    /// the conversation currently on screen (set by ChatView)
    @ObservationIgnored var visibleSessionId: String?
    var agents: [AgentInfo] = []
    var quotas: [String: QuotaInfo] = [:]
    var quotaErrors: [String: String] = [:]
    /// legacy single-agent accessors (Antigravity)
    var quota: QuotaInfo? { quotas["agy"] ?? quotas.values.first }
    var quotaError: String? { quotaErrors["agy"] }

    @ObservationIgnored private var stores: [String: ChatStore] = [:]
    let link = BridgeConnection()
    @ObservationIgnored private var nextCursor: String?

    var isConfigured: Bool { !host.isEmpty && !token.isEmpty }
    var isConnected: Bool { connection == .connected }
    /// at least one agent can take work
    var agentReady: Bool { isConnected && (agents.isEmpty ? agentState == "ready" : agents.contains { $0.isReady }) }
    func agent(_ id: String) -> AgentInfo? { agents.first { $0.id == id } }
    func isReady(_ agentId: String) -> Bool { isConnected && (agent(agentId)?.isReady ?? (agentId == "agy" && agentState == "ready")) }
    func steers(_ agentId: String) -> Bool { agent(agentId)?.steering ?? false }
    func hasTurbo(_ agentId: String) -> Bool { agent(agentId)?.turbo ?? false }
    func asksSideQuestions(_ agentId: String) -> Bool { agent(agentId)?.btw ?? false }
    func agentLabel(_ id: String) -> String { agent(id)?.label ?? (id == "claude" ? "Claude Code" : "Antigravity") }

    init() {
        let d = UserDefaults.standard
        host = d.string(forKey: "host") ?? ""
        let p = d.integer(forKey: "port")
        port = p == 0 ? 8787 : p
        token = Keychain.get("token") ?? ""
        macName = d.string(forKey: "macName") ?? ""
        link.onState = { [weak self] s in
            guard let self else { return }
            if case .failed = s, self.connection != s { Haptics.error() }   // e.g. token rejected
            self.connection = s
        }
        link.onMessage = { [weak self] m in self?.handle(m) }
        if isConfigured { reconfigure() }
    }

    func reconfigure() {
        link.configure(host: host, port: port, token: token)
        if isConfigured { link.connect() }
    }

    func foreground() {
        guard isConfigured else { return }
        link.ensureConnected()
        if isConnected { Task { await refreshQuota() } }
    }

    /// Asks the bridge for quota (it reuses data younger than 2 min unless forced).
    func refreshQuota(force: Bool = false) async {
        guard isConnected else { return }
        let ids = agents.isEmpty ? ["agy"] : agents.map(\.id)
        await withTaskGroup(of: Void.self) { group in
            for id in ids {
                group.addTask { await self.refreshQuota(agent: id, force: force) }
            }
        }
    }

    func refreshQuota(agent id: String, force: Bool = false) async {
        do {
            let r = try await link.call("bridge/quota", ["force": .bool(force), "agent": .string(id)], timeout: 75)
            if let q = QuotaInfo(r["quota"]) { quotas[id] = q }
            quotaErrors[id] = r["error"]?.string
        } catch { quotaErrors[id] = error.localizedDescription }
    }

    /// The tightest quota pool across all agents (for the list's rings).
    var tightestPool: QuotaPool? {
        quotas.values.compactMap(\.mostConstrained).min { ($0.minFraction ?? 1) < ($1.minFraction ?? 1) }
    }

    /// leash://pair?host=..&port=..&token=..&name=..
    @discardableResult
    func pair(with url: URL) -> Bool {
        guard url.scheme == "leash", let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        let q = Dictionary(uniqueKeysWithValues: (c.queryItems ?? []).compactMap { i in i.value.map { (i.name, $0) } })
        guard let h = q["host"], let t = q["token"] else { return false }
        host = h
        port = Int(q["port"] ?? "") ?? 8787
        token = t
        macName = q["name"] ?? h
        reconfigure()
        return true
    }

    // MARK: inbound
    private func handle(_ m: JSON) {
        switch m["op"]?.string {
        case "ready":
            applyAgent(m["agent"])
            bridgeHome = m["bridge"]?["home"]?.string
            agents = (m["agents"]?.array ?? []).compactMap(AppModel.agentInfo)
            for a in m["agents"]?.array ?? [] { if let id = a["id"]?.string, let q = QuotaInfo(a["quota"]) { quotas[id] = q } }
            if agents.isEmpty, let q = QuotaInfo(m["quota"]) { quotas["agy"] = q }
            pending = [:]
            for p in m["pending"]?.array ?? [] { addPending(p, notify: false) }
            running = Set((m["running"]?.array ?? []).compactMap { $0.string })
            Task { await refreshSessions() }
            Task { await refreshQuota() }
            for s in stores.values where s.attached { Task { await attach(s) } }
        case "agent":
            if let id = m["agentId"]?.string, let i = agents.firstIndex(where: { $0.id == id }) {
                agents[i].state = m["state"]?.string ?? agents[i].state
                agents[i].error = m["error"]?.string
                if let info = m["info"], !info.isNull {
                    agents[i].name = info["agentInfo"]?["title"]?.string ?? info["agentInfo"]?["name"]?.string
                    agents[i].imageInput = info["agentCapabilities"]?["promptCapabilities"]?["image"]?.bool ?? false
                }
                agents[i].steering = m["steering"]?.bool ?? false
                agents[i].turbo = m["turbo"]?.bool ?? false
                agents[i].btw = m["btw"]?.bool ?? false
            }
            if m["agentId"]?.string == nil || m["agentId"]?.string == "agy" { applyAgent(m) }
            if agentState == "ready" {
                Task { await refreshSessions() }
                for s in stores.values where s.attached { Task { await attach(s) } }
            }
        case "update":
            guard let sid = m["sessionId"]?.string, let store = stores[sid] else { return }
            if let ep = m["epoch"]?.string, store.epoch != nil, ep != store.epoch {
                Task { await attach(store) }   // agent restarted; resync
                return
            }
            store.ingest(seq: m["seq"]?.int ?? 0, update: m["update"] ?? .null, live: true)
        case "request":
            addPending(m, notify: true)
        case "resolved":
            if let k = m["key"]?.string { pending.removeValue(forKey: k) }
            refreshPendingCounts()
        case "turn":
            guard let sid = m["sessionId"]?.string else { return }
            let isRunning = m["running"]?.bool == true
            if isRunning { running.insert(sid) } else { running.remove(sid) }
            if let s = stores[sid] {
                if let ms = m["startedAt"]?.double { s.turnStartedAt = Date(timeIntervalSince1970: ms / 1000) }
                else if isRunning && !s.running { s.turnStartedAt = Date() }
                if isRunning && !s.running { s.lastStopReason = nil }
                s.running = isRunning
                if !isRunning { s.lastStopReason = m["stopReason"]?.string }
            }
            if let i = sessions.firstIndex(where: { $0.id == sid }), sessions[i].running != isRunning {
                withAnimation(Motion.layout) { sessions[i].running = isRunning }
            }
        case "session_info":
            guard let sid = m["sessionId"]?.string else { return }
            if let i = sessions.firstIndex(where: { $0.id == sid }), let t = m["title"]?.string, sessions[i].title != t {
                withAnimation(Motion.layout) { sessions[i].title = t }
            }
            if let s = stores[sid], let t = m["title"]?.string { s.title = t }
        case "queue":
            if let sid = m["sessionId"]?.string, let s = stores[sid] { s.queued = QueuedMessage.list(m["items"]) }
        case "shell":
            if let sid = m["sessionId"]?.string, let s = stores[sid] { s.shellRunning = m["running"]?.bool == true }
        case "session_flags":
            if let sid = m["sessionId"]?.string, let s = stores[sid] { s.autoApprove = m["autoApprove"]?.bool ?? false }
        case "session_locked":
            // opened in Claude Code on the computer: the bridge dropped its copy; Retry reloads once it is closed there
            guard let sid = m["sessionId"]?.string else { return }
            let by = m["lockedBy"]?.string ?? "other"
            if let s = stores[sid], !s.running { s.lockedBy = by; s.attached = false }
            if let i = sessions.firstIndex(where: { $0.id == sid }), sessions[i].lockedBy != by {
                withAnimation(Motion.layout) { sessions[i].lockedBy = by }
            }
        case "session_unlocked":
            // closed / finished on the computer: reload the transcript if this conversation is on screen
            guard let sid = m["sessionId"]?.string else { return }
            if let i = sessions.firstIndex(where: { $0.id == sid }), sessions[i].lockedBy != nil {
                withAnimation(Motion.layout) { sessions[i].lockedBy = nil }
            }
            if let s = stores[sid], s.lockedBy != nil {
                s.lockedBy = nil
                if visibleSessionId == sid { Task { await attach(s) } }
            }
        case "quota":
            if let q = QuotaInfo(m["quota"]) { quotas[m["agentId"]?.string ?? "agy"] = q; quotaErrors[m["agentId"]?.string ?? "agy"] = nil }
        case "session_created":
            Task { await refreshSessions() }
        default:
            break
        }
    }

    static func agentInfo(_ a: JSON) -> AgentInfo? {
        guard let id = a["id"]?.string else { return nil }
        let info = a["info"]
        return AgentInfo(id: id, label: a["label"]?.string ?? id, state: a["state"]?.string ?? "down", error: a["error"]?.string,
                         home: a["home"]?.string,
                         name: info?["agentInfo"]?["title"]?.string ?? info?["agentInfo"]?["name"]?.string,
                         imageInput: info?["agentCapabilities"]?["promptCapabilities"]?["image"]?.bool ?? false,
                         steering: a["steering"]?.bool ?? false,
                         turbo: a["turbo"]?.bool ?? false,
                         btw: a["btw"]?.bool ?? false)
    }

    private func applyAgent(_ a: JSON?) {
        guard let a else { return }
        agentState = a["state"]?.string ?? agentState
        agentError = a["error"]?.string
        if let info = a["info"], !info.isNull {
            agentName = info["agentInfo"]?["title"]?.string ?? info["agentInfo"]?["name"]?.string
            imageInput = info["agentCapabilities"]?["promptCapabilities"]?["image"]?.bool ?? false
        }
    }

    private func addPending(_ p: JSON, notify: Bool) {
        guard let key = p["key"]?.string, let method = p["method"]?.string else { return }
        var req = PendingRequest(key: key, agentId: p["agentId"]?.string ?? String(key.split(separator: ":").first ?? "agy"), method: method, sessionId: p["sessionId"]?.string, params: p["params"] ?? .null)
        if let sub = p["subagent"], let sid = sub["sessionId"]?.string {
            req.subagent = SubagentRef(sessionId: sid, title: sub["title"]?.string, agentType: sub["description"]?.string)
        }
        pending[key] = req
        refreshPendingCounts()
        if notify {
            lastAttention = req
            if req.sessionId != visibleSessionId { Haptics.warning() }
        }
    }

    /// Rows move between "Needs you" and their folder as counts change.
    private func refreshPendingCounts() {
        let next = withPendingCounts(sessions)
        guard next != sessions else { return }
        withAnimation(Motion.layout) { sessions = next }
    }

    private func withPendingCounts(_ list: [SessionSummary]) -> [SessionSummary] {
        var out = list
        for i in out.indices {
            let id = out[i].id
            out[i].pendingCount = pending.values.filter { $0.sessionId == id }.count
        }
        return out
    }

    func pendingFor(_ sessionId: String) -> [PendingRequest] {
        pending.values.filter { $0.sessionId == sessionId }.sorted { $0.key < $1.key }
    }

    // MARK: sessions
    func refreshSessions() async {
        guard agentReady else { return }
        sessionsLoading = true
        defer { sessionsLoading = false }
        do {
            var all: [SessionSummary] = []
            var cursor: String? = nil
            var pages = 0
            repeat {
                var params: [String: JSON] = [:]
                if let cursor { params["cursor"] = .string(cursor) }
                let r = try await link.call("session/list", .object(params))
                all += (r["sessions"]?.array ?? []).compactMap(AppModel.summary)
                cursor = r["nextCursor"]?.string
                pages += 1
            } while cursor != nil && pages < 10
            var next = all.sorted { ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }
            for i in next.indices where running.contains(next[i].id) { next[i].running = true }
            next = withPendingCounts(next)
            // the first load just appears; later ones move rows to their new places
            if sessions.isEmpty { sessions = next } else if next != sessions { withAnimation(Motion.layout) { sessions = next } }
            sessionsError = nil
        } catch {
            sessionsError = error.localizedDescription
        }
    }

    private static let iso: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }()
    private static let isoNoFrac = ISO8601DateFormatter()

    static func summary(_ j: JSON) -> SessionSummary? {
        guard let id = j["sessionId"]?.string else { return nil }
        let d = j["updatedAt"]?.string.flatMap { iso.date(from: $0) ?? isoNoFrac.date(from: $0) }
        return SessionSummary(id: id, agent: j["_leash"]?["agent"]?.string ?? "agy", cwd: j["cwd"]?.string ?? "", title: j["title"]?.string, updatedAt: d,
                              running: j["_leash"]?["running"]?.bool ?? false, pendingCount: j["_leash"]?["pending"]?.int ?? 0,
                              lockedBy: j["_leash"]?["lockedBy"]?.string)
    }

    func store(for id: String) -> ChatStore {
        if let s = stores[id] { return s }
        let s = ChatStore(sessionId: id)
        if let sum = sessions.first(where: { $0.id == id }) { s.cwd = sum.cwd; s.title = sum.title; s.agent = sum.agent }
        else if let p = pending.values.first(where: { $0.sessionId == id }) { s.agent = p.agentId }
        s.running = running.contains(id)
        stores[id] = s
        return s
    }

    func attach(_ s: ChatStore) async {
        guard isReady(s.agent) else { s.attached = true; return }
        if s.attaching { s.reattachNeeded = true; return }
        s.attaching = true
        s.loading = s.entries.isEmpty
        defer {
            s.loading = false
            s.attaching = false
            if s.reattachNeeded { s.reattachNeeded = false; Task { await attach(s) } }
        }
        // only a bubble that existed before this request can be judged by its result (a send during a re-attach can't)
        let echoAtStart = s.pendingEcho?.id
        do {
            var params: [String: JSON] = ["sessionId": .string(s.sessionId), "sinceSeq": JSON(s.lastSeq), "agent": .string(s.agent)]
            if let e = s.epoch { params["epoch"] = .string(e) }
            if let c = s.cwd { params["cwd"] = .string(c) }
            let r = try await link.call("bridge/attach", .object(params), timeout: 120)
            let events = r["events"]?.array ?? []
            // a replay or catch-up lands in one frame: implicit animations (live step list, rows) stay out of it
            var replay = Transaction()
            replay.disablesAnimations = true
            withTransaction(replay) {
                if r["reset"]?.bool == true {
                    s.reset(events: events)
                } else {
                    for e in events { s.ingest(seq: e["seq"]?.int ?? 0, update: e["update"] ?? .null) }   // catch-up: not live
                }
            }
            s.epoch = r["epoch"]?.string
            s.lastSeq = r["lastSeq"]?.int ?? s.lastSeq
            s.cwd = r["cwd"]?.string ?? s.cwd
            if let t = r["title"]?.string { s.title = t }
            s.running = r["running"]?.bool ?? false
            if let ms = r["turnStartedAt"]?.double { s.turnStartedAt = Date(timeIntervalSince1970: ms / 1000) }
            if s.running { running.insert(s.sessionId) } else { running.remove(s.sessionId) }
            s.setConfigOptions(r["configOptions"])
            s.setModes(r["modes"])
            s.autoApprove = r["autoApprove"]?.bool ?? false
            s.queued = QueuedMessage.list(r["queue"])
            for p in r["pending"]?.array ?? [] { addPending(p, notify: false) }
            s.attached = true
            s.error = nil
            s.lockedBy = nil
            // idle and the catch-up held no echo of it: the prompt never reached the bridge (connection dropped)
            if !s.running, let echoAtStart, s.pendingEcho?.id == echoAtStart { withAnimation(Motion.layout) { s.pendingEcho = nil } }
        } catch let e as BridgeError where e.code == BridgeError.lockedElsewhere {
            s.lockedBy = e.holder ?? "other"
            s.attached = false
            s.error = nil
            if let echoAtStart, s.pendingEcho?.id == echoAtStart { withAnimation(Motion.layout) { s.pendingEcho = nil } }
        } catch {
            s.error = error.localizedDescription
        }
        if let i = sessions.firstIndex(where: { $0.id == s.sessionId }), sessions[i].lockedBy != s.lockedBy {
            withAnimation(Motion.layout) { sessions[i].lockedBy = s.lockedBy }
        }
    }

    /// Views showing a conversation: its chat, and a subagent's transcript pushed on top of it (which hides the chat).
    @ObservationIgnored private var watchers: [String: Int] = [:]

    /// A view showing `s` appeared: it stays attached (live updates) and counts as on screen while any such view is.
    func watch(_ s: ChatStore) {
        watchers[s.sessionId, default: 0] += 1
        visibleSessionId = s.sessionId
    }

    /// The last view of `s` going away detaches it. A push from the chat to a subagent (or back) disappears one view
    /// as the other appears, in either order: the short wait lets the next one claim the conversation first.
    func unwatch(_ s: ChatStore) {
        let n = max(0, (watchers[s.sessionId] ?? 1) - 1)
        watchers[s.sessionId] = n
        guard n == 0 else { return }
        Task {
            try? await Task.sleep(for: .milliseconds(600))
            guard (watchers[s.sessionId] ?? 0) == 0 else { return }
            if visibleSessionId == s.sessionId { visibleSessionId = nil }
            detach(s)
        }
    }

    func detach(_ s: ChatStore) {
        // keep the store cached (cheap) but stop live updates for sessions we left
        s.attached = false
        Task { _ = try? await link.call("bridge/detach", ["sessionId": .string(s.sessionId), "agent": .string(s.agent)]) }
    }

    func newSession(cwd: String, agent: String = "agy") async throws -> String {
        let r = try await link.call("session/new", ["cwd": .string(cwd), "agent": .string(agent)], timeout: 120)
        guard let id = r["sessionId"]?.string else { throw BridgeError(code: -3, message: "agent returned no session id") }
        let s = store(for: id)
        s.cwd = cwd
        s.agent = agent
        s.epoch = r["epoch"]?.string
        s.lastSeq = r["lastSeq"]?.int ?? 0
        s.setConfigOptions(r["configOptions"])
        s.setModes(r["modes"])
        s.attached = true
        withAnimation(Motion.layout) {
            sessions.insert(SessionSummary(id: id, agent: agent, cwd: cwd, title: nil, updatedAt: Date(), running: false, pendingCount: 0), at: 0)
        }
        return id
    }

    /// Mid-turn the bridge steers the message into the running turn or queues it for the turn's end;
    /// `interrupt` stops the turn and sends it (with anything queued) right away.
    /// `attachments`: resource_link blocks of files already stored on the agent's host (see `upload`).
    func send(_ s: ChatStore, text: String, images: [Data], attachments: [JSON] = [], interrupt: Bool = false, retried: Bool = false) {
        var blocks: [JSON] = []
        if !text.isEmpty { blocks.append(["type": "text", "text": .string(text)]) }
        for img in images { blocks.append(["type": "image", "mimeType": "image/jpeg", "data": .string(img.base64EncodedString())]) }
        blocks += attachments
        // what goes back into the composer if nothing was sent (attached files return as their links)
        let restore = ([text] + attachments.compactMap(AppModel.linkText)).filter { !$0.isEmpty }.joined(separator: "\n")
        guard !blocks.isEmpty else { return }
        let wasIdle = !s.running
        // drawn in the transcript right away; the bridge's echo of it takes its place (ChatStore.appendText)
        let echo: PendingEcho? = wasIdle ? PendingEcho(item: AppModel.echoItem(text: text, images: images, attachments: attachments)) : nil
        let echoID = echo?.id
        if let echo {
            s.pendingEcho = echo
            s.turnStartedAt = Date()
        }
        s.running = true
        running.insert(s.sessionId)
        Task {
            do {
                var params: [String: JSON] = ["sessionId": .string(s.sessionId), "prompt": .array(blocks), "agent": .string(s.agent)]
                if interrupt { params["interrupt"] = .bool(true) }
                let r = try await link.call("session/prompt", .object(params), timeout: nil)
                // the bridge was mid-turn after all and queued it: the queue row shows it, not a bubble
                if r["queued"]?.bool == true { withAnimation(Motion.layout) { dropEcho(s, echoID) } }
            } catch let e as BridgeError where e.code == -1 {
                // connection dropped; the turn keeps running on the Mac and we resync on reconnect
                // (the pending echo stays until the resync's echo replaces it, or attach finds the session idle)
            } catch let e as BridgeError where e.code == BridgeError.lockedElsewhere {
                // opened on the computer meanwhile: nothing was sent; the bubble goes back toward the composer
                withAnimation(Motion.layout) { dropEcho(s, echoID) }
                s.running = false; running.remove(s.sessionId)
                s.lockedBy = e.holder ?? "other"
                s.attached = false
                s.unsentDraft = restore
                if !images.isEmpty { s.unsentImages = images }
                Haptics.error()
            } catch let e as BridgeError where e.code == BridgeError.changedElsewhere && !retried {
                // the conversation moved on elsewhere since we loaded it: reload the transcript, then send on top of it
                withAnimation(Motion.layout) { dropEcho(s, echoID) }
                s.running = false; running.remove(s.sessionId)
                await attach(s)
                if s.attached && s.lockedBy == nil {
                    withAnimation(Motion.layout) { send(s, text: text, images: images, attachments: attachments, interrupt: interrupt, retried: true) }
                } else if s.lockedBy != nil {
                    s.unsentDraft = restore
                    if !images.isEmpty { s.unsentImages = images }
                }
            } catch {
                // still waiting on its echo: the bridge refused it before any turn started, so nothing was sent
                // (after the echo, the error is the turn failing; the bridge logs that as a notice)
                if let echoID, s.pendingEcho?.id == echoID {
                    withAnimation(Motion.layout) { dropEcho(s, echoID) }
                    if wasIdle { s.running = false; running.remove(s.sessionId) }
                    s.unsentDraft = restore
                    if !images.isEmpty { s.unsentImages = images }
                }
                s.error = error.localizedDescription
                Haptics.error()
            }
        }
    }

    /// What the bridge will echo for this prompt (text, then images, then files), drawn before it arrives.
    private static func echoItem(text: String, images: [Data], attachments: [JSON]) -> MessageItem {
        let item = MessageItem(role: .user, text: text, messageId: nil)
        item.synthetic = true
        item.images = images
        item.files = attachments.compactMap { FileRef(block: $0) }
        return item
    }

    /// Removes the optimistic bubble `send` put up, unless the echo already replaced it.
    private func dropEcho(_ s: ChatStore, _ id: UUID?) {
        if let id, s.pendingEcho?.id == id { s.pendingEcho = nil }
    }

    /// "!" shell mode: run `command` in the conversation's folder on the agent's host. The bridge logs it in Claude
    /// Code's transcript format and hands it to the model with the next prompt.
    func runShell(_ s: ChatStore, command: String) {
        // the iOS keyboard's smart punctuation can't be turned off per field from SwiftUI: undo it for the shell
        let cmd = command.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\u{201C}", with: "\"").replacingOccurrences(of: "\u{201D}", with: "\"")
            .replacingOccurrences(of: "\u{2018}", with: "'").replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{2014}", with: "--")
        guard !cmd.isEmpty, !s.shellRunning else { return }
        s.shellRunning = true
        Task {
            do {
                let r = try await link.call("bridge/shell", ["sessionId": .string(s.sessionId), "command": .string(cmd), "agent": .string(s.agent)], timeout: 160)
                if let code = r["exitCode"]?.int, code != 0 { Haptics.warning() }
            } catch let e as BridgeError where e.code == -1 {
                // connection dropped; the command still finishes on the host and shows up after the resync
            } catch {
                s.error = error.localizedDescription
                Haptics.error()
            }
            s.shellRunning = false
        }
    }

    /// At most this many side questions are open at once; closing one makes room.
    static let maxSideQuestions = 3

    /// /btw: ask about the conversation without adding to it, also mid-turn. The bridge answers only this phone.
    func askSideQuestion(_ s: ChatStore, _ question: String) {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, s.sideQuestions.count < Self.maxSideQuestions else { return }
        let item = SideQuestion(question: q)
        s.sideQuestions.append(item)
        Task {
            do {
                let r = try await link.call("bridge/btw", ["sessionId": .string(s.sessionId), "question": .string(q), "agent": .string(s.agent)], timeout: 190)
                let text = (r["text"]?.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                withAnimation(Motion.layout) {
                    s.resolveSideQuestion(item.id, answer: text.isEmpty ? nil : text, error: text.isEmpty ? String(localized: "No answer came back.") : nil)
                }
                if text.isEmpty { Haptics.error() } else { Haptics.success() }
            } catch {
                withAnimation(Motion.layout) { s.resolveSideQuestion(item.id, answer: nil, error: error.localizedDescription) }
                Haptics.error()
            }
        }
    }

    func closeSideQuestion(_ s: ChatStore, _ id: UUID) {
        s.sideQuestions.removeAll { $0.id == id }
    }

    /// The queue's "Interrupt and Send" (mid-turn) / "Send Now" (after a Stop): send what is queued right away.
    func sendQueued(_ s: ChatStore) {
        guard !s.queued.isEmpty, isReady(s.agent), s.attached else { return }
        let wasIdle = !s.running
        if wasIdle { s.turnStartedAt = Date() }
        s.running = true
        running.insert(s.sessionId)
        Task {
            do {
                _ = try await link.call("session/prompt", ["sessionId": .string(s.sessionId), "prompt": .array([]), "interrupt": .bool(true), "agent": .string(s.agent)], timeout: nil)
            } catch let e as BridgeError where e.code == -1 {
                // connection dropped; the bridge still sends it and we resync on reconnect
            } catch let e as BridgeError where e.code == BridgeError.lockedElsewhere {
                // opened on the computer meanwhile: nothing was sent, the queue stays on the bridge
                if wasIdle { s.running = false; running.remove(s.sessionId) }
                s.lockedBy = e.holder ?? "other"
                s.attached = false
                Haptics.error()
            } catch {
                // refused before any turn started (changed elsewhere, agent down, …): no turn op will clear it
                if wasIdle { s.running = false; running.remove(s.sessionId) }
                s.error = error.localizedDescription
                Haptics.error()
            }
        }
    }

    /// Take a queued message back from the bridge; `edit` puts it into the composer, otherwise it is dropped.
    func unqueue(_ s: ChatStore, _ q: QueuedMessage, edit: Bool) {
        s.queued.removeAll { $0.id == q.id }
        Task {
            do {
                let r = try await link.call("bridge/unqueue", ["sessionId": .string(s.sessionId), "id": .string(q.id), "agent": .string(s.agent)])
                guard edit else { return }
                let blocks = r["prompt"]?.array ?? []
                // attached files are already on the host: they come back as "[@name](file://…)" links (what Claude Code itself sends)
                let links = blocks.compactMap(AppModel.linkText)
                let text = (blocks.filter { $0["type"]?.string == "text" }.compactMap { $0["text"]?.string } + links).joined(separator: "\n")
                let imgs = blocks.filter { $0["type"]?.string == "image" }.compactMap { $0["data"]?.string }.compactMap { Data(base64Encoded: $0) }
                if !text.isEmpty { s.unsentDraft = text }
                if !imgs.isEmpty { s.unsentImages = imgs }
            } catch let e as BridgeError where e.code == BridgeError.alreadySent {
                // it went out with the turn that just ended
            } catch {
                s.error = error.localizedDescription
            }
        }
    }

    func cancel(_ s: ChatStore) {
        Task { _ = try? await link.call("session/cancel", ["sessionId": .string(s.sessionId), "agent": .string(s.agent)]) }
    }

    func setConfig(_ s: ChatStore, option: ConfigOption, value: JSON) {
        s.setLocalConfigValue(option.id, value)
        Task {
            var params: [String: JSON] = ["sessionId": .string(s.sessionId), "configId": .string(option.id), "value": value, "agent": .string(s.agent)]
            if option.type == "boolean" { params["type"] = "boolean" }
            do {
                let r = try await link.call("session/set_config_option", .object(params))
                s.setConfigOptions(r["configOptions"])
            } catch { s.error = error.localizedDescription }
        }
    }

    /// Pick one of the agent's own modes, or (Antigravity only) "turbo": agy's auto mode plus bridge-side
    /// auto-approval of every permission prompt.
    func choose(mode: String, for s: ChatStore) {
        let turbo = mode == "turbo" && hasTurbo(s.agent)
        if turbo != s.autoApprove {
            s.autoApprove = turbo
            Task { _ = try? await link.call("bridge/setAutoApprove", ["sessionId": .string(s.sessionId), "enabled": .bool(turbo), "agent": .string(s.agent)]) }
        }
        let target = turbo ? "auto" : mode
        if let opt = s.modeOption {
            if opt.current.string != target, opt.choices.contains(where: { $0.value == target }) { setConfig(s, option: opt, value: .string(target)) }
        } else if s.currentMode != target, s.modes.contains(where: { $0.id == target }) {
            setMode(s, mode: target)
        }
        if turbo || ModeNames.approvesAll(mode) { Haptics.impact(.heavy) } else { Haptics.selection() }
    }

    func setMode(_ s: ChatStore, mode: String) {
        Task {
            do { _ = try await link.call("session/set_mode", ["sessionId": .string(s.sessionId), "modeId": .string(mode), "agent": .string(s.agent)]); s.currentMode = mode }
            catch { s.error = error.localizedDescription }
        }
    }

    func answerPermission(_ p: PendingRequest, optionId: String?, kind: String = "") {
        let outcome: JSON
        if let optionId { outcome = ["outcome": "selected", "optionId": .string(optionId)] } else { outcome = ["outcome": "cancelled"] }
        do { try link.reply(key: p.key, result: ["outcome": outcome]); pending.removeValue(forKey: p.key); refreshPendingCounts() }
        catch { Haptics.error(); return }
        switch kind {
        case "allow_once": Haptics.impact(.medium)
        case "allow_always": Haptics.impact(.heavy)
        case "reject_once", "reject_always": Haptics.impact(.soft)
        default: Haptics.impact(.light)
        }
    }

    func answerElicitation(_ p: PendingRequest, action: String, content: [String: JSON]? = nil) {
        var r: [String: JSON] = ["action": .string(action)]
        if let content { r["content"] = .object(content) }
        do { try link.reply(key: p.key, result: .object(r)); pending.removeValue(forKey: p.key); refreshPendingCounts() }
        catch { Haptics.error(); return }
        if action == "accept" { Haptics.impact(.medium) } else { Haptics.impact(.soft) }
    }

    // MARK: files
    func browse(_ path: String?, agent: String = "agy") async throws -> BrowseResult {
        var params: [String: JSON] = ["agent": .string(agent)]
        if let path { params["path"] = .string(path) }
        let r = try await link.call("bridge/browse", .object(params))
        return BrowseResult(path: r["path"]?.string ?? "", parent: r["parent"]?.string, isGit: r["isGit"]?.bool ?? false,
                            dirs: (r["dirs"]?.array ?? []).compactMap { $0.string }, files: (r["files"]?.array ?? []).compactMap { $0.string })
    }

    func readFile(_ path: String, agent: String = "agy") async throws -> (text: String?, truncated: Bool, binary: Bool) {
        let r = try await link.call("bridge/readFile", ["path": .string(path), "agent": .string(agent)], timeout: 60)
        return (r["text"]?.string, r["truncated"]?.bool ?? false, r["binary"]?.bool ?? false)
    }

    func restartAgent(_ id: String = "agy") {
        Task { _ = try? await link.call("bridge/restartAgent", ["agent": .string(id)]) }
    }

    func recentFolders(agent: String) -> [String] {
        var seen = Set<String>(); var out: [String] = []
        for s in sessions where s.agent == agent && !s.cwd.isEmpty && !seen.contains(s.cwd) { seen.insert(s.cwd); out.append(s.cwd) }
        return Array(out.prefix(12))
    }

    func home(for agent: String) -> String? { self.agent(agent)?.home ?? bridgeHome }

    func shortPath(_ p: String, agent: String = "agy") -> String {
        if let h = home(for: agent), p.hasPrefix(h) { return "~" + p.dropFirst(h.count) }
        return p
    }
}

/// Imperative haptics for model-level events (views use `.haptic(...)`). Honors the in-app switch;
/// the system "Haptics" setting is honored by UIKit automatically.
@MainActor
enum Haptics {
    static var enabled: Bool { UserDefaults.standard.object(forKey: "haptics") as? Bool ?? true }
    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle, intensity: CGFloat = 1) {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: style).impactOccurred(intensity: intensity)
    }
    static func selection() { guard enabled else { return }; UISelectionFeedbackGenerator().selectionChanged() }
    static func success() { guard enabled else { return }; UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { guard enabled else { return }; UINotificationFeedbackGenerator().notificationOccurred(.warning) }
    static func error() { guard enabled else { return }; UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

enum Keychain {
    static func set(_ key: String, _ value: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key, kSecAttrService as String: "leash"]
        SecItemDelete(base as CFDictionary)
        guard !value.isEmpty else { UserDefaults.standard.removeObject(forKey: "kc." + key); return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(add as CFDictionary, nil)
        // Some ad-hoc profiles lack keychain entitlements; keep the app usable.
        if status != errSecSuccess { UserDefaults.standard.set(value, forKey: "kc." + key) }
        else { UserDefaults.standard.removeObject(forKey: "kc." + key) }
    }
    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key, kSecAttrService as String: "leash",
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else {
            return UserDefaults.standard.string(forKey: "kc." + key)
        }
        return String(data: d, encoding: .utf8)
    }
}
