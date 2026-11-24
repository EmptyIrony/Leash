import UIKit
import UserNotifications

/// Leash's own notifications while it runs off screen (kept running for its Live Activity, or just backgrounded):
/// a request that needs you, with Allow / Deny, and a turn that ended. Each one is reported to the bridge
/// (`bridge/noticed`), so Bark, which still covers a suspended or closed Leash, doesn't push it again.
/// Local notifications need no push entitlement and no extension.
@MainActor
final class LocalNotifier: NSObject {
    static let shared = LocalNotifier()

    /// Allowed to show alerts: only then does Leash tell the bridge it alerted (otherwise Bark keeps doing it).
    private(set) var canAlert = false

    /// At launch: actions and responses can start the app in the background, so the delegate must exist from then.
    func start() {
        let c = UNUserNotificationCenter.current()
        c.delegate = self
        // Allow runs something on the Mac: a locked iPhone asks to be unlocked first (as on the Live Activity)
        let allow = UNNotificationAction(identifier: "allow", title: String(localized: "Allow"), options: [.authenticationRequired])
        let deny = UNNotificationAction(identifier: "deny", title: String(localized: "Deny"), options: [])
        c.setNotificationCategories([
            UNNotificationCategory(identifier: "approval", actions: [allow, deny], intentIdentifiers: []),
            UNNotificationCategory(identifier: "request", actions: [], intentIdentifiers: []),
        ])
        refresh()
    }

    /// Settings may have changed while Leash was away.
    func refresh() {
        Task {
            let s = await UNUserNotificationCenter.current().notificationSettings()
            canAlert = s.authorizationStatus == .authorized && s.alertSetting == .enabled
        }
    }

    /// Asked once, the first time you start a turn (the moment a notification starts to make sense).
    func askPermission() {
        Task {
            let c = UNUserNotificationCenter.current()
            guard await c.notificationSettings().authorizationStatus == .notDetermined else { return }
            _ = try? await c.requestAuthorization(options: [.alert, .sound])
            refresh()
        }
    }

    // MARK: posting

    /// A permission request or question. Returns whether Leash alerted about it.
    func request(_ p: PendingRequest, label: String, conversation: String?) -> Bool {
        guard canAlert else { return false }
        let c = UNMutableNotificationContent()
        c.title = p.isPermission ? String(localized: "\(label) needs approval") : String(localized: "\(label) has a question")
        if let conversation { c.subtitle = conversation }
        var info: [AnyHashable: Any] = ["key": p.key]
        if p.isPermission {
            let tool = ToolItem(toolCallId: p.params["toolCall"]?["toolCallId"]?.string ?? p.key)
            tool.apply(p.params["toolCall"] ?? .null)
            let title = tool.title == "Tool" ? p.toolTitle : tool.title
            c.body = [title, tool.commandPreview.map { String($0.prefix(300)) }].compactMap { $0 }.joined(separator: "\n")
            let (allow, deny) = Self.quickAnswers(p)
            if let allow {
                info["allow"] = allow
                if let deny { info["deny"] = deny }
                c.categoryIdentifier = "approval"
            } else {
                c.categoryIdentifier = "request"
            }
        } else {
            c.body = p.toolTitle   // a form: answered in the chat
            c.categoryIdentifier = "request"
        }
        if let sid = p.sessionId {
            c.threadIdentifier = sid
            info["url"] = Self.link(sid, agent: p.agentId)
        }
        c.userInfo = info
        c.sound = .default
        post(id: "req." + p.key, c)
        return true
    }

    /// A turn ended (not one you stopped). Returns whether Leash alerted about it.
    func turnEnded(sessionId: String, agent: String, label: String, conversation: String?, stopReason: String?, began: Date?) -> Bool {
        guard canAlert, stopReason != "cancelled" else { return false }
        let c = UNMutableNotificationContent()
        switch stopReason {
        case "end_turn", nil: c.title = String(localized: "\(label) finished")
        case "error", "agent_exited", "refusal": c.title = String(localized: "\(label) hit an error")
        default: c.title = String(localized: "\(label) stopped")
        }
        if let conversation { c.subtitle = conversation }
        if let began {
            let took = Duration.seconds(max(0, Int(Date().timeIntervalSince(began))))
                .formatted(.units(allowed: [.hours, .minutes, .seconds], width: .abbreviated))
            c.body = String(localized: "Took \(took)")
        }
        c.threadIdentifier = sessionId
        c.userInfo = ["url": Self.link(sessionId, agent: agent)]
        c.sound = .default
        post(id: "end." + sessionId, c)
        return true
    }

    /// An answer from a notification didn't reach the Mac.
    func answerFailed(unreachable: Bool, url: String?) {
        let c = UNMutableNotificationContent()
        c.title = unreachable ? String(localized: "Couldn't reach your Mac") : String(localized: "Couldn't send your answer")
        c.body = String(localized: "Open Leash to answer.")
        if let url { c.userInfo = ["url": url] }
        c.sound = .default
        post(id: "failed." + UUID().uuidString, c)
    }

    /// Answered or withdrawn: its notification goes.
    func remove(request key: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["req." + key])
    }

    private func post(id: String, _ c: UNMutableNotificationContent) {
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }

    /// Allow once and deny, never "always" (as on the Live Activity and the watch).
    private static func quickAnswers(_ p: PendingRequest) -> (allow: String?, deny: String?) {
        let opts = p.params["options"]?.array ?? []
        func option(_ test: (String) -> Bool) -> String? { opts.first { test($0["kind"]?.string ?? "") }?["optionId"]?.string }
        let allow = option { $0 == "allow_once" } ?? option { $0.hasPrefix("allow") && $0 != "allow_always" }
        let deny = option { $0 == "reject_once" } ?? option { !$0.hasPrefix("allow") }
        return (allow, deny)
    }

    private static func link(_ sid: String, agent: String) -> String {
        var u = URLComponents()
        u.scheme = "leash"; u.host = "session"
        u.queryItems = [URLQueryItem(name: "id", value: sid), URLQueryItem(name: "agent", value: agent)]
        return u.url?.absoluteString ?? "leash://open"
    }
}

extension LocalNotifier: UNUserNotificationCenterDelegate {
    /// On screen the chat already shows it.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        let key = info["key"] as? String
        let allow = info["allow"] as? String
        let deny = info["deny"] as? String
        let url = info["url"] as? String
        Task { @MainActor in
            let model = AppModel.shared
            switch action {
            case "allow":
                if let key, let allow { await model.answerFromNotification(key: key, optionId: allow, allow: true, url: url) }
            case "deny":
                // no deny option: cancelling the request denies it
                if let key { await model.answerFromNotification(key: key, optionId: deny, allow: false, url: url) }
            case UNNotificationDefaultActionIdentifier:
                model.open(url.flatMap(URL.init(string:)) ?? URL(string: "leash://open")!)
            default:
                break
            }
            completionHandler()
        }
    }
}
