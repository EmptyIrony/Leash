import SwiftUI

/// Messages sent while the agent works, waiting on the bridge for the turn to end. Docked above the composer like
/// the approval tray: they are pending input, not transcript yet. Tap a row to take it back into the composer,
/// × to drop it (its row offers Undo for a few seconds); the queue-level button sends everything now (it all goes
/// out as one message).
struct QueueTray: View {
    let items: [QueuedMessage]
    let running: Bool
    /// An approval or question tray is showing: just the header line.
    var collapsed = false
    /// The conversation can take a message now (attached, agent up).
    var enabled = true
    /// The message just removed: it keeps its place as a "Removed · Undo" row until the undo window ends.
    var removed: RemovedQueued? = nil
    var edit: (QueuedMessage) -> Void
    var delete: (QueuedMessage) -> Void
    var sendNow: () -> Void
    var undo: () -> Void

    private let shown = 2

    private enum Row: Identifiable {
        case queued(QueuedMessage)
        case removed(QueuedMessage)

        /// The message's own id in both states, so the row swaps in place.
        var id: String {
            switch self {
            case .queued(let q), .removed(let q): return q.id
            }
        }
        var isQueued: Bool {
            if case .queued = self { return true }
            return false
        }
    }

    /// The queue as shown: the removed message stays where it was.
    private var rows: [Row] {
        var r = items.map { Row.queued($0) }
        if let removed {
            r.removeAll { $0.id == removed.message.id }
            r.insert(.removed(removed.message), at: min(removed.index, r.count))
        }
        return r
    }

    var body: some View {
        let visible = Array(rows.prefix(shown))
        let more = items.count - visible.filter(\.isQueued).count
        VStack(alignment: .leading, spacing: 4) {
            if !items.isEmpty { header }
            // with nothing left queued, the Undo row is all there is (no header to collapse to)
            if !collapsed || items.isEmpty {
                ForEach(visible) { r in
                    ZStack(alignment: .leading) {
                        switch r {
                        case .queued(let q): row(q).transition(Motion.swap)
                        case .removed: removedRow.transition(Motion.swap)
                        }
                    }
                    .transition(Motion.reveal)
                }
                if more > 0 {
                    Text("+\(more) more").font(.footnote).foregroundStyle(Theme.textSecondary)
                        .padding(.vertical, 4)
                }
            }
        }
        .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 8)
        .glassEffect(.regular, in: .rect(cornerRadius: Theme.Radius.tray))
        .animation(Motion.quick, value: running)
        .animation(Motion.disclose, value: collapsed)
    }

    private var header: some View {
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
    }

    /// What a removed message leaves in its place until the undo window ends.
    private var removedRow: some View {
        HStack(spacing: 8) {
            Text("Removed").foregroundStyle(Theme.textTertiary)
            Spacer(minLength: 4)
            Button(action: undo) {
                Text("Undo").fontWeight(.semibold).foregroundStyle(Theme.accent)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.pressable(scale: 1))
        }
        .font(.callout)
        .frame(minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Removed"))
        .accessibilityAction(named: Text("Undo"), undo)
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
            .buttonStyle(.pressable(scale: 0.98))
            .disabled(!enabled)
            .accessibilityHint("Moves it back to the message field to edit")
            Button { delete(q) } label: {
                Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(Theme.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.pressable)
            .disabled(!enabled)
            .accessibilityLabel("Remove queued message")
        }
        .contextMenu {
            Button { edit(q) } label: { Label("Edit", systemImage: "pencil") }
            Button(role: .destructive) { delete(q) } label: { Label("Delete", systemImage: "trash") }
        }
    }
}
