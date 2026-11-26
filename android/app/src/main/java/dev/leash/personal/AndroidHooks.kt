package dev.leash.personal

import android.view.View
import dev.leash.personal.core.AppHooks
import dev.leash.personal.core.Clock
import dev.leash.personal.core.Haptic
import dev.leash.personal.core.PendingRequest
import dev.leash.personal.ui.design.LeashHaptics

/** The app's side of [AppHooks]: haptics through the window on screen, notifications, and the foreground service that follows turns. */
class AndroidHooks(private val app: LeashApplication) : AppHooks {
    /** The activity's decor view while one exists. */
    @Volatile var view: View? = null
    /** Shows the system's notification-permission prompt (set while an activity exists). */
    @Volatile var permissionAsker: (() -> Unit)? = null

    /** Core haptics: the agent's own events (needs you, a failure) vibrate as events, the rest are touch feedback (DESIGN.md §9). */
    override fun haptic(kind: Haptic) {
        val v = view ?: return
        when (kind) {
            Haptic.Warning, Haptic.Error -> LeashHaptics.event(v, kind)
            else -> LeashHaptics.touch(v, kind)
        }
    }

    override fun alertRequest(request: PendingRequest, agentLabel: String, conversation: String?): Boolean =
        app.notifier.request(request, agentLabel, conversation)

    override fun requestArrived(request: PendingRequest, alerted: Boolean) = app.background.requestArrived(request)

    override fun requestResolved(key: String) {
        app.notifier.requestResolved(key)
        app.background.requestResolved(key)
    }

    override fun alertTurnEnd(sessionId: String, agent: String, agentLabel: String, conversation: String?, stopReason: String?, beganMs: Long?): Boolean =
        app.notifier.turnEnd(sessionId, agent, agentLabel, conversation, stopReason, beganMs, Clock.now())

    override fun turnStarted(sessionId: String) = app.background.turnStarted(sessionId)
    override fun turnAborted(sessionId: String) = app.background.turnEnded(sessionId)

    override fun turnChanged(sessionId: String, running: Boolean, stopReason: String?, startedAtMs: Long?) {
        if (!running) app.background.turnEnded(sessionId)
    }

    override fun requestsReconciled(pendingKeys: Set<String>) {
        app.notifier.keepOnly(pendingKeys)
        app.background.requestsReconciled(pendingKeys)
    }

    override fun conversationSeen(sessionId: String) = app.notifier.conversationSeen(sessionId)

    override fun reset() {
        app.notifier.cancelAll()
        app.background.reset()
    }

    override fun askNotificationPermission(): Boolean {
        if (app.notifier.permissionGranted) return true
        val ask = permissionAsker ?: return false   // no screen to ask on: next time
        ask()
        return true
    }

}
