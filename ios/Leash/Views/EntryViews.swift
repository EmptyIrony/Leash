import SwiftUI

struct EntryView: View {
    let entry: ChatEntry
    var openFile: (String) -> Void
    var body: some View {
        switch entry {
        case .message(let m): MessageView(item: m)
        case .tool(let t): ToolCard(item: t, openFile: openFile)
        case .plan(let p): PlanCard(item: p)
        case .notice(let n): NoticeView(item: n)
        case .stats(let s): StatsLine(stats: s)
        }
    }
}

/// Pieces of a user message. Claude Code writes "!" shell runs and slash commands into the transcript as tagged text
/// (`<bash-input>`, `<bash-stdout>`…, `<command-name>`…, tag bodies HTML-escaped); they render as commands, not prose.
enum UserSegment: Hashable {
    case text(String)
    case shellInput(String)
    case shellOutput(stdout: String, stderr: String, exitCode: Int?)
    case command(String)

    private static let tagRegex = try! NSRegularExpression(
        pattern: "<(bash-input|bash-stdout|bash-stderr|bash-exit-code|command-name|command-message|command-args|local-command-stdout|local-command-stderr|local-command-caveat|system-reminder)>([\\s\\S]*?)</\\1>")

    static func unescape(_ s: String) -> String {
        s.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    static func parse(_ text: String) -> [UserSegment] {
        guard text.contains("</") else { return text.isEmpty ? [] : [.text(text)] }
        let ns = text as NSString
        var out: [UserSegment] = []
        var cursor = 0
        var command: (name: String, args: String)? = nil
        var output: (out: String, err: String, code: Int?, bash: Bool)? = nil
        func flushPending() {
            if let c = command { out.append(.command([c.name, c.args].filter { !$0.isEmpty }.joined(separator: " "))); command = nil }
            if let o = output {
                if o.bash || !o.out.isEmpty || !o.err.isEmpty {   // an empty /command result is dropped, an empty "!" result shows "No output"
                    out.append(.shellOutput(stdout: o.out, stderr: o.err, exitCode: o.code))
                }
                output = nil
            }
        }
        func addText(_ s: String) {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty { flushPending(); out.append(.text(t)) }
        }
        for m in tagRegex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            addText(ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor)))
            cursor = m.range.location + m.range.length
            let tag = ns.substring(with: m.range(at: 1))
            let body = unescape(ns.substring(with: m.range(at: 2)))
            switch tag {
            case "bash-input": flushPending(); out.append(.shellInput(body.trimmingCharacters(in: .whitespacesAndNewlines)))
            case "bash-stdout", "local-command-stdout", "bash-stderr", "local-command-stderr":
                var o = output ?? (out: "", err: "", code: nil, bash: false)
                if tag.hasSuffix("stdout") { o.out += body } else { o.err += body }
                if tag.hasPrefix("bash") { o.bash = true }
                output = o
            case "bash-exit-code": var o = output ?? (out: "", err: "", code: nil, bash: true); o.code = Int(body.trimmingCharacters(in: .whitespacesAndNewlines)); output = o
            case "command-name": flushPending(); command = (name: body.trimmingCharacters(in: .whitespacesAndNewlines), args: command?.args ?? "")
            case "command-args": var c = command ?? (name: "", args: ""); c.args = body.trimmingCharacters(in: .whitespacesAndNewlines); command = c
            default: break   // command-message duplicates the name; caveats and reminders are for the model
            }
        }
        addText(ns.substring(from: cursor))
        flushPending()
        return out
    }

    /// Output belongs to the command above it, so it doesn't open a new "turn" gap.
    static func isContinuation(_ text: String) -> Bool {
        text.hasPrefix("<bash-stdout>") || text.hasPrefix("<local-command-stdout>") || text.hasPrefix("<bash-stderr>")
    }
}

struct UserBubble: View {
    let item: MessageItem

    var body: some View {
        let linked = FileRef.extractLinks(item.text)
        let files = item.files + linked.refs.filter { r in !item.files.contains { $0.path == r.path } }
        let segments = UserSegment.parse(linked.text)
        VStack(alignment: .trailing, spacing: 8) {
            ForEach(Array(item.images.enumerated()), id: \.offset) { i, d in
                if let ui = UIImage(data: d) {
                    ImageTile(image: ui, data: d, name: "image-\(i + 1).png", maxWidth: 220, maxHeight: 220, cornerRadius: 16)
                }
            }
            if !files.isEmpty {
                VStack(alignment: .trailing, spacing: 6) {
                    ForEach(files) { f in FileChip(ref: f) }
                }
                .padding(.leading, 48)
            }
            ForEach(Array(segments.enumerated()), id: \.offset) { _, s in segment(s) }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }

    /// Something to draw: tag-only messages (a slash-command caveat, …) render nothing.
    static func hasContent(_ m: MessageItem) -> Bool {
        if !m.images.isEmpty || !m.files.isEmpty { return true }
        let linked = FileRef.extractLinks(m.text)
        return !linked.refs.isEmpty || !UserSegment.parse(linked.text).isEmpty
    }

    @ViewBuilder private func segment(_ s: UserSegment) -> some View {
        switch s {
        case .text(let t):
            HStack {
                Spacer(minLength: 48)
                Text(t)
                    .font(.system(size: Theme.readingSize))
                    .lineSpacing(3)
                    .textSelection(.enabled)
                    .foregroundStyle(Theme.textPrimary)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Theme.accent.opacity(0.16), in: Self.bubble)
                    .messageMenu(t)
            }
        case .shellInput(let cmd):
            HStack {
                Spacer(minLength: 48)
                HStack(alignment: .firstTextBaseline, spacing: 7) {
                    Text(verbatim: "!").font(.callout.monospaced().weight(.bold)).foregroundStyle(Theme.shell)
                    Text(cmd).font(.callout.monospaced()).foregroundStyle(Theme.textPrimary).textSelection(.enabled)
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.shell.opacity(0.13), in: Self.bubble)
                .contextMenu { Button { UIPasteboard.general.string = cmd } label: { Label("Copy", systemImage: "doc.on.doc") } }
                .accessibilityLabel(Text("Shell command \(cmd)"))
            }
        case .shellOutput(let out, let err, let code):
            ShellOutputView(stdout: out, stderr: err, exitCode: code)
        case .command(let c):
            HStack {
                Spacer(minLength: 48)
                Label(c, systemImage: "command").font(.callout.monospaced())
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Theme.hairline, in: Capsule())
            }
        }
    }

    private static let bubble = UnevenRoundedRectangle(topLeadingRadius: 20, bottomLeadingRadius: 20, bottomTrailingRadius: 6, topTrailingRadius: 20, style: .continuous)
}

/// Result of a "!" shell command: terminal-style block, long output collapsed.
struct ShellOutputView: View {
    let stdout: String
    let stderr: String
    let exitCode: Int?
    @State private var expanded = false
    private static let collapsedLines = 12

    var body: some View {
        let out = stdout.trimmingCharacters(in: .newlines)
        let err = stderr.trimmingCharacters(in: .newlines)
        let lines = (out.isEmpty ? 0 : out.split(separator: "\n", omittingEmptySubsequences: false).count) + (err.isEmpty ? 0 : err.split(separator: "\n", omittingEmptySubsequences: false).count)
        let failed = (exitCode ?? 0) != 0
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "terminal").font(.caption.weight(.semibold))
                Text("Output")
                if failed, let code = exitCode {
                    Label { Text("exit \(code)") } icon: { Image(systemName: "xmark.circle.fill") }
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(Theme.danger)
                }
                Spacer()
                Button { UIPasteboard.general.string = [out, err].filter { !$0.isEmpty }.joined(separator: "\n"); Haptics.impact(.light, intensity: 0.6) } label: {
                    Image(systemName: "doc.on.doc").font(.caption)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Copy")
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(Theme.textTertiary)

            if out.isEmpty && err.isEmpty {
                Text("No output").font(.footnote.monospaced()).foregroundStyle(Theme.textTertiary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 2) {
                        if !out.isEmpty { Text(clip(out, budget: Self.collapsedLines)).foregroundStyle(Theme.textPrimary) }
                        if !err.isEmpty { Text(clip(err, budget: max(3, Self.collapsedLines - lineCount(out)))).foregroundStyle(failed ? Theme.danger : Theme.attention) }
                    }
                    .font(.footnote.monospaced())
                    .lineSpacing(2)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: true, vertical: false)
                }
                if lines > Self.collapsedLines {
                    Button { withAnimation(Motion.disclose) { expanded.toggle() } } label: {
                        Text(expanded ? "Show less" : "Show all \(lines) lines").font(.caption.weight(.medium))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.accent)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.bgSunken, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.hairline, lineWidth: 0.5))
    }

    private func lineCount(_ s: String) -> Int { s.isEmpty ? 0 : s.split(separator: "\n", omittingEmptySubsequences: false).count }
    private func clip(_ s: String, budget: Int) -> String {
        expanded ? s : s.split(separator: "\n", omittingEmptySubsequences: false).prefix(budget).joined(separator: "\n")
    }
}

struct MessageView: View {
    let item: MessageItem
    @State private var thoughtsOpen = false

    var body: some View {
        switch item.role {
        case .user:
            HStack {
                Spacer(minLength: 40)
                VStack(alignment: .trailing, spacing: 6) {
                    ForEach(Array(item.images.enumerated()), id: \.offset) { _, d in
                        if let ui = UIImage(data: d) {
                            Image(uiImage: ui).resizable().scaledToFit().frame(maxWidth: 220, maxHeight: 220).clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    if !item.text.isEmpty {
                        Text(item.text)
                            .textSelection(.enabled)
                            .padding(.horizontal, 14).padding(.vertical, 9)
                            .background(Color.accentColor.opacity(0.18), in: RoundedRectangle(cornerRadius: 18))
                    }
                }
            }
        case .agent:
            MarkdownView(text: item.text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .messageMenu(item.text, markdown: true)
        case .thought:
            DisclosureGroup(isExpanded: $thoughtsOpen) {
                Text(item.text).font(.footnote).foregroundStyle(.secondary).textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "brain").font(.caption)
                    Text(thoughtsOpen ? "Thinking" : (item.text.split(separator: "\n").last.map(String.init) ?? "Thinking"))
                        .font(.footnote).lineLimit(1)
                }
                .foregroundStyle(.secondary)
            }
            .tint(.secondary)
        }
    }
}

struct NoticeView: View {
    let item: NoticeItem
    var body: some View {
        let color: Color = item.severity == "error" ? Theme.danger : (item.severity == "warning" ? Theme.attention : Theme.textSecondary)
        VStack(alignment: .leading, spacing: 2) {
            Label(item.title, systemImage: item.severity == "error" ? "xmark.octagon" : "info.circle").font(.footnote.weight(.semibold))
            if let d = item.detail { Text(d).font(.caption) }
        }
        .foregroundStyle(color)
    }
}

// MARK: - Tool calls

struct ToolCard: View {
    let item: ToolItem
    var openFile: (String) -> Void
    @State private var open = false

    private var icon: String {
        switch item.kind {
        case "read": return "doc.text"
        case "edit": return "pencil"
        case "delete": return "trash"
        case "move": return "arrow.right.doc.on.clipboard"
        case "search": return "magnifyingglass"
        case "execute": return "terminal"
        case "think": return "brain"
        case "fetch": return "globe"
        case "switch_mode": return "arrow.triangle.swap"
        default: return "wrench.and.screwdriver"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation(Motion.disclose) { open.toggle() } } label: {
                HStack(spacing: 8) {
                    Image(systemName: icon).frame(width: 18).foregroundStyle(.secondary)
                    Text(item.title).font(.subheadline).lineLimit(open ? nil : 1).multilineTextAlignment(.leading)
                    Spacer(minLength: 4)
                    StatusBadge(status: item.status)
                    Image(systemName: "chevron.right").font(.caption2).rotationEffect(.degrees(open ? 90 : 0)).foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if !open, let d = item.firstDiff {
                DiffSummary(path: d.path, old: d.old, new: d.new)
            }

            if open {
                if let cmd = item.commandPreview {
                    Text(cmd).font(.caption.monospaced()).textSelection(.enabled)
                        .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 8))
                }
                ForEach(item.content) { c in
                    switch c {
                    case .diff(let p, let o, let n): DiffView(path: p, old: o, new: n, openFile: openFile)
                    case .text(let t): CodeText(text: t, maxLines: 40)
                    case .terminal(let id): Text("Terminal \(id)").font(.caption).foregroundStyle(.secondary)
                    case .image(let d): if let ui = UIImage(data: d) { Image(uiImage: ui).resizable().scaledToFit().frame(maxHeight: 240) }
                    }
                }
                if item.content.isEmpty, let out = item.rawOutput, !out.pretty.isEmpty {
                    CodeText(text: out.pretty, maxLines: 40)
                }
                ForEach(item.locations, id: \.self) { loc in
                    Button { openFile(loc) } label: {
                        Label(loc, systemImage: "doc").font(.caption).lineLimit(1).truncationMode(.head)
                    }
                }
            }
        }
        .padding(10)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct StatusBadge: View {
    let status: String
    var body: some View {
        switch status {
        case "completed": Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.caption)
        case "failed": Image(systemName: "xmark.circle.fill").foregroundStyle(.red).font(.caption)
        case "in_progress": ActivitySpinner(size: 11)
        case "pending": Image(systemName: "circle.dotted").foregroundStyle(.secondary).font(.caption)
        default: EmptyView()
        }
    }
}

struct CodeText: View {
    let text: String
    var maxLines = 200
    @State private var expanded = false
    var body: some View {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        let shown = expanded ? text : lines.prefix(maxLines).joined(separator: "\n")
        VStack(alignment: .leading, spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                Text(shown).font(Theme.monoSmall).foregroundStyle(Theme.textSecondary).textSelection(.enabled).fixedSize(horizontal: true, vertical: false)
                    .padding(10)
            }
            .background(Theme.bgSunken, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            if lines.count > maxLines && !expanded {
                Button("Show all \(lines.count) lines") { expanded = true }.font(.caption)
            }
        }
    }
}

// MARK: - Plan

struct PlanCard: View {
    let item: PlanItem
    @State private var open = true
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation(Motion.disclose) { open.toggle() } } label: {
                HStack {
                    Label("Plan", systemImage: "list.bullet.clipboard").font(.subheadline.weight(.semibold))
                    Spacer()
                    if !item.entries.isEmpty {
                        Text("\(item.entries.filter { $0.status == "completed" }.count)/\(item.entries.count)").font(.caption).foregroundStyle(.secondary)
                    }
                    Image(systemName: "chevron.right").font(.caption2).rotationEffect(.degrees(open ? 90 : 0))
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                ForEach(item.entries) { e in
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: e.status == "completed" ? "checkmark.circle.fill" : (e.status == "in_progress" ? "circle.dashed.inset.filled" : "circle"))
                            .foregroundStyle(e.status == "completed" ? Color.green : (e.status == "in_progress" ? Color.accentColor : Color.secondary))
                            .font(.footnote)
                        Text(e.content).font(.footnote).strikethrough(e.status == "completed", color: .secondary)
                    }
                }
                if let md = item.markdown { MarkdownView(text: md) }
            }
        }
        .padding(14)
        .background(Theme.bgRaised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }
}
