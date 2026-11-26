package dev.leash.personal.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.leash.personal.LeashApplication
import dev.leash.personal.core.RemoteOutcome
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Allow / Deny on a request notification, answered without opening the app (ios AppModel.answerFromNotification). The process
 * may have been started just for this: the stored settings load first, then the model connects and answers. A broadcast gets
 * about ten seconds, so the whole thing is bounded below that.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LeashNotifier.ACTION_ANSWER) return
        val key = intent.getStringExtra(LeashNotifier.EXTRA_KEY) ?: return
        val optionId = intent.getStringExtra(LeashNotifier.EXTRA_OPTION)   // none: cancelling the request denies it
        val allow = intent.getBooleanExtra(LeashNotifier.EXTRA_ALLOW, false)
        val url = intent.getStringExtra(LeashNotifier.EXTRA_URL)
        val tool = intent.getStringExtra(LeashNotifier.EXTRA_TOOL)
        val app = context.applicationContext as LeashApplication
        val result = goAsync()
        app.appScope.launch {
            try {
                val outcome = withTimeoutOrNull(ANSWER_BUDGET_MS) {
                    app.model.awaitSettings()
                    app.model.answer(key, optionId)
                } ?: RemoteOutcome.BridgeUnreachable
                app.notifier.answered(key, allow, outcome, tool, url)
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val ANSWER_BUDGET_MS = 8_500L
    }
}
