import ActivityKit
import Foundation

/// The one Live Activity Leash runs while turns this iPhone started are working (HIG: one activity that follows
/// several events, not one per conversation). Everything lives in `ContentState`, focused on the most urgent
/// conversation: one waiting for approval wins, otherwise the most recently started.
/// There is no push (free team): the app updates it while it runs, and `staleDate` marks it as old otherwise.
/// Once every turn is over it ends with the outcome, which stays on the Lock Screen until that conversation is
/// opened (or for 4 hours, the system's limit).
struct LeashActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        enum Phase: String, Codable, Hashable {
            case working, approval, question
            case done, failed, stopped
            /// finished while Leash wasn't running: the outcome is unknown
            case ended

            var isLive: Bool { self == .working || self == .approval || self == .question }
        }

        /// What an action taken from the Live Activity came to; shown until the next update replaces it.
        enum Note: String, Codable, Hashable { case allowed, denied, alreadyAnswered, unreachable, failed }

        /// The turn's checklist (Claude Code's todos, Antigravity's steps): "3/7 · <the item in progress>".
        struct Progress: Codable, Hashable {
            var done: Int
            var total: Int
            /// the item in progress; nil between items
            var current: String?
        }

        /// A permission request the Live Activity can answer (Allow once / Deny only).
        struct Approval: Codable, Hashable {
            var key: String
            var title: String
            var allowOptionId: String?
            var denyOptionId: String?
        }

        var sessionId: String
        /// "claude" or "agy"
        var agent: String
        var title: String
        var phase: Phase
        /// the headline: what the agent is doing right now ("Editing Foo.swift…", "Running npm test…", or the line
        /// its reasoning is on); nil when Leash doesn't receive that conversation's updates
        var step: String?
        var progress: Progress?
        /// what the turn has done so far, "Explored 12 files, ran 3 commands, edited 2 files" (Lock Screen only)
        var summary: String?
        var startedAt: Date
        var endedAt: Date?
        /// when Leash last knew this to be true (the stale layout says how long ago)
        var updatedAt: Date
        var approval: Approval?
        /// other conversations this iPhone started that are still working
        var othersRunning: Int = 0
        var note: Note?
    }
}

extension LeashActivityAttributes.ContentState {
    /// The Live Activity's tap target: the focused conversation (handled by the app's onOpenURL).
    var deepLink: URL {
        var c = URLComponents()
        c.scheme = "leash"
        c.host = "session"
        c.queryItems = [URLQueryItem(name: "id", value: sessionId), URLQueryItem(name: "agent", value: agent)]
        return c.url ?? URL(string: "leash://open")!
    }
}
