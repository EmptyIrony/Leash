import SwiftUI

/// Permission request docked above the composer. Untinted glass (HIG: tint only key actions); the "needs you"
/// colour lives in the header symbol, and Allow is the one prominent button.
/// The glass shell stays in place while consecutive requests replace each other inside it (`RequestSwap`).
struct ApprovalTray: View {
    let request: PendingRequest
    var index = 1
    var total = 1
    var openFile: (String) -> Void

    struct PermOption: Identifiable, Hashable { let id: String; let name: String; let kind: String }

    private var tool: ToolItem {
        let j = request.params["toolCall"] ?? .null
        let t = ToolItem(toolCallId: j["toolCallId"]?.string ?? "?")
        t.apply(j)
        return t
    }

    var body: some View {
        let t = tool
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "hand.raised.fill").foregroundStyle(Theme.attention)
                RequestSwap(current: request.key) {
                    Text(t.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                        .swapped(as: request.key, with: Motion.swap)
                }
                Spacer(minLength: 4)
                RequestCounter(index: index, total: total)
            }
            RequestSwap(current: request.key) {
                ApprovalRequestBody(request: request, tool: t, openFile: openFile)
                    .swapped(as: request.key, with: Motion.tray)
            }
        }
        .requestTrayShell(key: request.key)
    }
}

/// One permission request's details and buttons; its state (the diff sheet) belongs to that request only.
private struct ApprovalRequestBody: View {
    @Environment(AppModel.self) private var model
    let request: PendingRequest
    let tool: ToolItem
    let openFile: (String) -> Void
    @State private var showDiff = false

    private var options: [ApprovalTray.PermOption] {
        (request.params["options"]?.array ?? []).compactMap { o in
            guard let id = o["optionId"]?.string else { return nil }
            return ApprovalTray.PermOption(id: id, name: o["name"]?.string ?? id, kind: o["kind"]?.string ?? "")
        }
    }

    var body: some View {
        let t = tool
        let opts = options
        VStack(alignment: .leading, spacing: 10) {
            if let cmd = t.commandPreview, cmd != t.title || t.kind == "execute" {
                Text(cmd).font(Theme.mono).lineLimit(4).textSelection(.enabled)
                    .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.bgSunken, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            if let d = t.firstDiff {
                Button { showDiff = true } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.badge.ellipsis")
                        Text((d.path as NSString).lastPathComponent).font(Theme.mono)
                        Text("+\(t.diffAdds)").foregroundStyle(Theme.success).font(Theme.monoSmall)
                        Text("−\(t.diffDels)").foregroundStyle(Theme.danger).font(Theme.monoSmall)
                        Text("· View diff").foregroundStyle(Theme.accent).font(.footnote)
                    }
                }
                .buttonStyle(.plain)
            }
            // side by side when they fit, stacked (prominent first) at large text sizes
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { buttons(opts, prominentFirst: false) }
                VStack(spacing: 8) { buttons(opts, prominentFirst: true) }
            }
            .controlSize(.large)
        }
        .sheet(isPresented: $showDiff) {
            if let d = t.firstDiff {
                NavigationStack {
                    ScrollView { DiffView(path: d.path, old: d.old, new: d.new, openFile: openFile).padding() }
                        .navigationTitle((d.path as NSString).lastPathComponent)
                        .navigationBarTitleDisplayMode(.inline)
                }
                .presentationDetents([.medium, .large])
            }
        }
    }

    @ViewBuilder private func buttons(_ opts: [ApprovalTray.PermOption], prominentFirst: Bool) -> some View {
        let deny = opts.filter { !$0.kind.hasPrefix("allow") }
        let always = opts.filter { $0.kind == "allow_always" }
        let allow = opts.filter { $0.kind.hasPrefix("allow") && $0.kind != "allow_always" }
        if prominentFirst { allowButtons(allow) }
        ForEach(deny) { o in
            Button(role: .destructive) { model.answerPermission(request, optionId: o.id, kind: o.kind) } label: { Text(PermissionNames.name(o)).lineLimit(1).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
        }
        ForEach(always) { o in
            Button { model.answerPermission(request, optionId: o.id, kind: o.kind) } label: { Text(PermissionNames.name(o)).lineLimit(1).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
        }
        if !prominentFirst { allowButtons(allow) }
    }

    private func allowButtons(_ allow: [ApprovalTray.PermOption]) -> some View {
        ForEach(allow) { o in
            Button { model.answerPermission(request, optionId: o.id, kind: o.kind) } label: { Text(PermissionNames.name(o)).bold().lineLimit(1).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
        }
    }
}

// MARK: - Request swapping

/// Overlays the content of the request being replaced and the next one's, sized by the current request's content
/// alone: the outgoing content blurs out over the incoming one instead of pushing the tray open (as a VStack or
/// ZStack would until the removal ends, then snap), and the tray's height animates from one to the other.
private struct RequestSwap: Layout {
    let current: String

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let live = live(subviews) else { return .zero }
        return live.sizeThatFits(proposal)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let live = live(subviews)
        for s in subviews {
            let p = s == live ? ProposedViewSize(bounds.size) : ProposedViewSize(width: bounds.width, height: proposal.height)
            s.place(at: bounds.origin, anchor: .topLeading, proposal: p)
        }
    }

    private func live(_ subviews: Subviews) -> LayoutSubview? {
        subviews.last(where: { $0[RequestKey.self] == current }) ?? subviews.last
    }
}

private struct RequestKey: LayoutValueKey {
    static var defaultValue: String? { nil }
}

private extension View {
    /// Content belonging to one request inside a `RequestSwap`: a new request replaces it with `transition`.
    func swapped(as key: String, with transition: AnyTransition) -> some View {
        layoutValue(key: RequestKey.self, value: key).id(key).transition(transition)
    }

    /// The tray's glass, which stays put across requests; its contents are clipped to it so outgoing content
    /// never spills past the shrinking glass.
    func requestTrayShell(key: String) -> some View {
        padding(14)
            .clipShape(.rect(cornerRadius: 22))
            .glassEffect(.regular, in: .rect(cornerRadius: 22))
            .animation(Motion.layout, value: key)
    }
}

/// "1 of 3", counting down as requests are answered.
private struct RequestCounter: View {
    let index: Int
    let total: Int

    var body: some View {
        Group {
            if total > 1 {
                Text("\(index) of \(total)").font(.caption.monospacedDigit()).foregroundStyle(Theme.textSecondary)
                    .contentTransition(.numericText(countsDown: true))
                    .transition(.opacity)
            }
        }
        .animation(Motion.quick, value: total)
    }
}

enum PermissionNames {
    /// agy's option names are English; use our localized wording for the standard kinds.
    static func name(_ o: ApprovalTray.PermOption) -> String {
        switch o.kind {
        case "allow_once": return String(localized: "Allow")
        case "allow_always": return String(localized: "Always allow")
        case "reject_once": return String(localized: "Deny")
        case "reject_always": return String(localized: "Always deny")
        default: return o.name
        }
    }
}

/// An ACP form elicitation (Claude's AskUserQuestion, agy's ask_question, MCP forms), parsed once.
struct ElicitationSpec {
    struct Choice: Identifiable, Hashable { let value: String; let title: String; let detail: String?; var id: String { value } }
    enum Kind { case single([Choice]), multi([Choice]), text, number, bool }
    struct Field: Identifiable {
        let name: String
        let title: String
        let detail: String?
        let kind: Kind
        let defaultValue: JSON?
        /// "question_0_custom" is question_0's free-text "Other" answer (claude-agent-acp's convention).
        let customFor: String?
        var id: String { name }
    }

    let fields: [Field]
    let message: String?
    let url: URL?

    init(_ request: PendingRequest) {
        let p = request.params
        message = p["message"]?.string
        url = p["mode"]?.string == "url" ? p["url"]?.string.flatMap { URL(string: $0) } : nil
        let schema = p["requestedSchema"] ?? .null
        let props = schema["properties"]?.object ?? [:]
        let req = Set((schema["required"]?.array ?? []).compactMap { $0.string })
        let names = props.keys.sorted { a, b in
            let ra = req.contains(a), rb = req.contains(b)
            return ra != rb ? ra : a.localizedStandardCompare(b) == .orderedAscending
        }
        fields = names.map { name -> Field in
            let f = props[name] ?? .null
            let kind: Kind
            switch f["type"]?.string {
            case "boolean": kind = .bool
            case "number", "integer": kind = .number
            case "array": kind = .multi(Self.choices(f["items"] ?? .null))
            default:
                let c = Self.choices(f)
                kind = c.isEmpty ? .text : .single(c)
            }
            let base = name.hasSuffix("_custom") ? String(name.dropLast("_custom".count)) : nil
            return Field(name: name, title: f["title"]?.string ?? name, detail: f["description"]?.string, kind: kind,
                         defaultValue: f["default"], customFor: base.flatMap { props[$0] != nil ? $0 : nil })
        }
    }

    private static func choices(_ p: JSON) -> [Choice] {
        if let one = p["oneOf"]?.array ?? p["anyOf"]?.array {
            return one.compactMap { o in
                guard let v = o["const"]?.string else { return nil }
                return Choice(value: v, title: o["title"]?.string ?? v, detail: o["description"]?.string)
            }
        }
        return (p["enum"]?.array ?? []).compactMap { j in j.string.map { Choice(value: $0, title: $0, detail: nil) } }
    }

    var questions: [Field] { fields.filter { $0.customFor == nil } }
    func custom(for f: Field) -> Field? { fields.first { $0.customFor == f.name } }

    /// One pick-from-a-few question without typing fits the tray; anything bigger opens a sheet (HIG: keep inline
    /// UI short, use a sheet for multi-step input and the keyboard).
    var fitsTray: Bool {
        guard url == nil, questions.count == 1, let q = questions.first else { return url != nil }
        switch q.kind {
        case .single(let c), .multi(let c): return c.count <= 4
        default: return false
        }
    }
}

/// Answers being filled in, shared by the tray and the sheet.
@Observable
final class ElicitationAnswers {
    var values: [String: JSON] = [:]
    var multi: [String: Set<String>] = [:]

    func isSelected(_ f: ElicitationSpec.Field, _ c: ElicitationSpec.Choice) -> Bool {
        if case .multi = f.kind { return multi[f.name, default: []].contains(c.value) }
        return (values[f.name]?.string ?? f.defaultValue?.string) == c.value
    }

    func toggle(_ f: ElicitationSpec.Field, _ c: ElicitationSpec.Choice) {
        if case .multi = f.kind {
            var s = multi[f.name, default: []]
            if s.contains(c.value) { s.remove(c.value) } else { s.insert(c.value) }
            multi[f.name] = s
        } else {
            values[f.name] = isSelected(f, c) ? nil : .string(c.value)
        }
    }

    var hasAnswer: Bool { values.values.contains { !$0.isNull && $0.string != "" } || multi.values.contains { !$0.isEmpty } }

    func content(_ spec: ElicitationSpec) -> [String: JSON] {
        var content = values.filter { !$0.value.isNull && $0.value.string != "" }
        for (k, s) in multi where !s.isEmpty { content[k] = .array(s.sorted().map { .string($0) }) }
        for f in spec.fields where content[f.name] == nil {
            if let d = f.defaultValue, !d.isNull { content[f.name] = d }
        }
        return content
    }
}

/// A question from the agent, docked above the composer in the same glass container as the composer.
/// Like `ApprovalTray`, the glass stays put while the next question replaces this one inside it.
struct QuestionTray: View {
    let request: PendingRequest
    var index = 1
    var total = 1

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "questionmark.bubble.fill").foregroundStyle(Theme.accent)
                Text("Agent asks").font(.subheadline.weight(.semibold))
                Spacer(minLength: 4)
                RequestCounter(index: index, total: total)
            }
            RequestSwap(current: request.key) {
                QuestionRequestBody(request: request)
                    .swapped(as: request.key, with: Motion.tray)
            }
        }
        .requestTrayShell(key: request.key)
    }
}

/// One question's content and actions; the answers and the sheet belong to that question only.
private struct QuestionRequestBody: View {
    @Environment(AppModel.self) private var model
    let request: PendingRequest
    @State private var answers = ElicitationAnswers()
    @State private var showSheet = false

    var body: some View {
        let spec = ElicitationSpec(request)
        VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .vertical) {
                content(spec)
                ScrollView { content(spec) }.scrollBounceBehavior(.basedOnSize)
            }
            .frame(maxHeight: 360)
            actions(spec)
        }
        .sheet(isPresented: $showSheet) {
            QuestionSheet(spec: spec, answers: answers, submit: { submit(spec) }, skip: skip)
        }
    }

    @ViewBuilder private func content(_ spec: ElicitationSpec) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if let msg = spec.message { Text(MarkdownView.inline(msg)).font(.body).fixedSize(horizontal: false, vertical: true) }
            if let url = spec.url {
                Link(destination: url) { Label(url.absoluteString, systemImage: "safari").lineLimit(1).truncationMode(.middle) }.font(.footnote)
            } else if spec.fitsTray, let q = spec.questions.first {
                if case .single(let choices) = q.kind { options(q, choices) }
                if case .multi(let choices) = q.kind { options(q, choices) }
                if spec.custom(for: q) != nil {
                    Button { showSheet = true } label: {
                        Label("Other answer…", systemImage: "square.and.pencil").font(.subheadline)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.accent)
                    .padding(.horizontal, 4)
                }
            } else {
                let n = spec.questions.count
                Text(n == 1 ? String(localized: "1 question") : String(localized: "\(n) questions"))
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private func options(_ q: ElicitationSpec.Field, _ choices: [ElicitationSpec.Choice]) -> some View {
        VStack(spacing: 6) {
            ForEach(choices) { c in
                OptionRow(choice: c, multi: { if case .multi = q.kind { return true }; return false }(), selected: answers.isSelected(q, c)) {
                    withAnimation(Motion.quick) { answers.toggle(q, c) }
                }
            }
        }
    }

    @ViewBuilder private func actions(_ spec: ElicitationSpec) -> some View {
        HStack(spacing: 8) {
            if spec.url != nil {
                Button(role: .destructive) { model.answerElicitation(request, action: "decline") } label: { Text("Decline").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
                Button { model.answerElicitation(request, action: "accept") } label: { Text("Done").bold().frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
            } else {
                Button(action: skip) { Text("Skip").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                if spec.fitsTray {
                    Button { submit(spec) } label: { Text("Submit").bold().frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                        .disabled(!answers.hasAnswer)
                } else {
                    Button { showSheet = true } label: { Text("Answer…").bold().frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                }
            }
        }
        .controlSize(.large)
    }

    private func submit(_ spec: ElicitationSpec) {
        showSheet = false
        model.answerElicitation(request, action: "accept", content: answers.content(spec))
    }

    private func skip() {
        showSheet = false
        model.answerElicitation(request, action: "decline")
    }
}

/// One choice: radio / checkbox symbol (selection never shown by colour alone), title, optional description.
struct OptionRow: View {
    let choice: ElicitationSpec.Choice
    let multi: Bool
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Image(systemName: multi ? (selected ? "checkmark.square.fill" : "square") : (selected ? "checkmark.circle.fill" : "circle"))
                    .foregroundStyle(selected ? Theme.accent : Theme.textTertiary)
                    .contentTransition(.symbolEffect(.replace))
                VStack(alignment: .leading, spacing: 2) {
                    Text(choice.title).font(.body).foregroundStyle(Theme.textPrimary)
                    if let d = choice.detail, !d.isEmpty { Text(d).font(.footnote).foregroundStyle(Theme.textSecondary) }
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .frame(minHeight: 44)
            .background(selected ? Theme.accent.opacity(0.12) : Theme.textPrimary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .haptic(.selection, trigger: selected)
    }
}

/// Every question at once, with the keyboard for "Other" and free-text fields.
struct QuestionSheet: View {
    let spec: ElicitationSpec
    @Bindable var answers: ElicitationAnswers
    let submit: () -> Void
    let skip: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                if let msg = spec.message { Section { Text(MarkdownView.inline(msg)).font(.body) } }
                ForEach(spec.questions) { q in
                    Section {
                        field(q)
                        if let c = spec.custom(for: q) {
                            TextField("Other answer (optional)", text: text(c), axis: .vertical)
                        }
                    } header: {
                        Text(q.title)
                    } footer: {
                        if let d = q.detail, spec.questions.count > 1 { Text(d) }
                    }
                }
            }
            .navigationTitle("Agent asks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Skip", action: skip) }
                ToolbarItem(placement: .confirmationAction) { Button("Submit", action: submit).bold() }
            }
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder private func field(_ f: ElicitationSpec.Field) -> some View {
        switch f.kind {
        case .single(let choices), .multi(let choices):
            let multi: Bool = { if case .multi = f.kind { return true }; return false }()
            ForEach(choices) { c in
                Button { withAnimation(Motion.quick) { answers.toggle(f, c) } } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Image(systemName: multi ? (answers.isSelected(f, c) ? "checkmark.square.fill" : "square") : (answers.isSelected(f, c) ? "checkmark.circle.fill" : "circle"))
                            .foregroundStyle(answers.isSelected(f, c) ? Theme.accent : Theme.textTertiary)
                            .contentTransition(.symbolEffect(.replace))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.title).foregroundStyle(Theme.textPrimary)
                            if let d = c.detail, !d.isEmpty { Text(d).font(.footnote).foregroundStyle(Theme.textSecondary) }
                        }
                    }
                }
                .accessibilityAddTraits(answers.isSelected(f, c) ? .isSelected : [])
                .haptic(.selection, trigger: answers.isSelected(f, c))
            }
        case .bool:
            Toggle(f.title, isOn: Binding(get: { answers.values[f.name]?.bool ?? f.defaultValue?.bool ?? false }, set: { answers.values[f.name] = .bool($0) }))
        case .number:
            TextField(f.title, text: Binding(get: { answers.values[f.name]?.double.map { String($0) } ?? "" }, set: { answers.values[f.name] = Double($0).map { .number($0) } ?? .null }))
                .keyboardType(.decimalPad)
        case .text:
            TextField(f.detail ?? f.title, text: text(f), axis: .vertical)
        }
    }

    private func text(_ f: ElicitationSpec.Field) -> Binding<String> {
        Binding(get: { answers.values[f.name]?.string ?? f.defaultValue?.string ?? "" }, set: { answers.values[f.name] = .string($0) })
    }
}
