import Foundation
import Observation
import WatchConnectivity
import WatchKit

/// The watch app's state. Everything goes through the iPhone app (WatchConnectivity): watchOS doesn't allow the
/// WebSocket the bridge speaks (TN3135), and the phone already holds the pairing. A message from here wakes the
/// iPhone app in the background, so this works while Leash is closed on the phone, as long as the phone is nearby.
@Observable
@MainActor
final class WatchModel {
    enum ActionResult: Equatable {
        case ok
        /// nil: the iPhone didn't answer
        case failed(WatchFailure?)
    }

    var snapshot: WatchSnapshot
    /// the iPhone can be messaged right now
    var reachable = false
    var activated = false
    var refreshing = false

    @ObservationIgnored private var knownRequests: Set<String>?
    @ObservationIgnored private let link = WatchLink()
    private static let cacheKey = "snapshot"

    init() {
        snapshot = WatchMessage.decode(UserDefaults.standard.data(forKey: Self.cacheKey)) ?? .empty
        link.model = self
        link.start()
    }

    var requests: [WatchSnapshot.Request] { snapshot.requests }
    var working: [WatchSnapshot.Session] { snapshot.sessions.filter { $0.running } }
    var recent: [WatchSnapshot.Session] { snapshot.sessions.filter { !$0.running } }
    func session(_ id: String) -> WatchSnapshot.Session? { snapshot.sessions.first { $0.id == id } }
    func request(_ id: String) -> WatchSnapshot.Request? { snapshot.requests.first { $0.id == id } }
    func requests(for sessionId: String) -> [WatchSnapshot.Request] { snapshot.requests.filter { $0.sessionId == sessionId } }

    /// Actions need the iPhone, and the iPhone needs the Mac.
    var canAct: Bool { reachable && snapshot.link != .notPaired }

    // MARK: from the iPhone

    func apply(_ s: WatchSnapshot) {
        guard s.at >= snapshot.at || snapshot.at == .distantPast else { return }
        let ids = Set(s.requests.map(\.id))
        if let known = knownRequests, !ids.subtracting(known).isEmpty, WKApplication.shared().applicationState == .active {
            // a new request while the app is open (sensoryFeedback has no "notification" equivalent)
            WKInterfaceDevice.current().play(.notification)
        }
        knownRequests = ids
        snapshot = s
        if let d = WatchMessage.encode(s) { UserDefaults.standard.set(d, forKey: Self.cacheKey) }
    }

    func setReachable(_ r: Bool) {
        let was = reachable
        reachable = r
        if r && !was { refresh() }
    }

    // MARK: to the iPhone

    func refresh() {
        guard activated, reachable, !refreshing else { return }
        refreshing = true
        Task {
            let r = await link.ask([WatchMessage.op: WatchMessage.snapshot])
            refreshing = false
            if let d = r?.snapshot, let s = WatchMessage.decode(d) { apply(s) }
            // the iPhone follows up with a fresh one once it has heard from the Mac
        }
    }

    func answer(_ request: WatchSnapshot.Request, option: WatchSnapshot.Request.Option) async -> ActionResult {
        await result(of: [WatchMessage.op: WatchMessage.answer, WatchMessage.key: request.id, WatchMessage.optionId: option.id])
    }

    func reply(to session: WatchSnapshot.Session, text: String) async -> ActionResult {
        await result(of: [WatchMessage.op: WatchMessage.reply, WatchMessage.sessionId: session.id,
                          WatchMessage.agent: session.agent, WatchMessage.text: text])
    }

    private func result(of message: [String: Any]) async -> ActionResult {
        // the iPhone may first have to wake, reach the Mac and open the conversation (it stops itself at ~25 s)
        guard let r = await link.ask(message, timeout: 45) else { return .failed(nil) }
        if r.ok { return .ok }
        return .failed(r.error.flatMap(WatchFailure.init(rawValue:)) ?? .failed)
    }
}

/// A Sendable copy of the iPhone's reply.
struct PhoneReply: Sendable {
    var ok = false
    var error: String?
    var snapshot: Data?
}

/// WCSessionDelegate for the watch side; hands everything to the model on the main actor.
final class WatchLink: NSObject, WCSessionDelegate, @unchecked Sendable {
    @MainActor weak var model: WatchModel?

    @MainActor func start() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// sendMessage with a reply, or nil (unreachable, error, timeout).
    func ask(_ message: [String: Any], timeout: Double = 12) async -> PhoneReply? {
        let s = WCSession.default
        guard s.activationState == .activated, s.isReachable else { return nil }
        return await withCheckedContinuation { (c: CheckedContinuation<PhoneReply?, Never>) in
            let once = Once(c)
            s.sendMessage(message, replyHandler: { r in
                once.resume(PhoneReply(ok: r[WatchMessage.ok] as? Bool ?? false, error: r[WatchMessage.error] as? String,
                                       snapshot: r[WatchMessage.snapshot] as? Data))
            }, errorHandler: { _ in once.resume(nil) })
            Task {
                try? await Task.sleep(for: .seconds(timeout))
                once.resume(nil)
            }
        }
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        let ok = activationState == .activated
        let reachable = session.isReachable
        let context = session.receivedApplicationContext[WatchMessage.snapshot] as? Data
        Task { @MainActor in
            guard let model = self.model else { return }
            model.activated = ok
            if let s = WatchMessage.decode(context) { model.apply(s) }
            model.setReachable(reachable)
            model.refresh()
        }
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        let r = session.isReachable
        Task { @MainActor in self.model?.setReachable(r) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let d = applicationContext[WatchMessage.snapshot] as? Data
        Task { @MainActor in if let s = WatchMessage.decode(d) { self.model?.apply(s) } }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        let d = message[WatchMessage.snapshot] as? Data
        Task { @MainActor in if let s = WatchMessage.decode(d) { self.model?.apply(s) } }
    }
}

/// Resumes a continuation once, whichever of reply / error / timeout comes first.
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var c: CheckedContinuation<PhoneReply?, Never>?
    init(_ c: CheckedContinuation<PhoneReply?, Never>) { self.c = c }
    func resume(_ v: PhoneReply?) {
        lock.lock()
        let c = self.c
        self.c = nil
        lock.unlock()
        c?.resume(returning: v)
    }
}
