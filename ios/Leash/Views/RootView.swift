import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var path = NavigationPath()
    @State private var showSettings = false
    @State private var showNew = false

    var body: some View {
        NavigationStack(path: $path) {
            SessionListView(showSettings: $showSettings, showNew: $showNew)
                .navigationDestination(for: String.self) { id in
                    ChatView(store: model.store(for: id), openSession: { next in
                        // Replace, don't stack: Back from the other conversation returns to the list.
                        withAnimation { path = NavigationPath([next]) }
                    })
                }
                .navigationDestination(for: FolderRoute.self) { FolderSessionsView(route: $0) }
        }
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
        .onAppear { if !model.isConfigured { showSettings = true } }
        .onChange(of: model.openRequest, initial: true) { _, request in
            guard let request else { return }
            model.openRequest = nil
            route(to: request.sessionId)
        }
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
            withAnimation { path = NavigationPath([id]) }
        }
    }
}

/// Connection status for toolbars: Mac glyph + a 7pt status dot.
struct ConnectionDot: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        Image(systemName: "macbook")
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
        let mac = macName.isEmpty ? "Mac" : macName
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
