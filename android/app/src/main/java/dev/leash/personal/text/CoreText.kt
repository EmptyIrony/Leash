package dev.leash.personal.text

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.leash.personal.R
import dev.leash.personal.core.ActivitySummary
import dev.leash.personal.core.DurationPart
import dev.leash.personal.core.DurationUnit
import dev.leash.personal.core.FileLimits
import dev.leash.personal.core.Issue
import dev.leash.personal.core.LinkState
import dev.leash.personal.core.LiveAction
import dev.leash.personal.core.Problem
import dev.leash.personal.core.QuotaFormat
import dev.leash.personal.core.SubagentLine
import dev.leash.personal.core.SummaryKind
import dev.leash.personal.core.toProblem
import java.util.Locale

// The words for what core reports as data (see core/Issue.kt, ActivitySummary, LiveAction, durations): core has no resources, so
// it stays JVM-testable, and everything the user reads is worded here from res/values*/ in the device language. Text that came
// from the bridge, the network stack or Tailscale is shown as it is.

/** The resources of the current configuration: a composable that reads them is redone when the language changes. */
@Composable
@ReadOnlyComposable
fun res(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/** The locale text is formatted in: the app's own language (per-app language on Android 13+), else the system's. */
val Resources.locale: Locale get() = configuration.locales[0]

// MARK: durations

private fun Resources.unit(u: DurationUnit, value: Long): String = getString(
    when (u) {
        DurationUnit.Day -> R.string.duration_day
        DurationUnit.Hour -> R.string.duration_hour
        DurationUnit.Minute -> R.string.duration_minute
        DurationUnit.Second -> R.string.duration_second
    }, value,
)

/** "1m 3s" in English, "1分钟3秒" in Chinese: the units and their order come from the resources. */
fun Resources.durationText(parts: List<DurationPart>): String =
    parts.joinToString(getString(R.string.duration_separator)) { unit(it.unit, it.value) }

fun Resources.durationText(seconds: Double, maxUnits: Int = 2): String = durationText(dev.leash.personal.core.durationParts(seconds, maxUnits))

fun Resources.agoText(ago: QuotaFormat.Ago): String = when (ago) {
    QuotaFormat.Ago.JustNow -> getString(R.string.ago_just_now)
    is QuotaFormat.Ago.Minutes -> getQuantityString(R.plurals.ago_minutes, ago.count, ago.count)
    is QuotaFormat.Ago.Hours -> getQuantityString(R.plurals.ago_hours, ago.count, ago.count)
    is QuotaFormat.Ago.Days -> getQuantityString(R.plurals.ago_days, ago.count, ago.count)
}

// MARK: quota windows

fun Resources.quotaWindowLabel(window: String): String = when (window) {
    "5h" -> getString(R.string.quota_window_5h)
    "weekly" -> getString(R.string.quota_window_weekly)
    "daily" -> getString(R.string.quota_window_daily)
    "monthly" -> getString(R.string.quota_window_monthly)
    else -> window.replaceFirstChar { it.uppercase() }
}

/** Compact window tag next to a percentage: "5h", "wk", "day". */
fun Resources.quotaShortLabel(window: String): String = when (window) {
    "5h" -> getString(R.string.quota_short_5h)
    "weekly" -> getString(R.string.quota_short_weekly)
    "daily" -> getString(R.string.quota_short_daily)
    else -> window
}

/** The label of a window on the chat's quota chip: "5h", "Week". */
fun Resources.quotaChipLabel(window: String): String = when (window) {
    "5h" -> getString(R.string.quota_short_5h)
    "weekly" -> getString(R.string.quota_chip_weekly)
    "daily" -> getString(R.string.quota_chip_daily)
    "monthly" -> getString(R.string.quota_chip_monthly)
    else -> window.replaceFirstChar { it.uppercase() }
}

// MARK: issues

/** Leash's own wording of an [Issue]; null for a kind without a text of its own (never the case for the ones core raises). */
private fun Resources.issue(issue: Issue, detail: String?): String = when (issue) {
    Issue.SetAddressAndToken -> getString(R.string.issue_set_address)
    Issue.InvalidAddress -> getString(R.string.issue_invalid_address)
    Issue.InvalidToken -> getString(R.string.issue_invalid_token)
    Issue.TokenRejected -> getString(R.string.issue_token_rejected)
    Issue.NoReply -> getString(R.string.issue_no_reply)
    Issue.Closed -> getString(R.string.issue_closed)
    Issue.ClosedByBridge -> if (detail == null) getString(R.string.issue_closed_by_bridge) else getString(R.string.issue_closed_by_bridge_detail, detail)
    Issue.NotConnected -> getString(R.string.issue_not_connected)
    Issue.TimedOut -> getString(R.string.issue_timed_out)
    Issue.EmptyResponse -> getString(R.string.issue_empty_response)
    Issue.UnsupportedImage -> getString(R.string.image_unsupported)
    Issue.ImageTooLarge -> getString(R.string.image_too_large)
    Issue.FileTooLarge -> getString(R.string.issue_file_too_large, detail ?: "", FileLimits.MAX_UPLOAD_TEXT)
    Issue.UploadFailed -> getString(R.string.issue_upload_failed)
    Issue.NoFileData -> getString(R.string.issue_no_file_data)
    Issue.NoSessionId -> getString(R.string.issue_no_session_id)
    Issue.NoAnswer -> getString(R.string.issue_no_answer)
    Issue.TailnetUnreadable -> getString(R.string.issue_tailnet_unreadable)
    Issue.TailnetUnexpected -> getString(R.string.issue_tailnet_unexpected)
    Issue.TailnetNoRoute -> getString(R.string.issue_tailnet_no_route)
    Issue.TailnetLogoutFailed -> getString(R.string.issue_tailnet_logout_failed)
    Issue.TailnetNotStarted -> getString(R.string.issue_tailnet_not_started)
    Issue.TailnetNotConnected -> getString(R.string.issue_tailnet_not_connected)
    Issue.TailnetStatusFailed -> getString(R.string.issue_tailnet_status_failed)
    Issue.TailnetNeedsKey -> getString(R.string.issue_tailnet_needs_key)
    Issue.TailnetStopped -> getString(R.string.issue_tailnet_stopped)
}

fun Resources.problemText(p: Problem): String = p.issue?.let { issue(it, p.detail) } ?: p.message

fun Resources.errorText(e: Throwable): String = problemText(e.toProblem())

/** Why the link is down, in words; the system's own text when Leash did not find the problem itself. */
fun Resources.linkText(reason: String, issue: Issue?, detail: String?): String = issue?.let { issue(it, detail) } ?: reason

fun Resources.linkText(s: LinkState.Waiting): String = linkText(s.reason, s.issue, s.detail)

fun Resources.linkText(s: LinkState.Failed): String = linkText(s.reason, s.issue, s.detail)

// MARK: what the agent did and does

/** "Explored 12 files, ran 6 commands, edited 3 files +1 more"; empty for nothing yet. */
fun Resources.summaryText(s: ActivitySummary): String {
    if (s.thoughtOnly) return getString(R.string.summary_thought)
    if (s.isEmpty) return ""
    val shown = s.clauses.joinToString(getString(R.string.summary_separator)) { c ->
        val id = when (c.kind) {
            SummaryKind.Agents -> R.plurals.summary_agents
            SummaryKind.Explored -> R.plurals.summary_explored
            SummaryKind.Ran -> R.plurals.summary_ran
            SummaryKind.Edited -> R.plurals.summary_edited
            SummaryKind.Browsed -> R.plurals.summary_browsed
            SummaryKind.Tools -> R.plurals.summary_tools
        }
        getQuantityString(id, c.count, c.count)
    } + (if (s.more > 0) getString(R.string.summary_more, s.more) else "")
    return shown.replaceFirstChar { it.titlecase(locale) }
}

/** "Running npm test…", "Reading Parser.kt…", "Thinking…": what the agent is doing right now. */
fun Resources.liveText(a: LiveAction): String = when (a) {
    is LiveAction.Running -> getString(R.string.live_running, a.command)
    is LiveAction.Reading -> getString(R.string.live_reading, a.file ?: getString(R.string.live_file))
    LiveAction.Searching -> getString(R.string.live_searching)
    is LiveAction.Editing -> getString(R.string.live_editing, a.file ?: getString(R.string.live_file))
    LiveAction.Browsing -> getString(R.string.live_browsing)
    is LiveAction.Tool -> getString(R.string.live_tool, toolTitle(a.title, a.asked))
    LiveAction.Thinking -> getString(R.string.live_thinking)
    is LiveAction.Step -> a.title
    LiveAction.NeedsApproval -> getString(R.string.needs_approval)
    is LiveAction.Subagent -> getString(R.string.live_subagent, a.name ?: getString(R.string.subagent_unnamed), liveText(a.action))
    is LiveAction.Summary -> summaryText(a.summary)
}

fun Resources.toolTitle(title: String, asked: Boolean): String = if (asked) getString(R.string.asked_prefix, title) else title

/** "Explore · Fable 5.2 · Running git status…" while a subagent runs, "Explore · 42 steps · 1m 3s" once done. */
fun Resources.subagentLineText(l: SubagentLine): String {
    val parts = mutableListOf<String>()
    l.agentType?.let { parts += it }
    l.model?.let { parts += it }
    l.action?.let { parts += liveText(it) }
    l.steps?.let { parts += getQuantityString(R.plurals.steps_count, it, it) }
    l.durationSeconds?.let { parts += durationText(it) }
    if (l.stopped) parts += getString(R.string.stopped)
    return parts.joinToString(" · ")
}
