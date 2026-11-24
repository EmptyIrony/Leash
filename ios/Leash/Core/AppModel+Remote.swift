import Foundation
import SwiftUI
import UIKit

/// The ways Leash is driven from outside its own screens: leash:// links (Bark, the Live Activity), Live Activity
/// buttons and the Apple Watch. The last two can wake the app in the background with no scene: they connect, do
/// one thing, and let go of the connection again so the bridge's away-detection for Bark stays honest.
extension AppModel {
    enum RemoteOutcome: Equatable {
        case done
        case macUnreachable, alreadyAnswered, locked, changedElsewhere, failed
    }

    // MARK: links

    /// leash://pair?… pairs; leash://session?id=…&agent=… opens that conversation; leash://open just opens the app.
    func open(_ url: URL) {
        guard url.scheme == "leash" else { return }
        switch url.host {
        case "session":
            let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            guard let id = items.first(where: { $0.name == "id" })?.value, !id.isEmpty else { return }
            let s = store(for: id)
            if let agent = items.first(where: { $0.name == "agent" })?.value, !agent.isEmpty,
               !sessions.contains(where: { $0.id == id }) { s.agent = agent }
            openRequest = OpenRequest(sessionId: id)
        case "open":
            break
        default:
            pair(with: url)
        }
    }

    // MARK: scene

    func scenePhaseChanged(_ phase: ScenePhase) {
        sceneActive = phase == .active
        switch phase {
        case .active:
            foreground()
            // a turn started while in the background (a watch reply) can get its Live Activity now
            liveActivity.setNeedsUpdate(immediate: true)
        case .background: liveActivity.appBackgrounded()
        default: break
        }
    }

    // MARK: background work

    /// Connected and `ready` (pending requests and running turns are then current), within `timeout`.
    func awaitConnection(timeout: TimeInterval = 8) async -> Bool {
        guard isConfigured else { return false }
        if isConnected { return true }
        link.ensureConnected()
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(100))
            if isConnected { return true }
            if case .failed = connection { return false }
        }
        return isConnected
    }

    /// Run work the system woke us for. A background task (the system's finish-what-you-started allowance, not a
    /// keep-alive) covers it; afterwards, if no scene is on screen, the socket is closed.
    func backgroundWork<T>(_ body: () async -> T) async -> T {
        backgroundJobs += 1
        if backgroundTask == .invalid {
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "leash.remote") { [weak self] in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    UIApplication.shared.endBackgroundTask(self.backgroundTask)
                    self.backgroundTask = .invalid
                }
            }
        }
        let result = await body()
        backgroundJobs -= 1
        if backgroundJobs == 0 {
            if UIApplication.shared.applicationState == .background {
                // let the last frames (and ActivityKit's update) go out first
                try? await Task.sleep(for: .milliseconds(600))
                if backgroundJobs == 0 && UIApplication.shared.applicationState == .background { link.disconnect() }
            }
            if backgroundJobs == 0 && backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
        }
        return result
    }

    // MARK: Live Activity buttons

    func perform(_ action: LiveActivityAction) async {
        await backgroundWork {
            switch action {
            case .answer(let key, let optionId, let allow):
                switch await answer(key: key, optionId: optionId) {
                case .done: liveActivity.show(allow ? .allowed : .denied)
                case .alreadyAnswered: liveActivity.show(.alreadyAnswered)
                case .macUnreachable: liveActivity.show(.unreachable)
                default: liveActivity.show(.failed)
                }
            case .refresh:
                if await awaitConnection() {
                    // `ready` reconciled running turns and pending requests (and re-attaches open chats); the list
                    // says how a turn that ended unseen came out
                    await refreshSessions()
                } else {
                    liveActivity.show(.unreachable)
                }
            }
            await liveActivity.flushNow()
        }
    }

    /// Answer a permission request and wait for the bridge to confirm it.
    func answer(key: String, optionId: String?) async -> RemoteOutcome {
        guard await awaitConnection() else { return .macUnreachable }
        guard pending[key] != nil else { return .alreadyAnswered }   // `ready` just refreshed the list
        let outcome: JSON = optionId.map { ["outcome": "selected", "optionId": .string($0)] } ?? ["outcome": "cancelled"]
        do {
            _ = try await link.replyConfirmed(key: key, result: ["outcome": outcome])
            return .done   // the bridge's `resolved` broadcast removes it here too
        } catch let e as BridgeError where e.code == -32002 {
            pending.removeValue(forKey: key)
            return .alreadyAnswered
        } catch let e as BridgeError where e.code == -1 || e.code == -2 {
            return .macUnreachable
        } catch {
            return .failed
        }
    }

    /// Tell the bridge we alerted about this request ourselves, so Bark doesn't push it too (newer bridges).
    func noticed(key: String) {
        guard let p = pending[key] else { return }
        var params: [String: JSON] = ["key": .string(key), "agent": .string(p.agentId)]
        if let sid = p.sessionId { params["sessionId"] = .string(sid) }
        Task { _ = try? await link.call("bridge/noticed", .object(params), timeout: 10) }
    }

    // MARK: watch

    /// A dictated reply from the watch: attach if needed, send, and report once the bridge has taken this message
    /// (echoed as the turn's first message, queued, or steered into the running turn). Bounded to about 25 s so the
    /// watch (which waits 45 s) never gives up first and invites a double send.
    func replyFromWatch(sessionId: String, agent: String, text: String) async -> RemoteOutcome {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .failed }
        guard await awaitConnection() else { return .macUnreachable }
        let s = store(for: sessionId)
        if !sessions.contains(where: { $0.id == sessionId }) { s.agent = agent }
        if !s.attached || s.epoch == nil {
            // opening a conversation the phone never loaded can take a while; don't outlast the watch
            Task { await attach(s) }
            let until = Date().addingTimeInterval(12)
            try? await Task.sleep(for: .milliseconds(100))
            while s.attaching && Date() < until { try? await Task.sleep(for: .milliseconds(150)) }
            if s.attaching { return .failed }
        }
        if s.lockedBy != nil { return .locked }
        guard s.attached else { return s.error == nil ? .macUnreachable : .failed }

        // what was already there must not count as this message's outcome
        let draftBefore = s.unsentDraft
        s.unsentDraft = nil
        s.error = nil
        let queuedBefore = s.queued.count
        let wasRunning = s.running
        send(s, text: text, images: [])
        let echoId = s.pendingEcho?.id
        func restore() { s.unsentDraft = draftBefore }   // a refused watch reply never lands in the phone's composer

        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(150))
            if s.lockedBy != nil { restore(); return .locked }
            if s.unsentDraft != nil { restore(); return s.error == nil ? .changedElsewhere : .failed }
            if s.queued.count > queuedBefore { restore(); return .done }
            if !wasRunning, let echoId {
                // idle: the optimistic bubble is adopted by the bridge's echo of this very message
                if s.pendingEcho?.id != echoId && s.error == nil { restore(); return .done }
            } else if wasRunning, s.error == nil, let last = s.entries.last, case .message(let m) = last, m.role == .user, m.text.contains(text) {
                // mid-turn with steering: the bridge echoes it into the running turn
                restore(); return .done
            }
            if s.error != nil && !s.running { restore(); return .failed }
        }
        restore()
        return isConnected ? .failed : .macUnreachable
    }

    /// What the watch shows: requests waiting for you, running conversations, a few recent ones.
    func watchSnapshot() -> WatchSnapshot {
        let link: WatchSnapshot.Link
        if !isConfigured { link = .notPaired }
        else if isConnected { link = .connected }
        else {
            switch connection {
            case .connecting, .waiting: link = .connecting
            default: link = .offline
            }
        }
        func title(_ sid: String?) -> String {
            guard let sid else { return String(localized: "Conversation") }
            return sessions.first(where: { $0.id == sid })?.displayTitle ?? existingStore(sid)?.title ?? String(localized: "Conversation")
        }
        let requests: [WatchSnapshot.Request] = pending.values.sorted { $0.key < $1.key }.map { p in
            let tool = ToolItem(toolCallId: p.params["toolCall"]?["toolCallId"]?.string ?? p.key)
            tool.apply(p.params["toolCall"] ?? .null)
            let opts = (p.params["options"]?.array ?? []).compactMap { o -> ApprovalTray.PermOption? in
                guard let id = o["optionId"]?.string else { return nil }
                return ApprovalTray.PermOption(id: id, name: o["name"]?.string ?? id, kind: o["kind"]?.string ?? "")
            }
            let allow = opts.first { $0.kind == "allow_once" } ?? opts.first { $0.kind.hasPrefix("allow") && $0.kind != "allow_always" }
            let deny = opts.first { $0.kind == "reject_once" } ?? opts.first { !$0.kind.hasPrefix("allow") }
            let toolTitle = tool.title == "Tool" ? p.toolTitle : tool.title
            return WatchSnapshot.Request(
                id: p.key, sessionId: p.sessionId, agent: p.agentId, agentLabel: agentLabel(p.agentId),
                sessionTitle: String(title(p.sessionId).prefix(80)),
                title: String((p.isPermission ? toolTitle : p.toolTitle).prefix(160)),
                detail: p.isPermission ? tool.commandPreview.map { String($0.prefix(400)) } : nil,
                isQuestion: !p.isPermission,
                allow: allow.map { .init(id: $0.id, name: PermissionNames.name($0)) },
                deny: deny.map { .init(id: $0.id, name: PermissionNames.name($0)) })
        }
        func row(_ s: SessionSummary) -> WatchSnapshot.Session {
            WatchSnapshot.Session(id: s.id, agent: s.agent, agentLabel: agentLabel(s.agent), title: String(s.displayTitle.prefix(80)),
                                  folder: s.folderName, running: running.contains(s.id),
                                  startedAt: existingStore(s.id)?.turnStartedAt ?? turnStarts[s.id],
                                  updatedAt: s.updatedAt, pendingCount: s.pendingCount, lockedBy: s.lockedBy)
        }
        let live = sessions.filter { running.contains($0.id) || $0.pendingCount > 0 }
        let recent = sessions.filter { !running.contains($0.id) && $0.pendingCount == 0 }.prefix(6)
        return WatchSnapshot(macName: macName, link: link, sessions: (live + recent).map(row), requests: requests, at: Date())
    }
}
