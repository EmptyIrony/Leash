package dev.leash.personal.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.leash.personal.MainActivity
import dev.leash.personal.R
import dev.leash.personal.core.NotificationContent
import dev.leash.personal.core.RequestKind
import dev.leash.personal.core.TurnEndKind
import dev.leash.personal.text.durationText
import dev.leash.personal.core.OngoingContent
import dev.leash.personal.core.OngoingPhase
import dev.leash.personal.core.PendingRequest
import dev.leash.personal.core.RemoteOutcome

/**
 * Leash's own notifications (ios Core/LocalNotifier.swift): a request that needs you with Allow / Deny, a turn that ended,
 * the ongoing "working" notification, and what happened to an answer given from a notification. Each alert is reported to the
 * bridge (bridge/noticed) by the model, so Bark does not push it again; that is only honest when [canAlert].
 */
class LeashNotifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)
    /** requests answered from their notification: the confirmation stays, the bridge's `resolved` must not wipe it */
    private val confirmed = HashSet<String>()

    fun createChannels() {
        fun channel(id: String, name: Int, detail: Int, importance: Int, configure: NotificationChannel.() -> Unit = {}) {
            val c = NotificationChannel(id, context.getString(name), importance).apply { description = context.getString(detail); configure() }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
        channel(NEEDS_YOU, R.string.channel_needs_you, R.string.channel_needs_you_detail, NotificationManager.IMPORTANCE_HIGH)
        channel(TURN_FINISHED, R.string.channel_turn_finished, R.string.channel_turn_finished_detail, NotificationManager.IMPORTANCE_DEFAULT)
        channel(BACKGROUND, R.string.channel_background, R.string.channel_background_detail, NotificationManager.IMPORTANCE_LOW) { setShowBadge(false) }
    }

    /** Notifications are allowed and this kind is not switched off: only then did an alert reach you (so Bark can stay quiet). */
    fun canAlert(channel: String): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        val c = nm.getNotificationChannelCompat(channel)
        return c == null || c.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Which kinds are switched off in the system settings, by name (for the settings screen). */
    fun blockedKinds(): List<String> = listOf(NEEDS_YOU, TURN_FINISHED).filter { !canAlert(it) }

    val permissionGranted: Boolean
        get() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // MARK: posting

    /** A permission request or question. True when Leash alerted about it. */
    fun request(p: PendingRequest, agentLabel: String, conversation: String?): Boolean {
        if (!canAlert(NEEDS_YOU)) return false
        val a = NotificationContent.request(p, agentLabel, conversation)
        val title = context.getString(if (a.kind == RequestKind.Question) R.string.notif_title_question else R.string.notif_title_approval, a.agentLabel)
        val body = a.body ?: context.getString(R.string.notif_needs_input)
        val b = base(NEEDS_YOU, title, body.lineSequence().first(), a.url, "req." + p.key)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(a.subtitle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPublicVersion(base(NEEDS_YOU, context.getString(R.string.notif_hidden_title), title, a.url, "req." + p.key).build())
        if (a.allowId != null) {
            // Allow runs something on the bridge's host: a locked phone asks to be unlocked first
            val tool = body.lineSequence().first()
            b.addAction(action(R.string.allow, p.key, a.allowId, true, a.url, tool, authenticate = true))
            b.addAction(action(R.string.deny, p.key, a.denyId, false, a.url, tool, authenticate = false))
        }
        return post("req." + p.key, b.build())
    }

    /** A turn ended (not one you stopped). True when Leash alerted about it. */
    fun turnEnd(sessionId: String, agent: String, agentLabel: String, conversation: String?, stopReason: String?, beganMs: Long?, nowMs: Long): Boolean {
        if (!canAlert(TURN_FINISHED)) return false
        val a = NotificationContent.turnEnd(sessionId, agent, agentLabel, conversation, stopReason, beganMs, nowMs) ?: return false
        val title = context.getString(
            when (a.kind) { TurnEndKind.Finished -> R.string.notif_title_finished; TurnEndKind.HitError -> R.string.notif_title_error; TurnEndKind.Stopped -> R.string.notif_title_stopped },
            a.agentLabel,
        )
        val took = a.tookMs?.let { context.getString(R.string.notif_took, context.resources.durationText(it / 1000.0, maxUnits = 3)) }
        val b = base(TURN_FINISHED, title, took, a.url, "end.$sessionId").setSubText(a.subtitle).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_STATUS)
        return post("end.$sessionId", b.build())
    }

    /** What came of Allow / Deny on a notification. */
    fun answered(key: String, allow: Boolean, outcome: RemoteOutcome, toolTitle: String?, url: String?) {
        val tag = "req.$key"
        when (outcome) {
            RemoteOutcome.Done -> {
                confirmed += key
                val b = base(NEEDS_YOU, context.getString(if (allow) R.string.notif_allowed else R.string.notif_denied), toolTitle, url, tag)
                    .quiet().setTimeoutAfter(6_000).setAutoCancel(true)
                post(tag, b.build())
            }
            RemoteOutcome.AlreadyAnswered -> nm.cancel(tag, 0)
            else -> {
                // keep a way to answer in the app
                val unreachable = outcome == RemoteOutcome.BridgeUnreachable
                val b = base(NEEDS_YOU, context.getString(if (unreachable) R.string.notif_unreachable else R.string.notif_answer_failed), context.getString(R.string.notif_open_to_answer), url, tag)
                    .quiet().setAutoCancel(true)
                post(tag, b.build())
            }
        }
    }

    /** Answered or withdrawn in the app or elsewhere: its notification goes (unless it shows what an answer from it did). */
    fun requestResolved(key: String) {
        if (confirmed.remove(key)) return
        nm.cancel("req.$key", 0)
    }

    fun conversationSeen(sessionId: String) = nm.cancel("end.$sessionId", 0)

    /** After `ready`: request notifications whose request is gone (it was answered while the app was not listening). */
    fun keepOnly(pendingKeys: Set<String>) {
        for (n in nm.activeNotifications) {
            val tag = n.tag ?: continue
            if (tag.startsWith("req.") && tag.removePrefix("req.") !in pendingKeys && tag.removePrefix("req.") !in confirmed) nm.cancel(tag, n.id)
        }
        confirmed.retainAll(pendingKeys)
    }

    fun cancelAll() {
        for (n in nm.activeNotifications) {
            val tag = n.tag ?: continue
            if (tag.startsWith("req.") || tag.startsWith("end.")) nm.cancel(tag, n.id)
        }
        confirmed.clear()
    }

    // MARK: ongoing

    /** The notification of the foreground service: the focused conversation, or "connected" while it only listens. */
    fun ongoing(content: OngoingContent?): Notification {
        if (content == null) {
            return NotificationCompat.Builder(context, BACKGROUND)
                .setSmallIcon(R.drawable.ic_stat_leash)
        .setColor(context.getColor(R.color.leash_accent))
                .setContentTitle(context.getString(R.string.notif_connected_title))
                .setContentText(context.getString(R.string.notif_connected_text))
                .setContentIntent(open(null, "ongoing"))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
        val b = NotificationCompat.Builder(context, BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_leash)
        .setColor(context.getColor(R.color.leash_accent))
            .setContentTitle(context.getString(when (content.phase) { OngoingPhase.Working -> R.string.notif_title_working; OngoingPhase.NeedsApproval -> R.string.notif_title_approval; OngoingPhase.NeedsAnswer -> R.string.notif_title_question }, content.agentLabel))
            .setContentText(content.text)
            .setSubText(if (content.others > 0) context.getString(R.string.notif_others, content.others) else null)
            .setContentIntent(open(content.url, "ongoing"))
            .setCategory(if (content.phase == OngoingPhase.Working) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (content.sinceMs != null) b.setWhen(content.sinceMs).setShowWhen(true).setUsesChronometer(true) else b.setShowWhen(false)
        if (content.phase == OngoingPhase.Working) b.setProgress(0, 0, true)
        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16 Live Update: shown in the status bar chip and on the lock screen (the user can turn it off per app)
            b.setRequestPromotedOngoing(true)
        }
        return b.build()
    }

    // MARK: building

    private fun base(channel: String, title: String, text: String?, url: String?, tag: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_leash)
        .setColor(context.getColor(R.color.leash_accent))
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open(url, tag))
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    /** Re-posting a notification that already alerted: no sound, no heads-up again. */
    private fun NotificationCompat.Builder.quiet() = setOnlyAlertOnce(true).setSilent(true)

    private fun open(url: String?, tag: String): PendingIntent {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url ?: "leash://open")).setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, tag.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(label: Int, key: String, optionId: String?, allow: Boolean, url: String, tool: String, authenticate: Boolean): NotificationCompat.Action {
        val i = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_ANSWER)
            .setData(Uri.fromParts("leash-answer", key, if (allow) "allow" else "deny"))
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_OPTION, optionId).putExtra(EXTRA_ALLOW, allow).putExtra(EXTRA_URL, url).putExtra(EXTRA_TOOL, tool)
        val pi = PendingIntent.getBroadcast(context, (key + allow).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, context.getString(label), pi).setAuthenticationRequired(authenticate).setShowsUserInterface(false).build()
    }

    private fun post(tag: String, n: Notification): Boolean = try {
        nm.notify(tag, 0, n)
        true
    } catch (e: SecurityException) {
        false   // the permission is gone
    }

    companion object {
        const val NEEDS_YOU = "needs_you"
        const val TURN_FINISHED = "turn_finished"
        const val BACKGROUND = "background"
        const val ONGOING_ID = 1
        const val ACTION_ANSWER = "dev.leash.personal.ANSWER"
        const val EXTRA_KEY = "key"
        const val EXTRA_OPTION = "optionId"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_URL = "url"
        const val EXTRA_TOOL = "tool"
    }
}
