import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// Lock Screen, Dynamic Island and the Apple Watch Smart Stack (.small).
///
/// Leash has no push, so this only changes while the app runs. Stale is the usual state, not an edge case: the
/// elapsed time keeps ticking on its own (`Text(timerInterval:)`), and once `staleDate` passes the layout dims
/// it, says when Leash last knew, and offers Refresh (which wakes the app to ask the Mac).
struct LeashLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LeashActivityAttributes.self) { context in
            ActivityBanner(state: context.state, isStale: context.isStale)
                .widgetURL(context.state.deepLink)
        } dynamicIsland: { context in
            let s = context.state
            let stale = context.isStale
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    AgentMark(agent: s.agent, size: 26)
                        .padding(.leading, 6)
                        .frame(maxHeight: .infinity)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    TrailingStatus(state: s, isStale: stale, font: .headline)
                        .padding(.trailing, 6)
                        .frame(maxHeight: .infinity)
                }
                DynamicIslandExpandedRegion(.center) {
                    // which conversation, quietly: the headline below says what it does
                    Text(verbatim: s.title)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .frame(maxHeight: .infinity)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    DetailStack(state: s, isStale: stale, roomy: false)
                        .padding(.horizontal, 6)
                        .padding(.top, 2)
                }
            } compactLeading: {
                AgentMark(agent: s.agent, size: 20)
            } compactTrailing: {
                CompactTrailing(state: s, isStale: stale)
            } minimal: {
                PhaseSymbol(phase: s.phase)
            }
            .widgetURL(s.deepLink)
            .keylineTint(LA.color(for: s.phase))
        }
        .supplementalActivityFamilies([.small])
    }
}

// MARK: - Palette (asset colors: light / dark; the Dynamic Island is always dark)
// The LA* colour sets hold LeashPalette's running / attention / success / danger values (SharedDesign); keep them equal.

enum LA {
    static let working = Color("LAWorking")
    static let attention = Color("LAAttention")
    static let success = Color("LASuccess")
    static let danger = Color("LADanger")

    static func color(for phase: LeashActivityAttributes.ContentState.Phase) -> Color {
        switch phase {
        case .working: return working
        case .approval, .question: return attention
        case .done: return success
        case .failed: return danger
        case .stopped, .ended: return .secondary
        }
    }

    static func symbol(for phase: LeashActivityAttributes.ContentState.Phase) -> String {
        switch phase {
        case .working: return "smallcircle.filled.circle"   // the app's "in progress" marker (checklist, live row)
        case .approval: return "hand.raised.fill"
        case .question: return "questionmark.bubble.fill"
        case .done: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.circle.fill"
        case .stopped: return "stop.circle.fill"
        case .ended: return "flag.checkered"
        }
    }

    static func agentLabel(_ agent: String) -> String { agent == "claude" ? "Claude Code" : "Antigravity" }

    /// "4m 12s", localized by the system.
    static func duration(from start: Date, to end: Date) -> String {
        let s = Int64(max(0, end.timeIntervalSince(start)).rounded())
        return Duration.seconds(s).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .narrow, maximumUnitCount: 2))
    }

    /// Elapsed time that keeps counting without updates (stops at the 8-hour Live Activity limit).
    static func elapsed(since start: Date) -> Text {
        Text(timerInterval: start...start.addingTimeInterval(8 * 3600), countsDown: false, showsHours: true)
    }
}

// MARK: - Pieces

/// The agent's mark without a container (HIG). A monochrome silhouette of it where the system strips colour
/// (Always-On, tinted).
private struct AgentMark: View {
    let agent: String
    var size: CGFloat
    @Environment(\.isLuminanceReduced) private var reduced
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        Group {
            if reduced || renderingMode != .fullColor {
                // the same agent's mark as a silhouette, so Always-On and tinted modes still say which agent it is
                Image(agent == "claude" ? "ProviderClaude" : "ProviderGemini").renderingMode(.template).resizable().scaledToFit()
                    .foregroundStyle(.primary)
            } else {
                Image(agent == "claude" ? "ProviderClaude" : "ProviderGemini").resizable().scaledToFit()
            }
        }
        .frame(width: size, height: size)
        .accessibilityLabel(Text(verbatim: LA.agentLabel(agent)))
    }
}

/// Minimal presentation: what is happening, not a logo (HIG).
private struct PhaseSymbol: View {
    let phase: LeashActivityAttributes.ContentState.Phase
    var body: some View {
        Image(systemName: LA.symbol(for: phase))
            .font(.body.weight(.semibold))
            .foregroundStyle(LA.color(for: phase))
            .contentTransition(.symbolEffect(.replace))
    }
}

/// Compact trailing: the elapsed time while working (fixed width, HIG), the hand while it waits for you.
private struct CompactTrailing: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        switch state.phase {
        case .working:
            ElapsedTime(start: state.startedAt, font: .subheadline.weight(.semibold), width: 52, dimmed: isStale)
        default:
            PhaseSymbol(phase: state.phase)
        }
    }
}

/// Elapsed time in a fixed-width, single-line slot. A timer `Text` sizes itself for the widest value it could show
/// (hours included), so with a flexible frame the narrow Dynamic Island regions squeeze it onto two lines ("0:" over
/// "05"). A fixed width, one line and a little scaling keep it a calm, right-aligned number.
private struct ElapsedTime: View {
    let start: Date
    let font: Font
    let width: CGFloat
    let dimmed: Bool

    var body: some View {
        LA.elapsed(since: start)
            .font(font.monospacedDigit())
            .foregroundStyle(LA.working.opacity(dimmed ? 0.55 : 1))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .multilineTextAlignment(.trailing)
            .frame(width: width, alignment: .trailing)
    }
}

/// Top-right of the expanded and Lock Screen layouts: elapsed time, the waiting symbol, or the final duration.
private struct TrailingStatus: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool
    var font: Font = .headline

    var body: some View {
        switch state.phase {
        case .working:
            ElapsedTime(start: state.startedAt, font: font, width: 72, dimmed: isStale)
        case .approval, .question:
            Image(systemName: LA.symbol(for: state.phase))
                .font(font)
                .foregroundStyle(LA.attention)
                .transition(.opacity)
        case .done, .failed, .stopped, .ended:
            Text(verbatim: LA.duration(from: state.startedAt, to: state.endedAt ?? state.updatedAt))
                .font(font.monospacedDigit())
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }
}

/// The headline, then what fits under it. `roomy` (Lock Screen): the action row, the checklist with its bar (or
/// what the turn has done so far) and the footer. The Dynamic Island has room for one row: actions, else the
/// checklist, else the footer.
/// Continuity (the app's Motion vocabulary): a new headline blurs in over the old one, the rows under it fade in
/// and out while the rest moves to its new place; Reduce Motion keeps only the cross-fade.
private struct DetailStack: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool
    var roomy = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            StatusLine(state: state)
                .id(statusID)
                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition(.blurReplace))
            if roomy {
                Actions(state: state, isStale: isStale)
                    .transition(.opacity)
                ProgressSummary(state: state)
                    .transition(.opacity)
                Footer(state: state, isStale: isStale)
                    .transition(.opacity)
            } else {
                IslandRow(state: state, isStale: isStale)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(LeashMotionCurves.layout(reduced: reduceMotion), value: statusID)
        .animation(LeashMotionCurves.layout(reduced: reduceMotion), value: isStale)
    }

    private var statusID: String {
        "\(state.phase.rawValue)|\(state.note?.rawValue ?? "")|\(state.step ?? "")|\(state.approval?.key ?? "")"
    }
}

private struct StatusLine: View {
    let state: LeashActivityAttributes.ContentState

    var body: some View {
        if let note = state.note {
            NoteLine(note: note)
        } else {
            switch state.phase {
            case .approval:
                VStack(alignment: .leading, spacing: 2) {
                    Text("Needs approval")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(LA.attention)
                    Text(verbatim: state.approval?.title ?? "")
                        .font(.subheadline.weight(.medium))
                        .lineLimit(2)
                }
            case .question:
                Label { Text("Has a question. Open Leash to answer.") } icon: { Image(systemName: LA.symbol(for: .question)) }
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(LA.attention)
                    .lineLimit(2)
            case .working:
                Label {
                    if let step = state.step { Text(verbatim: step) } else { Text("Working") }
                } icon: {
                    Image(systemName: LA.symbol(for: .working)).foregroundStyle(LA.working)
                }
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
            case .done, .failed, .stopped, .ended:
                Label { finalText } icon: { Image(systemName: LA.symbol(for: state.phase)).foregroundStyle(LA.color(for: state.phase)) }
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
            }
        }
    }

    private var finalText: Text {
        switch state.phase {
        case .done: return Text("Done")
        case .failed: return Text("Failed")
        case .stopped: return Text("Stopped")
        default: return Text("Turn ended")
        }
    }
}

/// What a button press from here came to (until the next update replaces it).
private struct NoteLine: View {
    let note: LeashActivityAttributes.ContentState.Note

    var body: some View {
        switch note {
        case .allowed:
            Label("Allowed", systemImage: "checkmark.circle.fill").foregroundStyle(LA.success).font(.subheadline.weight(.semibold))
        case .denied:
            Label("Denied", systemImage: "xmark.circle.fill").foregroundStyle(.secondary).font(.subheadline.weight(.semibold))
        case .alreadyAnswered:
            Label("Already answered", systemImage: "checkmark.circle").foregroundStyle(.secondary).font(.subheadline.weight(.semibold))
        case .unreachable:
            Label("Couldn't reach the bridge", systemImage: "wifi.exclamationmark").foregroundStyle(LA.attention).font(.subheadline.weight(.semibold))
        case .failed:
            Label("That didn't work. Open Leash.", systemImage: "exclamationmark.circle").foregroundStyle(LA.attention).font(.subheadline.weight(.semibold))
        }
    }
}

/// At most one row of controls (HIG): Deny / Allow while a permission waits, otherwise Refresh once stale.
private struct Actions: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        if state.canAnswer, let a = state.approval {
            HStack(spacing: 8) {
                if let deny = a.denyOptionId {
                    Button(intent: DenyRequestIntent(key: a.key, optionId: deny)) {
                        Text("Deny").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(Color.primary)
                }
                if let allow = a.allowOptionId {
                    Button(intent: AllowRequestIntent(key: a.key, optionId: allow)) {
                        Text("Allow").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.accentColor)
                }
            }
        } else if state.offersRefresh(isStale: isStale) {
            Button(intent: RefreshActivityIntent()) {
                Label("Refresh", systemImage: "arrow.clockwise").font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.bordered)
            .tint(Color.primary)
        }
    }
}

private struct Footer: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        let others = state.othersRunning > 0 && state.phase.isLive
        if others || (isStale && state.phase.isLive) {
            HStack(spacing: 6) {
                if isStale && state.phase.isLive {
                    Text("Updated \(Text(state.updatedAt, style: .relative)) ago")
                }
                if others && isStale && state.phase.isLive { Text(verbatim: "·") }
                if others {
                    Text("+\(state.othersRunning) more working")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .lineLimit(1)
        }
    }
}

extension LeashActivityAttributes.ContentState {
    /// Allow / Deny can be offered from here
    var canAnswer: Bool { phase == .approval && (approval?.allowOptionId != nil || approval?.denyOptionId != nil) }
    /// Refresh is offered while it works but Leash's word on it is old
    func offersRefresh(isStale: Bool) -> Bool { phase == .working && (isStale || note == .unreachable) }
}

/// The Dynamic Island's one row under the headline: the buttons (Refresh with how old this is), else the
/// checklist, else the footer.
private struct IslandRow: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        if state.canAnswer {
            Actions(state: state, isStale: isStale)
        } else if state.offersRefresh(isStale: isStale) {
            HStack(spacing: 10) {
                Actions(state: state, isStale: isStale)
                Footer(state: state, isStale: isStale)
            }
        } else if state.phase == .working, let p = state.progress {
            HStack(spacing: 6) {
                ProgressLine(progress: p)
                if state.othersRunning > 0 {
                    Spacer(minLength: 6)
                    Text("+\(state.othersRunning) more working")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
        } else {
            Footer(state: state, isStale: isStale)
        }
    }
}

/// "3/7 · Update the controller"
private struct ProgressLine: View {
    let progress: LeashActivityAttributes.ContentState.Progress

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "checklist")
            Text(verbatim: "\(progress.done)/\(progress.total)").monospacedDigit()
            if let current = progress.current { Text(verbatim: "· \(current)") }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }
}

/// Lock Screen, under the headline: the checklist with a bar while it works, else what the turn has done so far
/// (also once it's finished: "Explored 12 files, ran 3 commands, edited 2 files").
private struct ProgressSummary: View {
    let state: LeashActivityAttributes.ContentState

    var body: some View {
        if state.phase == .working, let p = state.progress {
            VStack(alignment: .leading, spacing: 5) {
                ProgressView(value: Double(min(p.done, p.total)), total: Double(max(1, p.total)))
                    .tint(LA.working)
                ProgressLine(progress: p)
            }
        } else if state.phase == .working || !state.phase.isLive, let summary = state.summary {
            Text(verbatim: summary)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

/// One line saying what is happening, for the smallest layout.
private struct Headline: View {
    let state: LeashActivityAttributes.ContentState

    var body: some View {
        switch state.phase {
        case .approval: Text("Needs approval").foregroundStyle(LA.attention)
        case .question: Text("Has a question").foregroundStyle(LA.attention)
        case .working:
            if let step = state.step { Text(verbatim: step) } else { Text("Working") }
        case .done: Text("Done")
        case .failed: Text("Failed")
        case .stopped: Text("Stopped")
        case .ended: Text("Turn ended")
        }
    }
}

// MARK: - Lock Screen (.medium) and Smart Stack (.small)

private struct ActivityBanner: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool
    @Environment(\.activityFamily) private var family

    var body: some View {
        switch family {
        case .small: SmallLayout(state: state, isStale: isStale)
        default: LockScreenLayout(state: state, isStale: isStale)
        }
    }
}

private struct LockScreenLayout: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 10) {
                AgentMark(agent: state.agent, size: 24)
                Text(verbatim: state.title).font(.headline).lineLimit(1)
                Spacer(minLength: 8)
                TrailingStatus(state: state, isStale: isStale)
            }
            DetailStack(state: state, isStale: isStale)
        }
        .padding(16)
    }
}

/// Apple Watch Smart Stack: which conversation and how long, then what it does. No buttons (Allow needs an
/// unlocked iPhone; the watch app answers requests).
private struct SmallLayout: View {
    let state: LeashActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                AgentMark(agent: state.agent, size: 16)
                Text(verbatim: state.title).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                Spacer(minLength: 4)
                if state.phase == .working {
                    ElapsedTime(start: state.startedAt, font: .caption2, width: 44, dimmed: isStale)
                } else {
                    Image(systemName: LA.symbol(for: state.phase)).font(.caption).foregroundStyle(LA.color(for: state.phase))
                }
            }
            Headline(state: state)
                .font(.caption.weight(.semibold))
                .lineLimit(2)
        }
    }
}
