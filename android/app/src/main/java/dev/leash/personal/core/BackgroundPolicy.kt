package dev.leash.personal.core

/** A conversation whose turn this phone started (ios LiveActivityController.Tracked). */
data class TrackedTurn(val sessionId: String, val agent: String, val title: String, val startedAtMs: Long)

/** The turns this phone started and that are still going; the ongoing notification follows them. */
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

/**
 * Whether the foreground service that keeps the connection alive should run. Leaving the app decides it: with work still
 * going on the bridge (a running turn, wherever it was started, or a waiting request) Leash stays until all of it is done,
 * otherwise it lets go. Work that only appears after Leash has let go does not bring the service back.
 */
class BackgroundPolicy {
    enum class Action {
        /** the service runs */
        Run,
        /** on screen with nothing for the notification: no service, the connection stays */
        Stop,
        /** off screen with nothing to stay for: after [SETTLE_MS] stop the service and let go of the connection */
        Settle,
    }

    /** staying in the background, since the app was last left with work going on */
    private var staying = false

    /** The app left the screen. [busy]: a turn runs or a request waits on the bridge. */
    fun left(enabled: Boolean, busy: Boolean) { staying = enabled && busy }
    fun returned() { staying = false }
    /** The service and the connection were let go. */
    fun released() { staying = false }

    /**
     * [tracked]: there is something for the ongoing notification, which is all that runs the service on screen. A process
     * that was never on screen (a notification action started it) has not [left], so it only settles.
     */
    fun decide(enabled: Boolean, sceneActive: Boolean, tracked: Boolean, busy: Boolean): Action = when {
        sceneActive -> if (enabled && tracked) Action.Run else Action.Stop
        staying && enabled && busy -> Action.Run
        else -> Action.Settle
    }

    companion object {
        /** how long things stay up once nothing is left to stay for, so the last calls (noticed, an answer) can finish */
        const val SETTLE_MS = 15_000L
    }
}
