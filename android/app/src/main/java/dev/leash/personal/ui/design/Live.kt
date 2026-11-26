package dev.leash.personal.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The live turn's one moving signal (DESIGN.md §7): a band of light sweeping every [Period] ms across the dot glyph
 * and into the "Working" words. Everything reads the same clock (frame time), so glyph and words carry one band.
 */
object LiveBand {
    const val Period = 2000L

    /** The band at phase [t] (0..1) over a span [span] px wide: its leading edge and its width. */
    fun band(span: Float, t: Float, minWidth: Float): Pair<Float, Float> {
        val width = max(span * 0.45f, minWidth)
        return (-width + (span + width * 2) * t) to width
    }

    /** How lit a point at [x] is: the band's own triangle, 0 at its edges, 1 mid-band. */
    fun lit(x: Float, lead: Float, width: Float): Float {
        val u = (x - lead) / width
        return max(0f, 1f - abs(2f * u - 1f))
    }
}

/**
 * The band phase 0..1 on the shared frame clock, or [fixed] (screenshots, previews). Stops when [active] is false.
 */
@Composable
fun rememberLivePhase(active: Boolean = true, fixed: Float? = null): State<Float> {
    val phase = remember { mutableFloatStateOf(fixed ?: 0f) }
    val inspection = LocalInspectionMode.current
    LaunchedEffect(active, fixed, inspection) {
        if (fixed != null || !active || inspection) {
            phase.floatValue = fixed ?: 0f
            return@LaunchedEffect
        }
        while (true) {
            androidx.compose.runtime.withFrameMillis { ms -> phase.floatValue = (ms % LiveBand.Period).toFloat() / LiveBand.Period }
        }
    }
    return phase
}

/**
 * Paints the band onto this content's own pixels (source-atop), as iOS's Shimmer. [leadingInset] is where this content
 * starts inside the span the band crosses (the glyph before the words shares the sweep). Off with reduced motion.
 */
@Composable
fun Modifier.shimmer(active: Boolean, phase: State<Float>, leadingInset: Dp = 0.dp): Modifier {
    if (!active || LocalReduceMotion.current) return this
    val glint = Leash.colors.glint
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val inset = leadingInset.toPx()
            val (lead, w) = LiveBand.band(inset + size.width, phase.value, 60.dp.toPx())
            val x0 = lead - inset
            val left = if (rtl) size.width - x0 - w else x0
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, glint.copy(alpha = 0.9f), Color.Transparent), startX = left, endX = left + w),
                topLeft = Offset(left, 0f),
                size = Size(w, size.height),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/**
 * A live turn's glyph: 21 accent dots (a 5x5 grid without its corners) that swell as the shimmer band passes over them
 * on its way into the words. [textWidth] is the width of the words after the glyph, [textInset] where they start and
 * [columnWidth] the glyph column, both from the column's leading edge. Reduced motion: the glyph only breathes.
 */
@Composable
fun LiveDots(
    phase: State<Float>,
    textWidth: Dp,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    textInset: Dp = 26.dp,
    columnWidth: Dp = 18.dp,
    highContrast: Boolean = false,
) {
    val accent = Leash.colors.accent
    val reduced = LocalReduceMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(size)) {
        val pitch = min(this.size.width, this.size.height) / 5f
        val ox = (this.size.width - pitch * 5) / 2
        val oy = (this.size.height - pitch * 5) / 2
        val pad = (columnWidth.toPx() - this.size.width) / 2
        val base = if (highContrast) 0.7f else 0.5f
        val (lead, w) = LiveBand.band(textInset.toPx() + textWidth.toPx(), phase.value, 60.dp.toPx())
        val t = phase.value
        val breathBase = max(base, 0.55f)
        val breath = breathBase + (1 - breathBase) * (0.5f - 0.5f * cos(2 * PI.toFloat() * t))
        for (row in 0 until 5) for (col in 0 until 5) {
            if ((row == 0 || row == 4) && (col == 0 || col == 4)) continue
            val cx = ox + (col + 0.5f) * pitch
            val cy = oy + (row + 0.5f) * pitch
            // the band travels from the leading edge: mirror the x it sees in RTL
            val bx = pad + (if (rtl) this.size.width - cx else cx)
            val v = if (reduced) 0f else LiveBand.lit(bx, lead, w)
            val d = pitch * (0.5f + 0.29f * v)
            val a = if (reduced) breath else base + (1 - base) * v
            drawCircle(accent.copy(alpha = a), d / 2, Offset(cx, cy))
        }
    }
}

/** Small progress ring (quota, context, checklist). [fraction] null = unknown (dashed). */
@Composable
fun RingGauge(
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    stroke: Dp = 2.dp,
    tint: Color = Leash.colors.accent,
) {
    val track = Leash.colors.textTertiary.copy(alpha = 0.35f)
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(this.size.width - w, this.size.height - w)
        drawArc(
            track, 0f, 360f, false, Offset(inset, inset), arcSize,
            style = Stroke(w, pathEffect = if (fraction == null) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())) else null),
        )
        if (fraction != null) {
            drawArc(tint, -90f, 360f * fraction.coerceIn(0.02f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** The platform's indeterminate spinner, thin and quiet, sized for the line it sits in. For "busy", not a live turn. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Leash.colors.textSecondary, label: String? = null) {
    val density = LocalDensity.current
    CircularProgressIndicator(
        modifier = modifier.size(size).semantics { if (label != null) contentDescription = label },
        color = color,
        strokeWidth = with(density) { max(1.5f, size.toPx() / 8f).toDp() },
        trackColor = Color.Transparent,
        strokeCap = StrokeCap.Round,
    )
}
