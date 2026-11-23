import SwiftUI

// MARK: - Model picker (grouped by provider, quota in the section header)

struct ModelPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let option: ConfigOption
    var agent: String = "agy"
    var onSelect: (String) -> Void

    private func provider(_ c: ConfigChoice) -> Provider { agent == "claude" ? .anthropic : Provider.of(c.value) }

    private var groups: [(provider: Provider, choices: [ConfigChoice])] {
        let order: [Provider] = [.gemini, .anthropic, .openai, .other]
        let byProvider = Dictionary(grouping: option.choices) { provider($0) }
        return order.compactMap { p in byProvider[p].map { (p, $0) } }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(groups, id: \.provider) { g in
                    VStack(alignment: .leading, spacing: 4) {
                        header(g.provider)
                        ForEach(g.choices) { c in row(c, provider: g.provider) }
                    }
                }
            }
            .padding(14)
        }
        .frame(minWidth: 280, idealWidth: 300, maxHeight: 460)
    }

    private func header(_ p: Provider) -> some View {
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

    private func row(_ c: ConfigChoice, provider: Provider) -> some View {
        let selected = c.value == option.current.string
        return Button {
            onSelect(c.value)
            dismiss()
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
            .background(selected ? Theme.accent.opacity(0.10) : Color.clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Effort: a detented slider, ChatGPT-style

struct EffortPopover: View {
    let option: ConfigOption
    var onSelect: (String) -> Void
    @State private var index: Int
    @State private var dragging = false

    init(option: ConfigOption, onSelect: @escaping (String) -> Void) {
        self.option = option
        self.onSelect = onSelect
        // start on the current level without firing a haptic tick
        _index = State(initialValue: option.choices.firstIndex { $0.value == option.current.string } ?? 0)
    }

    private var levels: [ConfigChoice] { option.choices }

    var body: some View {
        let level = levels.indices.contains(index) ? levels[index] : nil
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("Thinking").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(verbatim: level.map { EffortNames.name($0.value, fallback: $0.name) } ?? "")
                    .font(.headline)
                    .contentTransition(.numericText())
            }
            DetentSlider(count: levels.count, index: $index, dragging: $dragging) { committed in
                if levels.indices.contains(committed) { onSelect(levels[committed].value) }
            }
            .frame(height: 34)
            HStack {
                Text(verbatim: levels.first.map { EffortNames.name($0.value, fallback: $0.name) } ?? "")
                Spacer()
                Text(verbatim: levels.last.map { EffortNames.name($0.value, fallback: $0.name) } ?? "")
            }
            .font(.caption2).foregroundStyle(Theme.textTertiary)
            Text(verbatim: EffortNames.blurb(level?.value ?? ""))
                .font(.footnote).foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
        }
        .padding(16)
        .frame(width: 290)
        .animation(Motion.quick, value: index)
    }
}

/// Track with evenly spaced detents and a draggable knob. Haptic tick on every detent.
struct DetentSlider: View {
    let count: Int
    @Binding var index: Int
    @Binding var dragging: Bool
    var onCommit: (Int) -> Void

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let knob: CGFloat = 26
            let usable = max(1, w - knob)
            let step = count > 1 ? usable / CGFloat(count - 1) : 0
            let x = knob / 2 + CGFloat(index) * step
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.hairline).frame(height: 6)
                Capsule()
                    .fill(LinearGradient(colors: [Theme.accent.opacity(0.55), Theme.accent], startPoint: .leading, endPoint: .trailing))
                    .frame(width: x, height: 6)
                ForEach(0..<max(count, 1), id: \.self) { i in
                    Circle()
                        .fill(i <= index ? Color.white.opacity(0.9) : Theme.textTertiary.opacity(0.6))
                        .frame(width: 5, height: 5)
                        .position(x: knob / 2 + CGFloat(i) * step, y: geo.size.height / 2)
                }
                Circle()
                    .fill(Color.white)
                    .shadow(color: .black.opacity(0.18), radius: 4, y: 1)
                    .overlay(Circle().stroke(Theme.accent.opacity(0.35), lineWidth: 1))
                    .frame(width: knob, height: knob)
                    .scaleEffect(dragging ? 1.12 : 1)
                    .position(x: x, y: geo.size.height / 2)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        dragging = true
                        let i = Int(((g.location.x - knob / 2) / max(step, 1)).rounded())
                        let clamped = min(max(i, 0), count - 1)
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
