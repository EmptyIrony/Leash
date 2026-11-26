package dev.leash.personal.ui.theme

/**
 * Leash's colours, ported from ios/SharedDesign/LeashPalette.swift ("Orbit": a dark instrument panel floating over
 * deep space). Plain 0xRRGGBB values with an opacity each, so the palette has no Compose dependency and is unit-testable.
 * Change a value in iOS and here together.
 */
data class PaletteColor(
    val light: Long,
    val dark: Long,
    val lightAlpha: Float = 1f,
    val darkAlpha: Float = 1f,
)

object LeashPalette {
    // Surfaces
    val bgBase = PaletteColor(light = 0xF4F5FA, dark = 0x07080D)
    val bgRaised = PaletteColor(light = 0xFFFFFF, dark = 0x12141C, darkAlpha = 0.85f)
    val bgSunken = PaletteColor(light = 0xECEEF5, dark = 0x0C0D14)
    /** The bottom stop of the space background gradient (top stop: bgBase). */
    val bgGradientEnd = PaletteColor(light = 0xE9ECF8, dark = 0x0B0E1A)
    val hairline = PaletteColor(light = 0x0B1020, dark = 0xFFFFFF, lightAlpha = 0.08f, darkAlpha = 0.08f)

    // Text
    val textPrimary = PaletteColor(light = 0x0B0D14, dark = 0xF2F3F8)
    val textSecondary = PaletteColor(light = 0x5A6072, dark = 0x9AA0B4)
    val textTertiary = PaletteColor(light = 0x8F95A6, dark = 0x646A7E)

    // Theme and functional colours
    val accent = PaletteColor(light = 0x4B50E6, dark = 0x8088FF)
    /** An agent at work. */
    val running = PaletteColor(light = 0x0A9CB8, dark = 0x4FD8EE)
    /** Waiting for you: approvals, questions. */
    val attention = PaletteColor(light = 0xC96A00, dark = 0xFFB340)
    val success = PaletteColor(light = 0x1E9E5A, dark = 0x3DDC84)
    val danger = PaletteColor(light = 0xD43A3A, dark = 0xFF6B6B)
    val turbo = PaletteColor(light = 0xC0267E, dark = 0xFF5CA8)
    val codeInk = PaletteColor(light = 0x3D41A8, dark = 0xB9BDFF)
    val codeBg = PaletteColor(light = 0x4B50E6, dark = 0x8088FF, lightAlpha = 0.07f, darkAlpha = 0.12f)
    /** "!" shell mode (composer, command bubbles). */
    val shell = PaletteColor(light = 0x0F7B6C, dark = 0x5EE6C8)
}
