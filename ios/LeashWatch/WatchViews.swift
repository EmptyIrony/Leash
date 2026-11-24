import SwiftUI

// Leash's colours as they appear on the (always dark) watch: the dark values of the shared palette.
enum WatchTheme {
    static let accent = LeashPalette.accent.darkColor
    static let working = LeashPalette.running.darkColor
    static let attention = LeashPalette.attention.darkColor
    static let danger = LeashPalette.danger.darkColor

    /// Elapsed time that keeps counting on its own.
    static func elapsed(since start: Date) -> Text {
        Text(timerInterval: start...start.addingTimeInterval(8 * 3600), countsDown: false, showsHours: true)
    }

    static func message(for failure: WatchFailure?) -> LocalizedStringKey {
        switch failure {
        case nil: return "Couldn't reach your iPhone."
        case .macUnreachable?: return "Your iPhone couldn't reach the Mac."
        case .alreadyAnswered?: return "Already answered."
        case .locked?: return "This conversation is busy on your computer."
        case .changedElsewhere?: return "The conversation changed elsewhere. Try again."
        case .failed?: return "That didn't work."
        }
    }
}

enum WatchRoute: Hashable {
    case request(String)
    case session(String)
}

struct AgentMark: View {
    let agent: String
    var size: CGFloat = 16
    var body: some View {
        Image(agent == "claude" ? "ProviderClaude" : "ProviderGemini")
            .resizable().scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

// MARK: - Root

/// One list, shallow (HIG): what needs you, what is working, what was recent. Empty sections are hidden.
struct WatchRootView: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        NavigationStack {
            List {
                if let banner {
                    StatusRow(text: banner.0, symbol: banner.1)
                }
                if !model.requests.isEmpty {
                    Section("Needs You") {
                        ForEach(model.requests) { r in
                            NavigationLink(value: WatchRoute.request(r.id)) { RequestRow(request: r) }
                        }
                    }
                }
                if !model.working.isEmpty {
                    Section("Working") {
                        ForEach(model.working) { s in
                            NavigationLink(value: WatchRoute.session(s.id)) { SessionRow(session: s) }
                        }
                    }
                }
                if !model.recent.isEmpty {
                    Section("Recent") {
                        ForEach(model.recent) { s in
                            NavigationLink(value: WatchRoute.session(s.id)) { SessionRow(session: s) }
                        }
                    }
                }
                if model.snapshot.sessions.isEmpty && model.requests.isEmpty && banner == nil {
                    Text(model.refreshing ? "Loading…" : "Nothing running.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Leash")
            .navigationDestination(for: WatchRoute.self) { route in
                switch route {
                case .request(let id): RequestDetail(requestId: id)
                case .session(let id): SessionDetail(sessionId: id)
                }
            }
        }
    }

    /// The first row explains why actions are unavailable.
    private var banner: (LocalizedStringKey, String)? {
        if !model.reachable { return ("iPhone not reachable", "iphone.slash") }
        switch model.snapshot.link {
        case .notPaired: return ("Pair Leash on your iPhone first", "qrcode")
        case .offline: return ("Mac not connected", "wifi.exclamationmark")
        case .connecting: return ("Connecting to your Mac…", "arrow.triangle.2.circlepath")
        case .connected: return nil
        }
    }
}

private struct StatusRow: View {
    let text: LocalizedStringKey
    let symbol: String
    var body: some View {
        Label(text, systemImage: symbol)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .listRowBackground(Color.clear)
    }
}

private struct RequestRow: View {
    let request: WatchSnapshot.Request
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: request.isQuestion ? "questionmark.bubble.fill" : "hand.raised.fill")
                .foregroundStyle(WatchTheme.attention)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: request.title).font(.headline).lineLimit(2)
                Text(verbatim: request.sessionTitle).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }
}

private struct SessionRow: View {
    let session: WatchSnapshot.Session
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            AgentMark(agent: session.agent, size: 16).padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: session.title).font(.headline).lineLimit(2)
                SessionStatus(session: session).font(.footnote)
            }
        }
    }
}

private struct SessionStatus: View {
    let session: WatchSnapshot.Session
    var body: some View {
        if session.pendingCount > 0 {
            Text("Needs you").foregroundStyle(WatchTheme.attention)
        } else if session.running {
            if let start = session.startedAt {
                WatchTheme.elapsed(since: start).monospacedDigit().foregroundStyle(WatchTheme.working)
            } else {
                Text("Working").foregroundStyle(WatchTheme.working)
            }
        } else if let d = session.updatedAt {
            Text(d, style: .relative).foregroundStyle(.secondary)
        } else {
            Text(verbatim: session.folder).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Request

/// One request: what it is, then Allow (prominent) above Deny, full width. The screen stays until the iPhone
/// confirms, then goes back.
struct RequestDetail: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let requestId: String
    @State private var inFlight: String?
    @State private var failure: WatchModel.ActionResult?
    @State private var successTick = 0
    @State private var errorTick = 0
    /// kept so the screen still reads right once the request leaves the snapshot
    @State private var shown: WatchSnapshot.Request?

    var body: some View {
        let current = model.request(requestId)
        let r = current ?? shown
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let r {
                    HStack(spacing: 5) {
                        AgentMark(agent: r.agent, size: 14)
                        Text(verbatim: r.sessionTitle).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Text(verbatim: r.title).font(.headline)
                    if let d = r.detail, d != r.title {
                        Text(verbatim: d)
                            .font(.system(.footnote, design: .monospaced))
                            .lineLimit(6)
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    if current == nil && inFlight == nil {
                        Label("No longer waiting", systemImage: "checkmark.circle").font(.footnote).foregroundStyle(.secondary)
                    } else if r.isQuestion {
                        Text("Answer this on your iPhone.").font(.footnote).foregroundStyle(.secondary)
                    } else {
                        actions(r)
                    }
                    if case .failed(let f)? = failure {
                        Text(WatchTheme.message(for: f)).font(.footnote).foregroundStyle(WatchTheme.danger)
                    }
                } else {
                    Text("No longer waiting").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(Text(verbatim: r?.agentLabel ?? ""))
        .onAppear { if shown == nil { shown = current } }
        .onChange(of: current) { _, c in if let c { shown = c } }
        .sensoryFeedback(.success, trigger: successTick)
        .sensoryFeedback(.error, trigger: errorTick)
    }

    @ViewBuilder private func actions(_ r: WatchSnapshot.Request) -> some View {
        let disabled = inFlight != nil || !model.canAct
        if let allow = r.allow {
            Button { act(r, allow) } label: { buttonLabel(allow) }
                .buttonStyle(.borderedProminent)
                .tint(WatchTheme.accent)
                .disabled(disabled)
        }
        if let deny = r.deny {
            Button { act(r, deny) } label: { buttonLabel(deny) }
                .buttonStyle(.bordered)
                .disabled(disabled)
        }
    }

    private func buttonLabel(_ o: WatchSnapshot.Request.Option) -> some View {
        ZStack {
            Text(verbatim: o.name).opacity(inFlight == o.id ? 0 : 1)
            if inFlight == o.id { ProgressView() }
        }
        .frame(maxWidth: .infinity)
    }

    private func act(_ r: WatchSnapshot.Request, _ o: WatchSnapshot.Request.Option) {
        inFlight = o.id
        failure = nil
        Task {
            let result = await model.answer(r, option: o)
            inFlight = nil
            switch result {
            case .ok:
                successTick += 1
                dismiss()
            case .failed(.alreadyAnswered?):
                failure = result
                model.refresh()
            case .failed:
                failure = result
                errorTick += 1
            }
        }
    }
}

// MARK: - Session

/// A conversation at a glance, its waiting requests, and a dictated reply.
struct SessionDetail: View {
    @Environment(WatchModel.self) private var model
    let sessionId: String
    @State private var sending = false
    @State private var sent = false
    @State private var failure: WatchModel.ActionResult?
    @State private var successTick = 0
    @State private var errorTick = 0

    var body: some View {
        if let s = model.session(sessionId) {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 5) {
                            AgentMark(agent: s.agent, size: 14)
                            Text(verbatim: s.agentLabel).font(.footnote).foregroundStyle(.secondary)
                        }
                        Text(verbatim: s.title).font(.headline)
                        SessionStatus(session: s).font(.footnote)
                    }
                    .listRowBackground(Color.clear)
                }
                let reqs = model.requests(for: s.id)
                if !reqs.isEmpty {
                    Section("Needs You") {
                        ForEach(reqs) { r in
                            NavigationLink(value: WatchRoute.request(r.id)) { RequestRow(request: r) }
                        }
                    }
                }
                Section {
                    if s.lockedBy != nil {
                        Text("Busy on your computer.").font(.footnote).foregroundStyle(.secondary)
                    } else {
                        TextFieldLink(prompt: Text("Reply to \(s.agentLabel)")) {
                            HStack {
                                Label("Reply", systemImage: "mic.fill")
                                Spacer()
                                if sending { ProgressView().frame(width: 20, height: 20) }
                                else if sent { Image(systemName: "checkmark.circle.fill").foregroundStyle(WatchTheme.working) }
                            }
                        } onSubmit: { text in reply(s, text) }
                        .disabled(sending || !model.canAct)
                    }
                    if case .failed(let f)? = failure {
                        Text(WatchTheme.message(for: f)).font(.footnote).foregroundStyle(WatchTheme.danger)
                    }
                }
            }
            .navigationTitle(Text(verbatim: s.folder))
            .sensoryFeedback(.success, trigger: successTick)
            .sensoryFeedback(.error, trigger: errorTick)
        } else {
            Text("Not on your iPhone's list anymore.").foregroundStyle(.secondary)
        }
    }

    private func reply(_ s: WatchSnapshot.Session, _ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        sending = true
        sent = false
        failure = nil
        Task {
            let r = await model.reply(to: s, text: t)
            sending = false
            if r == .ok { sent = true; successTick += 1 } else { failure = r; errorTick += 1 }
        }
    }
}
