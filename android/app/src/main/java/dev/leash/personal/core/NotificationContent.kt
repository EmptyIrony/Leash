package dev.leash.personal.core

import java.net.URLEncoder
import kotlinx.serialization.json.JsonNull

/** What a notification about a permission request or a question says and offers. */
enum class RequestKind { Question, NeedsApproval }

enum class TurnEndKind { Finished, HitError, Stopped }

data class RequestAlert(
    val kind: RequestKind,
    val agentLabel: String,
    val subtitle: String?,
    /** what the request is about; null when it says nothing (the notification says "Needs your input") */
    val body: String?,
    /** option ids for the Allow / Deny actions: allow once and deny, never "always" (null: no quick answer, answer in the chat) */
    val allowId: String?,
    val denyId: String?,
    /** leash://session link opening the conversation (leash://open without one) */
    val url: String,
) {
    val hasQuickAnswer: Boolean get() = allowId != null
}

/** [tookMs]: how long the turn took, when known. */
data class TurnEndAlert(val kind: TurnEndKind, val agentLabel: String, val subtitle: String?, val tookMs: Long?, val url: String)

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
            return RequestAlert(RequestKind.Question, agentLabel, conversation, p.toolTitle, null, null, url)
        }
        val tool = ToolItem(p.params["toolCall"]["toolCallId"].string ?: p.key)
        tool.apply(p.params["toolCall"] ?: JsonNull)
        val title = if (tool.title == "Tool") p.toolTitle else tool.title
        // a title that already says the command (Claude Code names the tool call after it) needs no second line
        val preview = tool.commandPreview?.take(300)?.takeIf { it.isNotEmpty() && title?.contains(it) != true }
        val body = listOfNotNull(title, preview).joinToString("\n").ifEmpty { null }
        val opts = (p.params["options"].array ?: emptyList()).mapNotNull { o -> o["optionId"].string?.let { (o["kind"].string ?: "") to it } }
        val (allow, deny) = quickAnswers(opts)
        return RequestAlert(RequestKind.NeedsApproval, agentLabel, conversation, body, allow, if (allow != null) deny else null, url)
    }

    /** A turn ended (not one you stopped); null for a stop you pressed yourself. */
    fun turnEnd(sessionId: String, agent: String, agentLabel: String, conversation: String?, stopReason: String?, beganMs: Long?, nowMs: Long): TurnEndAlert? {
        if (stopReason == "cancelled") return null
        val kind = when (stopReason) {
            "end_turn", null -> TurnEndKind.Finished
            "error", "agent_exited", "refusal" -> TurnEndKind.HitError
            else -> TurnEndKind.Stopped
        }
        return TurnEndAlert(kind, agentLabel, conversation, beganMs?.let { (nowMs - it).coerceAtLeast(0) }, link(sessionId, agent))
    }
}
