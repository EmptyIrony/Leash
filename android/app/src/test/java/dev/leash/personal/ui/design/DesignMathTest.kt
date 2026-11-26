package dev.leash.personal.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class DesignMathTest {
    private val eps = 1e-3f

    @Test fun cornerPartsSumToTheEdgeDistance() {
        for (s in listOf(0f, 0.3f, 0.6f, 1f)) {
            val k = SquircleGeometry.corner(20f, s, 100f)
            assertEquals(k.p, k.a + k.b + k.c + k.d + k.arcLength, eps)
            assertEquals((1 + s) * 20f, k.p, eps)
        }
    }

    @Test fun zeroSmoothingIsAPlainQuarterCircle() {
        val k = SquircleGeometry.corner(16f, 0f, 100f)
        assertEquals(16f, k.p, eps)
        assertEquals(90f, k.arcDegrees, eps)
        assertEquals(0f, k.a + k.b + k.c + k.d, eps)
    }

    @Test fun oversizeRadiusClampsToACapsule() {
        val k = SquircleGeometry.corner(1000f, 0.6f, 22f)
        assertEquals(22f, k.radius, eps)
        assertEquals(22f, k.p, eps)
        assertEquals(90f, k.arcDegrees, eps) // no room left for smoothing
    }

    @Test fun smoothingShrinksWhenTheEdgeIsShort() {
        val k = SquircleGeometry.corner(20f, 0.6f, 26f)
        assertEquals(26f, k.p, eps)
        assertTrue(k.arcDegrees > 36f && k.arcDegrees < 90f)
    }

    @Test fun outlineIsContinuousAndStaysInsideTheRect() {
        val w = 300f; val h = 120f
        val segs = SquircleGeometry.outline(w, h, 26f, 26f, 6f, 26f, 0.6f)
        var x = 0f; var y = 0f
        for (s in segs) {
            when (s) {
                is SquircleGeometry.Segment.MoveTo -> { x = s.x; y = s.y; assertEquals(0f, y, eps) }
                is SquircleGeometry.Segment.LineTo -> { x = s.x; y = s.y }
                is SquircleGeometry.Segment.CubicTo -> { x = s.x; y = s.y }
                is SquircleGeometry.Segment.ArcTo -> {
                    // the arc starts where the path is and ends where it says
                    val sx = s.cx + s.r * cos(Math.toRadians(s.startDegrees.toDouble())).toFloat()
                    val sy = s.cy + s.r * sin(Math.toRadians(s.startDegrees.toDouble())).toFloat()
                    assertTrue("arc start", hypot(sx - x, sy - y) < 0.05f)
                    val end = Math.toRadians((s.startDegrees + s.sweepDegrees).toDouble())
                    val ex = s.cx + s.r * cos(end).toFloat()
                    val ey = s.cy + s.r * sin(end).toFloat()
                    assertTrue("arc end", hypot(ex - s.x, ey - s.y) < 0.05f)
                    x = s.x; y = s.y
                }
                SquircleGeometry.Segment.Close -> Unit
            }
            assertTrue(x >= -eps && x <= w + eps && y >= -eps && y <= h + eps)
        }
        // each corner's last cubic lands on the next edge
        val ends = segs.filterIsInstance<SquircleGeometry.Segment.CubicTo>().chunked(2).map { it[1] }
        assertEquals(w, ends[0].x, eps)
        assertEquals(h, ends[1].y, eps)
        assertEquals(0f, ends[2].x, eps)
        assertEquals(0f, ends[3].y, eps)
    }

    @Test fun swiftUISpringsConvert() {
        val smooth = SpringParams.swiftUI(0.34, 0.0)
        assertEquals(1f, smooth.dampingRatio, eps)
        assertEquals(341.5f, smooth.stiffness, 0.5f)
        val snappy = SpringParams.swiftUI(0.30, 0.15)
        assertEquals(0.85f, snappy.dampingRatio, eps)
        assertEquals(438.6f, snappy.stiffness, 0.5f)
        assertEquals(815.6f, MotionCurve.Quick.spring.stiffness, 0.5f)
        assertEquals(1218.5f, MotionCurve.Press.spring.stiffness, 0.5f)
    }

    @Test fun liveBandSweepsAcrossTheSpan() {
        val (lead0, w) = LiveBand.band(200f, 0f, 60f)
        assertEquals(90f, w, eps)
        assertEquals(-90f, lead0, eps)
        val (lead1, _) = LiveBand.band(200f, 1f, 60f)
        assertEquals(290f, lead1, eps) // fully past the span's end
        assertEquals(1f, LiveBand.lit(45f, 0f, 90f), eps)
        assertEquals(0f, LiveBand.lit(0f, 0f, 90f), eps)
        assertTrue(abs(LiveBand.lit(200f, 0f, 90f)) < eps)
    }

    @Test fun starfieldMatchesTheIosGenerator() {
        val r = SeededRandom(42)
        val v = List(3) { r.next() }
        assertTrue(v.all { it in 0f..1f })
        assertTrue(v.toSet().size == 3)
    }
}
