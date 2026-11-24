import SwiftUI

/// /btw answers, docked above the composer like the approval tray: a side question about the conversation that never
/// enters it. One card at a time ("1 of 2"); closing it shows the next. An answer taller than the card opens in a sheet.
struct SideQuestionTray: View {
    let item: SideQuestion
    var index = 1
    var total = 1
    /// An approval or question tray is showing: just the header line.
    var collapsed = false
    var close: () -> Void

    @State private var showFull = false
    @State private var answerHeight: CGFloat = 0
    private let cap: CGFloat = 176
    private var overflows: Bool { answerHeight > cap + 1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            if !collapsed { content.transition(Motion.reveal) }
        }
        .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 8)
        .glassEffect(.regular, in: .rect(cornerRadius: Theme.Radius.tray))
        .animation(Motion.disclose, value: collapsed)
        .animation(Motion.quick, value: item.pending)
        .sheet(isPresented: $showFull) { SideAnswerSheet(item: item) }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "bubble.left.and.bubble.right").foregroundStyle(Theme.accent)
                .accessibilityHidden(true)
            Text(item.question).font(.subheadline.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                .accessibilityLabel(Text("Side question: \(item.question)"))
            Spacer(minLength: 4)
            if total > 1 {
                Text("\(index) of \(total)").font(.caption.monospacedDigit()).foregroundStyle(Theme.textSecondary)
                    .contentTransition(.numericText(countsDown: true))
            }
            Button(action: close) {
                Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(Theme.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.pressable)
            .accessibilityLabel("Close side question")
        }
        .frame(minHeight: 36)
    }

    @ViewBuilder private var content: some View {
        if let a = item.answer {
            VStack(alignment: .leading, spacing: 10) {
                MarkdownView(text: a)
                    .fixedSize(horizontal: false, vertical: true)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { answerHeight = $0 }
                    .frame(maxHeight: cap, alignment: .top)
                    .clipped()
                    .mask {
                        LinearGradient(stops: [.init(color: .black, location: overflows ? 0.72 : 1), .init(color: .black.opacity(overflows ? 0 : 1), location: 1)],
                                       startPoint: .top, endPoint: .bottom)
                    }
                HStack(spacing: 8) {
                    Button { UIPasteboard.general.string = a; Haptics.selection() } label: { Label("Copy", systemImage: "doc.on.doc") }
                    if overflows {
                        Button { showFull = true } label: { Label("Show All", systemImage: "arrow.up.left.and.arrow.down.right") }
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .padding(.bottom, 4)
            }
            .padding(.trailing, 8)
        } else if let e = item.error {
            Label(e, systemImage: "exclamationmark.triangle").font(.callout).foregroundStyle(Theme.danger)
                .padding(.trailing, 8).padding(.bottom, 6)
        } else {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Answering from this conversation…").font(.callout).foregroundStyle(Theme.textSecondary)
            }
            .frame(minHeight: 32)
            .padding(.bottom, 4)
        }
    }
}

/// The whole answer of a side question, for answers taller than the card.
private struct SideAnswerSheet: View {
    let item: SideQuestion
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(item.question).font(.headline).foregroundStyle(Theme.textSecondary)
                    MarkdownView(text: item.answer ?? "")
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("Side Question")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { UIPasteboard.general.string = item.answer; Haptics.selection() } label: { Image(systemName: "doc.on.doc") }
                        .accessibilityLabel("Copy")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
