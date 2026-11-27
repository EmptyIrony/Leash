package dev.leash.personal.ui.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeBlurStyleScope
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizePoint
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Glass (DESIGN.md §6): where iOS uses Liquid Glass. Over whatever is marked [glassSource] inside the same
 * [GlassScope]: on API 33+ refracting glass (haze-glass), on 32 a backdrop blur; elsewhere (or outside a scope) a
 * near-opaque solid fill.
 */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** Real blur needs API 31; 31 itself stays on the solid fallback (RenderNode invalidation bugs there). */
val glassBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= 32

/** Refraction needs runtime shaders (API 33). haze-glass's own fallback has no blur, so 32 stays on [hazeBlur]. */
val glassLensSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

/** Whether glass over a source is drawn as liquid glass here (else blurred or solid). */
@Composable
fun glassLensActive(): Boolean = glassLensSupported && LocalGlassLensAllowed.current && LocalGlassBlurAllowed.current

object GlassDefaults {
    val blur: Dp = 24.dp
}

/** A screen region whose content can be seen, blurred, through the glass above it. */
@Composable
fun GlassScope(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalGlassState provides state) {
        Box(modifier, content = content)
    }
}

/**
 * The content glass blurs (the scrolling transcript, the list). Glass *inside* it can't blur its own source, so it is
 * given no state there and draws its solid fallback.
 */
@Composable
fun GlassSource(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glassSource()) {
        val scope = this
        CompositionLocalProvider(LocalGlassState provides null) { scope.content() }
    }
}

/** Marks content that glass in the same [GlassScope] blurs; prefer [GlassSource], which also stops glass inside it from blurring. */
@Composable
fun Modifier.glassSource(): Modifier {
    val state = LocalGlassState.current ?: return this
    return this.hazeSource(state)
}

/** Whether glass may blur at all: off in battery saver (and later with the app's Reduce transparency switch). */
val LocalGlassBlurAllowed = staticCompositionLocalOf { true }

/** Off draws API 33+ like 32: the debug catalog's `lens` extra, to compare the two on one device. */
val LocalGlassLensAllowed = staticCompositionLocalOf { true }

/**
 * A glass surface in [shape] (DESIGN.md §6): floating shadow, the glass, a meaning [tint] and the rim that carries the
 * shape. Glass never sits on glass.
 *
 * On API 33+ every surface over a source is liquid glass: it blurs and refracts what is behind it, in circular rather
 * than continuous corners, and with [interactive] lights up under the finger (iOS `.interactive()`; the press shrink
 * stays with `pressable`). [clear] keeps a surface see-through however tall it grows (the composer). Below API 33 [blur] is the budget: only the bands blur (top edge, bottom dock, a lone tray),
 * circles and chips inside a band pass blur = false and draw the tint alone.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color = Color.Unspecified,
    shadow: Boolean = true,
    blur: Boolean = true,
    interactive: Boolean = false,
    clear: Boolean = false,
): Modifier {
    val c = Leash.colors
    val state = LocalGlassState.current
    val lensShape = if (state != null && glassLensActive()) {
        shape.lensShape()
    } else {
        null
    }
    if (state != null && lensShape != null) {
        val optics = if (clear) ClearOptics else LeashOptics
        val style = remember(c, lensShape, interactive, optics) { lensStyle(c, lensShape, interactive, optics) }
        var m = this
        if (shadow) m = m.floatingShadow(lensShape)
        m = m.clip(lensShape).hazeGlass(HazeInput.Sources(state), style)
        if (tint.isSpecified) m = m.background(tint)
        // the shader draws the highlights; the hairline still carries the shape on a light page
        return m.border(Dp.Hairline, Brush.verticalGradient(listOf(c.glassLensLineTop, c.glassLensLineBottom)), lensShape)
    }
    var m = this
    if (shadow) m = m.floatingShadow(shape)
    m = m.clip(shape)
    val blurs = blur && state != null && glassBlurSupported && LocalGlassBlurAllowed.current
    val style = remember(c) {
        blurStyle(c.bgBase, GlassDefaults.blur) {
            colorEffects(listOf(HazeColorEffect.tint(c.glassTint)))
            fallbackColorEffect(HazeColorEffect.tint(c.glassFallback))
        }
    }
    m = if (blurs) {
        m.hazeBlur(HazeInput.Sources(state), style)
    } else {
        m.background(if (blur) c.glassFallback else c.glassTint)
    }
    if (tint.isSpecified) m = m.background(tint)
    return m.glassRim(shape)
}

private fun Shape.lensShape(): RoundedCornerShape? = when (this) {
    is RoundedCornerShape -> this
    is ContinuousShape -> rounded()
    else -> null
}

/**
 * Matched to the iPhone's composer and chips: small controls are nearly clear, so the transcript shows through them, and
 * large trays frost over so their own text stays legible. Only a narrow rim bends light: the library's Regular bends up
 * to 48 dp over a 20 dp edge, which on a 32 dp chip is the whole surface and mirrors the text behind its label. Depth
 * stays 1: mixing the sharp backdrop back in doubles every glyph.
 */
private val LeashOptics = GlassOptics.regular.copy(
    depth = OpticalSizeValue.Fixed(1f),
    blurRadius = OpticalSizeValue.Responsive(OpticalSizePoint(64.dp, 2.5.dp), OpticalSizePoint(160.dp, 16.dp)),
    refractionDisplacement = 16.dp,
    refractionProfile = RefractionProfile.Edge(9.dp),
)

/** The composer's: it grows with its text but stays see-through, with just enough more blur to keep typed lines apart. */
private val ClearOptics = LeashOptics.copy(
    blurRadius = OpticalSizeValue.Responsive(OpticalSizePoint(64.dp, 2.5.dp), OpticalSizePoint(160.dp, 5.dp)),
)

/**
 * Regular glass in Leash's colours. haze-glass picks its light or dark values by the *system* appearance, so every value
 * that differs between the two is written here from the app's theme.
 */
private fun lensStyle(c: LeashDesignColors, shape: RoundedCornerShape, interactive: Boolean, optics: GlassOptics) = GlassStyle {
    shape(shape)
    optics(optics)
    tint(c.glassLensTint)
    specularIntensity(if (c.dark) 0.5f else 0.55f)
    // the library's darker rim in dark mode painted over the hairline that carries the shape on straight edges
    edgeShadow(Color.Black.copy(alpha = 0.10f))
    // the clip already antialiases the edge; a softened rim lets the drop shadow under it show as a dark inner line
    edgeSoftness(0.dp)
    ambientResponse(if (c.dark) 0.14f else 0.12f)
    contrast(if (c.dark) 0.08f else 0f)
    whitePoint(if (c.dark) -0.15f else 0.1f)
    chromaMultiplier(1.1f)
    if (interactive) pressed {
        lightingIntensity(1f)
        refractionMultiplier(1.25f)
    }
}

/** The blur settings every Haze effect here shares: noise off, composited over the page colour. */
private fun blurStyle(background: Color, radius: Dp, more: HazeBlurStyleScope.() -> Unit) = HazeBlurStyle {
    backgroundColor(background)
    blurRadius(radius)
    noiseFactor(0f)
    more()
}

/** The edge that makes glass read as glass: a hairline outer line and, unless a shader lights it, a soft top highlight. */
@Composable
fun Modifier.glassRim(shape: Shape, highlight: Boolean = true): Modifier {
    val c = Leash.colors
    val top = if (!highlight) this else drawWithContent {
        drawContent()
        // inner highlight: white fading out over the top 6 dp
        val h = 6.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(c.glassHighlight, Color.Transparent), startY = 0f, endY = h), size = Size(size.width, h))
    }
    return top.border(Dp.Hairline, Brush.verticalGradient(listOf(c.glassLineTop, c.glassLineBottom)), shape)
}

/** The soft shadow under floating things (glass controls, panels). */
@Composable
fun Modifier.floatingShadow(shape: Shape, panel: Boolean = false): Modifier {
    val c = Leash.colors
    val shadow = if (c.dark) {
        Shadow(radius = if (panel) 24.dp else 12.dp, color = Color.Black, offset = DpOffset(0.dp, if (panel) 8.dp else 2.dp), alpha = 0.4f)
    } else {
        Shadow(radius = if (panel) 24.dp else 10.dp, color = c.shadow, offset = DpOffset(0.dp, if (panel) 8.dp else 2.dp), alpha = if (panel) 0.14f else 0.10f)
    }
    return this.dropShadow(shape, shadow)
}

/**
 * The soft top edge (iOS scrollEdgeEffectStyle(.soft)): content scrolling under a transparent top bar fades into a
 * progressive blur and a page-coloured scrim. Put it behind the bar, sized from the top of the screen to 24 dp below
 * the bar.
 */
@Composable
fun Modifier.softTopEdge(): Modifier {
    val c = Leash.colors
    val state = LocalGlassState.current
    val scrim = Brush.verticalGradient(0f to c.bgBase.copy(alpha = 0.92f), 0.55f to c.bgBase.copy(alpha = 0.6f), 1f to Color.Transparent)
    val style = remember(c) {
        blurStyle(c.bgBase, 16.dp) { progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)) }
    }
    var m = this
    // progressive blur needs runtime shaders (API 33); below that the scrim alone
    if (state != null && Build.VERSION.SDK_INT >= 33 && LocalGlassBlurAllowed.current) {
        m = m.hazeBlur(HazeInput.Sources(state), style)
    }
    return m.background(scrim)
}

/**
 * The bottom edge (IMG_5524): content scrolls on under the composer and chips to the screen bottom. Over liquid glass
 * [DockFade][dev.leash.personal.ui.design.components.DockFade] gives it only a low band, which dims towards the screen
 * edge with a trace of blur; under blurred or solid glass it covers the whole dock, blurred and faded harder so no sharp
 * text shows between the slab and the chips.
 */
@Composable
fun Modifier.softBottomEdge(blur: Boolean = true): Modifier {
    val c = Leash.colors
    val state = LocalGlassState.current
    val lens = glassLensActive()
    val style = remember(c, lens) {
        blurStyle(c.bgBase, if (lens) 2.dp else 12.dp) {
            mask(Brush.verticalGradient(0f to Color.Transparent, (if (lens) 0.6f else 0.25f) to Color.Black, 1f to Color.Black))
        }
    }
    var m = this
    if (!blur) {
        // the list: rows stay crisp between the bottom circles, only a light fade towards the page colour
        return m.background(Brush.verticalGradient(0f to Color.Transparent, 1f to c.bgGradientEnd.copy(alpha = 0.6f)))
    }
    if (state != null && glassBlurSupported && LocalGlassBlurAllowed.current) {
        m = m.hazeBlur(HazeInput.Sources(state), style)
    }
    val end = c.bgGradientEnd
    return m.background(
        if (lens) Brush.verticalGradient(0f to Color.Transparent, 0.5f to end.copy(alpha = 0.35f), 1f to end.copy(alpha = 0.8f))
        else Brush.verticalGradient(0f to Color.Transparent, 0.3f to end.copy(alpha = 0.6f), 1f to end.copy(alpha = 0.85f)),
    )
}
