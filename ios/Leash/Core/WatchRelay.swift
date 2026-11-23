import Foundation
import WatchConnectivity

/// The iPhone half of the watch app's link. watchOS doesn't allow WebSockets for an app like this (TN3135), so the
/// watch asks the phone, and the phone talks to the Mac. A message from the watch wakes this app in the background
/// (WCSession.sendMessage), so it works while Leash is suspended, as long as the iPhone is nearby.
///
/// Phone → watch: the latest `WatchSnapshot` as application context (kept for the watch app's next launch) and,
/// while the watch app is open, as a message.
@MainActor
final class WatchRelay: NSObject {
    static let shared = WatchRelay()

    private weak var model: AppModel?
    private var pushTask: Task<Void, Never>?
    private var lastPushed: WatchSnapshot?

    private var session: WCSession? { WCSession.isSupported() ? WCSession.default : nil }

    /// Called from LeashApp.init, so a background launch for a watch message finds the delegate in place.
    func start(model: AppModel) {
        self.model = model
        guard let session else { return }
        session.delegate = self
        session.activate()
    }

    /// Whether a watch app is there to talk to (Settings shows it).
    var watchAppInstalled: Bool {
        guard let s = session, s.activationState == .activated else { return false }
        return s.isPaired && s.isWatchAppInstalled
    }

    /// Something the watch shows changed: send a fresh snapshot shortly (coalesced).
    func setNeedsPush() {
        guard watchAppInstalled else { return }
        pushTask?.cancel()
        pushTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.push()
        }
    }

    private func push(force: Bool = false) {
        guard let model, let s = session, s.activationState == .activated, s.isWatchAppInstalled else { return }
        let snap = model.watchSnapshot()
        var same = snap; same.at = lastPushed?.at ?? snap.at
        if !force && same == lastPushed { return }
        lastPushed = snap
        guard let data = WatchMessage.encode(snap) else { return }
        try? s.updateApplicationContext([WatchMessage.snapshot: data])
        if s.isReachable {
            s.sendMessage([WatchMessage.op: WatchMessage.snapshot, WatchMessage.snapshot: data], replyHandler: nil, errorHandler: nil)
        }
    }

    // MARK: requests from the watch

    private func handle(_ message: [String: Any]) async -> [String: Any] {
        guard let model else { return [WatchMessage.error: WatchFailure.failed.rawValue] }
        switch message[WatchMessage.op] as? String {
        case WatchMessage.snapshot:
            // answer at once from what we know; a fresh one follows once the Mac has answered
            let now = model.watchSnapshot()
            Task {
                await model.backgroundWork {
                    if await model.awaitConnection() { await model.refreshSessions() }
                    self.push(force: true)
                }
            }
            return [WatchMessage.snapshot: WatchMessage.encode(now) ?? Data()]
        case WatchMessage.answer:
            guard let key = message[WatchMessage.key] as? String else { return [WatchMessage.error: WatchFailure.failed.rawValue] }
            let optionId = message[WatchMessage.optionId] as? String
            let r = await model.backgroundWork { await model.answer(key: key, optionId: optionId) }
            return Self.reply(r)
        case WatchMessage.reply:
            guard let sid = message[WatchMessage.sessionId] as? String, let text = message[WatchMessage.text] as? String
            else { return [WatchMessage.error: WatchFailure.failed.rawValue] }
            let agent = message[WatchMessage.agent] as? String ?? "agy"
            let r = await model.backgroundWork { await model.replyFromWatch(sessionId: sid, agent: agent, text: text) }
            return Self.reply(r)
        default:
            return [WatchMessage.error: WatchFailure.failed.rawValue]
        }
    }

    private static func reply(_ r: AppModel.RemoteOutcome) -> [String: Any] {
        let f: WatchFailure
        switch r {
        case .done: return [WatchMessage.ok: true]
        case .macUnreachable: f = .macUnreachable
        case .alreadyAnswered: f = .alreadyAnswered
        case .locked: f = .locked
        case .changedElsewhere: f = .changedElsewhere
        case .failed: f = .failed
        }
        return [WatchMessage.error: f.rawValue]
    }
}

extension WatchRelay: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        Task { @MainActor in self.setNeedsPush() }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        // the user switched watches: start over with the new one
        session.activate()
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.setNeedsPush() }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        let box = ReplyBox(message: message, reply: replyHandler)
        Task { @MainActor in
            let r = await self.handle(box.message)
            box.reply(r)
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        let box = ReplyBox(message: message, reply: { _ in })
        Task { @MainActor in _ = await self.handle(box.message) }
    }
}

/// Carries WatchConnectivity's (non-Sendable) message and reply handler onto the main actor.
private final class ReplyBox: @unchecked Sendable {
    let message: [String: Any]
    let reply: ([String: Any]) -> Void
    init(message: [String: Any], reply: @escaping ([String: Any]) -> Void) {
        self.message = message
        self.reply = reply
    }
}
