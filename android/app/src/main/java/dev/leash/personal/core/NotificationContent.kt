package dev.leash.personal.core

import java.net.URLEncoder
import kotlinx.serialization.json.JsonNull

/** What a notification about a permission request or a question says and offers. */
data class RequestAlert(
    val title: String,
    val subtitle: String?,
    val body: String,
    /** option ids for the Allow / Deny actions: allow once and deny, never "always" (null: no quick answer, answer in the chat) */
    val allowId: String?,
    val denyId: String?,
    /** leash://session link opening the conversation (leash://open without one) */
    val url: String,
) {
    val hasQuickAnswer: Boolean get() = allowId != null
}

data class TurnEndAlert(val title: String, val subtitle: String?, val body: String?, val url: String)

/** The words of Leash's own notifications (ios Core/LocalNotifier.swift), kept free of android.* so they are unit-tested. */
object NotificationContent {
    fun link(sessionId: String?, agent: String?): String {
        if (sessionId == null) return "leash://open"
        val q = "id=" + URLEncoder.encode(sessionId, "UTF-8") + (agent?.let { "&agent=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return "leash://session?$q"
    }

    /** (kind, optionId) pairs of a request in, option ids for Allow and Deny out: allow once and deny, never "always" (as on the iOS Live Activity and the watch). */
    fun quickAnswers(opts: List<Pair<String, String>>): Pair<String?, String?> {
        fun option(test: (String) -> Boolean) = opts.firstOrNull { test(it.first) }?.second
        val allow = option { it == "allow_once" } ?: option { it.startsWith("allow") && it != "allow_always" }
        val deny = option { it == "reject_once" } ?: option { !it.startsWith("allow") }
        return allow to deny
    }

    fun request(p: PendingRequest, agentLabel: String, conversation: String?): RequestAlert {
        val url = link(p.sessionId, p.agentId)
        if (!p.isPermission) {
            // a form: answered in the chat
            return RequestAlert("$agentLabel has a question", conversation, p.toolTitle, null, null, url)
        }
        val tool = ToolItem(p.params["toolCall"]["toolCallId"].string ?: p.key)
        tool.apply(p.params["toolCall"] ?: JsonNull)
        val title = if (tool.title == "Tool") p.toolTitle else tool.title
        val body = listOfNotNull(title, tool.commandPreview?.take(300)).joinToString("\n")
        val opts = (p.params["options"].array ?: emptyList()).mapNotNull { o -> o["optionId"].string?.let { (o["kind"].string ?: "") to it } }
        val (allow, deny) = quickAnswers(opts)
        return RequestAlert("$agentLabel needs approval", conversation, body, allow, if (allow != null) deny else null, url)
    }

    /** A turn ended (not one you stopped); null for a stop you pressed yourself. */
    fun turnEnd(sessionId: String, agent: String, agentLabel: String, conversation: String?, stopReason: String?, beganMs: Long?, nowMs: Long): TurnEndAlert? {
        if (stopReason == "cancelled") return null
        val title = when (stopReason) {
            "end_turn", null -> "$agentLabel finished"
            "error", "agent_exited", "refusal" -> "$agentLabel hit an error"
            else -> "$agentLabel stopped"
        }
        val body = beganMs?.let { "Took " + duration(nowMs - it) }
        return TurnEndAlert(title, conversation, body, link(sessionId, agent))
    }

    /** "1h 2m 3s", "4m 5s", "6s": abbreviated units, zero ones left out. */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        val parts = buildList {
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
            if (s > 0 || (h == 0L && m == 0L)) add("${s}s")
        }
        return parts.joinToString(" ")
    }
}
