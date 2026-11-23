import SwiftUI

/// Messages sent while the agent works, waiting on the bridge for the turn to end. Docked above the composer like
/// the approval tray: they are pending input, not transcript yet. Tap a row to take it back into the composer,
/// × to drop it; the queue-level button sends everything now (it all goes out as one message).
struct QueueTray: View {
    let items: [QueuedMessage]
    let running: Bool
    /// An approval or question tray is showing: just the header line.
    var collapsed = false
    /// The conversation can take a message now (attached, agent up).
    var enabled = true
    var edit: (QueuedMessage) -> Void
    var delete: (QueuedMessage) -> Void
    var sendNow: () -> Void

    private let shown = 2

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "clock").foregroundStyle(Theme.textSecondary)
                Text("\(items.count) queued").font(.subheadline.weight(.semibold))
                    .contentTransition(.numericText())
                Spacer(minLength: 4)
                Button(action: sendNow) {
                    (running ? Text("Interrupt and Send") : Text("Send Now")).bold().lineLimit(1)
                        .id(running)
                        .transition(Motion.swap)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(!enabled)
            }
            .frame(minHeight: 36)
            if !collapsed {
                ForEach(items.prefix(shown)) { q in row(q).transition(Motion.reveal) }
                if items.count > shown {
                    Text("+\(items.count - shown) more").font(.footnote).foregroundStyle(Theme.textSecondary)
                        .padding(.vertical, 4)
                }
            }
        }
        .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 8)
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .animation(Motion.quick, value: running)
        .animation(Motion.disclose, value: collapsed)
    }

    private func row(_ q: QueuedMessage) -> some View {
        HStack(spacing: 4) {
            Button { edit(q) } label: {
                HStack(spacing: 6) {
                    if q.imageCount > 0 { Image(systemName: "photo").foregroundStyle(Theme.textSecondary) }
                    if q.fileCount > 0 { Image(systemName: "doc").foregroundStyle(Theme.textSecondary) }
                    Text(q.text.isEmpty ? (q.imageCount == 0 && q.fileCount > 0 ? String(localized: "File") : String(localized: "Photo")) : q.text)
                        .lineLimit(1).truncationMode(.tail)
                        .foregroundStyle(Theme.textPrimary)
                    Spacer(minLength: 0)
                }
                .font(.callout)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .accessibilityHint("Moves it back to the message field to edit")
            Button { delete(q) } label: {
                Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(Theme.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .accessibilityLabel("Remove queued message")
        }
        .contextMenu {
            Button { edit(q) } label: { Label("Edit", systemImage: "pencil") }
            Button(role: .destructive) { delete(q) } label: { Label("Delete", systemImage: "trash") }
        }
    }
}
