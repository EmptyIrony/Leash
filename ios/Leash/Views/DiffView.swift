import SwiftUI

enum DiffLine: Hashable {
    case same(String), add(String), del(String), gap(Int)
}

enum LineDiff {
    static func compute(old: String?, new: String) -> [DiffLine] {
        let a = (old ?? "").isEmpty ? [] : (old ?? "").components(separatedBy: "\n")
        let b = new.components(separatedBy: "\n")
        if a.isEmpty { return b.map { .add($0) } }
        if a.count * b.count > 4_000_000 { return a.map { .del($0) } + b.map { .add($0) } }
        let diff = b.difference(from: a)
        var removed = Set<Int>(), inserted = Set<Int>()
        for c in diff {
            switch c {
            case .remove(let o, _, _): removed.insert(o)
            case .insert(let o, _, _): inserted.insert(o)
            }
        }
        var out: [DiffLine] = []
        var i = 0, j = 0
        while i < a.count || j < b.count {
            if i < a.count && removed.contains(i) { out.append(.del(a[i])); i += 1 }
            else if j < b.count && inserted.contains(j) { out.append(.add(b[j])); j += 1 }
            else if i < a.count && j < b.count { out.append(.same(a[i])); i += 1; j += 1 }
            else if i < a.count { out.append(.del(a[i])); i += 1 }
            else { out.append(.add(b[j])); j += 1 }
        }
        return out
    }

    /// Keep `context` unchanged lines around each change, collapse the rest.
    static func hunks(_ lines: [DiffLine], context: Int = 3) -> [DiffLine] {
        var keep = [Bool](repeating: false, count: lines.count)
        for (idx, l) in lines.enumerated() {
            if case .same = l { continue }
            for k in max(0, idx - context)...min(lines.count - 1, idx + context) { keep[k] = true }
        }
        var out: [DiffLine] = []
        var skipped = 0
        for (idx, l) in lines.enumerated() {
            if keep[idx] {
                if skipped > 0 { out.append(.gap(skipped)); skipped = 0 }
                out.append(l)
            } else { skipped += 1 }
        }
        if skipped > 0 { out.append(.gap(skipped)) }
        return out
    }

    static func stats(_ lines: [DiffLine]) -> (add: Int, del: Int) {
        var a = 0, d = 0
        for l in lines { if case .add = l { a += 1 } else if case .del = l { d += 1 } }
        return (a, d)
    }
}

struct DiffView: View {
    let path: String
    let old: String?
    let new: String
    var openFile: ((String) -> Void)? = nil
    var maxCollapsedLines = 400
    @State private var showAll = false

    var body: some View {
        let full = LineDiff.compute(old: old, new: new)
        let lines = showAll ? full : LineDiff.hunks(full)
        let s = LineDiff.stats(full)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Button { openFile?(path) } label: {
                    Text(path).font(.caption.monospaced()).lineLimit(1).truncationMode(.head)
                }.buttonStyle(.pressable(scale: 1))
                Spacer()
                Text("+\(s.add)").foregroundStyle(.green)
                Text("-\(s.del)").foregroundStyle(.red)
            }
            .font(.caption.monospaced())
            .padding(.horizontal, 8).padding(.vertical, 6)
            .background(Theme.hairline)

            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.prefix(showAll ? 5000 : maxCollapsedLines).enumerated()), id: \.offset) { _, l in
                        row(l)
                    }
                }
                .padding(.vertical, 4)
            }
            if lines.count > maxCollapsedLines && !showAll || full.count != lines.count {
                Button(showAll ? LocalizedStringKey("Show changes only") : LocalizedStringKey("Show whole file")) { showAll.toggle() }
                    .font(.caption).padding(8)
            }
        }
        .background(Theme.bgSunken)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.inset, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.inset, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    @ViewBuilder private func row(_ l: DiffLine) -> some View {
        switch l {
        case .same(let t): line(" ", t, bg: .clear, fg: .primary)
        case .add(let t): line("+", t, bg: Color.green.opacity(0.15), fg: .primary)
        case .del(let t): line("-", t, bg: Color.red.opacity(0.15), fg: .primary)
        case .gap(let n):
            Text("⋯ \(n) unchanged lines").font(.caption2).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.vertical, 3)
        }
    }

    private func line(_ sign: String, _ t: String, bg: Color, fg: Color) -> some View {
        Text(sign + " " + t)
            .font(.caption2.monospaced())
            .foregroundStyle(fg)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, 8).padding(.vertical, 1)
            .frame(minWidth: 0, alignment: .leading)
            .background(bg)
    }
}
