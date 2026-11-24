import SwiftUI

/// One collapsible row per agent turn: "Worked for 2m 14s ⌄  ◔ 4/4 steps" + "Explored 12 files, ran 6 commands ›".
struct ActivityRow: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let group: ActivityGroup
    let store: ChatStore
    var blocked = false
    var turnStartedAt: Date?
    var openFile: (String) -> Void
    @State private var showChecklist = false

    private var expanded: Bool { store.expandedGroups.contains(group.id) }
    /// A live turn not opened in full lays its messages and runs out below the header (ChatStore.liveTimeline), with
    /// the current action on its last run; the header keeps a sub-line only for an approval wait or before any step.
    private var showsSubLine: Bool {
        !group.live || expanded || blocked || (group.steps.isEmpty && !group.answerFollows)
    }

    var body: some View {
        let checklist = group.checklist
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(Motion.disclose) {
                    if expanded { store.expandedGroups.remove(group.id) } else { store.expandedGroups.insert(group.id) }
                }
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    leadingGlyph.frame(width: 18)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 4) {
                            headerText
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Theme.textSecondary)
                            Image(systemName: "chevron.down")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Theme.textTertiary)
                                .rotationEffect(.degrees(expanded ? 180 : 0))
                        }
                        if showsSubLine {
                            subLine
                                .font(.footnote)
                                .foregroundStyle(blocked ? Theme.attention : (group.live ? Theme.textSecondary : Theme.textTertiary))
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .transition(Motion.swap)
                        }
                    }
                    Spacer(minLength: 6)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.pressable(scale: 1))
            .overlay(alignment: .topTrailing) {
                if !checklist.isEmpty {
                    Button { withAnimation(Motion.disclose) { showChecklist.toggle() } } label: {
                        HStack(spacing: 5) {
                            RingGauge(fraction: Double(checklist.filter { $0.status == "completed" }.count) / Double(max(1, checklist.count)), size: 13, lineWidth: 1.8, tint: group.live ? Theme.accent : Theme.textTertiary)
                            Text("\(checklist.filter { $0.status == "completed" }.count)/\(checklist.count) steps")
                                .font(.footnote.monospacedDigit())
                                .contentTransition(.numericText())
                                .foregroundStyle(Theme.textSecondary)
                        }
                        .padding(.vertical, 2)
                    }
                    .buttonStyle(.pressable)
                }
            }

            if showChecklist && !checklist.isEmpty {
                ChecklistView(steps: checklist).padding(.leading, 26)
                    .transition(Motion.reveal)
            }

            if expanded {
                StepList(rows: StepRowModel.build(group.steps), live: group.live, store: store, openFile: openFile)
                    .padding(.leading, 8)
                    .transition(Motion.reveal)
            }
        }
        .padding(.vertical, 2)
        .animation(Motion.quick, value: group.live)
        .animation(Motion.quick, value: blocked)
        .animation(Motion.quick, value: showsSubLine)
        .animation(Motion.quick, value: checklist.filter { $0.status == "completed" }.count)
        .haptic(.increase, trigger: checklist.filter { $0.status == "completed" }.count) { old, new in group.live && new > old }
    }

    @ViewBuilder private var leadingGlyph: some View {
        if blocked { Image(systemName: "hand.raised.fill").foregroundStyle(Theme.attention).font(.footnote).symbolEffect(.pulse, options: .repeating).transition(Motion.pop) }
        // one moving signal per row: the shimmering header (a spinner instead when Reduce Motion is on)
        else if group.live && reduceMotion { ActivitySpinner(size: 14).accessibilityHidden(true).transition(Motion.pop) }
        // one Image for live and done, so the live marker morphs into the end-state symbol in place
        else {
            Image(systemName: glyphName)
                .font(glyphFont)
                .foregroundStyle(glyphColor)
                .contentTransition(glyphSwap)
                .transition(Motion.pop)
                .accessibilityLabel(glyphLabel ?? Text(verbatim: ""))
                .accessibilityHidden(glyphLabel == nil)
        }
    }

    /// The header already says working / worked; only failure and a stop need saying.
    private var glyphLabel: Text? {
        if group.live { return nil }
        if group.failedCount > 0 { return Text("Failed") }
        if group.stats?.stopReason == "cancelled" { return Text("Stopped") }
        return nil
    }

    private var glyphName: String {
        if group.live { return "smallcircle.filled.circle" }   // the same "in progress" marker as the checklist
        if group.failedCount > 0 { return "exclamationmark.circle" }
        if group.stats?.stopReason == "cancelled" { return "stop.circle" }
        return "checkmark.circle"
    }

    private var glyphColor: Color {
        if group.live { return Theme.accent }
        return group.failedCount > 0 ? Theme.attention : Theme.textTertiary
    }

    private var glyphFont: Font { .footnote }   // one weight, so the ring doesn't change stroke in the swap

    private var glyphSwap: ContentTransition { reduceMotion ? .opacity : .symbolEffect(.replace) }

    /// Working → done is one calm swap: the live line blurs away, then the summary resolves in a beat later.
    private var doneTransition: AnyTransition {
        .asymmetric(insertion: Motion.swap.animation(Motion.quick.delay(0.12)), removal: Motion.swap)
    }

    private var headerText: some View {
        ZStack(alignment: .leading) {
            if group.live {
                workingText.transition(Motion.swap)
            } else {
                doneText.transition(doneTransition)
            }
        }
    }

    private var workingText: some View {
        Group {
            if let start = turnStartedAt {
                TimelineView(.periodic(from: .now, by: 1)) { tl in
                    let secs = tl.date.timeIntervalSince(start).rounded()
                    Text("Working · \(durationText(secs))").monospacedDigit()
                        .contentTransition(.numericText(countsDown: false))
                        .animation(Motion.quick, value: secs)
                }
            } else {
                Text("Working")
            }
        }
        .shimmer(!blocked)
    }

    @ViewBuilder private var doneText: some View {
        if let ms = group.stats?.durationMs {
            Text("Worked for \(durationText(Double(ms) / 1000))") + (group.failedCount > 0 ? Text(" · \(group.failedCount) failed") : Text(verbatim: ""))
        } else {
            if group.visibleTools.count == 1 { Text("1 step") } else { Text("\(group.visibleTools.count) steps") }
        }
    }

    /// The live line cross-fades when the agent moves to its next action.
    @ViewBuilder private var subLine: some View {
        let line: String = blocked ? String(localized: "Waiting for your approval") : (group.live ? group.liveAction : (group.summary.isEmpty ? " " : group.summary + "  ›"))
        ZStack(alignment: .leading) {
            Text(line).id(line).transition(Motion.swap)
        }
        .animation(Motion.quick, value: line)
    }
}

/// One run of tools in a live turn: "Explored 3 files, ran 2 commands ⌄", or the current action while it is the last
/// run. No glyph of its own (the header carries the turn's status); it opens in place to its steps.
struct SegmentRow: View {
    let segment: StepSegment
    let store: ChatStore
    var openFile: (String) -> Void

    private var open: Bool { store.expandedGroups.contains(segment.id) }

    var body: some View {
        let rows = StepRowModel.build(segment.steps)
        let active = segment.liveLine != nil
        let line = segment.liveLine ?? segment.summary
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(Motion.disclose) {
                    if open { store.expandedGroups.remove(segment.id) } else { store.expandedGroups.insert(segment.id) }
                }
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    // the active line cross-fades as the agent moves to its next action
                    ZStack(alignment: .leading) {
                        Text(line).id(line).transition(Motion.swap)
                    }
                    .font(.footnote)
                    .foregroundStyle(active ? Theme.textSecondary : Theme.textTertiary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .shimmer(active)
                    if !rows.isEmpty {
                        Image(systemName: "chevron.down")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(Theme.textTertiary)
                            .rotationEffect(.degrees(open ? 180 : 0))
                    }
                    Spacer(minLength: 6)
                }
                .padding(.leading, 26)   // under the header's title, past its glyph column
                .frame(minHeight: 22)
                .contentShape(Rectangle())
            }
            .buttonStyle(.pressable(scale: 1))
            .disabled(rows.isEmpty)

            if open && !rows.isEmpty {
                StepList(rows: rows, live: active, store: store, openFile: openFile)
                    .padding(.leading, 8)
                    .transition(Motion.reveal)
            }
        }
        .animation(Motion.quick, value: line)
        .animation(Motion.quick, value: active)
    }
}

/// The rail of steps under an opened turn or run; a long list shows its first and last steps until asked for all.
struct StepList: View {
    let rows: [StepRowModel]
    var live = false
    let store: ChatStore
    var openFile: (String) -> Void
    @State private var showAllSteps = false

    var body: some View {
        let all = rows
        let collapsed = all.count > 30 && !showAllSteps
        let head = collapsed ? Array(all.prefix(3)) : all
        let tail = collapsed ? Array(all.suffix(10)) : []
        HStack(alignment: .top, spacing: 0) {
            Rectangle().fill(Theme.hairline).frame(width: 1.5).padding(.leading, 9).padding(.vertical, 4)
            VStack(alignment: .leading, spacing: 2) {
                ForEach(head) { r in StepRow(row: r, store: store, openFile: openFile).transition(Motion.reveal) }
                if collapsed {
                    Button("Show \(all.count - 13) earlier steps") { withAnimation(Motion.disclose) { showAllSteps = true } }
                        .font(.footnote).padding(.vertical, 6).padding(.leading, 12)
                    ForEach(tail) { r in StepRow(row: r, store: store, openFile: openFile).transition(Motion.reveal) }
                }
            }
        }
        // only a live turn's new steps rise in (the rail grows with them); a finished turn never animates here, and
        // the >30 head/tail split stays instant so crossing it doesn't fade every row out and back in
        .animation(live && !collapsed ? Motion.layout : nil, value: all.count)
    }
}

/// Rows in the expanded list; consecutive reads coalesce into "Read N files".
struct StepRowModel: Identifiable {
    enum Kind { case thought(MessageItem), note(MessageItem), tool(ToolItem), reads([ToolItem]) }
    let id: UUID
    let kind: Kind

    static func build(_ steps: [ChatEntry]) -> [StepRowModel] {
        var out: [StepRowModel] = []
        var reads: [ToolItem] = []
        func flushReads() {
            if reads.count == 1 { out.append(StepRowModel(id: reads[0].id, kind: .tool(reads[0]))) }
            else if reads.count > 1 { out.append(StepRowModel(id: reads[0].id, kind: .reads(reads))) }
            reads = []
        }
        for s in steps {
            switch s {
            case .tool(let t):
                if t.isStepsTool { continue }
                if t.kind == "read" && t.status != "failed" { reads.append(t); continue }
                flushReads(); out.append(StepRowModel(id: t.id, kind: .tool(t)))
            case .message(let m):
                flushReads()
                out.append(StepRowModel(id: m.id, kind: m.role == .thought ? .thought(m) : .note(m)))
            default: break
            }
        }
        flushReads()
        return out
    }
}

struct StepRow: View {
    let row: StepRowModel
    let store: ChatStore
    var openFile: (String) -> Void
    private var open: Bool { store.expandedSteps.contains(row.id) }

    private func toggle() {
        withAnimation(Motion.disclose) {
            if open { store.expandedSteps.remove(row.id) } else { store.expandedSteps.insert(row.id) }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button(action: toggle) {
                HStack(spacing: 8) {
                    icon.font(.caption).foregroundStyle(Theme.textTertiary).frame(width: 16)
                    title.lineLimit(open ? nil : 1).truncationMode(.middle)
                    Spacer(minLength: 4)
                    trailing
                }
                .frame(minHeight: 28)
                .contentShape(Rectangle())
            }
            // an open step's title is a tall text block: opacity only then
            .buttonStyle(.pressable(scale: open ? 1 : 0.98))
            if open && hasDetail { detail.padding(.leading, 24).padding(.bottom, 4).transition(Motion.reveal) }
        }
        .padding(.leading, 6)
    }

    @ViewBuilder private var icon: some View {
        switch row.kind {
        case .thought: Image(systemName: "brain")
        case .note: Image(systemName: "text.bubble")
        case .reads: Image(systemName: "doc.text")
        case .tool(let t): Image(systemName: ToolIcon.name(t.kind))
        }
    }

    @ViewBuilder private var title: some View {
        switch row.kind {
        case .thought(let m): Text(Self.inline(m.text)).font(.footnote).foregroundStyle(Theme.textSecondary).italic()
        case .note(let m): Text(Self.inline(m.text)).font(.footnote).foregroundStyle(Theme.textSecondary)
        case .reads(let ts): Text("Read \(ts.count) files").font(.footnote).foregroundStyle(Theme.textSecondary)
        case .tool(let t):
            if t.kind == "execute", let cmd = t.commandPreview {
                Text(cmd).font(Theme.mono).foregroundStyle(Theme.textPrimary)
            } else if ["edit", "delete", "move"].contains(t.kind), let p = t.firstDiff?.path ?? t.locations.first {
                Text((p as NSString).lastPathComponent).font(Theme.mono).foregroundStyle(Theme.textPrimary)
            } else {
                Text(t.title).font(.footnote).foregroundStyle(Theme.textPrimary)
            }
        }
    }

    @ViewBuilder private var trailing: some View {
        if case .tool(let t) = row.kind {
            HStack(spacing: 6) {
                if t.firstDiff != nil {
                    Text("+\(t.diffAdds)").foregroundStyle(Theme.success)
                    Text("−\(t.diffDels)").foregroundStyle(Theme.danger)
                }
                if t.autoApproved { Image(systemName: "bolt.fill").font(.system(size: 9)).foregroundStyle(Theme.turbo) }
                if t.status == "failed" { Image(systemName: "xmark").font(.caption2.weight(.bold)).foregroundStyle(Theme.danger) }
                else if t.status == "in_progress" { ActivitySpinner(size: 11) }
            }
            .font(Theme.monoSmall)
        }
    }

    /// A thought or note opens in place (its title drops the line limit): a second copy below would repeat it.
    private var hasDetail: Bool {
        switch row.kind {
        case .thought, .note: return false
        case .reads, .tool: return true
        }
    }

    /// Inline markdown only (bold, code, links), so the footnote title reads like the text it stands for.
    private static func inline(_ s: String) -> AttributedString {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return (try? AttributedString(markdown: t, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(t)
    }

    @ViewBuilder private var detail: some View {
        switch row.kind {
        case .thought, .note:
            EmptyView()
        case .reads(let ts):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(ts) { t in
                    let p = t.locations.first ?? t.commandPreview ?? t.title
                    Button { openFile(p) } label: { Text(p).font(Theme.monoSmall).lineLimit(1).truncationMode(.head) }.buttonStyle(.pressable(scale: 1))
                        .foregroundStyle(Theme.accent)
                }
            }
        case .tool(let t):
            VStack(alignment: .leading, spacing: 6) {
                if t.kind != "execute", let cmd = t.commandPreview { Text(cmd).font(Theme.monoSmall).foregroundStyle(Theme.textSecondary).textSelection(.enabled) }
                if let d = t.firstDiff { DiffView(path: d.path, old: d.old, new: d.new, openFile: openFile, maxCollapsedLines: 12) }
                ForEach(t.content) { c in
                    if case .text(let s) = c { CodeText(text: s, maxLines: 12) }
                }
                if t.content.isEmpty, let out = t.rawOutput, !out.pretty.isEmpty { CodeText(text: out.pretty, maxLines: 12) }
                ForEach(t.locations.prefix(3), id: \.self) { loc in
                    Button { openFile(loc) } label: { Label(loc, systemImage: "doc").font(.caption2).lineLimit(1).truncationMode(.head) }
                        .buttonStyle(.pressable(scale: 1)).foregroundStyle(Theme.accent)
                }
            }
        }
    }
}

enum ToolIcon {
    static func name(_ kind: String) -> String {
        switch kind {
        case "read": return "doc.text"
        case "edit": return "pencil"
        case "delete": return "trash"
        case "move": return "arrow.right.doc.on.clipboard"
        case "search": return "magnifyingglass"
        case "execute": return "terminal"
        case "think": return "brain"
        case "fetch": return "globe"
        default: return "wrench.and.screwdriver"
        }
    }
}

struct ChecklistView: View {
    let steps: [ChecklistStep]
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(steps) { s in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Image(systemName: s.status == "completed" ? "checkmark.circle.fill" : (s.status == "in_progress" ? "smallcircle.filled.circle" : "circle"))
                        .font(.footnote)
                        .foregroundStyle(s.status == "in_progress" ? Theme.accent : Theme.textTertiary)
                    Text(s.title).font(.footnote)
                        .foregroundStyle(s.status == "completed" ? Theme.textTertiary : Theme.textPrimary)
                        .strikethrough(s.status == "completed", color: Theme.textTertiary)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

/// "Neon · High · ↑12.4k ↓1.8k · 62 tok/s" under the final answer.
struct StatsLine: View {
    let stats: TurnStats
    @State private var open = false
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { withAnimation(Motion.disclose) { open.toggle() } } label: {
                Text(summary).font(Theme.monoSmall).foregroundStyle(Theme.textTertiary).lineLimit(1)
            }
            .buttonStyle(.pressable(scale: 1))
            if open {
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 4) {
                    if let t = stats.ttftMs { GridRow { label("First token"); value(durationText(Double(t) / 1000)) } }
                    if let d = stats.durationMs { GridRow { label("Duration"); value(durationText(Double(d) / 1000)) } }
                    if let t = stats.thoughtTokens { GridRow { label("Thinking"); value(compactNumber(t) + " tok") } }
                    if let c = stats.cachedTokens, c > 0 { GridRow { label("Cached"); value(compactNumber(c) + " tok") } }
                    if let r = stats.stopReason { GridRow { label("Stop"); value(r) } }
                }
                .padding(.leading, 2)
            }
        }
    }
    private var summary: String {
        var parts: [String] = []
        if let m = stats.model { parts.append(m.replacingOccurrences(of: "Gemini 4 ", with: "").replacingOccurrences(of: "Claude ", with: "")) }
        if let e = stats.effort { parts.append(e) }
        var tok = ""
        if let i = stats.inputTokens { tok += "↑" + compactNumber(i) }
        if let o = stats.outputTokens { tok += (tok.isEmpty ? "" : " ") + "↓" + compactNumber(o + (stats.thoughtTokens ?? 0)) }
        if !tok.isEmpty { parts.append(tok) }
        if let t = stats.tokensPerSecond { parts.append(String(format: "%.0f tok/s", t)) }
        return parts.joined(separator: " · ")
    }
    private func label(_ s: LocalizedStringKey) -> some View { Text(s).font(.caption2).foregroundStyle(Theme.textTertiary) }
    private func value(_ s: String) -> some View { Text(s).font(Theme.monoSmall).foregroundStyle(Theme.textSecondary) }
}
