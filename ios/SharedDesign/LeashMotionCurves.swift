import SwiftUI

/// The animation curves every target shares (the app's `Motion`, the Live Activity, the watch). Each target reads
/// Reduce Motion its own way and passes it in; with it on, movement becomes a short ease (Apple HIG).
enum LeashMotionCurves {
    /// Things entering or leaving the layout: trays, banners, rows moving to new places.
    static func layout(reduced: Bool) -> Animation { reduced ? .easeInOut(duration: 0.2) : .smooth(duration: 0.34) }
    /// Expanding and collapsing rows, checklists, details.
    static func disclose(reduced: Bool) -> Animation { reduced ? .easeInOut(duration: 0.18) : .snappy(duration: 0.3) }
    /// Small state feedback: enabled ↔ disabled, a label or symbol changing.
    static func quick(reduced: Bool) -> Animation { reduced ? .easeInOut(duration: 0.15) : .smooth(duration: 0.22) }
    /// Touch-down feedback on custom buttons: in fast, settles without a bounce.
    static func press(reduced: Bool) -> Animation { reduced ? .easeOut(duration: 0.1) : .snappy(duration: 0.18) }
}
