import SwiftUI

/// Lightweight block markdown renderer: headings, lists, quotes, fenced code, paragraphs.
/// Inline styling (bold, italic, code, links) uses Foundation's markdown parser.
struct MarkdownView: View {
    let text: String

    enum Block: Hashable {
        case heading(Int, String)
        case paragraph(String)
        case bullet(String, indent: Int)
        case numbered(String, String, indent: Int)
        case quote(String)
        case code(String, lang: String)
        case table(header: [String], rows: [[String]], align: [Int])   // align: 0 unspecified, 1 center, 2 trailing, 3 explicit leading
        case rule
        case embed(String)   // <agent-embed src="…"> (Antigravity generative-UI widget)
        case image(String, alt: String)   // ![alt](src) / <img src>
        case mentionedImages([String])    // image paths the text above lists: shown only when they load
    }

    /// "| a | b |" -> ["a", "b"]; honours \| escapes.
    static func tableCells(_ line: String) -> [String] {
        var s = line.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("|") { s.removeFirst() }
        if s.hasSuffix("|") && !s.hasSuffix("\\|") { s.removeLast() }
        var cells: [String] = []
        var cur = ""
        var prev: Character = " "
        var inCode = false
        for ch in s {
            if ch == "`" { inCode.toggle() }
            if ch == "|" && prev != "\\" && !inCode { cells.append(cur); cur = "" } else { cur.append(ch) }
            prev = ch
        }
        cells.append(cur)
        return cells.map { $0.replacingOccurrences(of: "\\|", with: "|").trimmingCharacters(in: .whitespaces) }
    }

    static func isTableSeparator(_ line: String) -> Bool {
        let cells = tableCells(line)
        guard !cells.isEmpty, line.contains("-") else { return false }
        return cells.allSatisfy { c in
            let t = c.trimmingCharacters(in: .whitespaces)
            return t.count >= 1 && t.allSatisfy { $0 == "-" || $0 == ":" } && t.contains("-")
        }
    }

    static func parse(_ text: String) -> [Block] {
        var blocks: [Block] = []
        var para: [String] = []
        var code: [String]? = nil
        var lang = ""
        func flush() { if !para.isEmpty { blocks.append(.paragraph(para.joined(separator: "\n"))); para = [] } }
        let lines = text.components(separatedBy: "\n")
        var i = 0
        while i < lines.count {
            let raw = lines[i]
            i += 1
            let line = raw
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            // GFM table: header row + separator row, then body rows starting with "|"
            if code == nil, trimmed.hasPrefix("|"), i < lines.count, isTableSeparator(lines[i]) {
                flush()
                let header = tableCells(trimmed)
                let align = tableCells(lines[i]).map { c -> Int in
                    let t = c.trimmingCharacters(in: .whitespaces)
                    if t.hasPrefix(":") && t.hasSuffix(":") { return 1 }
                    if t.hasPrefix(":") { return 3 }
                    return t.hasSuffix(":") ? 2 : 0
                }
                i += 1
                var rows: [[String]] = []
                while i < lines.count, lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("|") {
                    rows.append(tableCells(lines[i]))
                    i += 1
                }
                blocks.append(.table(header: header, rows: rows, align: align))
                continue
            }
            if code != nil {
                if trimmed.hasPrefix("```") { blocks.append(.code(code!.joined(separator: "\n"), lang: lang)); code = nil }
                else { code!.append(line) }
                continue
            }
            if trimmed.hasPrefix("```") { flush(); code = []; lang = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces); continue }
            if let pieces = MarkdownImages.pieces(line) {
                for p in pieces {
                    switch p {
                    case .text(let t): para.append(t)
                    case .image(let src, let alt): flush(); blocks.append(.image(src, alt: alt))
                    }
                }
                continue
            }
            if let pieces = AgentEmbed.pieces(line) {
                for p in pieces {
                    switch p {
                    case .text(let t): para.append(t)
                    case .embed(let src): flush(); blocks.append(.embed(src))
                    }
                }
                continue
            }
            if trimmed.isEmpty { flush(); continue }
            if trimmed == "---" || trimmed == "***" || trimmed == "___" { flush(); blocks.append(.rule); continue }
            if trimmed.hasPrefix("#") {
                let level = trimmed.prefix(while: { $0 == "#" }).count
                if level <= 6, trimmed.dropFirst(level).first == " " {
                    flush(); blocks.append(.heading(level, String(trimmed.dropFirst(level + 1)))); continue
                }
            }
            let indent = line.prefix(while: { $0 == " " }).count / 2
            if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") || trimmed.hasPrefix("+ ") {
                flush(); blocks.append(.bullet(String(trimmed.dropFirst(2)), indent: indent)); continue
            }
            if let dot = trimmed.firstIndex(where: { $0 == "." || $0 == ")" }), dot > trimmed.startIndex,
               trimmed[trimmed.startIndex..<dot].allSatisfy(\.isNumber), trimmed[trimmed.index(after: dot)...].hasPrefix(" ") {
                flush()
                blocks.append(.numbered(String(trimmed[trimmed.startIndex..<dot]), String(trimmed[trimmed.index(dot, offsetBy: 2)...]), indent: indent))
                continue
            }
            if trimmed.hasPrefix(">") {
                flush()
                let q = String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces)
                if case .quote(let prev)? = blocks.last { blocks[blocks.count - 1] = .quote(prev + "\n" + q) } else { blocks.append(.quote(q)) }
                continue
            }
            para.append(line)
        }
        if let code { blocks.append(.code(code.joined(separator: "\n"), lang: lang)) }  // unterminated while streaming
        flush()
        return MarkdownImages.addMentions(blocks)
    }

    /// Inline markdown with quiet inline code: smaller mono, tinted ink, faint background.
    static func inline(_ s: String, size: CGFloat = Theme.readingSize) -> AttributedString {
        var a = (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
        var codeRanges: [Range<AttributedString.Index>] = []
        var linkRanges: [Range<AttributedString.Index>] = []
        for run in a.runs {
            if let intent = run.inlinePresentationIntent, intent.contains(.code) { codeRanges.append(run.range) }
            if run.link != nil { linkRanges.append(run.range) }
        }
        for r in codeRanges {
            a[r].font = .system(size: size - 2.5, weight: .regular, design: .monospaced)
            a[r].foregroundColor = Theme.codeInk
            a[r].backgroundColor = Theme.codeBg
            // `out/report.pdf`, `src/App.swift:42`: tap opens the file (MarkdownView's openURL handler)
            if a[r].link == nil, let url = MarkdownImages.fileLink(String(a[r].characters)) { a[r].link = url }
        }
        for r in linkRanges { a[r].foregroundColor = Theme.accent }
        return a
    }

    @Environment(\.embedAgent) private var agent
    @Environment(\.transcriptSession) private var session
    @State private var linkedFile: FileRef?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(MarkdownView.parse(text).enumerated()), id: \.offset) { _, b in
                block(b)
            }
        }
        .font(.system(size: Theme.readingSize))
        .foregroundStyle(Theme.textPrimary)
        .textSelection(.enabled)
        // links to files on the agent's host ([report](out/report.pdf), file:///…) open in the file viewer
        .environment(\.openURL, OpenURLAction { url in
            guard let path = MarkdownImages.localPath(of: url) else { return .systemAction }
            linkedFile = FileRef(path: path)
            return .handled
        })
        .sheet(item: $linkedFile) { f in FileViewer(path: f.path, agent: agent, sessionId: session) }
    }

    @ViewBuilder private func block(_ b: Block) -> some View {
        switch b {
        case .heading(let l, let t):
            Text(MarkdownView.inline(t)).font(.system(size: l == 1 ? 19 : (l == 2 ? 17 : 16), weight: .semibold)).padding(.top, 6)
        case .paragraph(let t):
            Text(MarkdownView.inline(t)).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
        case .bullet(let t, let indent):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Circle().fill(Theme.textTertiary).frame(width: 4, height: 4).alignmentGuide(.firstTextBaseline) { d in d[.bottom] + 5 }
                Text(MarkdownView.inline(t)).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
            }.padding(.leading, 4 + CGFloat(indent) * 16)
        case .numbered(let n, let t, let indent):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(n + ".").foregroundStyle(Theme.textTertiary).monospacedDigit()
                Text(MarkdownView.inline(t)).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
            }.padding(.leading, CGFloat(indent) * 16)
        case .quote(let t):
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 1).fill(Theme.accent.opacity(0.35)).frame(width: 2)
                Text(MarkdownView.inline(t)).lineSpacing(4).foregroundStyle(Theme.textSecondary)
            }
            .padding(.vertical, 2)
        case .code(let c, let lang):
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(lang.isEmpty ? "code" : lang).font(.caption2).foregroundStyle(.secondary)
                    Spacer()
                    Button { UIPasteboard.general.string = c; Haptics.impact(.light, intensity: 0.6) } label: { Image(systemName: "doc.on.doc").font(.caption2) }
                }
                .padding(.horizontal, 10).padding(.top, 6)
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(c).font(.system(size: 13, design: .monospaced)).foregroundStyle(Theme.textPrimary).lineSpacing(2).fixedSize(horizontal: true, vertical: false).padding(10)
                }
            }
            .background(Theme.bgSunken, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        case .rule:
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.vertical, 4)
        case .table(let header, let rows, let align):
            MarkdownTable(header: header, rows: rows, align: align)
        case .embed(let src):
            AgentEmbedView(src: src).padding(.vertical, 2)
        case .image(let src, let alt):
            MarkdownImage(src: src, alt: alt).padding(.vertical, 2)
        case .mentionedImages(let srcs):
            VStack(alignment: .leading, spacing: 8) {
                ForEach(srcs, id: \.self) { MarkdownImage(src: $0, quiet: true) }
            }
        }
    }
}

/// GFM table as an inset card: measured column widths (fills the width when it fits, wraps long columns, scrolls
/// sideways inside the card only when it must), hairline row separators, quiet header.
struct MarkdownTable: View {
    let header: [String]
    let rows: [[String]]
    let align: [Int]
    @Environment(\.displayScale) private var displayScale
    @State private var available: CGFloat = 0

    // Dynamic Type text styles: body cells .subheadline, header .footnote semibold
    private static var cellFont: UIFont { .preferredFont(forTextStyle: .subheadline) }
    private static var headerFont: UIFont { UIFont.systemFont(ofSize: UIFont.preferredFont(forTextStyle: .footnote).pointSize, weight: .semibold) }
    @Environment(\.dynamicTypeSize) private var dynamicType   // re-measure when the text size changes
    private static let padH: CGFloat = 12
    private static let padV: CGFloat = 10
    private static let minText: CGFloat = 44
    private static let wrapAt: CGFloat = 240   // text width a column wraps at when the table has to scroll

    private var columns: Int { max(header.count, rows.map(\.count).max() ?? 0) }

    private func text(_ row: [String], _ c: Int) -> String { row.indices.contains(c) ? row[c] : "" }

    /// Columns of numbers read right-aligned when the markdown doesn't say otherwise.
    private func isNumeric(_ col: Int) -> Bool {
        let vals = rows.map { text($0, col).trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && $0 != "-" && $0 != "—" }
        guard !vals.isEmpty else { return false }
        return vals.allSatisfy { $0.range(of: #"^[-+±~≈]?[$€¥£]?[0-9][0-9,._ ]*(%|[kKmMbB]|ms|s|x|×)?$"#, options: .regularExpression) != nil }
    }

    /// GFM ":---" (3) is explicitly leading; only an unspecified "---" (0) column of numbers becomes trailing.
    private func alignment(_ col: Int) -> (Alignment, TextAlignment) {
        let a = align.indices.contains(col) ? align[col] : 0
        if a == 0 && isNumeric(col) { return (.trailing, .trailing) }
        switch a {
        case 1: return (.center, .center)
        case 2: return (.trailing, .trailing)
        default: return (.leading, .leading)
        }
    }

    /// One-line width of a cell's rendered text (markdown markers removed).
    private static func lineWidth(_ s: String, font: UIFont) -> CGFloat {
        guard !s.isEmpty else { return 0 }
        let plain = String(MarkdownView.inline(s, size: font.pointSize).characters)
        return plain.components(separatedBy: "\n").map { ceil(($0 as NSString).size(withAttributes: [.font: font]).width) }.max() ?? 0
    }

    /// Text width per column for the space we have.
    private func widths(natural: [CGFloat]) -> [CGFloat] {
        let n = natural.map { max($0, Self.minText) }
        let room = available - CGFloat(columns) * Self.padH * 2
        guard available > 0 else { return n.map { min($0, Self.wrapAt) } }
        let total = n.reduce(0, +)
        if total <= room {
            // fits on one line each: share the spare width so the card spans the column
            let extra = (room - total) / CGFloat(max(1, columns))
            return n.map { $0 + extra }
        }
        // wrap the long columns first: shrink the widest ones toward a common cap until it fits
        let sorted = n.sorted()
        var cap = sorted.last ?? Self.wrapAt
        var lo = Self.minText, hi = cap
        for _ in 0..<24 {
            let mid = (lo + hi) / 2
            if n.map({ min($0, mid) }).reduce(0, +) <= room { lo = mid } else { hi = mid }
        }
        cap = lo
        if cap >= 120 {
            let w = n.map { min($0, cap) }
            let spare = room - w.reduce(0, +)
            let wrapped = n.indices.filter { n[$0] > cap }
            return w.indices.map { i in wrapped.contains(i) ? w[i] + spare / CGFloat(max(1, wrapped.count)) : w[i] }
        }
        // too many columns for the screen: wrap at a readable width and scroll sideways
        return n.map { min($0, Self.wrapAt) }
    }

    var body: some View {
        let natural = (0..<columns).map { c in
            max(Self.lineWidth(text(header, c), font: Self.headerFont),
                rows.map { Self.lineWidth(text($0, c), font: Self.cellFont) }.max() ?? 0)
        }
        let w = widths(natural: natural)
        let aligns = (0..<columns).map(alignment)
        let hairline = 1 / max(displayScale, 1)
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                row(header, widths: w, aligns: aligns, isHeader: true)
                    .background(Theme.textPrimary.opacity(0.045))
                ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
                    Rectangle().fill(Theme.hairline).frame(height: hairline)
                    row(r, widths: w, aligns: aligns, isHeader: false)
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.bgRaised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.hairline, lineWidth: hairline))
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { available = $0 }
        .padding(.vertical, 4)
    }

    private func row(_ cells: [String], widths: [CGFloat], aligns: [(Alignment, TextAlignment)], isHeader: Bool) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<columns, id: \.self) { c in
                let (frameAlign, textAlign) = aligns[c]
                let font = isHeader ? Self.headerFont : Self.cellFont
                Text(MarkdownView.inline(text(cells, c), size: font.pointSize))
                    .font(isHeader ? .footnote.weight(.semibold) : .subheadline)
                    .monospacedDigit()
                    .foregroundStyle(isHeader ? Theme.textSecondary : Theme.textPrimary)
                    .lineSpacing(isHeader ? 0 : 3)
                    .multilineTextAlignment(textAlign)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(width: widths.indices.contains(c) ? widths[c] : Self.minText, alignment: frameAlign)
                    .padding(.horizontal, Self.padH)
                    .padding(.vertical, isHeader ? 8 : Self.padV)
                    .accessibilityAddTraits(isHeader ? .isHeader : [])
                    .accessibilityLabel(isHeader || text(header, c).isEmpty ? Text(text(cells, c)) : Text(verbatim: text(header, c) + ": " + text(cells, c)))
            }
        }
    }
}

/// Images in agent markdown: `![alt](src)` (also linked, `[![alt](src)](href)`, and HTML `<img src>`) become image
/// blocks; image paths an answer only lists (in code spans, links or bare) are shown under their paragraph if they load.
enum MarkdownImages {
    enum Piece: Equatable { case text(String), image(String, alt: String) }

    private static let imageTag = try! NSRegularExpression(
        pattern: #"\[?!\[([^\]\n]*)\]\(\s*<?([^)\s>]+)>?(?:\s+["'][^"'\n]*["'])?\s*\)(?:\]\([^)\n]*\))?|<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>"#,
        options: [.caseInsensitive])
    private static let altAttr = try! NSRegularExpression(pattern: #"\balt\s*=\s*["']([^"']*)["']"#, options: [.caseInsensitive])
    /// half-streamed "![alt](sr" / "<img src=…" at the end of a line
    private static let partial = try! NSRegularExpression(pattern: #"(?:!\[[^\]\n]*(?:\]\([^)\n]*)?|<img\b[^>]*)$"#, options: [.caseInsensitive])
    private static let listMarker = try! NSRegularExpression(pattern: #"^\s*(?:[-*+]|\d+[.)])?\s*$"#)

    /// Splits one markdown line into text and images; nil when the line has no image syntax.
    static func pieces(_ line: String) -> [Piece]? {
        guard line.contains("![") || line.range(of: "<img", options: .caseInsensitive) != nil else { return nil }
        let ns = line as NSString
        let matches = imageTag.matches(in: line, range: NSRange(location: 0, length: ns.length))
        var out: [Piece] = []
        var cursor = 0
        var used = 0
        func text(_ s: String) {
            let r = NSRange(location: 0, length: (s as NSString).length)
            if listMarker.firstMatch(in: s, range: r) == nil { out.append(.text(s)) }
        }
        for m in matches {
            // inside a code span (odd number of backticks before it) it is an example, not an image
            if ns.substring(to: m.range.location).filter({ $0 == "`" }).count % 2 == 1 { continue }
            used += 1
            text(ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor)))
            cursor = m.range.location + m.range.length
            if m.range(at: 2).location != NSNotFound {
                out.append(.image(ns.substring(with: m.range(at: 2)), alt: ns.substring(with: m.range(at: 1))))
            } else if m.range(at: 3).location != NSNotFound {
                let tag = ns.substring(with: m.range)
                let alt = altAttr.firstMatch(in: tag, range: NSRange(location: 0, length: (tag as NSString).length))
                    .map { (tag as NSString).substring(with: $0.range(at: 1)) } ?? ""
                out.append(.image(ns.substring(with: m.range(at: 3)), alt: alt))
            }
        }
        var tail = ns.substring(from: cursor)
        let tns = tail as NSString
        if let p = partial.firstMatch(in: tail, range: NSRange(location: 0, length: tns.length)) { tail = tns.substring(to: p.range.location) }
        if used == 0 && tail == line { return nil }
        if !tail.isEmpty { text(tail) }
        return out
    }

    /// `out/shot.png`, [shot](out/shot.png), file:///…/shot.png, /abs/shot.png, ~/shot.png
    private static let mention: NSRegularExpression = {
        let ext = #"\.(?:png|jpe?g|gif|webp|heic|bmp|tiff?)"#
        let code = "`([^`\\n]+?" + ext + ")`"
        let link = #"\]\(\s*<?([^)\s>]+?"# + ext + #")>?\s*\)"#
        let end = #"(?!\w)(?!\.\w)"#   // "a.png." ends a sentence; "a.png.bak" is not an image
        let uri = #"(file://[^\s)<>"'`]+?"# + ext + #")"# + end
        let bare = #"(?:^|(?<=[\s:：，,(（"']))((?:~/|/(?!/))[^\s<>()\[\]"'，。`]+?"# + ext + #")"# + end
        return try! NSRegularExpression(pattern: [code, link, uri, bare].joined(separator: "|"), options: [.caseInsensitive])
    }()

    static func mentions(_ text: String) -> [String] {
        guard text.contains(".") else { return [] }
        let ns = text as NSString
        var out: [String] = []
        for m in mention.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            for g in 1...4 where m.range(at: g).location != NSNotFound {
                let s = ns.substring(with: m.range(at: g)).trimmingCharacters(in: .whitespaces)
                if s.contains("*") || s.contains("{") || s.contains("$") || s.contains(" ") || s.lowercased().hasPrefix("http") { continue }
                out.append(s)
            }
        }
        return out
    }

    /// After each text block, the images its text mentions (each image once per message, at most 8).
    static func addMentions(_ blocks: [MarkdownView.Block]) -> [MarkdownView.Block] {
        var seen = Set<String>()
        for b in blocks { if case .image(let s, _) = b { seen.insert(s) } }
        var out: [MarkdownView.Block] = []
        var shown = 0
        for b in blocks {
            out.append(b)
            let text: String
            switch b {
            case .paragraph(let t), .quote(let t), .heading(_, let t): text = t
            case .bullet(let t, _), .numbered(_, let t, _): text = t
            case .table(let h, let rows, _): text = (h + rows.flatMap { $0 }).joined(separator: "\n")
            default: continue
            }
            var found: [String] = []
            for s in mentions(text) where !seen.contains(s) && shown + found.count < 8 { seen.insert(s); found.append(s) }
            if !found.isEmpty { shown += found.count; out.append(.mentionedImages(found)) }
        }
        return out
    }

    private static let fileScheme = "leash-file:"
    private static let pathLike = try! NSRegularExpression(
        pattern: #"^(?:~/|/|\./|\.\./)?(?:[^\s/:*?"<>|`]+/)*[^\s/:*?"<>|`]+\.([A-Za-z0-9]{1,8})(?::\d+)?$"#)
    private static let fileExts: Set<String> = [
        "swift", "m", "h", "c", "cc", "cpp", "hpp", "rs", "go", "py", "rb", "js", "mjs", "cjs", "ts", "tsx", "jsx", "java", "kt",
        "cs", "php", "lua", "sh", "zsh", "bash", "ps1", "sql", "md", "txt", "log", "json", "jsonl", "yaml", "yml", "toml", "ini",
        "xml", "plist", "html", "htm", "css", "scss", "csv", "tsv", "pdf", "zip", "gz", "tgz", "tar", "7z", "rar", "dmg", "ipa",
        "apk", "png", "jpg", "jpeg", "gif", "webp", "heic", "svg", "ico", "mp3", "m4a", "wav", "mp4", "mov", "webm", "docx",
        "doc", "xlsx", "xls", "pptx", "key", "pages", "numbers", "ipynb", "gd", "tscn", "tres", "godot", "unity", "prefab",
        "xcstrings", "strings", "pbxproj", "lock", "env", "cfg", "conf", "patch", "diff", "wasm", "bin", "dat", "db", "sqlite",
    ]

    /// A tappable link for a code span that names a file: a path with a known extension (optionally ":line").
    static func fileLink(_ s: String) -> URL? {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard t.count <= 300, !t.contains("://") else { return nil }
        let ns = t as NSString
        guard let m = pathLike.firstMatch(in: t, range: NSRange(location: 0, length: ns.length)),
              fileExts.contains(ns.substring(with: m.range(at: 1)).lowercased()) else { return nil }
        // a bare "name.ext" is a file only with a slash or a typical code/document extension (not "os.path")
        return URL(string: fileScheme + (t.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? t))
    }

    /// A link target that is a file on the agent's host (file://, an absolute / home / relative path); nil for web links.
    static func localPath(of url: URL) -> String? {
        if url.absoluteString.hasPrefix(fileScheme) {
            let rest = String(url.absoluteString.dropFirst(fileScheme.count))
            return rest.removingPercentEncoding ?? rest
        }
        if let scheme = url.scheme?.lowercased() {
            guard scheme == "file" else { return nil }
            return url.path.isEmpty ? nil : url.path
        }
        let raw = url.absoluteString
        guard !raw.isEmpty, !raw.hasPrefix("#") else { return nil }
        let path = raw.split(separator: "#", maxSplits: 1).first.map(String.init) ?? raw
        return path.removingPercentEncoding ?? path
    }
}
