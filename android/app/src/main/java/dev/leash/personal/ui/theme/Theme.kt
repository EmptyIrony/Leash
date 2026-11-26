package dev.leash.personal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The palette role's colour for the current mode, with its own opacity. */
fun PaletteColor.resolve(dark: Boolean): Color =
    if (dark) rgb(this.dark, darkAlpha) else rgb(light, lightAlpha)

private fun rgb(hex: Long, alpha: Float) = Color(0xFF000000 or hex).copy(alpha = alpha)

/** Composites a translucent role over a base so Material surface roles stay opaque. */
private fun Color.over(base: Color): Color {
    val a = alpha
    return Color(
        red = red * a + base.red * (1 - a),
        green = green * a + base.green * (1 - a),
        blue = blue * a + base.blue * (1 - a),
        alpha = 1f,
    )
}

/** Leash roles that Material's ColorScheme has no slot for. */
@Immutable
data class LeashColors(
    val bgGradientEnd: Color,
    val hairline: Color,
    val textTertiary: Color,
    val running: Color,
    val attention: Color,
    val success: Color,
    val turbo: Color,
    val codeInk: Color,
    val codeBg: Color,
    val shell: Color,
    val sunken: Color,
    val dark: Boolean,
)

val LocalLeashColors = staticCompositionLocalOf<LeashColors> { error("LeashTheme missing") }

/** Leash-specific colours next to `MaterialTheme.colorScheme`. */
object LeashTheme {
    val colors: LeashColors
        @Composable @ReadOnlyComposable get() = LocalLeashColors.current
}

internal fun leashColorScheme(dark: Boolean): ColorScheme {
    val p = LeashPalette
    val base = p.bgBase.resolve(dark)
    val raised = p.bgRaised.resolve(dark).over(base)
    val sunken = p.bgSunken.resolve(dark)
    val accent = p.accent.resolve(dark)
    val primary = p.textPrimary.resolve(dark)
    val secondary = p.textSecondary.resolve(dark)
    val danger = p.danger.resolve(dark)
    val hairline = p.hairline.resolve(dark).over(base)
    // The accent is dark in light mode and light in dark mode; text on it flips the other way.
    val onAccent = if (dark) Color(0xFF07080D) else Color.White
    val accentContainer = accent.copy(alpha = if (dark) 0.22f else 0.12f).over(base)
    return if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = accentContainer, onPrimaryContainer = primary,
            secondary = p.running.resolve(true), onSecondary = Color(0xFF07080D),
            secondaryContainer = raised, onSecondaryContainer = primary,
            tertiary = p.attention.resolve(true), onTertiary = Color(0xFF07080D),
            background = base, onBackground = primary,
            surface = base, onSurface = primary, onSurfaceVariant = secondary,
            surfaceVariant = sunken,
            surfaceContainerLowest = sunken, surfaceContainerLow = base,
            surfaceContainer = raised, surfaceContainerHigh = raised, surfaceContainerHighest = raised,
            outline = p.textTertiary.resolve(true), outlineVariant = hairline,
            error = danger, onError = Color(0xFF07080D),
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = accentContainer, onPrimaryContainer = primary,
            secondary = p.running.resolve(false), onSecondary = Color.White,
            secondaryContainer = raised, onSecondaryContainer = primary,
            tertiary = p.attention.resolve(false), onTertiary = Color.White,
            background = base, onBackground = primary,
            surface = base, onSurface = primary, onSurfaceVariant = secondary,
            surfaceVariant = sunken,
            surfaceContainerLowest = raised, surfaceContainerLow = base,
            surfaceContainer = raised, surfaceContainerHigh = raised, surfaceContainerHighest = sunken,
            outline = p.textTertiary.resolve(false), outlineVariant = hairline,
            error = danger, onError = Color.White,
        )
    }
}

internal fun leashColors(dark: Boolean): LeashColors {
    val p = LeashPalette
    val base = p.bgBase.resolve(dark)
    return LeashColors(
        bgGradientEnd = p.bgGradientEnd.resolve(dark),
        hairline = p.hairline.resolve(dark),
        textTertiary = p.textTertiary.resolve(dark),
        running = p.running.resolve(dark),
        attention = p.attention.resolve(dark),
        success = p.success.resolve(dark),
        turbo = p.turbo.resolve(dark),
        codeInk = p.codeInk.resolve(dark),
        codeBg = p.codeBg.resolve(dark).over(base),
        shell = p.shell.resolve(dark),
        sunken = p.bgSunken.resolve(dark),
        dark = dark,
    )
}

/** Corner radii by role, from Theme.Radius on iOS. */
object LeashRadius {
    val composer = 26.dp
    val tray = 22.dp
    val bubble = 20.dp
    val bubbleTail = 6.dp
    val card = 16.dp
    val row = 12.dp
    val inset = 10.dp
}

private val LeashShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(LeashRadius.inset),
    small = androidx.compose.foundation.shape.RoundedCornerShape(LeashRadius.row),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(LeashRadius.card),
    large = androidx.compose.foundation.shape.RoundedCornerShape(LeashRadius.tray),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(LeashRadius.composer),
)

/** Feeds [LeashTheme.colors] from the design theme while screens move over to `Leash.colors` (removed with this file in D2). */
@Composable
fun LeashLegacyColors(content: @Composable () -> Unit) {
    val dark = dev.leash.personal.ui.design.Leash.colors.dark
    androidx.compose.runtime.CompositionLocalProvider(LocalLeashColors provides leashColors(dark), content = content)
}

/** Light or dark follows the system; the palette is Leash's, not dynamic colour. */
@Composable
fun LeashTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLeashColors provides leashColors(dark)) {
        MaterialTheme(
            colorScheme = leashColorScheme(dark),
            shapes = LeashShapes,
            content = content,
        )
    }
}
