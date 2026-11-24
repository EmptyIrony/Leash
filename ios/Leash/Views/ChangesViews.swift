import SwiftUI

/// "+12 −3" (the signs carry it, not only the colours).
struct ChangeCounts: View {
    let adds: Int
    let dels: Int
    var body: some View {
        HStack(spacing: 6) {
            Text(verbatim: "+\(adds)").foregroundStyle(Theme.success)
            Text(verbatim: "−\(dels)").foregroundStyle(Theme.danger)
        }
        .font(.footnote.monospacedDigit())
    }
}

/// Under a finished turn's answer: what it changed, opening the per-file diffs. Transcript content (no glass),
/// in the diff boxes' sunken look.
struct ChangesRow: View {
    let changes: TurnChanges
    var open: () -> Void

    var body: some View {
        let n = changes.files.count
        Button(action: open) {
            HStack(spacing: 10) {
                Image(systemName: "plus.forwardslash.minus").foregroundStyle(Theme.textSecondary)
                Group { n == 1 ? Text("1 file changed") : Text("\(n) files changed") }
                    .font(.subheadline)
                    .foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 8)
                if changes.adds + changes.dels > 0 { ChangeCounts(adds: changes.adds, dels: changes.dels) }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(Theme.bgSunken, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.pressable(scale: 0.98))
        .accessibilityLabel(Text("\(n) files changed, \(changes.adds) additions, \(changes.dels) deletions"))
    }
}

/// A turn's changes file by file, each opening to its diffs. A sheet, so an approval arriving meanwhile still has the
/// composer under it. Commit (only on the newest turn that changed files) asks the agent in the conversation; its
/// own approval still gates the real git commit.
struct ChangesSheet: View {
    @Environment(\.dismiss) private var dismiss
    let changes: TurnChanges
    let cwd: String?
    let agent: String
    let sessionId: String
    /// nil: not offered
    var commit: (() -> Void)?
    var commitEnabled = true
    @State private var expanded: Set<String> = []
    @State private var fileToOpen: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(changes.files) { f in
                    DisclosureGroup(isExpanded: isExpanded(f.path)) {
                        diffs(f)
                    } label: {
                        label(f)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Changes")
            .navigationSubtitle(subtitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(role: .close) { dismiss() } }
                if let commit {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Commit") { dismiss(); commit() }
                            .disabled(!commitEnabled)
                    }
                }
            }
            .sheet(item: fileSheet) { f in FileViewer(path: f.path, agent: agent, sessionId: sessionId) }
        }
        .onAppear { if changes.files.count <= 3 { expanded = Set(changes.files.map(\.path)) } }
    }

    private var subtitle: String {
        let n = changes.files.count
        let files = n == 1 ? String(localized: "1 file") : String(localized: "\(n) files")
        return files + " · +\(changes.adds) −\(changes.dels)"
    }

    private func isExpanded(_ path: String) -> Binding<Bool> {
        Binding(get: { expanded.contains(path) }, set: { open in
            withAnimation(Motion.disclose) { if open { expanded.insert(path) } else { expanded.remove(path) } }
        })
    }

    private var fileSheet: Binding<IdentifiedPath?> {
        Binding(get: { fileToOpen.map { IdentifiedPath(path: $0) } }, set: { fileToOpen = $0?.path })
    }

    private func label(_ f: TurnChanges.File) -> some View {
        let rel = f.relative(to: cwd)
        let dir = (rel as NSString).deletingLastPathComponent
        return HStack(spacing: 10) {
            if f.deleted {
                Image(systemName: "trash").foregroundStyle(Theme.danger).accessibilityLabel(Text("Deleted"))
            } else if f.created {
                Image(systemName: "doc.badge.plus").foregroundStyle(Theme.success).accessibilityLabel(Text("New file"))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: (rel as NSString).lastPathComponent).font(.body).foregroundStyle(Theme.textPrimary).lineLimit(1)
                if !dir.isEmpty {
                    Text(verbatim: dir).font(.footnote).foregroundStyle(Theme.textSecondary).lineLimit(1).truncationMode(.head)
                }
            }
            Spacer(minLength: 8)
            if f.adds + f.dels > 0 { ChangeCounts(adds: f.adds, dels: f.dels) }
        }
    }

    @ViewBuilder private func diffs(_ f: TurnChanges.File) -> some View {
        let blocks = f.tools.flatMap(\.diffs)
        if blocks.isEmpty {
            (f.deleted ? Text("Deleted") : Text("No diff for this change")).font(.footnote).foregroundStyle(Theme.textSecondary)
        } else {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, d in
                DiffView(path: d.path, old: d.old, new: d.new, openFile: { fileToOpen = $0 })
                    // its buttons take only their own taps, not the whole list row's
                    .buttonStyle(.borderless)
                    .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
            }
        }
    }
}
