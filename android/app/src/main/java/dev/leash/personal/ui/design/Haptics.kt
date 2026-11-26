package dev.leash.personal.ui.design

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import dev.leash.personal.core.Haptic

/**
 * The iOS haptic moments on Android (DESIGN.md §9), split by cause:
 * - touch-initiated ([touch]): `View.performHapticFeedback` with semantic constants (honours the system touch-feedback
 *   setting);
 * - event-initiated ([event]: the agent needs you, a long task finished, an async send failed): a `Vibrator` effect
 *   with notification usage. That needs the VIBRATE permission (phase 3 adds it with notifications); without it the
 *   view constant plays instead.
 * Both sit behind the app's own switch ([LocalHapticsEnabled]). Never for decoration, always with a visible change.
 */
object LeashHaptics {
    /** The view constant for a touch-initiated [h]. */
    fun constant(h: Haptic, sdk: Int = Build.VERSION.SDK_INT): Int = when (h) {
        Haptic.Selection, Haptic.Soft -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        Haptic.Light -> HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.Medium -> HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.Heavy -> HapticFeedbackConstants.LONG_PRESS
        Haptic.Success -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.Warning -> HapticFeedbackConstants.LONG_PRESS
        Haptic.Error -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }

    fun toggleConstant(on: Boolean, sdk: Int = Build.VERSION.SDK_INT): Int = when {
        sdk >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        else -> HapticFeedbackConstants.CLOCK_TICK
    }

    fun touch(view: View, h: Haptic) {
        view.performHapticFeedback(constant(h))
    }

    fun toggle(view: View, on: Boolean) {
        view.performHapticFeedback(toggleConstant(on))
    }

    /** Warning: two pulses, strong then soft. */
    private val warningTimings = longArrayOf(0, 20, 110, 30)
    private val warningAmplitudes = intArrayOf(0, 255, 0, 140)

    /** The effect for an event-initiated [h], or null when it has none. */
    fun effect(h: Haptic, hasAmplitude: Boolean): VibrationEffect? = when (h) {
        Haptic.Warning ->
            if (hasAmplitude) VibrationEffect.createWaveform(warningTimings, warningAmplitudes, -1)
            else VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        Haptic.Error -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        Haptic.Success -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        else -> null
    }

    /** An event-initiated haptic (the agent did something), falling back to the view constant without VIBRATE. */
    fun event(view: View, h: Haptic) {
        val ctx = view.context
        val vibrator = vibrator(ctx)
        val effect = vibrator?.let { effect(h, it.hasAmplitudeControl()) }
        if (vibrator == null || effect == null || !vibrator.hasVibrator() ||
            ctx.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED
        ) {
            touch(view, h)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
        }
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
}

/** The app's Haptics setting (Settings > Haptics); the system setting applies on top. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Plays Leash haptics on the current view; everything is a no-op when haptics are off. */
class HapticsPlayer internal constructor(private val view: View, private val enabled: Boolean) {
    /** The user just did something. */
    fun touch(h: Haptic) { if (enabled) LeashHaptics.touch(view, h) }
    fun toggle(on: Boolean) { if (enabled) LeashHaptics.toggle(view, on) }
    /** The agent just did something (needs you, long task finished, async failure). */
    fun event(h: Haptic) { if (enabled) LeashHaptics.event(view, h) }
}

@Composable
fun rememberHaptics(): HapticsPlayer {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { HapticsPlayer(view, enabled) }
}
