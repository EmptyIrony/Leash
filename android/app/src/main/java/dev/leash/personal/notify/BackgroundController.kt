package dev.leash.personal.notify

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BackgroundPolicy
import dev.leash.personal.core.Clock
import dev.leash.personal.core.OngoingContent
import dev.leash.personal.core.OngoingFocus
import dev.leash.personal.core.PendingRequest
import dev.leash.personal.core.TrackedTurn
import dev.leash.personal.core.TurnTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Runs the foreground service ([LeashService]) by [BackgroundPolicy], and keeps its ongoing notification current: it follows the
 * turns this phone started and the requests that arrived live (other work the policy stays for shows as just connected).
 * Everything runs on the main thread (the model's scope).
 * The notification text only changes on state changes; the elapsed time is the system's chronometer, so nothing ticks here.
 */
class BackgroundController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val model: AppModel,
    private val notifier: LeashNotifier,
) {
    private val tracker = TurnTracker()
    private val attention = LinkedHashMap<String, PendingRequest>()
    /** what the ongoing notification shows (null: only listening) */
    var content: OngoingContent? = null
        private set
    private val policy = BackgroundPolicy()
    private var service: LeashService? = null
    /** a start was requested and the service has not been destroyed since */
    private var requested = false
    /** the system refused to start it from the background; tried again when the app is on screen */
    private var blocked = false
    /** what the ongoing notification shows right now, so it is only posted again when it changed */
    private var shown: OngoingContent? = null
    private var release: Job? = null

    fun start() {
        scope.launch { model.state.collect { evaluate() } }
    }

    // MARK: events

    fun turnStarted(sessionId: String) {
        val s = model.state.value
        val store = model.existingStore(sessionId)
        val agent = store?.agent ?: s.sessions.firstOrNull { it.id == sessionId }?.agent ?: "agy"
        val title = s.conversationTitle(sessionId) ?: store?.title ?: app.getString(R.string.conversation)
        tracker.started(TrackedTurn(sessionId, agent, title, Clock.now()))
        evaluate()
    }

    fun turnEnded(sessionId: String) { tracker.ended(sessionId); evaluate() }
    fun requestArrived(request: PendingRequest) { attention[request.key] = request; evaluate() }
    fun requestResolved(key: String) { attention.remove(key); evaluate() }
    fun requestsReconciled(keys: Set<String>) { attention.keys.retainAll(keys); evaluate() }
    fun reset() { tracker.clear(); attention.clear(); evaluate() }

    /** The app came on screen, or left it ([leaving] false: only a configuration change, the screen is coming right back). */
    fun sceneChanged(active: Boolean, leaving: Boolean = true) {
        if (active) { policy.returned(); blocked = false; cancelRelease() }
        else if (leaving) model.state.value.let { policy.left(it.keepAlive, it.hasWork) }
        evaluate()
    }

    fun serviceCreated(s: LeashService) { service = s }

    fun serviceDestroyed(s: LeashService) {
        if (service === s) service = null
        requested = false
        shown = null
    }

    // MARK: decision

    fun evaluate() {
        val s = model.state.value
        tracker.reconcile(s.running)
        attention.keys.retainAll(s.pending.keys)
        content = OngoingFocus.pick(tracker.all, attention.values.toList(), s::agentLabel, s::conversationTitle)
        when (policy.decide(s.keepAlive, model.sceneActive, content != null, s.hasWork)) {
            BackgroundPolicy.Action.Run -> {
                startService()
                // refused from the background: no service to stay with, so the connection goes too
                if (blocked && !model.sceneActive) settle() else cancelRelease()
            }
            BackgroundPolicy.Action.Stop -> { cancelRelease(); stopService() }
            BackgroundPolicy.Action.Settle -> settle()
        }
        // also while settling: the service is still up then
        if (requested && service != null && content != shown) notifyOngoing()
    }

    private fun settle() {
        if (release != null) return
        release = scope.launch {
            delay(BackgroundPolicy.SETTLE_MS)
            release = null
            policy.released()
            stopService()
            model.releaseConnection()
        }
    }

    private fun cancelRelease() { release?.cancel(); release = null }

    private fun notifyOngoing() {
        try { NotificationManagerCompat.from(app).notify(LeashNotifier.ONGOING_ID, ongoingNotification()) } catch (_: SecurityException) { /* permission gone */ }
    }

    /** The service's first notification, and every update of it. */
    fun ongoingNotification(): Notification {
        shown = content
        return notifier.ongoing(content)
    }

    private fun startService() {
        if (requested || blocked) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, LeashService::class.java))
            requested = true
        } catch (e: Exception) {
            // Android 12+: not allowed from the background (ForegroundServiceStartNotAllowedException)
            blocked = true
            Log.w(TAG, "the foreground service could not start: " + e.javaClass.simpleName)
        }
    }

    private fun stopService() {
        if (!requested) return
        requested = false
        app.stopService(Intent(app, LeashService::class.java))
    }

    private companion object { const val TAG = "LeashBackground" }
}
