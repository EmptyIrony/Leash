import ActivityKit
import Foundation
import SwiftUI

/// Runs Leash's single Live Activity (Lock Screen, Dynamic Island, Apple Watch Smart Stack) for turns started from
/// this iPhone. It follows several conversations at once and focuses the most urgent: one waiting for approval wins,
/// otherwise the most recently started. The focus changes only when a phase changes, never on step updates.
///
/// No push (free team): updates happen only while the app runs. With "Update in Background" on, silent audio keeps
/// it running while the activity follows a turn (BackgroundAudio). Every update sets `staleDate` 75 s ahead, so a
/// frozen activity says it is old instead of pretending; a heartbeat keeps it fresh while the app is alive and connected.
@MainActor
final class LiveActivityController {
    typealias LAState = LeashActivityAttributes.ContentState

    /// A conversation whose turn this iPhone started.
    struct Tracked: Codable, Equatable {
        var sessionId: String
        var agent: String
        var title: String
        var startedAt: Date
        var endedAt: Date?
        var outcome: LAState.Phase?
        /// ended while Leash wasn't watching: the outcome comes from the next session list, if at all
        var missed = false
    }

    static let staleAfter: TimeInterval = 75
    private static let defaultsKey = "liveActivity.tracked"

    private unowned let model: AppModel
    private var activity: Activity<LeashActivityAttributes>?
    private var tracked: [String: Tracked] = [:] { didSet { save() } }
    private var focusId: String?
    private var note: (LAState.Note, Date)?
    private var lastSent: LAState?
    private var lastSentAt = Date.distantPast
    /// last time the state above was confirmed by the bridge
    private var confirmedAt = Date()
    private var pendingAlertKey: String?
    private var flushTask: Task<Void, Never>?
    private var flushScheduled = false
    private var forceNext = false
    private var heartbeat: Task<Void, Never>?

    static var enabled: Bool { UserDefaults.standard.object(forKey: "liveActivities") as? Bool ?? true }
    static var updatesInBackground: Bool { UserDefaults.standard.object(forKey: "backgroundUpdates") as? Bool ?? true }

    init(model: AppModel) {
        self.model = model
        // adopt what an earlier process left running; there is only ever one
        let running = Activity<LeashActivityAttributes>.activities.filter { $0.activityState == .active || $0.activityState == .stale }
        activity = running.first
        for extra in running.dropFirst() { Task { await extra.end(nil, dismissalPolicy: .immediate) } }
        if activity != nil, let d = UserDefaults.standard.data(forKey: Self.defaultsKey),
           let t = try? JSONDecoder().decode([String: Tracked].self, from: d) {
            tracked = t
            lastSent = activity?.content.state
        } else {
            tracked = [:]
        }
        if activity != nil { startHeartbeat() }
    }

    // MARK: events from AppModel

    /// A turn this iPhone started (send, send-queued, a watch reply).
    func turnStarted(_ s: ChatStore) {
        guard Self.enabled else { return }
        let title = model.sessions.first(where: { $0.id == s.sessionId })?.displayTitle ?? s.title ?? String(localized: "Conversation")
        tracked[s.sessionId] = Tracked(sessionId: s.sessionId, agent: s.agent, title: title, startedAt: s.turnStartedAt ?? Date())
        // the newest turn takes the focus unless the current one is waiting for you
        if focusId.map({ pendingPhase($0) == nil }) ?? true { focusId = s.sessionId }
        note = nil
        setNeedsUpdate(immediate: true)
    }

    /// A send the bridge refused before any turn started: forget it quietly.
    func turnAborted(_ sid: String) {
        guard tracked.removeValue(forKey: sid) != nil else { return }
        setNeedsUpdate(immediate: true)
    }

    /// The bridge's `turn` broadcast for any conversation.
    func turnChanged(_ sid: String, running: Bool, stopReason: String?, startedAt: Date?) {
        guard var t = tracked[sid] else { return }
        if running {
            // a queued message (or another device) continued it before the activity ended
            if t.endedAt != nil { t.endedAt = nil; t.outcome = nil; t.missed = false; if let startedAt { t.startedAt = startedAt } }
        } else if t.endedAt == nil {
            t.endedAt = Date()
            t.outcome = Self.phase(forStopReason: stopReason)
        }
        tracked[sid] = t
        setNeedsUpdate(immediate: true)
    }

    /// A permission request or question arrived live; alert (once) if nobody is looking at the app.
    func requestArrived(_ req: PendingRequest) {
        guard let sid = req.sessionId, tracked[sid] != nil else { return }
        if !model.sceneActive { pendingAlertKey = req.key }
        setNeedsUpdate(immediate: true)
    }

    /// Something that may change the focused conversation's step line, title or pending requests.
    /// `immediate` for phase changes; otherwise coalesced to at most one update a second (a streaming answer
    /// doesn't keep pushing it back). `force` sends even an unchanged state, to move the stale date.
    func setNeedsUpdate(immediate: Bool = false, force: Bool = false) {
        guard activity != nil || !tracked.isEmpty else { return }
        if force { forceNext = true }
        if !immediate && flushScheduled { return }
        flushTask?.cancel()
        flushScheduled = !immediate
        flushTask = Task { [weak self] in
            if !immediate { try? await Task.sleep(for: .seconds(1)) }
            guard !Task.isCancelled else { return }
            self?.flushScheduled = false
            await self?.flush()
        }
    }

    /// After `ready`: a tracked turn the bridge no longer runs ended while we weren't connected.
    func reconcile() {
        for (sid, var t) in tracked where t.endedAt == nil && !model.running.contains(sid) {
            t.endedAt = Date(); t.outcome = .ended; t.missed = true
            tracked[sid] = t
        }
        confirmedAt = Date()
        setNeedsUpdate(immediate: true)
    }

    /// The session list carries each conversation's last stop reason (newer bridges): fill in missed outcomes.
    func sessionsRefreshed() {
        var changed = false
        for (sid, var t) in tracked where t.missed {
            guard let s = model.sessions.first(where: { $0.id == sid }) else { continue }
            if let r = s.lastStopReason { t.outcome = Self.phase(forStopReason: r); changed = true }
            if let at = s.turnEndedAt { t.endedAt = at }
            t.missed = false
            tracked[sid] = t
        }
        if changed { setNeedsUpdate(immediate: true) }
    }

    /// The result of a Live Activity button.
    func show(_ n: LAState.Note) {
        note = (n, Date())
        setNeedsUpdate(immediate: true)
    }

    /// Leaving the foreground: one last update so the stale clock starts from now.
    func appBackgrounded() { setNeedsUpdate(immediate: true, force: true) }

    /// The Settings switch was turned off.
    func stopAll() {
        tracked = [:]
        flushTask?.cancel()
        heartbeat?.cancel(); heartbeat = nil
        let a = activity
        activity = nil
        lastSent = nil
        syncKeepAlive()
        Task { await a?.end(nil, dismissalPolicy: .immediate) }
    }

    /// Keep Leash running while an activity on screen follows a turn; let it go once the final state is out.
    func syncKeepAlive() {
        let showing = activity.map { $0.activityState == .active || $0.activityState == .stale } ?? false
        model.keepRunning(Self.enabled && Self.updatesInBackground && showing && !tracked.isEmpty)
    }

    /// Wait for a scheduled update to go out (intents end right after this).
    func flushNow() async {
        flushTask?.cancel()
        flushScheduled = false
        // let one already in flight finish first, then send the final state ourselves
        for _ in 0..<40 where flushing { try? await Task.sleep(for: .milliseconds(50)) }
        await flush(final: true)
    }

    // MARK: building the state

    static func phase(forStopReason r: String?) -> LAState.Phase {
        switch r {
        case "end_turn", nil: return .done
        case "cancelled": return .stopped
        case "error", "agent_exited", "refusal": return .failed
        default: return .stopped   // max_tokens, max_turn_requests, …
        }
    }

    private func pendingPhase(_ sid: String) -> (LAState.Phase, LAState.Approval?)? {
        let reqs = model.pendingFor(sid)
        if let p = reqs.first(where: { $0.isPermission }) {
            let opts = p.params["options"]?.array ?? []
            func option(_ test: (String) -> Bool) -> String? {
                opts.first { test($0["kind"]?.string ?? "") }?["optionId"]?.string
            }
            let allow = option { $0 == "allow_once" } ?? option { $0.hasPrefix("allow") && $0 != "allow_always" }
            let deny = option { $0 == "reject_once" } ?? option { !$0.hasPrefix("allow") }
            let tool = ToolItem(toolCallId: p.params["toolCall"]?["toolCallId"]?.string ?? p.key)
            tool.apply(p.params["toolCall"] ?? .null)
            let title = tool.title == "Tool" ? p.toolTitle : tool.title
            return (.approval, LAState.Approval(key: p.key, title: String(title.prefix(140)), allowOptionId: allow, denyOptionId: deny))
        }
        if !reqs.isEmpty { return (.question, nil) }
        return nil
    }

    /// The live sub-line the chat shows ("Running npm test…"), only while Leash still receives that conversation.
    private func step(_ sid: String) -> String? {
        guard let s = model.existingStore(sid), s.attached, s.running else { return nil }
        for item in s.displayItems().reversed() {
            if case .activity(let g) = item, g.live { return String(g.liveAction.prefix(120)) }
        }
        return nil
    }

    private func buildState() -> LAState? {
        guard !tracked.isEmpty else { return nil }
        let live = tracked.values.filter { $0.endedAt == nil }
        let now = Date()
        if model.isConnected { confirmedAt = now }
        let n = note.flatMap { now.timeIntervalSince($0.1) < 60 ? $0.0 : nil }

        if live.isEmpty {
            // final state: the conversation that ended last
            guard let t = tracked.values.max(by: { ($0.endedAt ?? .distantPast) < ($1.endedAt ?? .distantPast) }) else { return nil }
            return LAState(sessionId: t.sessionId, agent: t.agent, title: title(t), phase: t.outcome ?? .ended, step: nil,
                         startedAt: t.startedAt, endedAt: t.endedAt ?? now, updatedAt: confirmedAt, approval: nil, othersRunning: 0, note: n)
        }
        // focus: keep the current one unless another now needs you more (or it ended)
        let waiting = live.filter { pendingPhase($0.sessionId) != nil }.sorted { $0.startedAt > $1.startedAt }
        let current = live.first { $0.sessionId == focusId }
        let currentWaits = current.map { pendingPhase($0.sessionId) != nil } ?? false
        let focus: Tracked
        if let c = current, currentWaits || waiting.isEmpty { focus = c }
        else if let w = waiting.first { focus = w }
        else { focus = live.max { $0.startedAt < $1.startedAt }! }
        focusId = focus.sessionId

        let (phase, approval) = pendingPhase(focus.sessionId) ?? (.working, nil)
        return LAState(sessionId: focus.sessionId, agent: focus.agent, title: title(focus), phase: phase,
                     step: phase == .working ? step(focus.sessionId) : nil,
                     startedAt: model.existingStore(focus.sessionId)?.turnStartedAt ?? focus.startedAt, endedAt: nil,
                     updatedAt: confirmedAt, approval: approval, othersRunning: live.count - 1, note: n)
    }

    private func title(_ t: Tracked) -> String {
        let fresh = model.sessions.first(where: { $0.id == t.sessionId })?.displayTitle ?? model.existingStore(t.sessionId)?.title
        return String((fresh ?? t.title).prefix(80))
    }

    // MARK: ActivityKit

    private var flushing = false
    private var flushAgain = false
    private var finalAgain = false

    /// One flush at a time: ending a leftover activity awaits, and two flushes both seeing "no activity" would start
    /// two. A request that arrives meanwhile runs once more afterwards.
    private func flush(final: Bool = false) async {
        if flushing { flushAgain = true; finalAgain = finalAgain || final; return }
        flushing = true
        var isFinal = final
        repeat {
            flushAgain = false
            await flushOnce(final: isFinal)
            isFinal = finalAgain
            finalAgain = false
        } while flushAgain
        flushing = false
        syncKeepAlive()
    }

    /// `final`: the process may be suspended right after this (an intent): don't defer anything.
    private func flushOnce(final: Bool) async {
        guard Self.enabled else { return }
        // a turn that ended while we weren't connected: give the session list a moment to say how it ended
        if !final, !tracked.isEmpty, tracked.values.allSatisfy({ $0.endedAt != nil }),
           let missedAt = tracked.values.filter(\.missed).compactMap(\.endedAt).max() {
            let wait = 5 - Date().timeIntervalSince(missedAt)
            if wait > 0 {
                flushTask = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(wait))
                    guard !Task.isCancelled else { return }
                    await self?.flush()
                }
                return
            }
        }
        guard let state = buildState() else {
            // nothing left to follow (a refused send): take it down
            if let a = activity {
                activity = nil; lastSent = nil
                heartbeat?.cancel(); heartbeat = nil
                await a.end(nil, dismissalPolicy: .immediate)
            }
            return
        }
        let staleDate = state.updatedAt.addingTimeInterval(Self.staleAfter)

        if !state.phase.isLive {
            // every turn this iPhone started is over: show the outcome briefly, then go
            tracked = [:]
            focusId = nil
            heartbeat?.cancel(); heartbeat = nil
            guard let a = activity else { return }
            activity = nil
            lastSent = nil
            let quick = model.sceneActive || state.phase == .ended
            await a.end(ActivityContent(state: state, staleDate: nil),
                        dismissalPolicy: quick ? .immediate : .after(Date().addingTimeInterval(15 * 60)))
            return
        }

        if activity == nil {
            // ActivityKit only starts one from the foreground (or from a LiveActivityIntent)
            guard model.sceneActive, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
            // an ended one may still sit on the Lock Screen: never show two
            for old in Activity<LeashActivityAttributes>.activities { await old.end(nil, dismissalPolicy: .immediate) }
            do {
                activity = try Activity.request(attributes: LeashActivityAttributes(), content: ActivityContent(state: state, staleDate: staleDate))
                lastSent = state; lastSentAt = Date()
                startHeartbeat()
            } catch {
                // denied in Settings, or too many activities: the app works the same without it
            }
            return
        }

        // skip identical updates unless the stale clock needs pushing out
        var same = state; same.updatedAt = lastSent?.updatedAt ?? state.updatedAt
        let alertKey = pendingAlertKey
        if same == lastSent && alertKey == nil && !forceNext && Date().timeIntervalSince(lastSentAt) < 30 { return }
        pendingAlertKey = nil
        forceNext = false

        var alert: AlertConfiguration? = nil
        if let alertKey, state.phase == .approval || state.phase == .question {
            let label = model.agentLabel(state.agent)
            alert = AlertConfiguration(title: "\(label) needs you", body: "\(state.approval?.title ?? state.title)", sound: .default)
            model.noticed(key: alertKey)
        }
        lastSent = state; lastSentAt = Date()
        await activity?.update(ActivityContent(state: state, staleDate: staleDate), alertConfiguration: alert)
    }

    /// While the app runs and the Mac answers, keep pushing the stale date out (a long tool call sends nothing).
    private func startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                guard let self, !Task.isCancelled else { return }
                if self.model.isConnected { self.setNeedsUpdate(immediate: true) }
            }
        }
    }

    private func save() {
        if tracked.isEmpty { UserDefaults.standard.removeObject(forKey: Self.defaultsKey); return }
        if let d = try? JSONEncoder().encode(tracked) { UserDefaults.standard.set(d, forKey: Self.defaultsKey) }
    }
}
