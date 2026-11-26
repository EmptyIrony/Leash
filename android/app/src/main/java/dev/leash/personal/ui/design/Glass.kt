package dev.leash.personal.ui.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Glass (DESIGN.md §6): where iOS uses Liquid Glass. On API 31+ a real backdrop blur of whatever is marked
 * [glassSource] inside the same [GlassScope]; elsewhere (or outside a scope) a near-opaque solid fill.
 */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** Real blur needs API 31; 31 itself stays on the solid fallback (RenderNode invalidation bugs there). */
val glassBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= 32

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

/**
 * A glass surface in [shape] (DESIGN.md §6): floating shadow, the tint over a blurred backdrop when [blur] is on and
 * allowed (else the solid fallback), a meaning [tint] on top, and the rim that carries the shape. Only the bands blur
 * (top edge, bottom dock, a lone tray): circles and chips inside a band pass blur = false. Glass never sits on glass.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = Color.Unspecified, shadow: Boolean = true, blur: Boolean = true): Modifier {
    val c = Leash.colors
    val state = LocalGlassState.current
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

/** The blur settings every Haze effect here shares: noise off, composited over the page colour. */
private fun blurStyle(background: Color, radius: Dp, more: HazeBlurStyleScope.() -> Unit) = HazeBlurStyle {
    backgroundColor(background)
    blurRadius(radius)
    noiseFactor(0f)
    more()
}

/** The edge that makes glass read as glass: a hairline outer line and a soft top highlight. */
@Composable
fun Modifier.glassRim(shape: Shape): Modifier {
    val c = Leash.colors
    return this
        .drawWithContent {
            drawContent()
            // inner highlight: white fading out over the top 6 dp
            val h = 6.dp.toPx()
            drawRect(Brush.verticalGradient(listOf(c.glassHighlight, Color.Transparent), startY = 0f, endY = h), size = Size(size.width, h))
        }
        .border(Dp.Hairline, Brush.verticalGradient(listOf(c.glassLineTop, c.glassLineBottom)), shape)
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
 * The bottom edge (IMG_5524): content scrolls on under the composer and chips to the screen bottom; this fades it
 * towards the page colour (and blurs it on 32+) so no sharp text shows between the slab and the chips.
 */
@Composable
fun Modifier.softBottomEdge(blur: Boolean = true): Modifier {
    val c = Leash.colors
    val state = LocalGlassState.current
    val style = remember(c) {
        blurStyle(c.bgBase, 12.dp) { mask(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color.Black, 1f to Color.Black)) }
    }
    var m = this
    if (!blur) {
        // the list: rows stay crisp between the bottom circles, only a light fade towards the page colour
        return m.background(Brush.verticalGradient(0f to Color.Transparent, 1f to c.bgGradientEnd.copy(alpha = 0.6f)))
    }
    if (state != null && glassBlurSupported && LocalGlassBlurAllowed.current) {
        m = m.hazeBlur(HazeInput.Sources(state), style)
    }
    return m.background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to c.bgGradientEnd.copy(alpha = 0.6f), 1f to c.bgGradientEnd.copy(alpha = 0.85f)))
}
