package dev.leash.personal.ui

import dev.leash.personal.core.ChatEntry
import dev.leash.personal.core.ChatStore
import dev.leash.personal.core.DemoScript
import dev.leash.personal.core.DisplayItem
import dev.leash.personal.ui.chat.foldSubagent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentFoldTest {
    private fun child(): dev.leash.personal.core.SubagentItem {
        val st = ChatStore("root")
        DemoScript.subagentHistory().forEach { st.apply(it) }
        return st.subagent("demo-child")!!
    }

    @Test fun promptThenOneGroupOfSteps() {
        val rows = foldSubagent(child())
        assertEquals(2, rows.size)
        assertEquals("List the Gradle files", (rows[0] as DisplayItem.User).item.text)
        val g = (rows[1] as DisplayItem.Activity).group
        // the child's note before its tool call is a step of the group, not an answer
        assertEquals(2, g.steps.size)
        assertTrue(g.steps[0] is ChatEntry.Message)
        assertTrue(g.steps[1] is ChatEntry.Tool)
        assertEquals(false, g.live)
    }

    @Test fun aRunningChildHasALiveGroup() {
        val st = ChatStore("root")
        DemoScript.subagentHistory().take(4).forEach { st.apply(it) }   // up to its first reply: still running
        val c = st.subagent("demo-child")
        assertNotNull(c)
        assertTrue(c!!.isRunning)
        val rows = foldSubagent(c)
        // prompt, the live (still empty) group, and the reply streaming in after it
        assertEquals(3, rows.size)
        assertTrue((rows[1] as DisplayItem.Activity).group.live)
        assertTrue(rows[2] is DisplayItem.Answer)
    }
}
