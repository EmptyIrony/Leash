import SwiftUI
import QuickLook
import UniformTypeIdentifiers

// Files in and out of a conversation:
// * attach any file in the composer: the bridge stores it on the agent's host (bridge/upload, ~/.leash/uploads/…)
//   and the prompt carries a resource_link the agent opens with its own tools;
// * open / save files the agent wrote or mentions (FileViewer: text with line numbers, or QuickLook, + Share);
// * images the agent puts in its answer (![…](…) or a path it lists) render inline.

extension EnvironmentValues {
    /// The conversation a transcript belongs to: relative paths in it are read from that conversation's folder.
    @Entry var transcriptSession: String? = nil
}

// MARK: - File references

/// A file on the agent's host that a message refers to (an attachment, a link the agent wrote).
struct FileRef: Hashable, Identifiable {
    let name: String
    let path: String
    var size: Int? = nil
    var id: String { path }

    init(name: String? = nil, path: String, size: Int? = nil) {
        let n = name?.trimmingCharacters(in: .whitespaces) ?? ""
        self.name = n.isEmpty ? (path as NSString).lastPathComponent : n
        self.path = path
        self.size = size
    }

    /// From a resource_link / resource block ("file:///…" uri, or a plain absolute path).
    init?(block b: JSON) {
        let uri = b["uri"]?.string ?? b["resource"]?["uri"]?.string ?? ""
        guard let p = FileRef.path(fromURI: uri) else { return nil }
        self.init(name: b["name"]?.string ?? b["title"]?.string, path: p, size: b["size"]?.int)
    }

    static func path(fromURI uri: String) -> String? {
        if uri.hasPrefix("/") || uri.hasPrefix("~/") { return uri }
        guard uri.lowercased().hasPrefix("file://") else { return nil }
        let rest = String(uri.dropFirst(7))
        let p = rest.removingPercentEncoding ?? rest
        return p.isEmpty ? nil : p
    }

    /// Claude Code writes attached files into its transcript as "[@name](file:///…)".
    private static let linkRegex = try! NSRegularExpression(pattern: #"\[@([^\]\n]+)\]\((file://[^)\s]+)\)"#)

    /// The attachment links in a message's text, and the text without them.
    static func extractLinks(_ text: String) -> (text: String, refs: [FileRef]) {
        guard text.contains("](file://") else { return (text, []) }
        let ns = text as NSString
        var refs: [FileRef] = []
        var out = ""
        var cursor = 0
        for m in linkRegex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor))
            cursor = m.range.location + m.range.length
            if let p = path(fromURI: ns.substring(with: m.range(at: 2))) { refs.append(FileRef(name: ns.substring(with: m.range(at: 1)), path: p)) }
        }
        out += ns.substring(from: cursor)
        return (out.trimmingCharacters(in: .whitespacesAndNewlines), refs)
    }

    var symbol: String { FileRef.symbol(for: name) }
    var isImage: Bool { FileRef.type(of: name)?.conforms(to: .image) ?? false }

    static func type(of name: String) -> UTType? {
        let ext = (name as NSString).pathExtension.lowercased()
        return ext.isEmpty ? nil : UTType(filenameExtension: ext)
    }

    static func symbol(for name: String) -> String {
        guard let t = type(of: name) else { return "doc" }
        if t.conforms(to: .pdf) { return "doc.richtext" }
        if t.conforms(to: .image) { return "photo" }
        if t.conforms(to: .audiovisualContent) && !t.conforms(to: .audio) { return "film" }
        if t.conforms(to: .audio) { return "waveform" }
        if t.conforms(to: .archive) { return "doc.zipper" }
        if t.conforms(to: .spreadsheet) || t.conforms(to: .commaSeparatedText) { return "tablecells" }
        if t.conforms(to: .presentation) { return "rectangle.on.rectangle" }
        if t.conforms(to: .text) { return "doc.text" }
        return "doc"
    }

    static func sizeText(_ n: Int) -> String { Int64(n).formatted(.byteCount(style: .file)) }
}

/// A file picked in the composer, not sent yet.
struct PendingFile: Identifiable, Equatable {
    let id = UUID()
    let name: String
    let data: Data
    var size: Int { data.count }

    /// Files from the document picker; ones over the upload limit are reported by name.
    static func load(_ result: Result<[URL], Error>) -> (files: [PendingFile], tooLarge: [String], unreadable: [String]) {
        guard case .success(let urls) = result else { return ([], [], []) }
        var files: [PendingFile] = [], tooLarge: [String] = [], unreadable: [String] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if size > AppModel.maxUpload { tooLarge.append(url.lastPathComponent); continue }
            guard let data = try? Data(contentsOf: url) else { unreadable.append(url.lastPathComponent); continue }
            if data.count > AppModel.maxUpload { tooLarge.append(url.lastPathComponent); continue }
            files.append(PendingFile(name: url.lastPathComponent, data: data))
        }
        return (files, tooLarge, unreadable)
    }
}

// MARK: - Bridge calls

/// A whole file fetched from the agent's host.
struct FetchedFile {
    let path: String
    let data: Data
    let mimeType: String?
    let binary: Bool
    var name: String { (path as NSString).lastPathComponent }

    /// A local copy (for QuickLook and the share sheet); same file name, so "Save to Files" keeps it.
    func temporaryURL() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appending(path: "leash-files/\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appending(path: name.isEmpty ? "file" : name, directoryHint: .notDirectory)
        try data.write(to: url, options: .atomic)
        return url
    }
}

extension AppModel {
    /// Phone → host attachments (the bridge accepts frames up to 48 MiB; base64 adds a third).
    nonisolated static let maxUpload = 25 * 1024 * 1024
    /// Host → phone downloads (the bridge's cap; frames up to 64 MiB here).
    nonisolated static let maxDownload = 32 * 1024 * 1024
    nonisolated static let fileTooLarge = -32007

    /// A resource_link as the text Claude Code itself sends for it ("[@name](file://…)"), for putting a message back
    /// into the composer: the file is already on the host, so the link alone attaches it again.
    nonisolated static func linkText(_ block: JSON) -> String? {
        guard block["type"]?.string == "resource_link", let uri = block["uri"]?.string else { return nil }
        return "[@\(block["name"]?.string ?? (uri as NSString).lastPathComponent)](\(uri))"
    }

    /// Stores a picked file on the agent's host and returns the prompt block that points at it.
    func upload(_ f: PendingFile, to s: ChatStore) async throws -> JSON {
        guard f.size <= Self.maxUpload else {
            throw BridgeError(code: Self.fileTooLarge, message: String(localized: "\(f.name) is larger than 25 MB"))
        }
        let r = try await link.call("bridge/upload", ["sessionId": .string(s.sessionId), "name": .string(f.name),
                                                      "data": .string(f.data.base64EncodedString()), "agent": .string(s.agent)], timeout: 300)
        guard let uri = r["uri"]?.string else { throw BridgeError(code: -3, message: String(localized: "Upload failed")) }
        var b: [String: JSON] = ["type": "resource_link", "uri": .string(uri), "name": .string(r["name"]?.string ?? f.name), "size": JSON(f.size)]
        if let m = r["mimeType"]?.string { b["mimeType"] = .string(m) }
        return .object(b)
    }

    /// The whole file (bridge/readFile in base64 mode); a relative path is taken from the conversation's folder.
    func fetchFile(_ path: String, agent: String, sessionId: String?, maxBytes: Int = AppModel.maxDownload) async throws -> FetchedFile {
        var p: [String: JSON] = ["path": .string(path), "agent": .string(agent), "mode": "base64", "maxBytes": JSON(maxBytes)]
        if let sessionId { p["sessionId"] = .string(sessionId) }
        let r = try await link.call("bridge/readFile", .object(p), timeout: 180)
        guard let b64 = r["data"]?.string, let data = Data(base64Encoded: b64) else {
            throw BridgeError(code: -3, message: String(localized: "The Mac sent no file data"))
        }
        return FetchedFile(path: r["path"]?.string ?? path, data: data, mimeType: r["mimeType"]?.string, binary: r["binary"]?.bool ?? true)
    }

    /// The start of a text file (for files too large to download whole).
    func readTextPreview(_ path: String, agent: String, sessionId: String?) async throws -> (text: String?, truncated: Bool, binary: Bool) {
        var p: [String: JSON] = ["path": .string(path), "agent": .string(agent)]
        if let sessionId { p["sessionId"] = .string(sessionId) }
        let r = try await link.call("bridge/readFile", .object(p), timeout: 60)
        return (r["text"]?.string, r["truncated"]?.bool ?? false, r["binary"]?.bool ?? false)
    }
}

// MARK: - QuickLook

/// QLPreviewController without a navigation controller of its own, so the sheet's toolbar is the only one.
struct QuickLookView: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let c = QLPreviewController()
        c.dataSource = context.coordinator
        return c
    }

    func updateUIViewController(_ c: QLPreviewController, context: Context) {
        if context.coordinator.url != url { context.coordinator.url = url; c.reloadData() }
    }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

// MARK: - Chips

/// An attachment in a sent message (or one the agent handed back): tap opens it, with Share / Save to Files there.
struct FileChip: View {
    let ref: FileRef
    var fill: Color = Theme.accent.opacity(0.16)
    @Environment(\.embedAgent) private var agent
    @Environment(\.transcriptSession) private var session
    @State private var open = false

    var body: some View {
        Button { open = true } label: {
            HStack(spacing: 10) {
                Image(systemName: ref.symbol).font(.title3).foregroundStyle(Theme.accent).frame(width: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: ref.name).font(.footnote.weight(.medium)).foregroundStyle(Theme.textPrimary)
                        .lineLimit(1).truncationMode(.middle)
                    if let size = ref.size { Text(verbatim: FileRef.sizeText(size)).font(.caption2).foregroundStyle(Theme.textSecondary) }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .frame(minHeight: 44)
            .frame(maxWidth: 260, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous))
        }
        .buttonStyle(.pressable)
        .contextMenu {
            Button { open = true } label: { Label("Open", systemImage: "eye") }
            Button { UIPasteboard.general.string = ref.path } label: { Label("Copy Path", systemImage: "doc.on.doc") }
        }
        .accessibilityLabel(Text("Attachment \(ref.name)"))
        .accessibilityHint(Text("Opens the file"))
        .sheet(isPresented: $open) { FileViewer(path: ref.path, agent: agent, sessionId: session) }
    }
}

/// A picked file in the composer strip, next to the photo thumbnails.
struct ComposerFileChip: View {
    let file: PendingFile
    var uploading = false
    var remove: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: FileRef.symbol(for: file.name)).font(.system(size: 22)).foregroundStyle(Theme.accent).frame(width: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: file.name).font(.footnote.weight(.medium)).foregroundStyle(Theme.textPrimary).lineLimit(1).truncationMode(.middle)
                Text(verbatim: FileRef.sizeText(file.size)).font(.caption2).foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, 10).padding(.trailing, 14)
        .frame(width: 168, height: 56)
        .background(Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous))
        .opacity(uploading ? 0.5 : 1)
        .overlay { if uploading { ProgressView() } }
        .overlay(alignment: .topTrailing) {
            if !uploading {
                Button(action: remove) {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.white, Color.black.opacity(0.6))
                        .frame(width: 44, height: 44, alignment: .topTrailing)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .offset(x: 4, y: -4)
                .accessibilityLabel(Text("Remove \(file.name)"))
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// Images and files that came as content blocks of a message (not as markdown).
struct MessageAttachments: View {
    let images: [Data]
    let files: [FileRef]
    var alignment: HorizontalAlignment = .leading

    var body: some View {
        VStack(alignment: alignment, spacing: 6) {
            ForEach(Array(images.enumerated()), id: \.offset) { i, d in
                if let ui = UIImage(data: d) { ImageTile(image: ui, data: d, name: "image-\(i + 1).png") }
            }
            ForEach(files) { f in FileChip(ref: f) }
        }
    }
}

// MARK: - Inline images

/// Loaded inline images per agent|conversation|src, so a lazy transcript doesn't refetch or jump while scrolling.
@MainActor
final class InlineImageCache {
    static let shared = InlineImageCache()
    var images: [String: (image: UIImage, data: Data, name: String)] = [:]
    /// when each key was last fetched: agents rewrite the same file (screenshot.png), so a copy older than this is refetched
    var fetchedAt: [String: Date] = [:]
    /// recent failures (reason, when), so scrolling past a path that doesn't exist (yet) doesn't refetch it every time
    var failed: [String: (reason: String, at: Date)] = [:]
    static let fresh: TimeInterval = 30
}

/// An image from a markdown answer: `![alt](src)` (remote, data:, or a path on the agent's host), or a path the
/// agent merely mentions (`quiet`: rendered only when it loads, so a false match never shows an error tile).
struct MarkdownImage: View {
    let src: String
    var alt: String = ""
    var quiet = false
    @Environment(AppModel.self) private var model
    @Environment(\.embedAgent) private var agent
    @Environment(\.transcriptSession) private var session
    @State private var loaded: (image: UIImage, data: Data, name: String)?
    @State private var failure: String?
    @State private var openFile = false

    static let maxInlineBytes = 16 * 1024 * 1024
    private var key: String { agent + "|" + (session ?? "") + "|" + src }
    private var isRemote: Bool { src.lowercased().hasPrefix("http://") || src.lowercased().hasPrefix("https://") }
    private var localPath: String? {
        if isRemote || src.lowercased().hasPrefix("data:") { return nil }
        return FileRef.path(fromURI: src) ?? (src.removingPercentEncoding ?? src)
    }

    var body: some View {
        // A lazy row scrolled back in starts with fresh @State: read the cache here, not only in .task, so its first
        // layout already has the image's height (not the 180 pt placeholder, or 0 for a quiet mention) and the
        // transcript above the viewport doesn't change height under the user's finger.
        let cache = InlineImageCache.shared
        Group {
            if let shown = loaded ?? cache.images[key] {
                ImageTile(image: shown.image, data: shown.data, name: shown.name, alt: alt, path: localPath)
            } else if quiet {
                Color.clear.frame(height: 0)
            } else if let failure = failure ?? cache.failed[key]?.reason {
                failureRow(failure)
            } else {
                RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous).fill(Color(uiColor: .secondarySystemFill))
                    .frame(maxWidth: 480).frame(height: 180)
                    .overlay { ProgressView() }
                    .accessibilityLabel(Text("Loading image"))
            }
        }
        .task(id: key) { await load(force: false) }
        .sheet(isPresented: $openFile) { if let p = localPath { FileViewer(path: p, agent: agent, sessionId: session) } }
    }

    private func failureRow(_ reason: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(Theme.attention)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: alt.isEmpty ? (src as NSString).lastPathComponent : alt).font(.footnote.weight(.medium)).foregroundStyle(Theme.textPrimary).lineLimit(1)
                Text(reason).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(2)
            }
            Spacer()
            if localPath != nil { Button("Open") { openFile = true }.font(.footnote) }
            Button("Retry") { Task { await load(force: true) } }.font(.footnote)
        }
        .padding(12)
        .frame(maxWidth: 480, alignment: .leading)
        .background(Theme.bgSunken, in: RoundedRectangle(cornerRadius: Theme.Radius.inset, style: .continuous))
    }

    /// Shows the cached copy at once and refetches it when it is older than 30 s; a failure is never cached, so a
    /// path mentioned before the file existed shows up once it does (next appearance or Retry).
    private func load(force: Bool) async {
        let cache = InlineImageCache.shared
        if let c = cache.images[key], loaded?.data != c.data { loaded = c }
        if !force, cache.images[key] != nil, let at = cache.fetchedAt[key], Date().timeIntervalSince(at) < InlineImageCache.fresh { return }
        if !force, loaded == nil, let f = cache.failed[key], Date().timeIntervalSince(f.at) < InlineImageCache.fresh { failure = f.reason; return }
        failure = nil
        do {
            let (data, name) = try await fetch()
            cache.fetchedAt[key] = Date()
            cache.failed[key] = nil
            if cache.images[key]?.data == data { return }
            guard let ui = UIImage(data: data) else { throw BridgeError(code: -3, message: String(localized: "This image format can't be shown inline")) }
            let v = (image: ui, data: data, name: name)
            cache.images[key] = v
            loaded = v
        } catch {
            cache.failed[key] = (reason: error.localizedDescription, at: Date())
            if loaded == nil { failure = error.localizedDescription }
        }
    }

    private func fetch() async throws -> (Data, String) {
        let fallbackName = (src.split(separator: "?").first.map(String.init) ?? src).split(separator: "/").last.map(String.init) ?? "image"
        if isRemote, let url = URL(string: src) {
            let (data, resp) = try await URLSession.shared.data(from: url)
            if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) {
                throw BridgeError(code: h.statusCode, message: HTTPURLResponse.localizedString(forStatusCode: h.statusCode))
            }
            guard data.count <= Self.maxInlineBytes else { throw BridgeError(code: AppModel.fileTooLarge, message: String(localized: "Image is larger than 16 MB")) }
            return (data, fallbackName.removingPercentEncoding ?? fallbackName)
        }
        if src.lowercased().hasPrefix("data:"), let comma = src.firstIndex(of: ",") {
            let meta = src[..<comma], body = String(src[src.index(after: comma)...])
            guard let data = meta.contains(";base64") ? Data(base64Encoded: body) : body.removingPercentEncoding?.data(using: .utf8) else {
                throw BridgeError(code: -3, message: String(localized: "Invalid image data"))
            }
            return (data, "image")
        }
        guard let p = localPath else { throw BridgeError(code: -3, message: String(localized: "Unsupported image source")) }
        let f = try await model.fetchFile(p, agent: agent, sessionId: session, maxBytes: Self.maxInlineBytes)
        return (f.data, f.name)
    }
}

/// A decoded image in the transcript: never upscaled, capped at 480×320 (220×220 in a user bubble), tap for
/// QuickLook (zoom, share).
struct ImageTile: View {
    let image: UIImage
    let data: Data
    var name: String = "image.png"
    var alt: String = ""
    var path: String? = nil
    var maxWidth: CGFloat = 480
    var maxHeight: CGFloat = 320
    var cornerRadius: CGFloat = Theme.Radius.row
    @State private var preview: URL?
    @State private var fileURL: URL?

    var body: some View {
        let w = image.size.width, h = image.size.height
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .frame(maxWidth: min(w, maxWidth), maxHeight: min(h, maxHeight))
            .clipShape(shape)
            .overlay(shape.strokeBorder(Color(uiColor: .separator), lineWidth: 0.5))
            .contentShape(shape)
            .onTapGesture { preview = localURL() }
            .contextMenu {
                Button { UIPasteboard.general.image = image } label: { Label("Copy", systemImage: "doc.on.doc") }
                ShareLink(item: Image(uiImage: image), preview: SharePreview(name, image: Image(uiImage: image))) {
                    Label("Share…", systemImage: "square.and.arrow.up")
                }
                Button { UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil); Haptics.success() } label: { Label("Save to Photos", systemImage: "square.and.arrow.down") }
                if let path { Button { UIPasteboard.general.string = path } label: { Label("Copy Path", systemImage: "link") } }
            }
            .accessibilityElement()
            .accessibilityLabel(alt.isEmpty ? Text("Image") : Text(verbatim: alt))
            .accessibilityAddTraits([.isImage, .isButton])
            .accessibilityAction { preview = localURL() }
            .quickLookPreview($preview)
    }

    /// QuickLook and the share sheet need a file; written once per tile.
    private func localURL() -> URL? {
        if let fileURL { return fileURL }
        let u = try? FetchedFile(path: name, data: data, mimeType: nil, binary: true).temporaryURL()
        fileURL = u
        return u
    }
}
