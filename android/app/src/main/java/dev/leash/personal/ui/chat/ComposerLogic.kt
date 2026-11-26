package dev.leash.personal.ui.chat

import dev.leash.personal.core.AgentCommand
import dev.leash.personal.core.QueuedMessage
import dev.leash.personal.core.RemovedQueued

/** What the primary circle of the composer shows (ios ChatView.ActionGlyph). */
enum class ActionGlyph { Stop, Send, Disabled }

enum class PlaceholderKind { AgentDown, Offline, Steer, Queue, AskClaude, AskAgent }

/** The composer's decisions, kept out of the Compose code so they can be tested. */
object ComposerLogic {
    fun hasDraft(draft: String): Boolean = draft.isNotBlank()

    /** The conversation can take a message now: its agent is up and the transcript is attached. */
    fun enabled(agentReady: Boolean, attached: Boolean): Boolean = agentReady && attached

    fun canSend(enabled: Boolean, draft: String): Boolean = enabled && hasDraft(draft)

    fun glyph(running: Boolean, draft: String, canSend: Boolean): ActionGlyph = when {
        running && !hasDraft(draft) -> ActionGlyph.Stop
        canSend -> ActionGlyph.Send
        else -> ActionGlyph.Disabled
    }

    /** Stop sits next to Send while a draft is waiting; Send then also offers "Interrupt and send". */
    fun showsSecondaryStop(running: Boolean, draft: String): Boolean = running && hasDraft(draft)

    fun placeholder(agentReady: Boolean, connected: Boolean, running: Boolean, steers: Boolean, agent: String): PlaceholderKind = when {
        !agentReady -> if (connected) PlaceholderKind.AgentDown else PlaceholderKind.Offline
        running -> if (steers) PlaceholderKind.Steer else PlaceholderKind.Queue
        agent == "claude" -> PlaceholderKind.AskClaude
        else -> PlaceholderKind.AskAgent
    }

    /**
     * The draft is a bare "/word" being typed: the commands it could start, in the order the agent lists them. Mid-turn a
     * message is steered into the turn or queued, not run as a command.
     */
    fun paletteMatches(draft: String, focused: Boolean, running: Boolean, commands: List<AgentCommand>): List<AgentCommand> {
        if (!focused || !draft.startsWith("/") || draft.any { it.isWhitespace() } || running) return emptyList()
        val q = draft.drop(1).lowercase()
        return if (q.isEmpty()) commands else commands.filter { it.name.lowercase().startsWith(q) }
    }

    /** A command was picked (or typed) and takes something: "/review pr-number". */
    fun pickedCommand(draft: String, commands: List<AgentCommand>): AgentCommand? {
        if (!draft.startsWith("/")) return null
        val space = draft.indexOfFirst { it.isWhitespace() }
        if (space < 0) return null
        val name = draft.substring(1, space)
        return commands.firstOrNull { it.name == name && !it.hint.isNullOrEmpty() }
    }

    /** A message taken back from the queue goes in front of whatever is being typed. */
    fun withUnsent(draft: String, unsent: String): String = if (draft.isEmpty()) unsent else unsent + "\n" + draft
}

/** One row of the queue tray: the queued message, or the "Removed · Undo" row that keeps its place. */
sealed interface QueueRow {
    val message: QueuedMessage
    val id: String get() = message.id
    data class Queued(override val message: QueuedMessage) : QueueRow
    data class Removed(override val message: QueuedMessage) : QueueRow
}

/** The queue as shown: a message just removed stays where it was until its undo window ends. */
fun queueRows(items: List<QueuedMessage>, removed: RemovedQueued?): List<QueueRow> {
    val rows = items.map<QueuedMessage, QueueRow> { QueueRow.Queued(it) }.toMutableList()
    if (removed != null) {
        rows.removeAll { it.id == removed.message.id }
        rows.add(minOf(removed.index, rows.size), QueueRow.Removed(removed.message))
    }
    return rows
}
