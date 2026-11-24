import SwiftUI

/// The commands a bare "/word" in the composer could start, docked above it like the other trays. Tapping one puts
/// "/name " in the field (it never sends: a command like /compact shouldn't go out on one tap, and a missing hint
/// doesn't mean it takes nothing). It shows only while the draft is that word, so typing on or deleting the slash
/// dismisses it.
struct CommandPalette: View {
    let items: [AgentCommand]
    /// Another tray is showing: room for fewer rows.
    var compact = false
    var pick: (AgentCommand) -> Void
    @ScaledMetric(relativeTo: .body) private var rowHeight: CGFloat = 52

    var body: some View {
        // a half row at the bottom says the list scrolls; fewer matches shrink it
        let rows = min(CGFloat(items.count), compact ? 2.5 : 4.5)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.element.id) { i, c in
                    row(c)
                        .overlay(alignment: .top) {
                            if i > 0 { Rectangle().fill(Theme.hairline).frame(height: 0.5).padding(.leading, 16) }
                        }
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(height: rows * rowHeight)
        .clipShape(.rect(cornerRadius: 22))
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
    }

    private func row(_ c: AgentCommand) -> some View {
        Button { pick(c) } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(verbatim: "/" + c.name).font(.body.weight(.semibold)).foregroundStyle(Theme.textPrimary)
                    if let h = c.hint, !h.isEmpty { Text(verbatim: h).font(.body).foregroundStyle(Theme.textTertiary) }
                }
                .lineLimit(1)
                if !c.description.isEmpty {
                    Text(verbatim: c.description).font(.footnote).foregroundStyle(Theme.textSecondary).lineLimit(1)
                }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: rowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(PaletteRowStyle())
        .accessibilityLabel(Text(verbatim: c.description.isEmpty ? c.name : c.name + ", " + c.description))
        .accessibilityHint(Text("Inserts the command"))
    }
}

/// A list row's pressed highlight (no scale: the row is part of a list, not a button on its own).
private struct PaletteRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? AnyShapeStyle(.quaternary) : AnyShapeStyle(.clear))
    }
}
