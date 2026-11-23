import Foundation

/// What the iPhone app tells the watch app (WatchConnectivity). The watch never talks to the Mac itself:
/// watchOS blocks WebSockets for ordinary apps (TN3135), so the phone relays.
struct WatchSnapshot: Codable, Equatable {
    enum Link: String, Codable { case connected, connecting, offline, notPaired }

    struct Session: Codable, Identifiable, Equatable, Hashable {
        var id: String
        var agent: String
        var agentLabel: String
        var title: String
        var folder: String
        var running: Bool
        var startedAt: Date?
        var updatedAt: Date?
        var pendingCount: Int
        /// open in another client ("cli", "claude-desktop", …): can't be prompted from here
        var lockedBy: String?
    }

    struct Request: Codable, Identifiable, Equatable, Hashable {
        struct Option: Codable, Equatable, Hashable {
            var id: String
            var name: String
        }
        /// the bridge's request key
        var id: String
        var sessionId: String?
        var agent: String
        var agentLabel: String
        var sessionTitle: String
        var title: String
        /// command / path preview
        var detail: String?
        /// an agent question (elicitation): answered on the iPhone
        var isQuestion: Bool
        var allow: Option?
        var deny: Option?
    }

    var macName: String
    var link: Link
    var sessions: [Session]
    var requests: [Request]
    var at: Date

    static let empty = WatchSnapshot(macName: "", link: .offline, sessions: [], requests: [], at: .distantPast)
}

/// Message keys shared by both sides. Payloads are property-list types; the snapshot travels as JSON `Data`.
enum WatchMessage {
    static let op = "op"
    static let snapshot = "snapshot"     // watch → phone: send me the state; phone → watch: here it is
    static let answer = "answer"         // watch → phone: key, optionId
    static let reply = "reply"           // watch → phone: sessionId, agent, text
    static let key = "key"
    static let optionId = "optionId"
    static let sessionId = "sessionId"
    static let agent = "agent"
    static let text = "text"
    static let ok = "ok"
    static let error = "error"           // one of WatchFailure's raw values

    static func encode(_ s: WatchSnapshot) -> Data? { try? JSONEncoder().encode(s) }
    static func decode(_ d: Any?) -> WatchSnapshot? {
        guard let d = d as? Data else { return nil }
        return try? JSONDecoder().decode(WatchSnapshot.self, from: d)
    }
}

/// Why the phone could not do what the watch asked (the watch words it).
enum WatchFailure: String, Codable {
    case macUnreachable, alreadyAnswered, locked, changedElsewhere, failed
}
