import AppIntents
import Foundation

/// What a Live Activity button asks the app to do.
enum LiveActivityAction: Sendable {
    case answer(key: String, optionId: String, allow: Bool)
    case refresh
}

/// Live Activity buttons are `LiveActivityIntent`s, so the system runs them in the app's process (starting it in the
/// background when needed). The app installs `handler` at launch; the extension compiles these types only to draw
/// the buttons and never runs them.
@MainActor
enum LiveActivityActions {
    static var handler: ((LiveActivityAction) async -> Void)?

    static func run(_ action: LiveActivityAction) async {
        await handler?(action)
    }
}

/// Allow once. Approving runs something on the Mac, so a locked iPhone asks to be unlocked first.
struct AllowRequestIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Allow Request"
    static let isDiscoverable = false
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "Request") var key: String
    @Parameter(title: "Option") var optionId: String

    init() {}
    init(key: String, optionId: String) {
        self.key = key
        self.optionId = optionId
    }

    func perform() async throws -> some IntentResult {
        await LiveActivityActions.run(.answer(key: key, optionId: optionId, allow: true))
        return .result()
    }
}

/// Deny once; harmless, so it also works from the Lock Screen.
struct DenyRequestIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Deny Request"
    static let isDiscoverable = false

    @Parameter(title: "Request") var key: String
    @Parameter(title: "Option") var optionId: String

    init() {}
    init(key: String, optionId: String) {
        self.key = key
        self.optionId = optionId
    }

    func perform() async throws -> some IntentResult {
        await LiveActivityActions.run(.answer(key: key, optionId: optionId, allow: false))
        return .result()
    }
}

/// Ask the Mac again (the Live Activity can't update itself while Leash isn't running).
struct RefreshActivityIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Refresh"
    static let isDiscoverable = false

    init() {}

    func perform() async throws -> some IntentResult {
        await LiveActivityActions.run(.refresh)
        return .result()
    }
}
