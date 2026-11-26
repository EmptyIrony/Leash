package dev.leash.personal.ui

import dev.leash.personal.core.SessionSummary
import dev.leash.personal.ui.list.SessionGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {
    private fun s(id: String, cwd: String, agent: String = "claude", title: String? = id, pending: Int = 0, running: Boolean = false) =
        SessionSummary(id, agent, cwd, title, 0L, running, pending)

    @Test fun groupsKeepOrderAndSplitByAgent() {
        val rows = listOf(s("a", "/p/x"), s("b", "/p/y"), s("c", "/p/x"), s("d", "/p/x", agent = "agy"))
        val g = SessionGrouping.groups(rows)
        assertEquals(listOf("claude|/p/x", "claude|/p/y", "agy|/p/x"), g.map { it.id })
        assertEquals(listOf("a", "c"), g[0].items.map { it.id })
    }

    @Test fun waitingRowsLeaveTheirFolder() {
        val rows = listOf(s("a", "/p/x", pending = 1), s("b", "/p/x"))
        assertEquals(listOf("a"), SessionGrouping.needsYou(rows).map { it.id })
        assertEquals(listOf("b"), SessionGrouping.groups(rows).single().items.map { it.id })
    }

    @Test fun filterByAgentAndSearch() {
        val rows = listOf(s("a", "/p/x", title = "Fix parser"), s("b", "/p/y", agent = "agy", title = "Other"))
        assertEquals(2, SessionGrouping.filter(rows, "all", true, "").size)
        assertEquals(listOf("b"), SessionGrouping.filter(rows, "agy", true, "").map { it.id })
        assertEquals(2, SessionGrouping.filter(rows, "agy", false, "").size)   // single agent: the filter is ignored
        assertEquals(listOf("a"), SessionGrouping.filter(rows, "all", true, "PARSER").map { it.id })
        assertEquals(listOf("b"), SessionGrouping.filter(rows, "all", true, "/p/y").map { it.id })
    }

    @Test fun bigFoldersShowFiveAndRunningOnes() {
        val items = (1..9).map { s("r$it", "/p", running = it == 8) }
        val g = SessionGrouping.groups(items).single()
        val (shown, hidden) = SessionGrouping.visibleItems(g, searching = false)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5", "r8"), shown.map { it.id })
        assertEquals(3, hidden)
        assertEquals(0, SessionGrouping.visibleItems(g, searching = true).second)
        val small = SessionGrouping.groups((1..7).map { s("q$it", "/p") }).single()
        assertEquals(0, SessionGrouping.visibleItems(small, false).second)
    }

    @Test fun folderTitles() {
        assertEquals("General" to true, SessionGrouping.folderTitle("/Users/me", "/Users/me"))
        assertEquals("General" to true, SessionGrouping.folderTitle("", "/Users/me"))
        assertEquals("leash" to false, SessionGrouping.folderTitle("/Users/me/workspace/leash/", "/Users/me"))
        assertTrue(SessionGrouping.initialExpansion(emptyList()).isEmpty())
    }
}
