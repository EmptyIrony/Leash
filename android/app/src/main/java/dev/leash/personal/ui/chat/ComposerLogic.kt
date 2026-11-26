package dev.leash.personal.ui.chat

import dev.leash.personal.core.AgentCommand
import dev.leash.personal.core.QueuedMessage
import dev.leash.personal.core.RemovedQueued

/** What the primary circle of the composer shows (ios ChatView.ActionGlyph); Run is the shell mode's return arrow. */
enum class ActionGlyph { Stop, Send, Run, Disabled, RunDisabled }

/** What the draft is: a message for the agent, a "!" shell command, or a "/btw" side question (ios isShell / isBtw). */
enum class DraftKind { Message, Shell, SideQuestion }

enum class PlaceholderKind { AgentDown, Offline, Steer, Queue, AskClaude, AskAgent }

/** The composer's decisions, kept out of the Compose code so they can be tested. */
object ComposerLogic {
    fun hasDraft(draft: String, hasAttachments: Boolean = false): Boolean = draft.isNotBlank() || hasAttachments

    /** "!" at the start turns the composer into a shell prompt; "/btw" (where the agent has side questions) into a side question. */
    fun kind(draft: String, asksSideQuestions: Boolean): DraftKind = when {
        draft.startsWith("!") -> DraftKind.Shell
        asksSideQuestions && draft.lowercase().startsWith("/btw") && (draft.length == 4 || draft[4].isWhitespace()) -> DraftKind.SideQuestion
        else -> DraftKind.Message
    }

    fun shellCommand(draft: String): String = draft.drop(1).trim()
    fun sideQuestion(draft: String): String = draft.drop(4).trim()

    /** What leaving the mode keeps: the draft without its "!" or "/btw". */
    fun leaveMode(draft: String, kind: DraftKind): String = when (kind) {
        DraftKind.Shell -> draft.drop(1)
        DraftKind.SideQuestion -> draft.drop(4).trimStart()
        DraftKind.Message -> draft
    }

    /** The conversation can take a message now: its agent is up and the transcript is attached. */
    fun enabled(agentReady: Boolean, attached: Boolean): Boolean = agentReady && attached

    /**
     * Whether the primary circle can act. A side question is not held back by a running turn (that is what it is for) but
     * needs room among the open ones; a shell command needs the agent idle; files being uploaded hold the message back.
     */
    fun canSend(
        enabled: Boolean, draft: String, kind: DraftKind = DraftKind.Message, hasAttachments: Boolean = false, uploading: Boolean = false,
        running: Boolean = false, shellRunning: Boolean = false, sideQuestionsFull: Boolean = false,
    ): Boolean {
        if (!enabled) return false
        return when (kind) {
            DraftKind.SideQuestion -> sideQuestion(draft).isNotEmpty() && !sideQuestionsFull
            DraftKind.Shell -> shellCommand(draft).isNotEmpty() && !running && !shellRunning
            DraftKind.Message -> !uploading && hasDraft(draft, hasAttachments)
        }
    }

    fun glyph(running: Boolean, draft: String, canSend: Boolean, hasAttachments: Boolean = false, kind: DraftKind = DraftKind.Message): ActionGlyph {
        val shell = kind == DraftKind.Shell
        return when {
            running && !hasDraft(draft, hasAttachments) -> ActionGlyph.Stop
            canSend -> if (shell) ActionGlyph.Run else ActionGlyph.Send
            else -> if (shell) ActionGlyph.RunDisabled else ActionGlyph.Disabled
        }
    }

    /** Stop sits next to Send while a draft is waiting; Send then also offers "Interrupt and send" (not for shell or side questions). */
    fun showsSecondaryStop(running: Boolean, draft: String, hasAttachments: Boolean = false): Boolean = running && hasDraft(draft, hasAttachments)

    fun offersInterrupt(running: Boolean, draft: String, hasAttachments: Boolean, kind: DraftKind): Boolean =
        running && hasDraft(draft, hasAttachments) && kind == DraftKind.Message

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
    fun paletteMatches(draft: String, focused: Boolean, running: Boolean, commands: List<AgentCommand>, sideQuestion: AgentCommand? = null): List<AgentCommand> {
        if (!focused || !draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
        // Leash's own /btw comes first and works mid-turn; the agent's commands don't (a message then is steered or queued)
        val all = listOfNotNull(sideQuestion) + if (running) emptyList() else commands.filter { !it.name.equals("btw", ignoreCase = true) }
        val q = draft.drop(1).lowercase()
        return if (q.isEmpty()) all else all.filter { it.name.lowercase().startsWith(q) }
    }

    /** A command was picked (or typed) and takes something: "/review pr-number". */
    fun pickedCommand(draft: String, commands: List<AgentCommand>): AgentCommand? {
        if (!draft.startsWith("/") || kind(draft, true) == DraftKind.SideQuestion) return null
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
