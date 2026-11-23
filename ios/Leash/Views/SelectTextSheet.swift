import SwiftUI
import UIKit

extension View {
    /// Long-press menu for a message: Copy (the original text) and Select Text (a sheet with native
    /// selection, since SwiftUI `Text` on iOS 26 only copies whole views). `markdown` messages show a
    /// readable excerpt as the menu preview instead of the whole answer shrunk to a thumbnail.
    func messageMenu(_ text: String, markdown: Bool = false) -> some View {
        modifier(MessageMenu(text: text, markdown: markdown))
    }
}

private struct MessageMenu: ViewModifier {
    let text: String
    let markdown: Bool
    @State private var selecting = false

    func body(content: Content) -> some View {
        Group {
            if markdown {
                content.contextMenu { items } preview: { MessageMenuPreview(text: MarkdownView.plainText(text)) }
            } else {
                content.contextMenu { items }
            }
        }
        .sheet(isPresented: $selecting) {
            SelectTextSheet(text: markdown ? MarkdownView.plainText(text) : text)
        }
    }

    @ViewBuilder private var items: some View {
        Button { UIPasteboard.general.string = text } label: { Label("Copy", systemImage: "doc.on.doc") }
        Button { selecting = true } label: { Label("Select Text", systemImage: "character.cursor.ibeam") }
    }
}

/// The top of the answer at reading size; context-menu previews are hosted outside the view tree,
/// so this stays a plain Text with no environment dependencies.
private struct MessageMenuPreview: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: Theme.readingSize))
            .lineSpacing(4)
            .foregroundStyle(Theme.textPrimary)
            .lineLimit(9)
            .frame(width: 320, alignment: .topLeading)
            .padding(16)
    }
}

struct SelectTextSheet: View {
    let text: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            SelectableTextView(text: text)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("Select Text")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.large])   // at medium, dragging selection handles fights sheet resizing
    }
}

/// Read-only UITextView: system selection handles, loupe and edit menu.
private struct SelectableTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let v = UITextView()
        v.isEditable = false
        v.isSelectable = true
        v.backgroundColor = .clear
        v.adjustsFontForContentSizeCategory = true
        v.font = .preferredFont(forTextStyle: .body)
        v.textColor = .label
        v.textContainerInset = UIEdgeInsets(top: 12, left: 16, bottom: 32, right: 16)
        v.alwaysBounceVertical = true
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        if v.text != text { v.text = text }
    }
}

extension MarkdownView {
    /// The answer as it reads on screen, without markdown syntax: list items on consecutive lines,
    /// table cells tab-separated, other blocks separated by a blank line.
    static func plainText(_ text: String) -> String {
        func flat(_ s: String) -> String { String(inline(s).characters) }
        var out = ""
        var lastWasItem = false
        for b in parse(text) {
            let piece: String?
            var isItem = false
            switch b {
            case .heading(_, let t), .paragraph(let t), .quote(let t):
                piece = flat(t)
            case .bullet(let t, let indent):
                piece = String(repeating: "  ", count: indent) + "• " + flat(t)
                isItem = true
            case .numbered(let n, let t, let indent):
                piece = String(repeating: "  ", count: indent) + n + ". " + flat(t)
                isItem = true
            case .code(let c, _):
                piece = c
            case .table(let header, let rows, _):
                piece = ([header] + rows).map { $0.map(flat).joined(separator: "\t") }.joined(separator: "\n")
            case .image(let src, let alt):
                piece = alt.isEmpty ? src : alt
            case .rule, .embed, .mentionedImages:
                piece = nil
            }
            guard let piece else { continue }
            if !out.isEmpty { out += (isItem && lastWasItem) ? "\n" : "\n\n" }
            out += piece
            lastWasItem = isItem
        }
        return out
    }
}
