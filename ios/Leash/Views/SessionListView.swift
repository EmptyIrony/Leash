import SwiftUI

struct SessionListView: View {
    @Environment(AppModel.self) private var model
    @Binding var showSettings: Bool
    @Binding var showNew: Bool
    @State private var search = ""
    @State private var showQuota = false
    /// The quota sheet zooms out of (and back into) the toolbar rings.
    @Namespace private var quotaZoom
    @AppStorage("agentFilter") private var filter = "all"
    /// Open folder sections (group ids). @State drives the disclosure animation; mirrored to defaults below.
    @State private var expanded: Set<String> = []
    @State private var expansionLoaded = false
    @AppStorage("expandedFolders") private var expandedRaw: String?

    private var multiAgent: Bool { model.agents.count > 1 }

    private var filtered: [SessionSummary] {
        model.sessions.filter { s in
            (filter == "all" || !multiAgent || s.agent == filter) &&
            (search.isEmpty || s.displayTitle.localizedCaseInsensitiveContains(search) || s.cwd.localizedCaseInsensitiveContains(search))
        }
    }

    private var needsYou: [SessionSummary] { filtered.filter { $0.pendingCount > 0 } }

    /// Sections keyed by agent + folder ("General" = the agent's home directory).
    private var grouped: [FolderGroup] {
        var order: [String] = []
        var map: [String: [SessionSummary]] = [:]
        for s in filtered where s.pendingCount == 0 {
            let k = s.agent + "|" + s.cwd
            if map[k] == nil { order.append(k) }
            map[k, default: []].append(s)
        }
        return order.compactMap { k in
            guard let items = map[k], let first = items.first else { return nil }
            return FolderGroup(id: k, agent: first.agent, folder: first.cwd, items: items)
        }
    }

    // MARK: Collapsing (Notes / Files style: native sidebar disclosure)

    /// Search looks through every folder, so it shows them all open without touching what the user chose.
    private var searching: Bool { !search.isEmpty }
    private func isExpanded(_ id: String) -> Bool { searching || expanded.contains(id) }

    private func expansion(_ id: String) -> Binding<Bool> {
        Binding(get: { isExpanded(id) }, set: { open in
            guard !searching else { return }
            withAnimation(Motion.disclose) { if open { expanded.insert(id) } else { expanded.remove(id) } }
        })
    }

    private func setExpanded(_ ids: Set<String>) {
        withAnimation(Motion.disclose) { expanded = ids }
    }

    /// First launch: only the folder with the newest conversation starts open.
    private func loadExpansion() {
        guard !expansionLoaded else { return }
        if let raw = expandedRaw {
            expanded = Set(raw.split(separator: "\n").map(String.init))
        } else if let first = grouped.first?.id {
            expanded = [first]
        } else { return }
        expansionLoaded = true
    }

    /// Big folders show their newest few inline and the rest behind "Show All" (Mail / Notes); running ones always show.
    private func visibleItems(_ g: FolderGroup) -> (shown: [SessionSummary], hidden: Int) {
        let cap = 5
        if searching || g.items.count <= cap + 2 { return (g.items, 0) }
        let shown = Array(g.items.prefix(cap)) + g.items.dropFirst(cap).filter(\.running)
        return (shown, g.items.count - shown.count)
    }

    var body: some View {
        List {
            if multiAgent {
                Section {
                    Picker("Agent", selection: $filter) {
                        Text("All").tag("all")
                        ForEach(model.agents) { a in Text(verbatim: a.label).tag(a.id) }
                    }
                    .pickerStyle(.segmented)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            }
            statusCard
            if !needsYou.isEmpty {
                Section {
                    ForEach(needsYou) { s in row(s) }
                } header: {
                    Label("Needs you", systemImage: "hand.raised.fill")
                        .font(.subheadline.weight(.semibold)).textCase(nil)
                        .foregroundStyle(Theme.attention)
                }
            }
            ForEach(grouped) { group in
                Section(isExpanded: expansion(group.id)) {
                    let v = visibleItems(group)
                    ForEach(v.shown) { s in row(s) }
                    if v.hidden > 0 {
                        NavigationLink(value: FolderRoute(agent: group.agent, folder: group.folder)) {
                            Text("Show All (\(group.items.count))").foregroundStyle(Theme.accent)
                        }
                        .listRowBackground(Theme.bgRaised)
                    }
                } header: {
                    folderHeader(group)
                }
            }
        }
        .listStyle(.sidebar)
        .onAppear {
            loadExpansion()
            UserDefaults.standard.removeObject(forKey: "collapsedFolders")
        }
        .onChange(of: grouped.first?.id) { loadExpansion() }
        .onChange(of: expanded) { _, ids in if expansionLoaded { expandedRaw = ids.sorted().joined(separator: "\n") } }
        .scrollContentBackground(.hidden)
        .background { SpaceBackground() }
        .scrollEdgeEffectStyle(.soft, for: .top)
        .refreshable { await model.refreshSessions() }
        .overlay {
            if searching && filtered.isEmpty {
                ContentUnavailableView.search(text: search)
            } else if model.agentReady && filtered.isEmpty && !model.sessionsLoading {
                ContentUnavailableView {
                    Label("No conversations", systemImage: "bubble.left.and.bubble.right")
                } actions: {
                    Button("New conversation") { showNew = true }
                        .buttonStyle(.glassProminent)
                }
            }
        }
        .sheet(isPresented: $showQuota) {
            QuotaSheet()
                .navigationTransition(.zoom(sourceID: "quota", in: quotaZoom))
                .presentationDetents([.medium, .large])
        }
        .navigationTitle("Leash")
        .navigationSubtitle(model.statusText)
        .searchable(text: $search, prompt: "Search conversations")
        .searchToolbarBehavior(.minimize)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showSettings = true } label: { ConnectionDot() }
            }
            if model.isConnected {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showQuota = true } label: { QuotaRings(pool: model.tightestPool, known: !model.quotas.isEmpty) }
                }
                .matchedTransitionSource(id: "quota", in: quotaZoom)
            }
            DefaultToolbarItem(kind: .search, placement: .bottomBar)
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button { showNew = true } label: { Image(systemName: "square.and.pencil") }
                    .disabled(!model.agentReady)
            }
        }
    }

    /// The sidebar list draws the disclosure chevron and makes the header toggle. Collapsed, the header shows how
    /// many conversations it holds and whether one is working. Long-press for Collapse All / Expand All.
    private func folderHeader(_ group: FolderGroup) -> some View {
        let (title, isGeneral) = model.folderTitle(group.folder, agent: group.agent)
        let open = isExpanded(group.id)
        let runningCount = group.items.filter(\.running).count
        let unreadCount = group.items.filter { model.isUnread($0) }.count
        let ids = Set(grouped.map(\.id))
        return HStack(spacing: 6) {
            if multiAgent && filter == "all" { AgentIcon(agent: group.agent, size: 13) }
            Label(title, systemImage: isGeneral ? "tray" : "folder")
                .sectionLabelStyle()
            Spacer(minLength: 8)
            if !open {
                if runningCount > 0 { ActivitySpinner(size: 12) }
                if unreadCount > 0 {
                    Circle().fill(Theme.accent).frame(width: 7, height: 7)
                        .accessibilityLabel(Text("\(unreadCount) unread"))
                        .transition(Motion.pop)
                }
                Text(group.items.count, format: .number)
                    .font(.subheadline).monospacedDigit()
                    .foregroundStyle(Theme.textTertiary)
                    .contentTransition(.numericText())
            }
        }
        .animation(Motion.quick, value: open)
        .contextMenu {
            if !searching {
                Button("Collapse All", systemImage: "rectangle.compress.vertical") { setExpanded(expanded.subtracting(ids)) }
                    .disabled(ids.isDisjoint(with: expanded))
                Button("Expand All", systemImage: "rectangle.expand.vertical") { setExpanded(expanded.union(ids)) }
                    .disabled(ids.isSubset(of: expanded))
            }
        }
    }

    private func row(_ s: SessionSummary) -> some View {
        let unread = model.isUnread(s)
        return NavigationLink(value: s.id) {
            SessionRow(session: s, showAgent: multiAgent && filter == "all", draft: model.drafts[s.id], unread: unread)
        }
        .listRowBackground(Theme.bgRaised)
        .readStateActions(s.id, unread: unread)
    }

    private var downAgents: [AgentInfo] {
        if model.agents.isEmpty {
            return model.agentState == "ready" ? [] : [AgentInfo(id: "agy", label: "Antigravity", state: model.agentState, error: model.agentError, home: nil, name: nil, imageInput: false)]
        }
        return model.agents.filter { !$0.isReady }
    }

    @ViewBuilder private var statusCard: some View {
        if !model.isConfigured {
            Section {
                Button { showSettings = true } label: { Label("Pair with your Mac", systemImage: "qrcode.viewfinder") }
            } footer: { Text("Run the bridge on your Mac, then scan its QR code.") }
                .listRowBackground(Theme.bgRaised)
        } else if case .failed(let msg) = model.connection {
            Section { Label(msg, systemImage: "exclamationmark.triangle").foregroundStyle(Theme.danger) }
                .listRowBackground(Theme.bgRaised)
        } else if model.isConnected && !downAgents.isEmpty {
            Section {
                ForEach(downAgents) { a in
                    HStack(spacing: 10) {
                        if a.state == "starting" { ActivitySpinner() } else { AgentIcon(agent: a.id, size: 18) }
                        VStack(alignment: .leading, spacing: 4) {
                            if a.state == "starting" { Text("Starting \(a.label)…") } else { Text("\(a.label) is not running") }
                            if let e = a.error { Text(verbatim: e).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(3) }
                        }
                        Spacer()
                        if a.state == "down" { Button("Restart") { model.restartAgent(a.id) }.buttonStyle(.bordered).controlSize(.small) }
                    }
                }
            }
            .listRowBackground(Theme.bgRaised)
        } else if let e = model.sessionsError {
            Section { Label(e, systemImage: "exclamationmark.triangle").foregroundStyle(Theme.attention) }
                .listRowBackground(Theme.bgRaised)
        }
    }
}

extension View {
    /// Mail's leading swipe (full swipe toggles) and the same action in the context menu.
    func readStateActions(_ sessionId: String, unread: Bool) -> some View {
        modifier(ReadStateActions(sessionId: sessionId, unread: unread))
    }
}

private struct ReadStateActions: ViewModifier {
    @Environment(AppModel.self) private var model
    let sessionId: String
    let unread: Bool

    func body(content: Content) -> some View {
        content
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                button.tint(Theme.accent)
            }
            .contextMenu { button }
    }

    private var button: some View {
        Button {
            withAnimation(Motion.quick) { model.setUnread(sessionId, !unread) }
        } label: {
            if unread { Label("Mark as Read", systemImage: "envelope.open.fill") } else { Label("Mark as Unread", systemImage: "envelope.badge.fill") }
        }
    }
}

/// Push target for a folder's "Show All".
struct FolderRoute: Hashable {
    let agent: String
    let folder: String
}

extension AppModel {
    /// Section title for a working folder; the agent's home directory is "General".
    func folderTitle(_ folder: String, agent: String) -> (title: String, isGeneral: Bool) {
        let isGeneral = folder.isEmpty || folder == home(for: agent)
        return (isGeneral ? String(localized: "General") : (folder as NSString).lastPathComponent, isGeneral)
    }
}

/// Every conversation in one folder, newest first.
struct FolderSessionsView: View {
    @Environment(AppModel.self) private var model
    let route: FolderRoute
    @State private var search = ""

    private var all: [SessionSummary] { model.sessions.filter { $0.agent == route.agent && $0.cwd == route.folder } }
    private var shown: [SessionSummary] {
        search.isEmpty ? all : all.filter { $0.displayTitle.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        List {
            ForEach(shown) { s in
                let unread = model.isUnread(s)
                NavigationLink(value: s.id) { SessionRow(session: s, draft: model.drafts[s.id], unread: unread) }
                    .listRowBackground(Theme.bgRaised)
                    .readStateActions(s.id, unread: unread)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background { SpaceBackground() }
        .refreshable { await model.refreshSessions() }
        .overlay {
            if !search.isEmpty && shown.isEmpty { ContentUnavailableView.search(text: search) }
        }
        .navigationTitle(model.folderTitle(route.folder, agent: route.agent).title)
        .navigationSubtitle(String(localized: "\(all.count) conversations"))
        .searchable(text: $search, prompt: "Search conversations")
    }
}

private struct FolderGroup: Identifiable {
    let id: String          // agent + "|" + cwd
    let agent: String
    let folder: String
    let items: [SessionSummary]
}

struct SessionRow: View {
    let session: SessionSummary
    var showAgent = false
    /// Text left unsent in the composer (AppModel.drafts).
    var draft: String?
    /// A turn ended here since it was last opened (AppModel.isUnread).
    var unread = false
    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            // Mail's unread dot, in a gutter every row keeps so titles never shift; it sits on the title's first line
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Circle().fill(Theme.accent)
                    .frame(width: 9, height: 9)
                    .scaleEffect(unread ? 1 : 0.4)
                    .opacity(unread ? 1 : 0)
                    .accessibilityLabel(Text("Unread"))
                    .accessibilityHidden(!unread)
                VStack(alignment: .leading, spacing: 3) {
                    Text(session.displayTitle).font(.body.weight(.medium)).foregroundStyle(Theme.textPrimary).lineLimit(2)
                    HStack(spacing: 5) {
                        if showAgent { AgentIcon(agent: session.agent, size: 11) }
                        if let draft { draftLine(draft) }
                        if let d = session.updatedAt {
                            Text(d, format: .relative(presentation: .named))
                                .foregroundStyle(Theme.textSecondary)
                                .fixedSize()
                                .layoutPriority(1)
                        }
                    }
                    .font(.footnote)
                }
            }
            .animation(Motion.quick, value: unread)
            Spacer(minLength: 4)
            ZStack { statusGlyph }
                .animation(Motion.quick, value: status)
        }
        .padding(.vertical, 3)
    }

    /// "Draft: first line of it ·" before the date, as Messages does: the word and its weight say it, no colour;
    /// only the snippet truncates.
    @ViewBuilder private func draftLine(_ text: String) -> some View {
        let first = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        let snippet = Text(verbatim: String(first.trimmingCharacters(in: .whitespaces).prefix(120)))
            .fontWeight(.regular)
            .foregroundStyle(Theme.textSecondary)
        Text("Draft: \(snippet)")
            .fontWeight(.semibold)
            .foregroundStyle(Theme.textPrimary)
            .lineLimit(1)
            .truncationMode(.tail)
        if session.updatedAt != nil { Text(verbatim: "·").foregroundStyle(Theme.textSecondary) }
    }

    private enum Status: Equatable { case none, needsYou, running, locked }

    private var status: Status {
        if session.pendingCount > 0 { return .needsYou }
        if session.running { return .running }
        return session.lockedBy != nil ? .locked : .none
    }

    /// The trailing glyph cross-fades in place (ZStack, so old and new overlap) instead of popping.
    @ViewBuilder private var statusGlyph: some View {
        switch status {
        case .needsYou:
            Image(systemName: "hand.raised.fill").font(.caption).foregroundStyle(Theme.attention)
                .frame(width: 26, height: 26)
                .background(Theme.attention.opacity(0.15), in: Circle())
                .transition(Motion.pop)
        case .running:
            ActivitySpinner(size: 16)
                .transition(Motion.pop)
        case .locked:
            Image(systemName: "lock.fill").font(.caption).foregroundStyle(Theme.textTertiary)
                .frame(width: 26, height: 26)
                .accessibilityLabel(Text("Running on your computer"))
                .transition(Motion.pop)
        case .none:
            EmptyView()
        }
    }
}
