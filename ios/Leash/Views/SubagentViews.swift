import SwiftUI

/// Pushes a subagent's transcript; `root` is the conversation the bridge files it under.
struct SubagentRoute: Hashable {
    let root: String
    let child: String
}

/// A subagent in a step list: its title, then "type · model · current action" (or "type · N steps · 1m 3s"). It
/// navigates to the subagent's own transcript instead of opening in place.
struct SubagentStepRow: View {
    let item: SubagentItem
    let rootSessionId: String

    var body: some View {
        NavigationLink(value: SubagentRoute(root: rootSessionId, child: item.sessionId)) {
            HStack(spacing: 8) {
                Image(systemName: "arrow.triangle.branch").font(.caption).foregroundStyle(Theme.textTertiary).frame(width: 16)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.displayTitle).font(.footnote).foregroundStyle(Theme.textPrimary).lineLimit(1)
                    ZStack(alignment: .leading) {
                        Text(item.secondaryLine).id(item.secondaryLine).transition(Motion.swap)
                    }
                    .font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 4)
                trailing
                Image(systemName: "chevron.right").font(.caption2.weight(.semibold)).foregroundStyle(Theme.textTertiary)
            }
            .frame(minHeight: 36)
            .contentShape(Rectangle())
            .accessibilityElement(children: .combine)
        }
        .buttonStyle(.pressable(scale: 0.98))
        .padding(.leading, 6)
        .animation(Motion.quick, value: item.secondaryLine)
        .animation(Motion.quick, value: item.state)
    }

    @ViewBuilder private var trailing: some View {
        if item.needsAction {
            Image(systemName: "exclamationmark.circle.fill").font(.caption).foregroundStyle(Theme.attention)
                .accessibilityLabel("Needs approval").transition(Motion.pop)
        } else if item.isRunning {
            ActivitySpinner(size: 11).transition(Motion.pop)
        } else if item.stopReason == "cancelled" {
            Image(systemName: "stop.circle").font(.caption).foregroundStyle(Theme.textTertiary).accessibilityLabel("Stopped")
        } else if let r = item.stopReason, r != "end_turn" {
            Image(systemName: "xmark").font(.caption2.weight(.bold)).foregroundStyle(Theme.danger).accessibilityLabel("Failed")
        }
    }
}

/// A subagent's own transcript, pushed from its row: the parent's prompt as a quote, its steps (flat, already
/// open), its reply. Requests from it (or agents it launched) are answered here too.
struct SubagentView: View {
    @Environment(AppModel.self) private var model
    let store: ChatStore
    let childId: String
    @State private var fileToOpen: String?
    @Namespace private var glassNS

    private var item: SubagentItem? { store.subagent(childId) }

    var body: some View {
        content
            .background { SpaceBackground(stars: false) }
            .navigationTitle(item?.displayTitle ?? String(localized: "Agent"))
            .navigationSubtitle(subtitle)
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaBar(edge: .bottom, spacing: 0) { trays }
            .sheet(item: fileSheet) { f in FileViewer(path: f.path, agent: store.agent, sessionId: store.sessionId) }
            .environment(\.embedAgent, store.agent)
            .environment(\.transcriptSession, store.sessionId)
    }

    @ViewBuilder private var content: some View {
        if let item {
            SubagentTranscript(item: item, store: store, openFile: { fileToOpen = $0 })
        } else {
            // a reattach can rebuild the transcript without it for a moment
            ContentUnavailableView("Agent not loaded", systemImage: "arrow.triangle.branch")
        }
    }

    private var subtitle: String {
        guard let item else { return "" }
        return [item.agentType == item.title ? nil : item.agentType, item.model].compactMap { $0 }.joined(separator: " · ")
    }

    private var fileSheet: Binding<IdentifiedPath?> {
        Binding(get: { fileToOpen.map { IdentifiedPath(path: $0) } }, set: { fileToOpen = $0?.path })
    }

    /// This agent's requests and those of agents it launched (they are filed under the root conversation).
    private var requests: [PendingRequest] {
        guard let item else { return [] }
        let ids = item.lineage
        return model.pendingFor(store.sessionId).filter { r in r.subagent.map { ids.contains($0.sessionId) } ?? false }
    }

    @ViewBuilder private var trays: some View {
        let reqs = requests
        let perms = reqs.filter(\.isPermission)
        let questions = reqs.filter { !$0.isPermission }
        GlassEffectContainer(spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                if let req = perms.first {
                    ApprovalTray(request: req, index: 1, total: perms.count, openFile: { fileToOpen = $0 }, showsAgent: req.subagent?.sessionId != childId)
                        .glassEffectID("tray", in: glassNS)
                        .glassEffectTransition(.materialize)
                        .transition(Motion.tray)
                } else if let req = questions.first {
                    QuestionTray(request: req, index: 1, total: questions.count, showsAgent: req.subagent?.sessionId != childId)
                        .glassEffectID("tray", in: glassNS)
                        .glassEffectTransition(.materialize)
                        .transition(Motion.tray)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, reqs.isEmpty ? 0 : 6)
            .padding(.top, reqs.isEmpty ? 0 : 10)
            .animation(Motion.layout, value: perms.first?.key)
            .animation(Motion.layout, value: questions.first?.key)
        }
    }
}

private struct SubagentTranscript: View {
    let item: SubagentItem
    let store: ChatStore
    var openFile: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(Self.fold(item)) { row($0) }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 4)
        }
        .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
    }

    @ViewBuilder private func row(_ d: DisplayItem) -> some View {
        switch d {
        case .user(let m):
            PromptQuote(text: m.text)
        case .activity(let g):
            VStack(alignment: .leading, spacing: 8) {
                if g.live {
                    HStack(spacing: 8) {
                        ActivitySpinner(size: 11)
                        Text(item.needsAction ? String(localized: "Waiting for your approval") : g.liveAction)
                            .id(item.needsAction ? "approval" : g.liveAction).transition(Motion.swap)
                            .lineLimit(1).truncationMode(.middle)
                    }
                    .font(.footnote)
                    .foregroundStyle(item.needsAction ? Theme.attention : Theme.textSecondary)
                    .animation(Motion.quick, value: g.liveAction)
                }
                let checklist = g.checklist
                if !checklist.isEmpty { ChecklistView(steps: checklist) }
                let rows = StepRowModel.build(g.steps)
                if !rows.isEmpty { StepList(rows: rows, live: g.live, store: store, openFile: openFile) }
            }
        case .answer(let m):
            AnswerView(item: m, live: item.isRunning && isLast(m))
        case .notice(let n):
            NoticeView(item: n)
        case .planDoc(let p):
            PlanCard(item: p)
        case .stats, .segment:
            EmptyView()
        }
    }

    private func isLast(_ m: MessageItem) -> Bool {
        if case .message(let last)? = item.entries.last { return last === m }
        return false
    }

    /// Prompt → steps → reply, like a turn of the main conversation (no live timeline: the steps are already open).
    static func fold(_ s: SubagentItem) -> [DisplayItem] {
        var out: [DisplayItem] = []
        var group: ActivityGroup? = nil
        var answer: MessageItem? = nil
        func demoteAnswer() {
            if let a = answer {
                if group == nil { group = ActivityGroup(id: a.id.derived(1)) }
                group!.steps.append(.message(a))
                answer = nil
            }
        }
        func flush() {
            if let g = group { out.append(.activity(g)) }
            if let a = answer { out.append(.answer(a)) }
            group = nil; answer = nil
        }
        for e in s.entries {
            switch e {
            case .message(let m):
                switch m.role {
                case .user: flush(); out.append(.user(m))
                case .agent: demoteAnswer(); answer = m
                case .thought:
                    demoteAnswer()
                    if group == nil { group = ActivityGroup(id: m.id) }
                    group!.steps.append(e)
                }
            case .tool, .subagent:
                demoteAnswer()
                if group == nil { group = ActivityGroup(id: e.id) }
                group!.steps.append(e)
            case .plan(let p):
                if group == nil { group = ActivityGroup(id: p.id) }
                group!.planEntries = p.entries
            case .notice(let n):
                flush(); out.append(.notice(n))
            case .stats:
                break
            }
        }
        if s.isRunning {
            if group == nil { group = ActivityGroup(id: s.id.derived(3)) }
            group?.live = true
        }
        flush()
        return out
    }
}

/// The parent agent's instructions to a subagent: a quote, not a user bubble (the user didn't write it).
struct PromptQuote: View {
    let text: String
    @State private var expanded = false

    var body: some View {
        let long = text.count > 240 || text.filter { $0 == "\n" }.count > 2
        VStack(alignment: .leading, spacing: 6) {
            Text("Prompt").font(.caption.weight(.medium)).foregroundStyle(Theme.textTertiary)
            Text(text.trimmingCharacters(in: .whitespacesAndNewlines))
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(expanded ? nil : 3)
                .textSelection(.enabled)
            if long {
                Button(expanded ? String(localized: "Show less") : String(localized: "Show more")) {
                    withAnimation(Motion.disclose) { expanded.toggle() }
                }
                .font(.footnote)
                .buttonStyle(.pressable(scale: 1))
                .foregroundStyle(Theme.accent)
            }
        }
        .padding(.leading, 12)
        .overlay(alignment: .leading) { Capsule().fill(Theme.hairline).frame(width: 3) }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
