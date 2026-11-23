import SwiftUI
import UniformTypeIdentifiers

/// A file on the agent's host: text with line numbers, anything else in QuickLook (images, PDF, Office, media…).
/// The whole file is fetched (up to 32 MB) so Share → Save to Files / AirDrop hands over the real thing.
struct FileViewer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let path: String
    var agent: String = "agy"
    /// relative paths are taken from this conversation's folder
    var sessionId: String? = nil

    private enum Content { case text(String), preview(URL) }
    @State private var content: Content?
    @State private var fileURL: URL?
    @State private var note: String?
    @State private var error: String?
    @State private var tooLarge: String?
    @State private var wrap = false

    /// "path/to/file.swift:42" -> ("path/to/file.swift", 42)
    private var parsed: (path: String, line: Int?) {
        if let r = path.range(of: ":\\d+$", options: .regularExpression), let n = Int(path[r].dropFirst()) {
            return (String(path[..<r.lowerBound]), n)
        }
        return (path, nil)
    }
    private var name: String { (parsed.path as NSString).lastPathComponent }
    private var isText: Bool { if case .text = content { return true }; return false }

    var body: some View {
        NavigationStack {
            Group {
                switch content {
                case .text(let text): textView(text)
                case .preview(let url): QuickLookView(url: url).ignoresSafeArea(edges: .bottom)
                case nil:
                    if let tooLarge {
                        ContentUnavailableView {
                            Label("File Too Large", systemImage: "doc")
                        } description: {
                            Text(tooLarge)
                        } actions: {
                            Button("Copy Path") { UIPasteboard.general.string = parsed.path; Haptics.selection() }
                        }
                    } else if let error {
                        ContentUnavailableView("Can't open file", systemImage: "doc.questionmark", description: Text(error))
                    } else {
                        ProgressView()
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if let note { Text(note).font(.caption).foregroundStyle(.secondary).padding(8).frame(maxWidth: .infinity) }
            }
            .navigationTitle(name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                if isText {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { wrap.toggle() } label: { Image(systemName: wrap ? "text.alignleft" : "arrow.left.and.right.text.vertical") }
                            .accessibilityLabel(wrap ? Text("Don't wrap lines") : Text("Wrap lines"))
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if let fileURL {
                        ShareLink(item: fileURL) { Image(systemName: "square.and.arrow.up") }
                            .accessibilityLabel(Text("Share"))
                    } else {
                        Image(systemName: "square.and.arrow.up").foregroundStyle(.tertiary).accessibilityHidden(true)
                    }
                }
            }
            .task { await load() }
        }
    }

    private func textView(_ text: String) -> some View {
        ScrollViewReader { proxy in
            ScrollView(wrap ? .vertical : [.vertical, .horizontal]) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(text.components(separatedBy: "\n").enumerated()), id: \.offset) { i, l in
                        HStack(alignment: .top, spacing: 8) {
                            Text("\(i + 1)").foregroundStyle(.tertiary).frame(minWidth: 34, alignment: .trailing)
                            Text(l.isEmpty ? " " : l).fixedSize(horizontal: !wrap, vertical: false)
                        }
                        .font(.caption.monospaced())
                        .background(parsed.line == i + 1 ? Color.yellow.opacity(0.25) : Color.clear)
                        .id(i + 1)
                    }
                }
                .padding(8)
                .textSelection(.enabled)
            }
            .onAppear { if let l = parsed.line { proxy.scrollTo(l, anchor: .center) } }
        }
    }

    /// Images, PDFs, media and HTML read better in QuickLook even when their bytes are text (SVG, HTML).
    private static func prefersPreview(_ name: String) -> Bool {
        guard let t = FileRef.type(of: name) else { return false }
        return t.conforms(to: .image) || t.conforms(to: .pdf) || t.conforms(to: .audiovisualContent) || t.conforms(to: .html)
    }

    private func load() async {
        do {
            let f = try await model.fetchFile(parsed.path, agent: agent, sessionId: sessionId)
            let url = try f.temporaryURL()
            fileURL = url
            if !f.binary, f.data.count <= 2 * 1024 * 1024, !Self.prefersPreview(f.name), let s = String(data: f.data, encoding: .utf8) {
                content = .text(s)
            } else {
                content = .preview(url)
            }
        } catch let e as BridgeError where e.code == AppModel.fileTooLarge {
            // can't hand over the whole file; a text file still shows its start
            if let r = try? await model.readTextPreview(parsed.path, agent: agent, sessionId: sessionId), !r.binary, let t = r.text {
                content = .text(t)
                note = String(localized: "Showing the first 512 KB · too large to download")
            } else {
                tooLarge = e.localizedDescription
            }
        } catch {
            self.error = error.localizedDescription
        }
    }
}
