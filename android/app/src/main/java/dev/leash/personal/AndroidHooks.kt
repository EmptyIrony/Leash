package dev.leash.personal

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import dev.leash.personal.core.AppHooks
import dev.leash.personal.core.Haptic

/** The app's side of [AppHooks]: haptics through the window that is on screen (phase 3 adds notifications and the service). */
class AndroidHooks : AppHooks {
    /** The activity's decor view while one exists. */
    @Volatile var view: View? = null

    override fun haptic(kind: Haptic) {
        view?.performHapticFeedback(constant(kind))
    }

    private fun constant(kind: Haptic): Int = when (kind) {
        Haptic.Light, Haptic.Soft -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.Medium -> HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.Heavy -> HapticFeedbackConstants.LONG_PRESS
        Haptic.Selection -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        Haptic.Success -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.Warning -> HapticFeedbackConstants.LONG_PRESS
        Haptic.Error -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
}
