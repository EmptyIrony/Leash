import SwiftUI

// Declarative haptics. Principles (Apple HIG "Playing haptics"):
//  • use sparingly and only where the user benefits; never for decoration
//  • keep meanings consistent with the system: selection = picking a value,
//    impact = a physical-feeling action, success/warning/error = an outcome
//  • always pair with a visible change; respect the user's settings
extension View {
    func haptic<T: Equatable>(_ feedback: SensoryFeedback, trigger: T, when: @escaping (T, T) -> Bool = { _, _ in true }) -> some View {
        modifier(HapticModifier(feedback: feedback, trigger: trigger, when: when))
    }
}

private struct HapticModifier<T: Equatable>: ViewModifier {
    let feedback: SensoryFeedback
    let trigger: T
    let when: (T, T) -> Bool
    @AppStorage("haptics") private var enabled = true

    func body(content: Content) -> some View {
        content.sensoryFeedback(feedback, trigger: trigger) { old, new in enabled && when(old, new) }
    }
}
