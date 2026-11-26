package dev.leash.personal.ui.list

import dev.leash.personal.core.SessionSummary

/** A folder's conversations in the list: one section per agent and working directory. */
data class FolderGroup(val id: String, val agent: String, val folder: String, val items: List<SessionSummary>) {
    companion object {
        fun id(agent: String, folder: String) = "$agent|$folder"
    }
}

/** What the conversation list shows, from the sessions, the agent filter and the search text (ios SessionListView's computed sections). */
object SessionGrouping {
    /** Rows after the agent filter ("all" or an agent id; ignored with a single agent) and the search text (title or folder). */
    fun filter(sessions: List<SessionSummary>, agentFilter: String, multiAgent: Boolean, search: String): List<SessionSummary> {
        val q = search.trim()
        return sessions.filter { s ->
            (agentFilter == "all" || !multiAgent || s.agent == agentFilter) &&
                (q.isEmpty() || (s.displayTitle ?: "").contains(q, ignoreCase = true) || s.cwd.contains(q, ignoreCase = true))
        }
    }

    /** Conversations waiting on an answer, shown first. */
    fun needsYou(filtered: List<SessionSummary>): List<SessionSummary> = filtered.filter { it.pendingCount > 0 }

    /** Sections in order of their newest conversation (the bridge sends newest first); rows waiting on the user are in "Needs you" instead. */
    fun groups(filtered: List<SessionSummary>): List<FolderGroup> {
        val order = mutableListOf<String>()
        val map = LinkedHashMap<String, MutableList<SessionSummary>>()
        for (s in filtered) {
            if (s.pendingCount > 0) continue
            val k = FolderGroup.id(s.agent, s.cwd)
            if (k !in map) order += k
            map.getOrPut(k) { mutableListOf() } += s
        }
        return order.mapNotNull { k ->
            val items = map[k] ?: return@mapNotNull null
            val first = items.firstOrNull() ?: return@mapNotNull null
            FolderGroup(k, first.agent, first.cwd, items)
        }
    }

    const val INLINE_CAP = 5

    /** Big folders show their newest few inline and the rest behind "Show all"; running ones always show. Returns (shown, hidden count). */
    fun visibleItems(g: FolderGroup, searching: Boolean, runningIds: Set<String> = emptySet()): Pair<List<SessionSummary>, Int> {
        if (searching || g.items.size <= INLINE_CAP + 2) return g.items to 0
        val shown = g.items.take(INLINE_CAP) + g.items.drop(INLINE_CAP).filter { it.running || it.id in runningIds }
        return shown to (g.items.size - shown.size)
    }

    /** Section title for a working folder; the agent's home directory is "General". Returns (title, isGeneral). */
    fun folderTitle(folder: String, home: String?): Pair<String, Boolean> {
        val general = folder.isEmpty() || folder == home
        return (if (general) "General" else folder.trimEnd('/').substringAfterLast('/')) to general
    }

    /** First launch: only the folder with the newest conversation starts open. */
    fun initialExpansion(groups: List<FolderGroup>): Set<String> = groups.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
}
