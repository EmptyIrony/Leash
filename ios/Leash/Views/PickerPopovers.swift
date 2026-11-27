import SwiftUI

// MARK: - Combined Model + Effort popover (compact detented slider card, expands in-place to model list)

struct ModelEffortPopover: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let modelOption: ConfigOption?
    let effortOption: ConfigOption?
    var agent: String = "agy"
    var onSelectModel: (String) -> Void
    var onSelectEffort: (String) -> Void

    @State private var showModels: Bool
    @State private var effortIndex: Int
    @State private var dragging = false

    init(
        modelOption: ConfigOption?,
        effortOption: ConfigOption?,
        agent: String = "agy",
        onSelectModel: @escaping (String) -> Void,
        onSelectEffort: @escaping (String) -> Void
    ) {
        self.modelOption = modelOption
        self.effortOption = effortOption
        self.agent = agent
        self.onSelectModel = onSelectModel
        self.onSelectEffort = onSelectEffort
        _showModels = State(initialValue: effortOption == nil)
        let idx = effortOption.flatMap { opt in opt.choices.firstIndex { $0.value == opt.current.string } } ?? 0
        _effortIndex = State(initialValue: idx)
    }

    var body: some View {
        Group {
            if showModels || effortOption == nil, let opt = modelOption {
                modelList(opt)
                    .transition(.opacity)
            } else if let opt = effortOption {
                effortCard(opt)
                    .transition(.opacity)
            }
        }
        .animation(Motion.layout, value: showModels)
        .onChange(of: effortOption?.current.string) { _, newValue in
            guard let opt = effortOption, !dragging else { return }
            effortIndex = opt.choices.firstIndex { $0.value == newValue } ?? 0
        }
        .onChange(of: effortOption?.choices.map(\.value)) { _, _ in
            guard let opt = effortOption else { return }
            if opt.choices.isEmpty {
                if modelOption != nil { showModels = true }
            } else {
                effortIndex = opt.choices.firstIndex { $0.value == opt.current.string } ?? 0
            }
        }
    }

    private func effortCard(_ opt: ConfigOption) -> some View {
        let levels = opt.choices
        let level = levels.indices.contains(effortIndex) ? levels[effortIndex] : nil
        let isElevated = levels.count > 1 && effortIndex > 0
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 8) {
                if let m = modelOption {
                    let p: Provider = agent == "claude" ? .anthropic : Provider.of(m.current.string)
                    Button {
                        withAnimation(Motion.layout) { showModels = true }
                        Haptics.selection()
                    } label: {
                        HStack(spacing: 6) {
                            ProviderIcon(provider: p, size: 16)
                            Text(verbatim: Self.shortModelName(m.currentName))
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Theme.textPrimary)
                                .lineLimit(1)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(Theme.textTertiary)
                        }
                        .padding(.vertical, 2)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.pressable)
                    .accessibilityLabel(Text("Switch model, \(m.currentName)"))
                } else {
                    Text("Thinking")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer(minLength: 8)
                Text(verbatim: level.map { EffortNames.name($0.value, fallback: $0.name) } ?? "")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(isElevated ? Theme.accent : Theme.textSecondary)
                    .contentTransition(.numericText())
            }
            DetentSlider(count: levels.count, index: $effortIndex, dragging: $dragging) { committed in
                if levels.indices.contains(committed) { onSelectEffort(levels[committed].value) }
            }
            .frame(height: 32)
        }
        .padding(14)
        .frame(width: 284)
        .animation(Motion.quick, value: effortIndex)
    }

    private func modelList(_ opt: ConfigOption) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let eff = effortOption {
                HStack {
                    Button {
                        withAnimation(Motion.layout) { showModels = false }
                        Haptics.selection()
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "chevron.left").font(.caption.weight(.bold))
                            Text(verbatim: EffortNames.name(eff.current.string ?? "", fallback: eff.currentName))
                                .font(.footnote.weight(.semibold))
                        }
                        .foregroundStyle(Theme.accent)
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.pressable)
                    Spacer()
                    Text("Model")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Theme.textSecondary)
                }
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .padding(.bottom, 8)
                Divider().overlay(Theme.hairline)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(groups(for: opt), id: \.provider) { g in
                        VStack(alignment: .leading, spacing: 4) {
                            header(g.provider, option: opt)
                            ForEach(g.choices) { c in row(c, option: opt, provider: g.provider) }
                        }
                    }
                }
                .padding(14)
            }
        }
        .frame(minWidth: 280, idealWidth: 300, maxHeight: 460)
    }

    private func provider(for c: ConfigChoice) -> Provider {
        agent == "claude" ? .anthropic : Provider.of(c.value)
    }

    private func groups(for opt: ConfigOption) -> [(provider: Provider, choices: [ConfigChoice])] {
        let order: [Provider] = [.gemini, .anthropic, .openai, .other]
        let byProvider = Dictionary(grouping: opt.choices) { provider(for: $0) }
        return order.compactMap { p in byProvider[p].map { (p, $0) } }
    }

    private func header(_ p: Provider, option: ConfigOption) -> some View {
        HStack(spacing: 8) {
            ProviderIcon(provider: p, size: 15)
            Text(verbatim: p.displayName).font(.footnote.weight(.semibold)).foregroundStyle(Theme.textSecondary)
            Spacer()
            if let lim = model.quotas[agent]?.limiting(forModel: agent == "claude" ? option.current.string : p.quotaModelHint, agent: agent), let f = lim.bucket.fraction {
                let b = lim.bucket
                HStack(spacing: 4) {
                    RingGauge(fraction: f, size: 11, lineWidth: 1.6, tint: f < 0.4 ? quotaTint(f) : Theme.accent)
                    Text(verbatim: "\(b.shortLabel) \(Int((f * 100).rounded()))%").font(.caption2.monospacedDigit())
                        .foregroundStyle(f < 0.4 ? quotaTint(f) : Theme.textTertiary)
                }
            }
        }
        .padding(.horizontal, 6).padding(.bottom, 2)
    }

    private func row(_ c: ConfigChoice, option: ConfigOption, provider: Provider) -> some View {
        let selected = c.value == option.current.string
        return Button {
            if !selected {
                onSelectModel(c.value)
            }
            if effortOption != nil && !selected {
                withAnimation(Motion.layout) { showModels = false }
            } else {
                dismiss()
            }
        } label: {
            HStack(spacing: 10) {
                ProviderIcon(provider: provider, size: 18)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: c.name).font(.body.weight(selected ? .semibold : .regular)).foregroundStyle(Theme.textPrimary)
                    if let d = c.description { Text(verbatim: d).font(.caption).foregroundStyle(Theme.textTertiary).lineLimit(2) }
                }
                Spacer(minLength: 8)
                if selected { Image(systemName: "checkmark").font(.footnote.weight(.bold)).foregroundStyle(Theme.accent) }
            }
            .padding(.horizontal, 10).padding(.vertical, 9)
            .background(selected ? Theme.accent.opacity(0.10) : Color.clear, in: RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous))
        }
        .buttonStyle(.pressable(scale: 0.98))
    }

    static func shortModelName(_ n: String) -> String {
        n.replacingOccurrences(of: "Gemini 4 ", with: "")
            .replacingOccurrences(of: "Claude ", with: "")
            .replacingOccurrences(of: "Gemini ", with: "")
    }
}

/// Chunky capsule track with discrete detents and a draggable knob. Haptic tick on every detent.
struct DetentSlider: View {
    let count: Int
    @Binding var index: Int
    @Binding var dragging: Bool
    var onCommit: (Int) -> Void

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let trackH: CGFloat = 32
            let knob: CGFloat = 26
            let usable = max(1, w - trackH)
            let step = count > 1 ? usable / CGFloat(count - 1) : 0
            let x = trackH / 2 + CGFloat(index) * step
            let fillWidth = min(w, max(trackH, x + trackH / 2))
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Theme.hairline)
                    .frame(height: trackH)
                Capsule()
                    .fill(LinearGradient(colors: [Theme.accent.opacity(0.65), Theme.accent], startPoint: .leading, endPoint: .trailing))
                    .frame(width: fillWidth, height: trackH)
                ForEach(0..<max(count, 1), id: \.self) { i in
                    if i != index {
                        Circle()
                            .fill(i < index ? Color.white.opacity(0.4) : Theme.textTertiary.opacity(0.55))
                            .frame(width: 5, height: 5)
                            .position(x: trackH / 2 + CGFloat(i) * step, y: geo.size.height / 2)
                    }
                }
                Circle()
                    .fill(Color.white)
                    .shadow(color: .black.opacity(0.22), radius: 4, y: 1)
                    .frame(width: knob, height: knob)
                    .scaleEffect(dragging ? 1.08 : 1)
                    .position(x: x, y: geo.size.height / 2)
            }
            .contentShape(Capsule())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        dragging = true
                        let i = Int(((g.location.x - trackH / 2) / max(step, 1)).rounded())
                        let clamped = min(max(i, 0), max(0, count - 1))
                        if clamped != index { index = clamped }
                    }
                    .onEnded { _ in dragging = false; onCommit(index) }
            )
        }
        .haptic(.selection, trigger: index)
        .haptic(.impact(weight: .light), trigger: dragging) { was, now in was && !now }
        .accessibilityElement()
        .accessibilityValue(Text(verbatim: "\(index + 1)/\(count)"))
        .accessibilityAdjustableAction { dir in
            switch dir {
            case .increment: if index < count - 1 { index += 1; onCommit(index) }
            case .decrement: if index > 0 { index -= 1; onCommit(index) }
            @unknown default: break
            }
        }
    }
}
