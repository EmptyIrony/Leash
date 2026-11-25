import SwiftUI
import UIKit

// "Orbit" design tokens: a dark instrument panel floating over deep space. The values live in `LeashPalette`
// (SharedDesign), shared with the watch and the Live Activity.
extension Color {
    /// A dynamic colour: the role's light value in light mode, its dark value in dark mode.
    init(palette p: PaletteColor) {
        self.init(uiColor: UIColor { t in
            t.userInterfaceStyle == .dark ? UIColor(hex: p.dark, alpha: CGFloat(p.darkAlpha)) : UIColor(hex: p.light, alpha: CGFloat(p.lightAlpha))
        })
    }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}

enum Theme {
    static let bgBase = Color(palette: LeashPalette.bgBase)
    static let bgRaised = Color(palette: LeashPalette.bgRaised)
    static let bgSunken = Color(palette: LeashPalette.bgSunken)
    static let hairline = Color(palette: LeashPalette.hairline)
    static let textPrimary = Color(palette: LeashPalette.textPrimary)
    static let textSecondary = Color(palette: LeashPalette.textSecondary)
    static let textTertiary = Color(palette: LeashPalette.textTertiary)
    static let accent = Color(palette: LeashPalette.accent)
    static let running = Color(palette: LeashPalette.running)
    static let attention = Color(palette: LeashPalette.attention)
    static let success = Color(palette: LeashPalette.success)
    static let danger = Color(palette: LeashPalette.danger)
    static let turbo = Color(palette: LeashPalette.turbo)
    static let codeInk = Color(palette: LeashPalette.codeInk)
    static let codeBg = Color(palette: LeashPalette.codeBg)
    /// "!" shell mode (composer, command bubbles).
    static let shell = Color(palette: LeashPalette.shell)

    /// Reading size for agent text (a notch below .body keeps long answers calm).
    static let readingSize: CGFloat = 16

    static let mono = Font.system(.footnote, design: .monospaced)
    static let monoSmall = Font.system(.caption2, design: .monospaced)

    /// Corner radii by role (always drawn continuous). Pills, capsules and circles are shapes, not radii.
    enum Radius {
        /// The composer's glass slab.
        static let composer: CGFloat = 26
        /// Glass trays docked above the composer: approval / question, queue, side question, command palette.
        static let tray: CGFloat = 22
        /// A sent message bubble; `bubbleTail` is its corner nearest the sender.
        static let bubble: CGFloat = 20
        static let bubbleTail: CGFloat = 6
        /// Raised blocks in the transcript: plan, tables, quota, load and lock notices; images in a bubble.
        static let card: CGFloat = 16
        /// Selectable rows, chips, thumbnails and inline images.
        static let row: CGFloat = 12
        /// Sunken boxes inside content: code blocks, diffs, shell output, inline errors.
        static let inset: CGFloat = 10
    }
}

extension View {
    /// Uppercase, tracked, expanded section label.
    func sectionLabelStyle() -> some View {
        self.font(.subheadline.weight(.semibold)).textCase(nil).foregroundStyle(Theme.textSecondary)
    }
}

/// Gradient + soft accent glow (+ a fixed starfield in dark mode).
struct SpaceBackground: View {
    var stars = true
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        if reduceTransparency {
            Theme.bgBase.ignoresSafeArea()
        } else {
            ZStack {
                LinearGradient(colors: [Theme.bgBase, Color(uiColor: UIColor(hex: scheme == .dark ? LeashPalette.bgGradientEnd.dark : LeashPalette.bgGradientEnd.light))],
                               startPoint: .top, endPoint: .bottom)
                RadialGradient(colors: [Theme.accent.opacity(0.10), .clear], center: .topTrailing, startRadius: 0, endRadius: 320)
                if stars && scheme == .dark { Starfield() }
            }
            .ignoresSafeArea()
        }
    }
}

struct Starfield: View {
    var body: some View {
        Canvas { ctx, size in
            var rng = SeededRandom(seed: 42)
            for _ in 0..<60 {
                let x = rng.next() * size.width, y = rng.next() * size.height
                let r = 0.5 + rng.next() * 0.25
                let o = 0.25 + rng.next() * 0.35
                ctx.fill(Path(ellipseIn: CGRect(x: x, y: y, width: r * 2, height: r * 2)), with: .color(.white.opacity(o)))
            }
        }
        .allowsHitTesting(false)
    }
}

struct SeededRandom {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double((state >> 33) & 0xFFFFFF) / Double(0xFFFFFF)
    }
}

// MARK: - Motion

/// The app's only animation vocabulary. Every withAnimation / .animation / .transition uses these, so trays,
/// disclosures and small state changes move the same way everywhere. With Reduce Motion on, movement becomes
/// a short cross-fade (Apple HIG).
enum Motion {
    private static var reduced: Bool { UIAccessibility.isReduceMotionEnabled }

    // The curves themselves are in `LeashMotionCurves` (SharedDesign), shared with the watch and the Live Activity.
    /// Things entering or leaving the layout: trays, banners, the scroll-to-bottom button.
    static var layout: Animation { LeashMotionCurves.layout(reduced: reduced) }
    /// Expanding and collapsing rows, checklists, details.
    static var disclose: Animation { LeashMotionCurves.disclose(reduced: reduced) }
    /// Small state feedback: enabled ↔ disabled, a label or symbol changing.
    static var quick: Animation { LeashMotionCurves.quick(reduced: reduced) }
    /// Touch-down feedback on custom buttons: in fast, settles without a bounce.
    static var press: Animation { LeashMotionCurves.press(reduced: reduced) }

    /// Glass docked above the composer (approval / question trays): the glass itself materializes through
    /// `glassEffectTransition(.materialize)`; this is only for its contents.
    static var tray: AnyTransition { reduced ? .opacity : AnyTransition(.blurReplace) }
    /// Under the navigation bar (other-conversation banner).
    static var banner: AnyTransition { reduced ? .opacity : .move(edge: .top).combined(with: .opacity) }
    /// Content revealed by a disclosure.
    static var reveal: AnyTransition { reduced ? .opacity : .asymmetric(insertion: .opacity.combined(with: .offset(y: -6)), removal: .opacity) }
    /// A control appearing in place (stop button, scroll button).
    static var pop: AnyTransition { reduced ? .opacity : .scale(scale: 0.9).combined(with: .opacity) }
    /// One line of text replaced by another (live status).
    static var swap: AnyTransition { reduced ? .opacity : AnyTransition(.blurReplace) }
    /// A message the user just sent rising from the composer into the transcript.
    static var sent: AnyTransition { reduced ? .opacity : .asymmetric(insertion: .offset(y: 24).combined(with: .opacity), removal: .opacity) }
    /// A secondary control sliding out from behind the control at its trailing side (stop next to send).
    static var trailingPop: AnyTransition { reduced ? .opacity : .scale(scale: 0.6, anchor: .trailing).combined(with: .opacity) }
}

// MARK: - Press feedback

/// Touch-down feedback for custom (otherwise `.plain`) buttons, so they answer a press the way system buttons do:
/// the label shrinks slightly and dims, then springs back on release. With Reduce Motion on, it only dims.
/// The label is used as is: its own `contentShape` still decides the hit area, and the disabled look is left to the
/// label (as with `.plain`).
///
/// - Compact controls (circles, chips, small icons): `.buttonStyle(.pressable)`.
/// - Full-width rows: `.buttonStyle(.pressable(scale: 0.98))`.
/// - Text links and disclosure headers, where scaling a block of text would wobble: `.pressable(scale: 1)` (dims only).
/// - Not for Menus, `.glassEffect(.interactive())` controls (glass has its own press response) or List rows.
struct PressableButtonStyle: ButtonStyle {
    var scale: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        PressableButtonLabel(label: configuration.label, pressed: configuration.isPressed, scale: scale)
    }
}

/// Reads Reduce Motion from the environment (a nested view, so the value is current inside the style).
private struct PressableButtonLabel: View {
    let label: ButtonStyleConfiguration.Label
    let pressed: Bool
    let scale: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var currentScale: CGFloat { pressed && !reduceMotion ? scale : 1 }

    var body: some View {
        label
            .scaleEffect(currentScale)
            .opacity(pressed ? 0.85 : 1)
            .animation(Motion.press, value: pressed)
    }
}

extension ButtonStyle where Self == PressableButtonStyle {
    /// Shrink-and-dim press feedback for a compact custom control.
    static var pressable: PressableButtonStyle { .init() }
    /// Press feedback with a custom shrink: 0.98 for full-width rows, 1 to only dim.
    static func pressable(scale: CGFloat) -> PressableButtonStyle { .init(scale: scale) }
}

// MARK: - Activity

/// The system activity indicator, sized for the line it sits in. (Replaces the old orbiting dot.)
struct ActivitySpinner: View {
    var size: CGFloat = 16
    var color: Color = Theme.textSecondary

    var body: some View {
        ProgressView()
            .progressViewStyle(.circular)
            .controlSize(size < 15 ? .mini : (size < 21 ? .small : .regular))
            .tint(color)
            .frame(width: size, height: size)
            .accessibilityLabel("Working")
    }
}

/// A soft highlight sweeping across text, like Apple Intelligence's "thinking" labels.
/// The band is painted source-atop onto the content's own pixels: masking with a second copy of the content laid it
/// out again, and a middle-truncated or mid-transition line came out different there, leaving ghost glyphs.
struct Shimmer: ViewModifier {
    /// Half accent, half ink: the band carries the live glyph's hue across the words.
    static let glint = Theme.accent.mix(with: Theme.textPrimary, by: 0.5)

    var active = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if active && !reduceMotion {
            content.overlay {
                TimelineView(.animation) { tl in
                    let period = 2.0
                    let t = tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
                    GeometryReader { g in
                        let band = max(g.size.width * 0.45, 60)
                        LinearGradient(colors: [.clear, Shimmer.glint.opacity(0.9), .clear], startPoint: .leading, endPoint: .trailing)
                            .frame(width: band)
                            .offset(x: -band + (g.size.width + band * 2) * t)
                    }
                }
                .blendMode(.sourceAtop)
                .allowsHitTesting(false)
            }
            .compositingGroup()
        } else {
            content
        }
    }
}

extension View {
    func shimmer(_ active: Bool = true) -> some View { modifier(Shimmer(active: active)) }
}

/// Small progress ring (quota, context, steps).
struct RingGauge: View {
    var fraction: Double?        // nil = unknown (dashed)
    var size: CGFloat = 14
    var lineWidth: CGFloat = 2
    var tint: Color = Theme.accent

    var body: some View {
        ZStack {
            Circle().stroke(Theme.textTertiary.opacity(0.35), style: StrokeStyle(lineWidth: lineWidth, dash: fraction == nil ? [2, 2] : []))
            if let f = fraction {
                Circle().trim(from: 0, to: max(0.02, min(1, f)))
                    .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
        }
        .frame(width: size, height: size)
    }
}

/// "12.4k", "1.2M"
func compactNumber(_ n: Int) -> String {
    let d = Double(n)
    if d >= 1_000_000 { return String(format: d >= 10_000_000 ? "%.0fM" : "%.1fM", d / 1_000_000) }
    if d >= 1_000 { return String(format: d >= 10_000 ? "%.0fk" : "%.1fk", d / 1_000) }
    return "\(n)"
}

/// "48s", "2m 14s", "1h 3m" — localized by the system ("2分钟14秒").
func durationText(_ seconds: Double) -> String {
    let s = max(0, Int64(seconds.rounded()))
    return Duration.seconds(s).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .narrow, maximumUnitCount: 2))
}
