package dev.leash.personal.core

/** A conversation whose turn this phone started (ios LiveActivityController.Tracked). */
data class TrackedTurn(val sessionId: String, val agent: String, val title: String, val startedAtMs: Long)

/** The turns this phone started and that are still going. Only these keep the app running in the background and get the ongoing notification. */
class TurnTracker {
    private val turns = LinkedHashMap<String, TrackedTurn>()
    val all: List<TrackedTurn> get() = turns.values.toList()
    val isEmpty: Boolean get() = turns.isEmpty()

    fun started(t: TrackedTurn) { turns.remove(t.sessionId); turns[t.sessionId] = t }
    fun ended(sessionId: String) { turns.remove(sessionId) }
    /** The bridge's list of running turns is the truth (a turn that ended while disconnected, a new connection). */
    fun reconcile(running: Set<String>) { turns.keys.retainAll(running) }
    fun clear() = turns.clear()
}

enum class OngoingPhase { Working, NeedsApproval, NeedsAnswer }

/** What the ongoing notification shows (the Android counterpart of the Live Activity's focused conversation). */
data class OngoingContent(
    val sessionId: String?,
    val agent: String,
    val phase: OngoingPhase,
    /** whose turn or request it is; the title is worded from [phase] and this in the device language */
    val agentLabel: String,
    /** the conversation, or what the request is about */
    val text: String?,
    /** elapsed time counts from here */
    val sinceMs: Long?,
    /** other conversations that work or wait as well */
    val others: Int,
    val url: String,
)

object OngoingFocus {
    /**
     * One conversation is in focus: one waiting for you wins, otherwise the most recently started turn. [attention] are the
     * requests that arrived live and still wait.
     */
    fun pick(
        turns: List<TrackedTurn>,
        attention: List<PendingRequest>,
        agentLabel: (String) -> String,
        conversation: (String?) -> String?,
    ): OngoingContent? {
        val waiting = attention.minByOrNull { it.key }
        val ids = (turns.map { it.sessionId } + attention.mapNotNull { it.sessionId }).toSet()
        if (waiting != null) {
            val label = agentLabel(waiting.agentId)
            val turn = turns.firstOrNull { it.sessionId == waiting.sessionId }
            return OngoingContent(
                waiting.sessionId, waiting.agentId,
                if (waiting.isPermission) OngoingPhase.NeedsApproval else OngoingPhase.NeedsAnswer,
                label,
                conversation(waiting.sessionId) ?: turn?.title,
                turn?.startedAtMs,
                (ids.size - 1).coerceAtLeast(0),
                NotificationContent.link(waiting.sessionId, waiting.agentId),
            )
        }
        val t = turns.maxByOrNull { it.startedAtMs } ?: return null
        return OngoingContent(
            t.sessionId, t.agent, OngoingPhase.Working, agentLabel(t.agent), conversation(t.sessionId) ?: t.title,
            t.startedAtMs, (ids.size - 1).coerceAtLeast(0), NotificationContent.link(t.sessionId, t.agent),
        )
    }
}

/** Whether the foreground service that keeps the connection alive should run. */
object BackgroundPolicy {
    /** how long the connection stays up after you leave the app with nothing running */
    const val GRACE_MS = 180_000L
    /** how long the connection stays up after the service is no longer needed, so the last calls (noticed, an answer) can finish */
    const val RELEASE_MS = 15_000L

    data class Decision(val run: Boolean, /** when to decide again (the grace ends), if the answer then changes */ val recheckAtMs: Long?)

    /**
     * Runs while a turn this phone started works or a request waits, and for [GRACE_MS] after leaving the app. In the
     * foreground it only runs while something is busy (that is when its notification is wanted). [leftAtMs]: when the app
     * last left the screen (null: it did not, e.g. started in the background by a notification action: no grace then).
     */
    fun decide(enabled: Boolean, sceneActive: Boolean, busy: Boolean, leftAtMs: Long?, nowMs: Long): Decision {
        if (!enabled) return Decision(false, null)
        if (busy) return Decision(true, null)
        if (sceneActive || leftAtMs == null) return Decision(false, null)
        val end = leftAtMs + GRACE_MS
        return if (nowMs < end) Decision(true, end) else Decision(false, null)
    }
}
