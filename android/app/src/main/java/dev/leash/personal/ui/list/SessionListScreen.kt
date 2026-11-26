package dev.leash.personal.ui.list

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AgentInfo
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.AppState
import dev.leash.personal.core.LinkState
import dev.leash.personal.core.SessionSummary
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.components.color
import dev.leash.personal.ui.components.connectionStatus
import dev.leash.personal.ui.design.components.GlassCircleButton
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LocalReduceMotion
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.components.BottomActions
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.DraftText
import dev.leash.personal.ui.design.components.EmptyState
import dev.leash.personal.ui.design.components.LargeTitle
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LeashMenu
import dev.leash.personal.ui.design.components.LeashMenuItem
import dev.leash.personal.ui.design.components.LeashRefreshBox
import dev.leash.personal.ui.design.components.LoadingState
import dev.leash.personal.ui.design.components.RowStatus
import dev.leash.personal.ui.design.components.SearchField
import dev.leash.personal.ui.design.components.SectionHeader
import dev.leash.personal.ui.design.components.SegmentedControl
import dev.leash.personal.ui.design.components.SessionRow
import dev.leash.personal.ui.design.components.ShowAllRow
import dev.leash.personal.ui.design.components.TopBar
import dev.leash.personal.ui.design.components.UnreadDot
import dev.leash.personal.ui.design.components.groupItem
import dev.leash.personal.ui.design.softBottomEdge
import dev.leash.personal.ui.design.tabular
import kotlinx.coroutines.launch

/** The conversation list (ios SessionListView): conversations waiting on you first, then one collapsible section per folder. */
@Composable
fun SessionListScreen(
    model: AppModel,
    onOpenChat: (String) -> Unit,
    onOpenFolder: (agent: String, folder: String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val c = Leash.colors
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var agentFilter by rememberSaveable { mutableStateOf("all") }
    // open folder sections (ids joined by newlines, so it survives recreation); null until the first launch default is set
    var expandedRaw by rememberSaveable { mutableStateOf<String?>(null) }

    val multiAgent = state.agents.size > 1
    val filtered = remember(state.sessions, agentFilter, multiAgent, search) { SessionGrouping.filter(state.sessions, agentFilter, multiAgent, search) }
    val needsYou = remember(filtered) { SessionGrouping.needsYou(filtered) }
    val groups = remember(filtered) { SessionGrouping.groups(filtered) }
    val searching = search.isNotBlank()
    LaunchedEffect(groups.firstOrNull()?.id) {
        if (expandedRaw == null && groups.isNotEmpty()) expandedRaw = SessionGrouping.initialExpansion(groups).joinToString("\n")
    }
    val expanded = remember(expandedRaw) { expandedRaw?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet() }
    fun setExpanded(ids: Set<String>) { expandedRaw = ids.sorted().joinToString("\n") }
    val showAgent = multiAgent && agentFilter == "all"

    val list = rememberLazyListState()
    // the large title is the first item: once it has scrolled away the inline title fades in; the top edge shows as soon as anything is under the bar
    val titleGone by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 120 } }
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    GlassScope(Modifier.fillMaxSize()) {
        LeashRefreshBox(
            refreshing = state.sessionsLoading && state.sessions.isNotEmpty(),
            onRefresh = { scope.launch { model.refreshSessions() } },
            modifier = Modifier.fillMaxSize(),
            indicatorTop = top + 56.dp,
        ) {
            GlassSource(Modifier.fillMaxSize()) {
                // the page itself is part of what the glass blurs, or the bands would show a flat colour over the gradient
                SpaceBackground()
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top + 60.dp, bottom = bottom + 96.dp)) {
                    item("title") {
                        val sub = if (state.settingsLoaded && state.isConfigured) connectionStatus(state).text else null
                        LargeTitle(stringResource(R.string.app_name_title), subtitle = sub)
                    }
                    if (multiAgent) item("agent-filter") {
                        val options = listOf("all" to stringResource(R.string.all)) + state.agents.map { it.id to it.label }
                        SegmentedControl(
                            options.map { it.second }, options.indexOfFirst { it.first == agentFilter }.coerceAtLeast(0), { agentFilter = options[it].first },
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth(),
                        )
                    }
                    item("status") { StatusCard(state, model, onSettings) }
                    if (needsYou.isNotEmpty()) {
                        item("needs-you-header") { SectionHeader(stringResource(R.string.needs_you), Modifier.padding(top = 12.dp), icon = LeashIcons.needsYou) }
                        conversationItems(needsYou, "n:", state, model, showAgent, onOpenChat)
                    }
                    val ids = groups.map { it.id }.toSet()
                    groups.forEach { g ->
                        val open = searching || g.id in expanded
                        item("h:" + g.id) {
                            FolderHeader(
                                g, state, model, showAgent, open, searching,
                                onToggle = { if (!searching) setExpanded(if (g.id in expanded) expanded - g.id else expanded + g.id) },
                                onCollapseAll = { setExpanded(expanded - ids) }, onExpandAll = { setExpanded(expanded + ids) },
                            )
                        }
                        if (open) {
                            val (shown, hidden) = SessionGrouping.visibleItems(g, searching, state.running)
                            conversationItems(shown, "r:", state, model, showAgent, onOpenChat, lastIsEnd = hidden == 0)
                            if (hidden > 0) item("more:" + g.id) {
                                ShowAllRow(stringResource(R.string.show_all_count, g.items.size), { onOpenFolder(g.agent, g.folder) }, Modifier.groupItem(first = false, last = true))
                            }
                        }
                    }
                    val empty = state.isConfigured && filtered.isEmpty()
                    if (empty && searching) item("no-match") {
                        EmptyState(LeashIcons.search, stringResource(R.string.no_matches), Modifier.padding(top = 48.dp), message = stringResource(R.string.no_matches_detail, search))
                    } else if (empty && state.sessions.isNotEmpty()) item("no-agent-rows") {
                        EmptyState(LeashIcons.note, stringResource(R.string.no_conversations), Modifier.padding(top = 48.dp))
                    } else if (empty && state.agentReady && !state.sessionsLoading) item("none") {
                        EmptyState(
                            LeashIcons.note, stringResource(R.string.no_conversations), Modifier.padding(top = 48.dp),
                            message = stringResource(R.string.no_conversations_detail),
                            actionLabel = stringResource(R.string.new_conversation), onAction = onNew,
                        )
                    } else if (empty && state.sessionsLoading && state.sessions.isEmpty()) item("loading") {
                        LoadingState(stringResource(R.string.starting), Modifier.padding(top = 64.dp))
                    }
                }
            }
        }
        TopBar(
            title = stringResource(R.string.app_name_title),
            titleVisible = titleGone,
            edgeVisible = scrolled,
            navigation = {
                val st = if (state.isConfigured) connectionStatus(state) else null
                GlassCircleButton(
                    LeashIcons.server, stringResource(R.string.settings), onSettings,
                    badge = st?.let { s -> { Box(Modifier.padding(4.dp).size(9.dp).clip(CircleShape).background(s.kind.color())) } },
                )
            },
        )
        // the bottom: content fades out under the actions; search turns the circles into a field above the keyboard
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom + 80.dp).softBottomEdge(blur = false))
        val reduced = LocalReduceMotion.current
        AnimatedVisibility(
            visible = !searchOpen && state.isConfigured, modifier = Modifier.align(Alignment.BottomCenter),
            enter = Motion.popEnter(reduced), exit = Motion.popExit(reduced),
        ) {
            BottomActions(
                start = if (state.sessions.isNotEmpty()) ({ GlassCircleButton(LeashIcons.search, stringResource(R.string.search), { searchOpen = true }, size = 52.dp) }) else null,
                end = {
                    GlassCircleButton(
                        LeashIcons.squareAndPencil, stringResource(R.string.new_conversation), { if (state.agentReady) onNew() },
                        size = 52.dp, tint = if (state.agentReady) c.accentControl else c.textTertiary, enabled = state.agentReady,
                    )
                },
            )
        }
        AnimatedVisibility(
            visible = searchOpen, modifier = Modifier.align(Alignment.BottomCenter),
            enter = Motion.popEnter(reduced), exit = Motion.popExit(reduced),
        ) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(search, { search = it }, stringResource(R.string.search_conversations), stringResource(R.string.clear), Modifier.weight(1f))
                GlassCircleButton(LeashIcons.close, stringResource(R.string.close_search), { searchOpen = false; search = "" }, size = 48.dp)
            }
        }
    }
}

/** Rows of one grouped card, split into lazy items. The card ends with the last row unless a "Show all" row follows ([lastIsEnd]). */
internal fun LazyListScope.conversationItems(
    rows: List<SessionSummary>, keyPrefix: String, state: AppState, model: AppModel, showAgent: Boolean,
    onOpen: (String) -> Unit, lastIsEnd: Boolean = true,
) {
    itemsIndexed(rows, key = { _, s -> keyPrefix + s.id }) { i, s ->
        val last = i == rows.lastIndex && lastIsEnd
        ConversationItem(s, state, model, showAgent, onOpen, Modifier.groupItem(first = i == 0, last = last), separator = !last)
    }
}

/** Not paired, the connection failed, an agent is down, or the list could not load. */
@Composable
private fun StatusCard(state: AppState, model: AppModel, onSettings: () -> Unit) {
    if (!state.settingsLoaded) return
    val c = Leash.colors
    if (!state.isConfigured) {
        Column(
            Modifier.padding(top = 16.dp).groupItem(first = true, last = true).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(LeashIcons.scan, null, Modifier.size(28.dp), tint = c.accent)
            Text(stringResource(R.string.pair_with_bridge), style = Leash.type.title3, color = c.textPrimary)
            Text(stringResource(R.string.pair_with_bridge_detail), style = Leash.type.subheadline, color = c.textSecondary)
            Spacer(Modifier.height(4.dp))
            LeashButton(stringResource(R.string.settings), onSettings, Modifier.fillMaxWidth())
        }
        return
    }
    val failed = state.connection as? LinkState.Failed
    if (failed != null) {
        Notice(LeashIcons.failed, failed.reason, c.danger)
        return
    }
    if (state.isConnected) {
        val down = if (state.agents.isEmpty()) {
            if (state.agentState == "ready") emptyList() else listOf(AgentInfo("agy", "Antigravity", state.agentState, state.agentError))
        } else state.agents.filter { !it.isReady }
        if (down.isNotEmpty()) {
            Column(Modifier.padding(top = 16.dp).groupItem(first = true, last = true).padding(horizontal = 16.dp, vertical = 8.dp)) {
                down.forEach { a ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (a.state == "starting") Spinner(size = 18.dp) else AgentIcon(a.id, 18.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (a.state == "starting") stringResource(R.string.agent_starting, a.label) else stringResource(R.string.agent_not_running, a.label),
                                style = Leash.type.body, color = c.textPrimary,
                            )
                            a.error?.let { Text(it, style = Leash.type.footnote, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                        if (a.state == "down") LeashButton(stringResource(R.string.restart), { model.restartAgent(a.id) }, style = ButtonStyle.Tinted, size = ButtonSize.Small)
                    }
                }
            }
            return
        }
    }
    state.sessionsError?.let { Notice(LeashIcons.warning, it, c.attention) }
}

@Composable
private fun Notice(icon: ImageVector, text: String, tint: Color) {
    Row(
        Modifier.padding(top = 16.dp).groupItem(first = true, last = true).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(text, style = Leash.type.subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Leash.colors.textPrimary)
    }
}

/** The collapsed header also says how many conversations the folder holds and whether one is working. Long-press: collapse or expand all. */
@Composable
private fun FolderHeader(
    g: FolderGroup, state: AppState, model: AppModel, showAgent: Boolean, open: Boolean, searching: Boolean,
    onToggle: () -> Unit, onCollapseAll: () -> Unit, onExpandAll: () -> Unit,
) {
    val c = Leash.colors
    val (title, general) = SessionGrouping.folderTitle(g.folder, state.home(g.agent))
    val running = g.items.count { it.running || it.id in state.running }
    val unread = g.items.count { model.isUnread(it) }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 12.dp)) {
        SectionHeader(
            if (general) stringResource(R.string.general) else title,
            icon = if (general) LeashIcons.general else LeashIcons.folder,
            leading = if (showAgent) ({ AgentIcon(g.agent, 14.dp) }) else null,
            expanded = open,
            onClick = onToggle,
            onLongClick = { if (!searching) menu = true },
            trailing = if (open) null else ({
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running > 0) Spinner(size = 12.dp)
                    if (unread > 0) UnreadDot(true, Modifier.semantics { contentDescription = "$unread" }, size = 7.dp)
                    Text(g.items.size.toString(), style = Leash.type.subheadline.tabular(), color = c.textTertiary)
                }
            }),
        )
        LeashMenu(menu, { menu = false }) {
            LeashMenuItem(stringResource(R.string.collapse_all), { menu = false; onCollapseAll() })
            LeashMenuItem(stringResource(R.string.expand_all), { menu = false; onExpandAll() })
        }
    }
}

/** One conversation row with its long-press menu (mark as read / unread). */
@Composable
internal fun ConversationItem(s: SessionSummary, state: AppState, model: AppModel, showAgent: Boolean, onOpen: (String) -> Unit, modifier: Modifier, separator: Boolean) {
    val unread = model.isUnread(s)
    val running = s.running || s.id in state.running
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        SessionRow(
            s.displayTitle,
            unread = unread,
            status = when {
                s.pendingCount > 0 -> RowStatus.NeedsYou
                running -> RowStatus.Running
                s.lockedBy != null -> RowStatus.Locked
                else -> RowStatus.None
            },
            last = !separator,
            onClick = { onOpen(s.id) },
            onLongClick = { menu = true },
        ) { ConversationMeta(s, showAgent, state.drafts[s.id]) }
        LeashMenu(menu, { menu = false }) {
            LeashMenuItem(stringResource(if (unread) R.string.mark_read else R.string.mark_unread), { menu = false; model.setUnread(s.id, !unread) })
        }
    }
}

/** The row's meta line: agent glyph, "Draft: …" or the age, and who holds it. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ConversationMeta(s: SessionSummary, showAgent: Boolean, draft: String?) {
    if (showAgent) AgentIcon(s.agent, 12.dp)
    val first = draft?.lineSequence()?.firstOrNull()?.trim()?.take(120)
    if (!first.isNullOrEmpty()) {
        val label = stringResource(R.string.draft_prefix, "").trim()
        DraftText(label, first, Modifier.weight(1f, fill = false))
        if (s.updatedAtMs != null) Text("·")
    }
    s.updatedAtMs?.let { Text(relativeAge(it), maxLines = 1) }
    s.lockedBy?.let { Text("· " + stringResource(R.string.open_in_other, it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** "5 min. ago" in the system language; a phone clock a little behind the bridge's must not read "in 1 minute". */
internal fun relativeAge(ms: Long): String {
    val now = System.currentTimeMillis()
    return DateUtils.getRelativeTimeSpanString(minOf(ms, now), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
