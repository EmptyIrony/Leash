import Foundation

struct QuotaBucket: Identifiable, Hashable {
    let id: String
    let window: String          // 5h | weekly | daily | monthly | other
    let fraction: Double?       // remaining 0...1
    let reset: Date?

    /// Compact window tag for chips: "5h", "wk".
    var shortLabel: String {
        switch window {
        case "5h": return String(localized: "5h")
        case "weekly": return String(localized: "wk")
        case "daily": return String(localized: "day")
        default: return window
        }
    }

    var windowLabel: String {
        switch window {
        case "5h": return String(localized: "5-hour")
        case "weekly": return String(localized: "Weekly")
        case "daily": return String(localized: "Daily")
        case "monthly": return String(localized: "Monthly")
        default: return window.capitalized
        }
    }
}

struct QuotaPool: Identifiable, Hashable {
    let id: String
    let label: String
    var model: String? = nil       // set for model-scoped pools (e.g. "Fable")
    let buckets: [QuotaBucket]
    var fiveHour: QuotaBucket? { buckets.first { $0.window == "5h" } }
    var weekly: QuotaBucket? { buckets.first { $0.window == "weekly" } }
    var minFraction: Double? { buckets.compactMap(\.fraction).min() }
    /// The bucket that limits you first (lowest remaining).
    var tightest: QuotaBucket? { buckets.filter { $0.fraction != nil }.min { ($0.fraction ?? 1) < ($1.fraction ?? 1) } }
}

struct QuotaInfo: Hashable {
    let planTier: String?
    let pools: [QuotaPool]
    let fetchedAt: Date

    init?(_ j: JSON?) {
        guard let j, !j.isNull, let pools = j["pools"]?.array else { return nil }
        planTier = j["planTier"]?.string
        fetchedAt = j["fetchedAt"]?.double.map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date()
        let iso = ISO8601DateFormatter()
        self.pools = pools.compactMap { p in
            guard let id = p["id"]?.string else { return nil }
            let buckets: [QuotaBucket] = (p["buckets"]?.array ?? []).compactMap { b in
                guard let bid = b["id"]?.string else { return nil }
                return QuotaBucket(id: bid, window: b["window"]?.string ?? "other", fraction: b["remainingFraction"]?.double,
                                   reset: b["resetTime"]?.string.flatMap { iso.date(from: $0) })
            }
            return QuotaPool(id: id, label: p["label"]?.string ?? id, model: p["model"]?.string, buckets: buckets)
        }
    }

    /// The pool a model draws from: Claude/GPT share "3p", Argon has its own weekly pool, the rest is Gemini.
    func pool(forModel value: String?) -> QuotaPool? {
        let v = (value ?? "").lowercased()
        func byId(_ ids: [String]) -> QuotaPool? {
            for id in ids { if let p = pools.first(where: { $0.id == id }) { return p } }
            return nil
        }
        if v.contains("claude") || v.contains("gpt") || v.contains("oss") { return byId(["3p"]) ?? pools.first { $0.id.hasPrefix("3p") } }
        if v.contains("argon") { return byId(["gemini-argon-wtu", "gemini-argon"]) ?? byId(["gemini"]) }
        return byId(["gemini"]) ?? pools.first
    }

    /// The limiting (pool, bucket) for a model on a given agent.
    /// Claude: the general pool plus any model-scoped pool (e.g. Fable); Antigravity: the pool the model draws from.
    func limiting(forModel value: String?, agent: String) -> (pool: QuotaPool, bucket: QuotaBucket)? {
        var candidates: [QuotaPool]
        if agent == "claude" {
            let v = (value ?? "").lowercased()
            candidates = pools.filter { $0.model == nil || v.contains(($0.model ?? "").lowercased()) }
        } else {
            candidates = pool(forModel: value).map { [$0] } ?? []
        }
        var best: (QuotaPool, QuotaBucket)? = nil
        for p in candidates { if let b = p.tightest, (best == nil || (b.fraction ?? 1) < (best!.1.fraction ?? 1)) { best = (p, b) } }
        return best.map { (pool: $0.0, bucket: $0.1) }
    }

    /// The pool closest to running out.
    var mostConstrained: QuotaPool? { pools.min { ($0.minFraction ?? 1) < ($1.minFraction ?? 1) } }
}
