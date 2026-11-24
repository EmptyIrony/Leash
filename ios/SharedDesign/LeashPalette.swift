import SwiftUI

// Leash's colours, one source for every target (app, Live Activity, watch). Plain sRGB values, no UIKit, so this
// builds for watchOS too. Each target turns them into colours its own way:
// - the app: `Theme` builds dynamic light/dark colours from both values;
// - the watch (always dark): `WatchTheme` uses the dark values;
// - the Live Activity: its LA* asset colour sets (Assets.xcassets) hold these same values, so the system resolves
//   light/dark for the Lock Screen and the always-dark Dynamic Island. Change a value here and there together.

/// One colour role: a light and a dark 0xRRGGBB value, each with its own opacity.
struct PaletteColor: Sendable {
    let light: UInt32
    let dark: UInt32
    let lightAlpha: Double
    let darkAlpha: Double

    init(light: UInt32, dark: UInt32, lightAlpha: Double = 1, darkAlpha: Double = 1) {
        self.light = light
        self.dark = dark
        self.lightAlpha = lightAlpha
        self.darkAlpha = darkAlpha
    }

    /// The dark value as a fixed colour (the watch, which is always dark).
    var darkColor: Color { Color(paletteHex: dark, opacity: darkAlpha) }
}

extension Color {
    /// A fixed sRGB colour from 0xRRGGBB.
    init(paletteHex hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

/// The roles. "Orbit": a dark instrument panel floating over deep space.
enum LeashPalette {
    // Surfaces
    static let bgBase = PaletteColor(light: 0xF4F5FA, dark: 0x07080D)
    static let bgRaised = PaletteColor(light: 0xFFFFFF, dark: 0x12141C, darkAlpha: 0.85)
    static let bgSunken = PaletteColor(light: 0xECEEF5, dark: 0x0C0D14)
    /// The bottom stop of the space background gradient (top stop: bgBase).
    static let bgGradientEnd = PaletteColor(light: 0xE9ECF8, dark: 0x0B0E1A)
    static let hairline = PaletteColor(light: 0x0B1020, dark: 0xFFFFFF, lightAlpha: 0.08, darkAlpha: 0.08)

    // Text
    static let textPrimary = PaletteColor(light: 0x0B0D14, dark: 0xF2F3F8)
    static let textSecondary = PaletteColor(light: 0x5A6072, dark: 0x9AA0B4)
    static let textTertiary = PaletteColor(light: 0x8F95A6, dark: 0x646A7E)

    // Theme and functional colours
    static let accent = PaletteColor(light: 0x4B50E6, dark: 0x8088FF)
    /// An agent at work (Live Activity: LAWorking).
    static let running = PaletteColor(light: 0x0A9CB8, dark: 0x4FD8EE)
    /// Waiting for you: approvals, questions (LAAttention).
    static let attention = PaletteColor(light: 0xC96A00, dark: 0xFFB340)
    /// (LASuccess)
    static let success = PaletteColor(light: 0x1E9E5A, dark: 0x3DDC84)
    /// (LADanger)
    static let danger = PaletteColor(light: 0xD43A3A, dark: 0xFF6B6B)
    static let turbo = PaletteColor(light: 0xC0267E, dark: 0xFF5CA8)
    static let codeInk = PaletteColor(light: 0x3D41A8, dark: 0xB9BDFF)
    static let codeBg = PaletteColor(light: 0x4B50E6, dark: 0x8088FF, lightAlpha: 0.07, darkAlpha: 0.12)
    /// "!" shell mode (composer, command bubbles).
    static let shell = PaletteColor(light: 0x0F7B6C, dark: 0x5EE6C8)
}
