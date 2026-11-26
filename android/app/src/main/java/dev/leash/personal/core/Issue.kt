package dev.leash.personal.core

/**
 * What went wrong when Leash itself found the problem, for the UI to word in the device language (core has no resources, so it
 * stays JVM-testable). The English message next to it is for logs and tests only. Text that comes from the bridge, the network
 * stack or Tailscale is not an [Issue]: it is shown as it is.
 */
enum class Issue {
    SetAddressAndToken, InvalidAddress, InvalidToken, TokenRejected, NoReply, Closed, ClosedByBridge, NotConnected, TimedOut,
    EmptyResponse, UnsupportedImage, ImageTooLarge, FileTooLarge, UploadFailed, NoFileData, NoSessionId, NoAnswer,
    TailnetUnreadable, TailnetUnexpected, TailnetNoRoute, TailnetLogoutFailed, TailnetNotStarted, TailnetNotConnected,
    TailnetStatusFailed, TailnetNeedsKey, TailnetStopped,
}

/** The [Issue] to show when [message] is Leash's own fallback (the system gave none), else null: its own text is shown. */
internal fun fallbackIssue(message: String?, issue: Issue): Issue? = if (message == null) issue else null

/** An error to show: [message] is the bridge's, the system's or Leash's English words; [issue] and [detail] let the UI word Leash's own in the device language. */
data class Problem(val message: String, val issue: Issue? = null, val detail: String? = null)

fun Throwable.toProblem(): Problem = when (this) {
    is BridgeError -> Problem(message, issue, detail)
    is TunnelError -> Problem(message, issue)
    else -> Problem(message ?: toString())
}
