package dev.leash.personal.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun PaletteColor.color(dark: Boolean): Color =
    if (dark) Color(0xFF000000 or this.dark).copy(alpha = darkAlpha) else Color(0xFF000000 or light).copy(alpha = lightAlpha)

/**
 * Every colour a Leash screen uses (DESIGN.md §2): the palette roles plus the derived alphas, named so a screen never
 * invents one. Built from [LeashPalette], shared with iOS.
 */
@Immutable
data class LeashDesignColors(
    val dark: Boolean,
    // surfaces
    val bgBase: Color,
    val bgGradientEnd: Color,
    val bgRaised: Color,
    val bgSunken: Color,
    val hairline: Color,
    // ink
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // meaning
    val accent: Color,
    /** iOS's AccentColor asset: the tint of system-style controls (prominent buttons, switch, toolbar glyphs). */
    val accentControl: Color,
    val running: Color,
    val attention: Color,
    val success: Color,
    val danger: Color,
    val turbo: Color,
    val codeInk: Color,
    val codeBg: Color,
    val shell: Color,
    // derived
    val onAccent: Color,
    val bubbleFill: Color,
    val shellBubbleFill: Color,
    val selectedFill: Color,
    val restFill: Color,
    val pressedOverlay: Color,
    val controlFill: Color,
    val diffAddBg: Color,
    val diffDelBg: Color,
    val needsYouBadge: Color,
    val glassTint: Color,
    /** The tint of liquid glass (API 33+): thinner than [glassTint], so the refraction shows through. */
    val glassLensTint: Color,
    /** The edge of liquid glass: its fill is nearly clear, so the dark line is brighter than [glassLineTop]. */
    val glassLensLineTop: Color,
    val glassLensLineBottom: Color,
    val glassFallback: Color,
    /** The glass edge: a 1 px outer line (light) or a top-to-bottom fading line (dark), plus a top inner highlight. */
    val glassLineTop: Color,
    val glassLineBottom: Color,
    val glassHighlight: Color,
    /** Sheets: page colour, with an edge in dark mode. */
    val bgSheet: Color,
    val sheetEdge: Color,
    /** Menus and popovers (separate windows, no blur). */
    val panel: Color,
    val segmentTrack: Color,
    val segmentThumb: Color,
    val shadow: Color,
    val scrim: Color,
    /** The shimmer band's colour: half accent, half ink. */
    val glint: Color,
) {
    companion object {
        fun of(dark: Boolean, highContrast: Boolean = false): LeashDesignColors {
            val p = LeashPalette
            val base = p.bgBase.color(dark)
            val accent = p.accent.color(dark)
            val ink = p.textPrimary.color(dark)
            val success = p.success.color(dark)
            val danger = p.danger.color(dark)
            val attention = p.attention.color(dark)
            val shell = p.shell.color(dark)
            val hairline = if (highContrast) p.hairline.color(dark).copy(alpha = 0.14f) else p.hairline.color(dark)
            return LeashDesignColors(
                dark = dark,
                bgBase = base,
                bgGradientEnd = p.bgGradientEnd.color(dark),
                bgRaised = p.bgRaised.color(dark),
                bgSunken = p.bgSunken.color(dark),
                hairline = hairline,
                textPrimary = ink,
                textSecondary = p.textSecondary.color(dark),
                textTertiary = if (highContrast) p.textSecondary.color(dark) else p.textTertiary.color(dark),
                accent = accent,
                accentControl = if (dark) Color(0xFF8A94FF) else Color(0xFF5E6DFC),
                running = p.running.color(dark),
                attention = attention,
                success = success,
                danger = danger,
                turbo = p.turbo.color(dark),
                codeInk = p.codeInk.color(dark),
                codeBg = p.codeBg.color(dark),
                shell = shell,
                onAccent = if (dark) Color(0xFF07080D) else Color.White,
                bubbleFill = accent.copy(alpha = 0.16f),
                shellBubbleFill = shell.copy(alpha = 0.13f),
                selectedFill = accent.copy(alpha = 0.12f),
                restFill = ink.copy(alpha = 0.04f),
                pressedOverlay = ink.copy(alpha = 0.06f),
                controlFill = ink.copy(alpha = if (dark) 0.10f else 0.06f),
                diffAddBg = success.copy(alpha = 0.15f),
                diffDelBg = danger.copy(alpha = 0.15f),
                needsYouBadge = attention.copy(alpha = 0.15f),
                glassTint = if (dark) Color(0xFF262A3A).copy(alpha = 0.70f) else Color.White.copy(alpha = 0.60f),
                glassLensTint = if (dark) Color(0xFF464C60).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.52f),
                glassLensLineTop = if (dark) Color.White.copy(alpha = 0.26f) else Color(0xFF0B1020).copy(alpha = 0.12f),
                glassLensLineBottom = if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFF0B1020).copy(alpha = 0.12f),
                glassFallback = if (dark) Color(0xFF1C1F2B).copy(alpha = 0.96f) else Color.White.copy(alpha = 0.94f),
                glassLineTop = if (dark) Color.White.copy(alpha = 0.14f) else Color(0xFF0B1020).copy(alpha = 0.12f),
                glassLineBottom = if (dark) Color.White.copy(alpha = 0.04f) else Color(0xFF0B1020).copy(alpha = 0.12f),
                glassHighlight = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.80f),
                bgSheet = if (dark) Color(0xFF0E1018) else base,
                sheetEdge = if (dark) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                panel = if (dark) Color(0xFF1C1F2B) else Color.White,
                segmentTrack = if (dark) Color.White.copy(alpha = 0.08f) else ink.copy(alpha = 0.06f),
                segmentThumb = if (dark) Color(0xFF3A3D4B) else Color.White,
                shadow = if (dark) Color.Black else Color(0xFF1E2250),
                scrim = if (dark) Color.Black.copy(alpha = 0.5f) else Color(0xFF0B1020).copy(alpha = 0.25f),
                glint = lerp(accent, ink, 0.5f),
            )
        }
    }

    /** An opaque version of a translucent role over the page (for Material slots that need opaque colours). */
    fun opaque(c: Color): Color = c.compositeOver(bgBase)
}

/** The type scale (DESIGN.md §3): iOS Dynamic Type "Large", one step tighter for body sizes, all in sp. */
@Immutable
data class LeashTypography(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val reading: TextStyle,
    val callout: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    val caption2: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
) {
    companion object {
        private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0, family: FontFamily = FontFamily.Default) =
            TextStyle(
                fontFamily = family,
                fontSize = size.sp,
                lineHeight = line.sp,
                fontWeight = weight,
                letterSpacing = tracking.sp,
                lineHeightStyle = trim,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )

        val Default = LeashTypography(
            largeTitle = style(32, 40, FontWeight.Bold, -0.4),
            title1 = style(26, 32, FontWeight.SemiBold, -0.2),
            title2 = style(21, 27, FontWeight.SemiBold, -0.2),
            title3 = style(19, 24, FontWeight.SemiBold, -0.2),
            headline = style(16, 22, FontWeight.SemiBold),
            body = style(16, 22),
            reading = style(16, 24),
            callout = style(15, 20),
            subheadline = style(14, 19),
            footnote = style(13, 18),
            caption = style(12, 16),
            caption2 = style(11, 13),
            mono = style(13, 18, family = FontFamily.Monospace),
            monoSmall = style(11, 14, family = FontFamily.Monospace),
        )
    }
}

fun TextStyle.medium() = copy(fontWeight = FontWeight.Medium)
fun TextStyle.semibold() = copy(fontWeight = FontWeight.SemiBold)
fun TextStyle.bold() = copy(fontWeight = FontWeight.Bold)
fun TextStyle.italic() = copy(fontStyle = FontStyle.Italic)
/** This style with its size following the system font scale only up to [max] (fixed-height chrome like the top bar). */
@Composable
fun TextStyle.capScale(max: Float): TextStyle {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    if (scale <= max) return this
    val f = max / scale
    return copy(fontSize = fontSize * f, lineHeight = lineHeight * f)
}

/** Tabular digits for numbers that tick (elapsed time, counters, quota). */
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/** The 4 dp grid (DESIGN.md §4). */
object LeashSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 6.dp
    val m = 8.dp
    val l = 12.dp
    val xl = 16.dp
    val xxl = 20.dp
    val xxxl = 24.dp
    val huge = 32.dp
    /** Transcript text, large title and grouped-list outer inset. */
    val page = 16.dp
    /** Android's minimum touch target. */
    val touch = 48.dp
}

val LocalLeashColors = staticCompositionLocalOf { LeashDesignColors.of(dark = false) }
val LocalLeashType = staticCompositionLocalOf { LeashTypography.Default }

/** Access to the design tokens inside [LeashDesignTheme]. */
object Leash {
    val colors: LeashDesignColors
        @Composable @ReadOnlyComposable get() = LocalLeashColors.current
    val type: LeashTypography
        @Composable @ReadOnlyComposable get() = LocalLeashType.current
}
