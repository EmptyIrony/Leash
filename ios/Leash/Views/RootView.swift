import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var path = NavigationPath()
    @State private var showSettings = false
    @State private var showNew = false
    @State private var gateDismissed = false

    private var showBootGate: Bool {
        !gateDismissed && model.tailnet.enabled && model.isConfigured
    }

    var body: some View {
        ZStack {
            SpaceBackground()

            NavigationStack(path: $path) {
                SessionListView(showSettings: $showSettings, showNew: $showNew)
                    .navigationDestination(for: String.self) { id in
                        ChatView(store: model.store(for: id), openSession: { next in
                            // Replace, don't stack: Back from the other conversation returns to the list.
                            withAnimation(Motion.layout) { path = NavigationPath([next]) }
                        })
                        // Swapping the stack's only conversation for another (the banner above, a Bark tap, the Live
                        // Activity) keeps the same slot, so without this SwiftUI reuses the view and its @State: the
                        // composer's draft would follow into the other conversation. A new id leaves first (saving the
                        // draft under its own conversation) and opens the next one fresh.
                        .id(id)
                    }
                    .navigationDestination(for: FolderRoute.self) { FolderSessionsView(route: $0) }
            }
            .scaleEffect(showBootGate && !reduceMotion ? 0.96 : 1)
            .opacity(showBootGate ? 0 : 1)
            .allowsHitTesting(!showBootGate)
            .accessibilityHidden(showBootGate)

            if showBootGate {
                TailnetBootView(
                    isSettingsPresented: showSettings,
                    onOpenSettings: { showSettings = true },
                    onDismissGate: {
                        withAnimation(Motion.layout) { gateDismissed = true }
                    }
                )
                .transition(reduceMotion ? .opacity : .scale(scale: 1.04).combined(with: .opacity))
            }
        }
        .animation(Motion.layout, value: showBootGate)
        .sheet(isPresented: $showSettings) { SettingsView() }
        .sheet(isPresented: $showNew) {
            NewSessionView { id in
                // One motion, like Messages: the chat is pushed in place under the sheet without animation,
                // then the sheet slides down to reveal it.
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) { path.append(id) }
                showNew = false
            }
            .presentationDetents([.medium, .large])
        }
        .onAppear {
            if !model.isConfigured {
                gateDismissed = true
                showSettings = true
            } else if !model.tailnet.enabled {
                gateDismissed = true
            } else if case .connected = model.tailnet.status {
                gateDismissed = true
            }
        }
        .onChange(of: model.openRequest, initial: true) { _, request in
            consumeOpenRequest(request)
        }
        .onChange(of: showBootGate) { _, gating in
            if !gating { consumeOpenRequest(model.openRequest) }
        }
        .onChange(of: model.tailnet.enabled) { _, on in
            if !on { gateDismissed = true }
        }
        .onChange(of: model.isConfigured) { _, configured in
            if !configured { gateDismissed = true }
        }
    }

    private func consumeOpenRequest(_ request: OpenRequest?) {
        guard !showBootGate, let request else { return }
        model.openRequest = nil
        route(to: request.sessionId)
    }

    /// A Bark tap or the Live Activity: show that conversation with the list under it, so Back always leads to the
    /// list and repeated taps never deepen the stack.
    private func route(to id: String) {
        showSettings = false
        showNew = false
        guard model.visibleSessionId != id else { return }
        if path.isEmpty {
            path.append(id)
        } else {
            withAnimation(Motion.layout) { path = NavigationPath([id]) }
        }
    }
}

/// Connection status for toolbars: bridge glyph + a 7pt status dot.
struct ConnectionDot: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        Image(systemName: "server.rack")
            .foregroundStyle(Theme.textPrimary)
            .overlay(alignment: .bottomTrailing) {
                Circle().fill(model.statusColor).frame(width: 7, height: 7)
                    .overlay(Circle().stroke(Theme.bgBase, lineWidth: 1.5))
                    .offset(x: 3, y: 2)
            }
            .accessibilityLabel("Connection: " + model.statusText)
    }
}

/// Larger status pill (settings sheet).
struct ConnectionPill: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(model.statusColor).frame(width: 8, height: 8)
            Text(model.statusText).font(.caption.weight(.medium)).lineLimit(1)
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(Theme.hairline, in: Capsule())
    }
}

extension AppModel {
    var statusColor: Color {
        switch connection {
        case .connected:
            if !agents.isEmpty {
                if agents.allSatisfy(\.isReady) { return Theme.success }
                return agents.contains(where: \.isReady) ? Theme.attention : Theme.danger
            }
            return agentState == "ready" ? Theme.success : (agentState == "starting" ? Theme.attention : Theme.danger)
        case .connecting, .waiting: return Theme.attention
        case .failed: return Theme.danger
        case .idle: return Theme.textTertiary
        }
    }
    var statusText: String {
        let mac = macName.isEmpty ? "Bridge" : macName
        switch connection {
        case .connected:
            if agents.count > 1 {
                let ready = agents.filter(\.isReady).count
                return ready == agents.count ? String(localized: "\(mac) · \(ready) agents ready") : String(localized: "\(mac) · \(ready)/\(agents.count) agents ready")
            }
            switch agentState {
            case "ready": return String(localized: "\(mac) · agy ready")
            case "starting": return String(localized: "\(mac) · starting agy…")
            default: return String(localized: "\(mac) · agy down")
            }
        case .connecting: return String(localized: "Connecting…")
        case .waiting: return String(localized: "Reconnecting…")
        case .failed: return String(localized: "Not connected")
        case .idle: return String(localized: "Offline")
        }
    }
}
