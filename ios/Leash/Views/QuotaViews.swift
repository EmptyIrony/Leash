import SwiftUI

func quotaTint(_ f: Double?) -> Color {
    guard let f else { return Theme.textTertiary }
    if f < 0.15 { return Theme.danger }
    if f < 0.40 { return Theme.attention }
    return Theme.textSecondary
}

/// Two concentric rings: outer = weekly, inner = 5-hour, for the most constrained pool.
struct QuotaRings: View {
    let pool: QuotaPool?
    var known = true
    var size: CGFloat = 22
    var body: some View {
        let tint = quotaTint(pool?.minFraction)
        ZStack {
            RingGauge(fraction: pool?.weekly?.fraction ?? (known ? pool?.minFraction : nil), size: size, lineWidth: 2.5, tint: tint)
            RingGauge(fraction: pool?.fiveHour?.fraction, size: size - 9, lineWidth: 2.5, tint: tint)
        }
        .accessibilityLabel(pool.map { "\($0.label) quota \(Int(($0.minFraction ?? 0) * 100)) percent left" } ?? "Quota unknown")
    }
}

struct QuotaSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var refreshing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    ForEach(agentIds, id: \.self) { id in agentSection(id) }
                }
                .padding(16)
            }
            .navigationTitle("Quota")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        refreshing = true
                        Task { await model.refreshQuota(force: true); refreshing = false }
                    } label: {
                        if refreshing { ActivitySpinner(size: 16) } else { Image(systemName: "arrow.clockwise") }
                    }
                    .disabled(refreshing)
                }
            }
        }
    }
}

extension QuotaSheet {
    var agentIds: [String] { model.agents.isEmpty ? ["agy"] : model.agents.map(\.id) }

    @ViewBuilder func agentSection(_ id: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if agentIds.count > 1 {
                HStack(spacing: 8) {
                    AgentIcon(agent: id, size: 16)
                    Text(verbatim: model.agentLabel(id)).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                }
            }
            if let q = model.quotas[id] {
                ForEach(q.pools) { pool in PoolCard(pool: pool) }
                (Text("Updated \(q.fetchedAt.formatted(.relative(presentation: .named)))") + Text(verbatim: q.planTier.map { " · " + $0 } ?? ""))
                    .font(.footnote).foregroundStyle(Theme.textTertiary)
                if let e = model.quotaErrors[id] { Label(e, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(Theme.attention) }
            } else {
                HStack(spacing: 10) {
                    ActivitySpinner(size: 18)
                    Group { if let e = model.quotaErrors[id] { Text(verbatim: e) } else { Text("Reading quota…") } }
                        .font(.footnote).foregroundStyle(Theme.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12)
            }
        }
    }
}

struct PoolCard: View {
    let pool: QuotaPool
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(pool.label).font(.headline).fontWidth(.expanded)
            ForEach(pool.buckets) { b in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(b.windowLabel).font(.subheadline.weight(.medium))
                        Spacer()
                        Group {
                            if let f = b.fraction { Text("\(Int((f * 100).rounded()))% left") } else { Text(verbatim: "—") }
                        }
                        .font(.subheadline.monospacedDigit()).foregroundStyle(quotaTint(b.fraction))
                    }
                    CapacityBar(fraction: b.fraction ?? 0, tint: (b.fraction ?? 1) < 0.4 ? quotaTint(b.fraction) : Theme.accent)
                    if let r = b.reset {
                        Text(resetText(r)).font(.caption).foregroundStyle(Theme.textTertiary)
                    }
                }
            }
        }
        .padding(16)
        .background(Theme.bgRaised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func resetText(_ d: Date) -> String {
        let s = d.timeIntervalSinceNow
        if s <= 0 { return String(localized: "Resetting now") }
        let day = Calendar.current.isDateInToday(d) ? d.formatted(date: .omitted, time: .shortened) : d.formatted(.dateTime.weekday(.abbreviated).hour().minute())
        let left = durationText(s)
        return String(localized: "Resets \(day) (in \(left))")
    }
}

struct CapacityBar: View {
    let fraction: Double
    var tint: Color = Theme.accent
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.hairline)
                Capsule().fill(tint).frame(width: max(4, geo.size.width * min(1, max(0, fraction))))
            }
        }
        .frame(height: 6)
    }
}
