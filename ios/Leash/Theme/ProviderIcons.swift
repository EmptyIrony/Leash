import SwiftUI

/// Which family a model belongs to (drives icon + grouping).
enum Provider: String, CaseIterable, Identifiable {
    case gemini, anthropic, openai, other
    var id: String { rawValue }

    static func of(_ modelValue: String?) -> Provider {
        let v = (modelValue ?? "").lowercased()
        if v.contains("claude") || ["opus", "sonnet", "haiku", "fable"].contains(where: { v.contains($0) }) { return .anthropic }
        if v.contains("gpt") || v.contains("oss") || v.contains("openai") { return .openai }
        if v.contains("gemini") || v.contains("orchestrator") { return .gemini }
        return .other
    }

    var displayName: String {
        switch self {
        case .gemini: return "Gemini"
        case .anthropic: return "Anthropic"
        case .openai: return "OpenAI"
        case .other: return String(localized: "Other")
        }
    }

    /// quota pool id used by agy for this family
    var quotaModelHint: String {
        switch self {
        case .gemini, .other: return "gemini"
        case .anthropic: return "claude"
        case .openai: return "gpt"
        }
    }
}

/// Agent glyph: Claude Code = the Claude mark, Antigravity (and anything else) = the Gemini mark.
struct AgentIcon: View {
    let agent: String
    var size: CGFloat = 16
    var body: some View {
        ProviderIcon(provider: agent == "claude" ? .anthropic : .gemini, size: size)
    }
}

/// Official provider marks: vector SVGs in Assets.xcassets (Provider*.imageset).
/// OpenAI's mark is monochrome, so it's a template image tinted to the text colour (works in light and dark).
struct ProviderIcon: View {
    let provider: Provider
    var size: CGFloat = 16

    var body: some View {
        Group {
            switch provider {
            case .gemini:
                Image("ProviderGemini").resizable().scaledToFit()
            case .anthropic:
                Image("ProviderClaude").resizable().scaledToFit()
            case .openai:
                Image("ProviderOpenAI").renderingMode(.template).resizable().scaledToFit()
                    .foregroundStyle(Theme.textPrimary)
            case .other:
                Image(systemName: "cpu").resizable().scaledToFit().foregroundStyle(Theme.textSecondary)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
