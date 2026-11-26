package dev.leash.personal.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A rounded rectangle with continuous (squircle) corners, Leash's only rounded-rectangle shape (iOS draws every
 * radius `.continuous`). Radii are start/end so a bubble's tail corner mirrors in RTL. A radius larger than half the
 * short side makes a capsule end.
 */
@Immutable
class ContinuousShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
    private val smoothing: Float = DefaultSmoothing,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val (tl, tr, br, bl) = with(density) {
            listOf(
                (if (ltr) topStart else topEnd).toPx(),
                (if (ltr) topEnd else topStart).toPx(),
                (if (ltr) bottomEnd else bottomStart).toPx(),
                (if (ltr) bottomStart else bottomEnd).toPx(),
            )
        }
        val path = Path()
        for (s in SquircleGeometry.outline(size.width, size.height, tl, tr, br, bl, smoothing)) {
            when (s) {
                is SquircleGeometry.Segment.MoveTo -> path.moveTo(s.x, s.y)
                is SquircleGeometry.Segment.LineTo -> path.lineTo(s.x, s.y)
                is SquircleGeometry.Segment.CubicTo -> path.cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
                is SquircleGeometry.Segment.ArcTo -> path.arcTo(
                    Rect(s.cx - s.r, s.cy - s.r, s.cx + s.r, s.cy + s.r), s.startDegrees, s.sweepDegrees, false,
                )
                SquircleGeometry.Segment.Close -> path.close()
            }
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is ContinuousShape && other.topStart == topStart && other.topEnd == topEnd &&
            other.bottomEnd == bottomEnd && other.bottomStart == bottomStart && other.smoothing == smoothing

    override fun hashCode(): Int =
        (((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()) * 31 + smoothing.hashCode()

    override fun toString() = "ContinuousShape($topStart, $topEnd, $bottomEnd, $bottomStart, $smoothing)"

    companion object {
        /** Close to iOS's continuous corner curve. */
        const val DefaultSmoothing = 0.6f
    }
}

/** Corner radii by role (DESIGN.md §5), always drawn continuous. Capsules and circles are shapes, not radii. */
object LeashRadius {
    val composer = 26.dp
    val sheet = 28.dp
    val group = 24.dp
    val tray = 22.dp
    val bubble = 20.dp
    val bubbleTail = 6.dp
    val card = 16.dp
    val row = 12.dp
    val inset = 10.dp
}

/** The shapes by role. */
object LeashShapes {
    val composer = ContinuousShape(LeashRadius.composer)
    val sheet = ContinuousShape(LeashRadius.sheet, LeashRadius.sheet, 0.dp, 0.dp)
    val group = ContinuousShape(LeashRadius.group)
    val tray = ContinuousShape(LeashRadius.tray)
    /** A sent message: the tail corner (bottom-end) is the one nearest the sender. */
    val bubble = ContinuousShape(LeashRadius.bubble, LeashRadius.bubble, LeashRadius.bubbleTail, LeashRadius.bubble)
    val card = ContinuousShape(LeashRadius.card)
    val row = ContinuousShape(LeashRadius.row)
    val inset = ContinuousShape(LeashRadius.inset)
    /** Half the height: chips, buttons, segmented control, banner. */
    val capsule = ContinuousShape(1000.dp, smoothing = 0f)
}
