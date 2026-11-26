package dev.leash.personal.core

import android.content.Intent
import kotlinx.coroutines.CoroutineScope

/** Only debug builds take the auth key from an intent. */
object TailnetDebug {
    @Suppress("UNUSED_PARAMETER")
    fun apply(model: AppModel, scope: CoroutineScope, intent: Intent?) {}
}
