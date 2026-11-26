package dev.leash.personal.ui.design

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure geometry of iOS-style continuous ("squircle") corners, after the figma-squircle construction: each corner is a
 * cubic easing off the straight edge, a circular arc, and a mirrored cubic onto the next edge. Smoothing 0 is a plain
 * rounded corner; iOS's `.continuous` style looks like about 0.6. No Compose types, so it is unit-testable.
 */
object SquircleGeometry {
    /** How one corner is drawn: [p] is how far from the corner point the curve leaves the straight edge. */
    data class Corner(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val p: Float,
        val arcLength: Float,
        val radius: Float,
        val arcDegrees: Float,
    )

    sealed interface Segment {
        data class MoveTo(val x: Float, val y: Float) : Segment
        data class LineTo(val x: Float, val y: Float) : Segment
        data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Segment
        /** A circular arc around ([cx], [cy]); positive sweep is clockwise on screen (y down). Ends at ([x], [y]). */
        data class ArcTo(val cx: Float, val cy: Float, val r: Float, val startDegrees: Float, val sweepDegrees: Float, val x: Float, val y: Float) : Segment
        data object Close : Segment
    }

    /** One corner's construction; a radius over [budget] (half the short side) is clamped, ending as a capsule end. */
    fun corner(radius: Float, smoothing: Float, budget: Float): Corner {
        val r = min(radius, budget).coerceAtLeast(0f)
        if (r <= 0f) return Corner(0f, 0f, 0f, 0f, 0f, 0f, 0f, 90f)
        // the smoothing needs room along the edge: shrink it rather than overlap the next corner
        val s = min(smoothing.coerceIn(0f, 1f), budget / r - 1f).coerceAtLeast(0f)
        val p = min((1f + s) * r, budget)
        val arcDegrees = 90f * (1f - s)
        val arcLength = sin(rad(arcDegrees / 2f)) * r * sqrt(2f)
        val angleAlpha = (90f - arcDegrees) / 2f
        val p3ToP4 = r * tan(rad(angleAlpha / 2f))
        val angleBeta = 45f * s
        val c = p3ToP4 * cos(rad(angleBeta))
        val d = c * tan(rad(angleBeta))
        val b = (p - arcLength - c - d) / 3f
        val a = 2f * b
        return Corner(a, b, c, d, p, arcLength, r, arcDegrees)
    }

    /**
     * The outline of a [width] x [height] rectangle with continuous corners, clockwise from the top edge.
     * Radii are physical: top-left, top-right, bottom-right, bottom-left.
     */
    fun outline(
        width: Float,
        height: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        smoothing: Float,
    ): List<Segment> {
        val budget = min(width, height) / 2f
        val tl = corner(topLeft, smoothing, budget)
        val tr = corner(topRight, smoothing, budget)
        val br = corner(bottomRight, smoothing, budget)
        val bl = corner(bottomLeft, smoothing, budget)
        val out = ArrayList<Segment>(20)
        out += Segment.MoveTo(tl.p, 0f)
        // corner point, incoming edge direction (u), outgoing edge direction (v)
        addCorner(out, tr, width, 0f, 1f, 0f, 0f, 1f)
        addCorner(out, br, width, height, 0f, 1f, -1f, 0f)
        addCorner(out, bl, 0f, height, -1f, 0f, 0f, -1f)
        addCorner(out, tl, 0f, 0f, 0f, -1f, 1f, 0f)
        out += Segment.Close
        return out
    }

    private fun addCorner(out: MutableList<Segment>, k: Corner, kx: Float, ky: Float, ux: Float, uy: Float, vx: Float, vy: Float) {
        if (k.radius <= 0f) {
            out += Segment.LineTo(kx, ky)
            return
        }
        // a point (s along the incoming edge from where the curve starts, t towards the outgoing edge)
        val sx = kx - k.p * ux
        val sy = ky - k.p * uy
        fun px(s: Float, t: Float) = sx + s * ux + t * vx
        fun py(s: Float, t: Float) = sy + s * uy + t * vy
        out += Segment.LineTo(sx, sy)
        val e1s = k.a + k.b + k.c
        val e1t = k.d
        out += Segment.CubicTo(px(k.a, 0f), py(k.a, 0f), px(k.a + k.b, 0f), py(k.a + k.b, 0f), px(e1s, e1t), py(e1s, e1t))
        val cx = px(k.p - k.radius, k.radius)
        val cy = py(k.p - k.radius, k.radius)
        val e2s = e1s + k.arcLength
        val e2t = e1t + k.arcLength
        val start = deg(atan2(py(e1s, e1t) - cy, px(e1s, e1t) - cx))
        out += Segment.ArcTo(cx, cy, k.radius, start, k.arcDegrees, px(e2s, e2t), py(e2s, e2t))
        out += Segment.CubicTo(
            px(e2s + k.d, e2t + k.c), py(e2s + k.d, e2t + k.c),
            px(e2s + k.d, e2t + k.b + k.c), py(e2s + k.d, e2t + k.b + k.c),
            px(e2s + k.d, e2t + k.a + k.b + k.c), py(e2s + k.d, e2t + k.a + k.b + k.c),
        )
    }

    private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()
    private fun deg(rad: Float) = (rad * 180.0 / PI).toFloat()
}
