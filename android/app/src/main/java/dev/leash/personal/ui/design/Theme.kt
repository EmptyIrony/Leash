package dev.leash.personal.ui.design

import android.app.Activity
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Leash's design system root (DESIGN.md). Provides the colour and type tokens, press feedback instead of ripples,
 * reduced motion, and a Material colour scheme mapped from the tokens so the few wrapped Material pieces (sheet,
 * menu, pull-to-refresh) wear Leash's colours. Light/dark follows the system unless [dark] is given.
 * [systemBars] sets the status/navigation bar icon colour for this theme (off when nested in another theme).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeashDesignTheme(
    dark: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    hapticsEnabled: Boolean = true,
    systemBars: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = remember(dark, highContrast) { LeashDesignColors.of(dark, highContrast) }
    val type = LeashTypography.Default
    val reduced = remember { systemReducesMotion() }
    val indication = remember(reduced, colors) { LeashIndication(PressScale.Compact.value, reduced, focusRing = colors.accent) }
    val view = LocalView.current
    if (systemBars && !view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = materialScheme(colors),
        typography = materialType(type),
        // Material wants corner-based shapes; Leash components use ContinuousShape themselves
        shapes = Shapes(
            extraSmall = RoundedCornerShape(LeashRadius.inset),
            small = RoundedCornerShape(LeashRadius.row),
            medium = RoundedCornerShape(LeashRadius.card),
            large = RoundedCornerShape(LeashRadius.tray),
            extraLarge = RoundedCornerShape(topStart = LeashRadius.sheet, topEnd = LeashRadius.sheet),
        ),
    ) {
        CompositionLocalProvider(
            LocalLeashColors provides colors,
            LocalLeashType provides type,
            LocalReduceMotion provides reduced,
            LocalHapticsEnabled provides hapticsEnabled,
            LocalIndication provides indication,
            LocalRippleConfiguration provides null,
            LocalContentColor provides colors.textPrimary,
            LocalTextStyle provides type.body,
            LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

private fun materialScheme(c: LeashDesignColors) = if (c.dark) {
    darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.opaque(c.selectedFill), onPrimaryContainer = c.textPrimary,
        secondary = c.running, onSecondary = c.onAccent,
        secondaryContainer = c.opaque(c.bgRaised), onSecondaryContainer = c.textPrimary,
        tertiary = c.attention, onTertiary = c.onAccent,
        background = c.bgBase, onBackground = c.textPrimary,
        surface = c.bgBase, onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary,
        surfaceVariant = c.bgSunken, surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.bgSunken, surfaceContainerLow = c.opaque(c.bgRaised),
        surfaceContainer = c.opaque(c.bgRaised), surfaceContainerHigh = c.opaque(c.bgRaised), surfaceContainerHighest = c.opaque(c.bgRaised),
        outline = c.textTertiary, outlineVariant = c.opaque(c.hairline),
        error = c.danger, onError = c.onAccent, scrim = c.scrim,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.opaque(c.selectedFill), onPrimaryContainer = c.textPrimary,
        secondary = c.running, onSecondary = c.onAccent,
        secondaryContainer = c.bgRaised, onSecondaryContainer = c.textPrimary,
        tertiary = c.attention, onTertiary = c.onAccent,
        background = c.bgBase, onBackground = c.textPrimary,
        surface = c.bgBase, onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary,
        surfaceVariant = c.bgSunken, surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.bgRaised, surfaceContainerLow = c.bgRaised,
        surfaceContainer = c.bgRaised, surfaceContainerHigh = c.bgRaised, surfaceContainerHighest = c.bgSunken,
        outline = c.textTertiary, outlineVariant = c.opaque(c.hairline),
        error = c.danger, onError = c.onAccent, scrim = c.scrim,
    )
}

private fun materialType(t: LeashTypography) = Typography(
    displayLarge = t.largeTitle, displayMedium = t.largeTitle, displaySmall = t.title1,
    headlineLarge = t.title1, headlineMedium = t.title2, headlineSmall = t.title3,
    titleLarge = t.title2, titleMedium = t.headline, titleSmall = t.subheadline.semibold(),
    bodyLarge = t.body, bodyMedium = t.callout, bodySmall = t.footnote,
    labelLarge = t.subheadline.medium(), labelMedium = t.footnote.medium(), labelSmall = t.caption.medium(),
)
