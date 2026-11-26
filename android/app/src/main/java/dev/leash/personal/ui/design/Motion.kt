package dev.leash.personal.ui.design

import android.animation.ValueAnimator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI

/**
 * A SwiftUI `spring(duration:bounce:)` as Compose spring parameters: stiffness (2π / duration)² with unit mass and a
 * damping ratio of 1 − bounce. Pure, so the conversion is unit-tested.
 */
data class SpringParams(val dampingRatio: Float, val stiffness: Float) {
    companion object {
        fun swiftUI(duration: Double, bounce: Double): SpringParams {
            val w = 2 * PI / duration
            return SpringParams((1 - bounce).toFloat(), (w * w).toFloat())
        }
    }
}

/**
 * The app's only animation vocabulary (ios/SharedDesign/LeashMotionCurves.swift). With reduced motion (animations off
 * in the system) movement becomes a short ease.
 */
enum class MotionCurve(val spring: SpringParams, val reducedMillis: Int) {
    /** Things entering or leaving the layout: trays, banners, rows moving. iOS .smooth(0.34). */
    Layout(SpringParams.swiftUI(0.34, 0.0), 200),
    /** Expanding and collapsing rows, checklists, details. iOS .snappy(0.30). */
    Disclose(SpringParams.swiftUI(0.30, 0.15), 180),
    /** Small state feedback: enabled ↔ disabled, a label or glyph changing. iOS .smooth(0.22). */
    Quick(SpringParams.swiftUI(0.22, 0.0), 150),
    /** Touch-down feedback: in fast, settles without a visible bounce. iOS .snappy(0.18). */
    Press(SpringParams.swiftUI(0.18, 0.15), 100);

    fun <T> spec(reduced: Boolean, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (reduced) tween(reducedMillis, easing = if (this == Press) LinearOutSlowInEasing else FastOutSlowInEasing)
        else spring(spring.dampingRatio, spring.stiffness, visibilityThreshold)
}

/** True when the system has animations turned off (Developer options or Accessibility > Remove animations). */
val LocalReduceMotion = staticCompositionLocalOf { false }

fun systemReducesMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

/** Motion tokens and transitions for the current reduce-motion setting. */
object Motion {
    val reduced: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current

    @Composable @ReadOnlyComposable
    fun <T> layout(): FiniteAnimationSpec<T> = MotionCurve.Layout.spec(reduced)
    @Composable @ReadOnlyComposable
    fun <T> disclose(): FiniteAnimationSpec<T> = MotionCurve.Disclose.spec(reduced)
    @Composable @ReadOnlyComposable
    fun <T> quick(): FiniteAnimationSpec<T> = MotionCurve.Quick.spec(reduced)
    @Composable @ReadOnlyComposable
    fun <T> press(): FiniteAnimationSpec<T> = MotionCurve.Press.spec(reduced)

    private fun ints(c: MotionCurve, r: Boolean) = c.spec<IntOffset>(r, IntOffset(1, 1))
    private fun sizes(c: MotionCurve, r: Boolean) = c.spec<IntSize>(r, IntSize(1, 1))
    private fun floats(c: MotionCurve, r: Boolean) = c.spec<Float>(r, 0.001f)

    /** Trays, banners, rows: the space opens and the content fades in. */
    fun layoutEnter(r: Boolean): EnterTransition =
        if (r) fadeIn(floats(MotionCurve.Layout, true))
        else expandVertically(sizes(MotionCurve.Layout, false)) + fadeIn(floats(MotionCurve.Layout, false))
    fun layoutExit(r: Boolean): ExitTransition =
        if (r) fadeOut(floats(MotionCurve.Layout, true))
        else shrinkVertically(sizes(MotionCurve.Layout, false)) + fadeOut(floats(MotionCurve.Layout, false))

    /** Content revealed by a disclosure: drops in 6 dp from above. */
    fun revealEnter(r: Boolean, sixDpPx: Int): EnterTransition =
        if (r) fadeIn(floats(MotionCurve.Disclose, true))
        else fadeIn(floats(MotionCurve.Disclose, false)) + slideInVertically(ints(MotionCurve.Disclose, false)) { -sixDpPx }
    fun revealExit(r: Boolean): ExitTransition = fadeOut(floats(MotionCurve.Quick, r))

    /** A control appearing in place (stop, scroll-to-bottom, badges). */
    fun popEnter(r: Boolean): EnterTransition =
        if (r) fadeIn(floats(MotionCurve.Quick, true))
        else scaleIn(floats(MotionCurve.Quick, false), initialScale = 0.9f) + fadeIn(floats(MotionCurve.Quick, false))
    fun popExit(r: Boolean): ExitTransition =
        if (r) fadeOut(floats(MotionCurve.Quick, true))
        else scaleOut(floats(MotionCurve.Quick, false), targetScale = 0.9f) + fadeOut(floats(MotionCurve.Quick, false))

    /** A secondary control sliding out from behind the control at its end side (stop next to send). */
    fun trailingPopEnter(r: Boolean): EnterTransition =
        if (r) fadeIn(floats(MotionCurve.Quick, true))
        else scaleIn(floats(MotionCurve.Quick, false), 0.6f, TransformOrigin(1f, 0.5f)) + fadeIn(floats(MotionCurve.Quick, false))
    fun trailingPopExit(r: Boolean): ExitTransition =
        if (r) fadeOut(floats(MotionCurve.Quick, true))
        else scaleOut(floats(MotionCurve.Quick, false), 0.6f, TransformOrigin(1f, 0.5f)) + fadeOut(floats(MotionCurve.Quick, false))

    /** A message the user just sent rising from the composer. */
    fun sentEnter(r: Boolean, riseDpPx: Int): EnterTransition =
        if (r) fadeIn(floats(MotionCurve.Layout, true))
        else slideInVertically(ints(MotionCurve.Layout, false)) { riseDpPx } + fadeIn(floats(MotionCurve.Layout, false))

    /** Under the top bar (other-conversation banner). */
    fun bannerEnter(r: Boolean): EnterTransition =
        if (r) fadeIn(floats(MotionCurve.Layout, true))
        else slideInVertically(ints(MotionCurve.Layout, false)) { -it } + fadeIn(floats(MotionCurve.Layout, false))
    fun bannerExit(r: Boolean): ExitTransition =
        if (r) fadeOut(floats(MotionCurve.Layout, true))
        else slideOutVertically(ints(MotionCurve.Layout, false)) { -it } + fadeOut(floats(MotionCurve.Layout, false))

    /** Glass docked above the composer materialising (iOS glassEffectTransition(.materialize)). */
    fun materializeEnter(r: Boolean): EnterTransition =
        if (r) fadeIn(floats(MotionCurve.Layout, true))
        else scaleIn(floats(MotionCurve.Layout, false), 0.96f, TransformOrigin(0.5f, 1f)) + fadeIn(floats(MotionCurve.Layout, false)) +
            expandVertically(sizes(MotionCurve.Layout, false), expandFrom = androidx.compose.ui.Alignment.Bottom)
    fun materializeExit(r: Boolean): ExitTransition =
        if (r) fadeOut(floats(MotionCurve.Layout, true))
        else scaleOut(floats(MotionCurve.Layout, false), 0.96f, TransformOrigin(0.5f, 1f)) + fadeOut(floats(MotionCurve.Quick, false)) +
            shrinkVertically(sizes(MotionCurve.Layout, false), shrinkTowards = androidx.compose.ui.Alignment.Bottom)
}
