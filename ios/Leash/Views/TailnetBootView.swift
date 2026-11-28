import SwiftUI

/// Cold-start gate shown while built-in Tailscale joins the tailnet (Android TailnetBootGate).
/// Sits over the shared SpaceBackground so handing off to SessionListView transitions seamlessly.
struct TailnetBootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let isSettingsPresented: Bool
    let onOpenSettings: () -> Void
    let onDismissGate: () -> Void

    @State private var timedOut = false
    @State private var attempt = 0
    @State private var showEarlyActions = false
    @State private var completionTask: Task<Void, Never>?

    private static let timeoutSeconds: Double = 15
    private static let connectedBeatNanoseconds: UInt64 = 900_000_000
    private static let earlyActionsDelayNanoseconds: UInt64 = 1_500_000_000

    enum Phase: Equatable {
        case connecting
        case connected(name: String, address: String)
        case needsKey(String?)
        case failed(String)
        case timedOut

        var isConnecting: Bool { self == .connecting }
        var isActionable: Bool {
            switch self {
            case .timedOut, .needsKey, .failed: return true
            case .connecting, .connected: return false
            }
        }
    }

    private var phase: Phase {
        switch model.tailnet.status {
        case .connected(let name, let address): return .connected(name: name, address: address)
        case .needsKey(let reason): return .needsKey(reason)
        case .failed(let message): return .failed(message)
        case .off, .stopped, .connecting: return timedOut ? .timedOut : .connecting
        }
    }

    private var accentColor: Color {
        switch phase {
        case .connecting: return Theme.accent
        case .connected: return Theme.success
        case .needsKey, .timedOut: return Theme.attention
        case .failed: return Theme.danger
        }
    }

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                OrbitBootEmblem(phase: phase, tint: accentColor)

                Spacer().frame(height: 28)

                Text(titleText)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                    .shimmer(phase.isConnecting)

                Spacer().frame(height: 8)

                if let detail = detailText {
                    let isConnected: Bool = {
                        if case .connected = phase { return true }
                        return false
                    }()
                    Text(verbatim: detail)
                        .font(isConnected ? Theme.monoSmall : .subheadline)
                        .foregroundStyle(isConnected ? Theme.success : Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .lineLimit(4)
                        .frame(maxWidth: 300)
                }

                if phase.isActionable {
                    VStack(spacing: 10) {
                        Button(action: onOpenSettings) {
                            Label("Open Settings", systemImage: "gearshape")
                                .font(.body.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.glassProminent)

                        Button {
                            withAnimation(Motion.layout) {
                                timedOut = false
                                attempt += 1
                            }
                            model.retryTailnet()
                        } label: {
                            Label("Retry", systemImage: "arrow.clockwise")
                                .font(.body.weight(.medium))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.glass)

                        Button("Continue anyway", action: onDismissGate)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(Theme.textSecondary)
                            .buttonStyle(.pressable(scale: 1))
                            .padding(.top, 4)
                    }
                    .frame(maxWidth: 280)
                    .padding(.top, 28)
                    .transition(Motion.reveal)
                }
            }
            .padding(.horizontal, 28)
            .animation(Motion.layout, value: phase)

            if phase.isConnecting && showEarlyActions {
                VStack {
                    Spacer()
                    HStack(spacing: 16) {
                        Button("Settings", action: onOpenSettings)
                            .buttonStyle(.pressable(scale: 1))
                        Circle()
                            .fill(Theme.textTertiary.opacity(0.5))
                            .frame(width: 3, height: 3)
                        Button("Continue anyway", action: onDismissGate)
                            .buttonStyle(.pressable(scale: 1))
                    }
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Theme.textTertiary)
                    .padding(.bottom, 24)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 6)))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: TimerKey(attempt: attempt, connecting: phase.isConnecting, paused: isSettingsPresented)) {
            guard phase.isConnecting, !isSettingsPresented else { return }
            try? await Task.sleep(nanoseconds: UInt64(Self.timeoutSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            withAnimation(Motion.layout) { timedOut = true }
        }
        .task(id: attempt) {
            showEarlyActions = false
            try? await Task.sleep(nanoseconds: Self.earlyActionsDelayNanoseconds)
            guard !Task.isCancelled else { return }
            withAnimation(Motion.layout) { showEarlyActions = true }
        }
        .onChange(of: isSettingsPresented) { wasOpen, isOpen in
            // Fresh timeout attempt when returning from Settings unless already settled
            if wasOpen && !isOpen {
                timedOut = false
                attempt += 1
            }
        }
        .onChange(of: phase, initial: true) { _, next in
            switch next {
            case .connected:
                guard completionTask == nil else { return }
                Haptics.success()
                completionTask = Task { @MainActor in
                    if !reduceMotion { try? await Task.sleep(nanoseconds: Self.connectedBeatNanoseconds) }
                    guard !Task.isCancelled else { return }
                    onDismissGate()
                }
            case .needsKey, .timedOut:
                Haptics.warning()
            case .failed:
                Haptics.error()
            case .connecting:
                completionTask?.cancel()
                completionTask = nil
            }
        }
        .onDisappear {
            completionTask?.cancel()
            completionTask = nil
        }
    }

    private var titleText: LocalizedStringKey {
        switch phase {
        case .connecting: return "Connecting to Tailscale…"
        case .connected: return "Tailscale connected"
        case .timedOut: return "Tailscale connection timed out"
        case .needsKey: return "Tailscale needs a new auth key"
        case .failed: return "Tailscale could not connect"
        }
    }

    private var detailText: String? {
        switch phase {
        case .connecting:
            return String(localized: "Joining your tailnet before opening your conversations")
        case .connected(let name, let address):
            let text = [name, address].filter { !$0.isEmpty }.joined(separator: " · ")
            return text.isEmpty ? nil : text
        case .timedOut:
            return String(localized: "Could not reach your tailnet in time. Check your network, auth key, or control URL in Settings.")
        case .needsKey(let reason):
            if let reason, !reason.isEmpty { return reason }
            return String(localized: "Built-in Tailscale needs a new auth key. Enter one in Settings.")
        case .failed(let message):
            return message
        }
    }

    private struct TimerKey: Equatable {
        let attempt: Int
        let connecting: Bool
        let paused: Bool
    }
}

private struct OrbitBootEmblem: View {
    let phase: TailnetBootView.Phase
    let tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            TimelineView(.animation(paused: !phase.isConnecting || reduceMotion)) { tl in
                Canvas { ctx, size in
                    drawRings(&ctx, size: size, date: tl.date)
                }
            }
            .frame(width: 136, height: 136)

            Circle()
                .fill(Theme.bgRaised.opacity(0.65))
                .frame(width: 72, height: 72)
                .glassEffect(.regular, in: .circle)
                .overlay(Circle().stroke(tint.opacity(0.28), lineWidth: 1))
                .overlay {
                    Image(systemName: symbolName)
                        .font(.system(size: 28, weight: .medium))
                        .foregroundStyle(tint)
                        .contentTransition(.symbolEffect(.replace.downUp))
                        .animation(Motion.quick, value: symbolName)
                }
        }
        .animation(Motion.quick, value: tint)
    }

    private var symbolName: String {
        switch phase {
        case .connecting: return "point.3.connected.trianglepath.dotted"
        case .connected: return "checkmark"
        case .needsKey, .timedOut: return "exclamationmark.triangle"
        case .failed: return "exclamationmark.circle"
        }
    }

    private func drawRings(_ ctx: inout GraphicsContext, size: CGSize, date: Date) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let connecting = phase.isConnecting
        let t = date.timeIntervalSinceReferenceDate
        let pulse = (connecting && !reduceMotion) ? (0.5 - 0.5 * cos(2 * .pi * (t.truncatingRemainder(dividingBy: 2.2) / 2.2))) : 0

        let outerR = min(size.width, size.height) * (0.44 + 0.04 * pulse)
        let midR = min(size.width, size.height) * 0.36

        let outerRect = CGRect(x: center.x - outerR, y: center.y - outerR, width: outerR * 2, height: outerR * 2)
        ctx.stroke(Path(ellipseIn: outerRect), with: .color(tint.opacity(connecting ? 0.08 + 0.10 * pulse : 0.14)), lineWidth: 1)

        let midRect = CGRect(x: center.x - midR, y: center.y - midR, width: midR * 2, height: midR * 2)
        ctx.stroke(Path(ellipseIn: midRect), with: .color(tint.opacity(connecting ? 0.20 : 0.35)), lineWidth: 1.5)

        if connecting && !reduceMotion {
            let spin = (t.truncatingRemainder(dividingBy: 2.4) / 2.4) * 360
            var arc = Path()
            arc.addArc(center: center, radius: midR, startAngle: .degrees(spin), endAngle: .degrees(spin + 78), clockwise: false)
            ctx.stroke(arc, with: .color(tint), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
        }
    }
}
