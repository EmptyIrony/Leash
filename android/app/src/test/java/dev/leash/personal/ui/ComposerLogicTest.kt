package dev.leash.personal.ui

import dev.leash.personal.core.AgentCommand
import dev.leash.personal.core.QueuedMessage
import dev.leash.personal.core.RemovedQueued
import dev.leash.personal.ui.chat.ActionGlyph
import dev.leash.personal.ui.chat.ComposerLogic
import dev.leash.personal.ui.chat.PlaceholderKind
import dev.leash.personal.ui.chat.QueueRow
import dev.leash.personal.ui.chat.queueRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerLogicTest {
    private val cmds = listOf(
        AgentCommand("review", "Review a pull request", "pr-number"),
        AgentCommand("init", "Create CLAUDE.md", null),
        AgentCommand("Compact", "Compact the conversation", ""),
    )

    @Test fun sendNeedsAReadyAttachedConversationAndText() {
        assertTrue(ComposerLogic.canSend(ComposerLogic.enabled(true, true), "hi"))
        assertFalse(ComposerLogic.canSend(ComposerLogic.enabled(true, false), "hi"))
        assertFalse(ComposerLogic.canSend(ComposerLogic.enabled(false, true), "hi"))
        assertFalse(ComposerLogic.canSend(true, "  \n "))
    }

    @Test fun glyphIsStopOnlyWhileRunningWithoutADraft() {
        assertEquals(ActionGlyph.Stop, ComposerLogic.glyph(running = true, draft = "", canSend = true))
        assertEquals(ActionGlyph.Send, ComposerLogic.glyph(running = true, draft = "more", canSend = true))
        assertEquals(ActionGlyph.Send, ComposerLogic.glyph(running = false, draft = "x", canSend = true))
        assertEquals(ActionGlyph.Disabled, ComposerLogic.glyph(running = false, draft = "", canSend = false))
        assertEquals(ActionGlyph.Disabled, ComposerLogic.glyph(running = true, draft = "x", canSend = false))
    }

    @Test fun secondaryStopAppearsWithADraftMidTurn() {
        assertTrue(ComposerLogic.showsSecondaryStop(true, "x"))
        assertFalse(ComposerLogic.showsSecondaryStop(true, " "))
        assertFalse(ComposerLogic.showsSecondaryStop(false, "x"))
    }

    @Test fun placeholderFollowsTheConnection() {
        assertEquals(PlaceholderKind.Offline, ComposerLogic.placeholder(false, false, false, false, "claude"))
        assertEquals(PlaceholderKind.AgentDown, ComposerLogic.placeholder(false, true, false, false, "claude"))
        assertEquals(PlaceholderKind.Steer, ComposerLogic.placeholder(true, true, true, true, "claude"))
        assertEquals(PlaceholderKind.Queue, ComposerLogic.placeholder(true, true, true, false, "agy"))
        assertEquals(PlaceholderKind.AskClaude, ComposerLogic.placeholder(true, true, false, true, "claude"))
        assertEquals(PlaceholderKind.AskAgent, ComposerLogic.placeholder(true, true, false, false, "agy"))
    }

    @Test fun paletteShowsForABareSlashWordOnly() {
        assertEquals(cmds, ComposerLogic.paletteMatches("/", true, false, cmds))
        assertEquals(listOf("review"), ComposerLogic.paletteMatches("/RE", true, false, cmds).map { it.name })
        assertEquals(listOf("Compact"), ComposerLogic.paletteMatches("/comp", true, false, cmds).map { it.name })
        assertTrue(ComposerLogic.paletteMatches("/review x", true, false, cmds).isEmpty())
        assertTrue(ComposerLogic.paletteMatches("hello /", true, false, cmds).isEmpty())
        assertTrue(ComposerLogic.paletteMatches("/", false, false, cmds).isEmpty())
        assertTrue(ComposerLogic.paletteMatches("/", true, true, cmds).isEmpty())
        assertTrue(ComposerLogic.paletteMatches("/zzz", true, false, cmds).isEmpty())
    }

    @Test fun pickedCommandNeedsAHint() {
        assertEquals("review", ComposerLogic.pickedCommand("/review 12", cmds)?.name)
        assertNull(ComposerLogic.pickedCommand("/review", cmds))
        assertNull(ComposerLogic.pickedCommand("/init now", cmds))
        assertNull(ComposerLogic.pickedCommand("/Compact now", cmds))
        assertNull(ComposerLogic.pickedCommand("review 12", cmds))
    }

    @Test fun takenBackMessageGoesInFront() {
        assertEquals("a", ComposerLogic.withUnsent("", "a"))
        assertEquals("a\nb", ComposerLogic.withUnsent("b", "a"))
    }

    private fun q(id: String) = QueuedMessage(id, "text $id", 0)

    @Test fun queueRowsWithoutARemovalAreTheQueue() {
        val rows = queueRows(listOf(q("1"), q("2")), null)
        assertEquals(listOf("1", "2"), rows.map { it.id })
        assertTrue(rows.all { it is QueueRow.Queued })
    }

    @Test fun removedMessageKeepsItsPlace() {
        val rows = queueRows(listOf(q("1"), q("3")), RemovedQueued(q("2"), 1))
        assertEquals(listOf("1", "2", "3"), rows.map { it.id })
        assertTrue(rows[1] is QueueRow.Removed)
    }

    @Test fun removedRowAtTheEndOrAloneIsClamped() {
        assertEquals(listOf("1", "2"), queueRows(listOf(q("1")), RemovedQueued(q("2"), 9)).map { it.id })
        val alone = queueRows(emptyList(), RemovedQueued(q("2"), 0))
        assertEquals(1, alone.size)
        assertTrue(alone[0] is QueueRow.Removed)
    }

    @Test fun aRestoredMessageIsNotShownTwice() {
        val rows = queueRows(listOf(q("1"), q("2")), RemovedQueued(q("2"), 1))
        assertEquals(listOf("1", "2"), rows.map { it.id })
        assertTrue(rows[1] is QueueRow.Removed)
    }
}
