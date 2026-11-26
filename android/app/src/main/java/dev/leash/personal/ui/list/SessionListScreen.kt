package dev.leash.personal.ui.list

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AgentInfo
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.AppState
import dev.leash.personal.core.LinkState
import dev.leash.personal.core.SessionSummary
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.components.StatusDot
import dev.leash.personal.ui.components.color
import dev.leash.personal.ui.components.connectionStatus
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.coroutines.launch

/** The conversation list (ios SessionListView): conversations waiting on you first, then one collapsible section per folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    model: AppModel,
    onOpenChat: (String) -> Unit,
    onOpenFolder: (agent: String, folder: String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
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

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            if (searchOpen) SearchBar(search, { search = it }, onClose = { searchOpen = false; search = "" })
            else TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (state.settingsLoaded && state.isConfigured) {
                            val st = connectionStatus(state)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(st.kind.color(), 7.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(st.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                actions = {
                    if (state.sessions.isNotEmpty()) IconButton(onClick = { searchOpen = true }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (state.isConfigured) ExtendedFloatingActionButton(
                onClick = { if (state.agentReady) onNew() },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.new_conversation)) },
                containerColor = if (state.agentReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (state.agentReady) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.sessionsLoading && state.sessions.isNotEmpty(),
            onRefresh = { scope.launch { model.refreshSessions() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (multiAgent) item("agent-filter") {
                    AgentFilter(state.agents, agentFilter) { agentFilter = it }
                }
                item("status") { StatusCard(state, model, onSettings) }
                if (needsYou.isNotEmpty()) {
                    item("needs-you-header") {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PanTool, null, Modifier.size(16.dp), tint = LeashTheme.colors.attention)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.needs_you), style = MaterialTheme.typography.titleSmall, color = LeashTheme.colors.attention)
                        }
                    }
                    items(needsYou, key = { "n:" + it.id }) { s -> SessionItem(s, state, model, multiAgent && agentFilter == "all", onOpenChat) }
                }
                val ids = groups.map { it.id }.toSet()
                groups.forEach { g ->
                    val open = searching || g.id in expanded
                    item("h:" + g.id) {
                        FolderHeader(g, state, model, multiAgent && agentFilter == "all", open,
                            onToggle = { if (!searching) setExpanded(if (g.id in expanded) expanded - g.id else expanded + g.id) },
                            onCollapseAll = { setExpanded(expanded - ids) }, onExpandAll = { setExpanded(expanded + ids) }, searching = searching)
                    }
                    if (open) {
                        val (shown, hidden) = SessionGrouping.visibleItems(g, searching, state.running)
                        items(shown, key = { "r:" + it.id }) { s -> SessionItem(s, state, model, multiAgent && agentFilter == "all", onOpenChat) }
                        if (hidden > 0) item("more:" + g.id) {
                            Text(
                                stringResource(R.string.show_all_count, g.items.size),
                                Modifier.fillMaxWidth().clickable { onOpenFolder(g.agent, g.folder) }.padding(horizontal = 20.dp, vertical = 14.dp),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                val empty = state.isConfigured && filtered.isEmpty()
                if (empty && searching) item("no-match") { EmptyState(Icons.Filled.Search, stringResource(R.string.no_matches), stringResource(R.string.no_matches_detail, search)) }
                else if (empty && state.sessions.isNotEmpty()) item("no-agent-rows") { EmptyState(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.no_conversations), null) }
                else if (empty && state.agentReady && !state.sessionsLoading) item("none") {
                    EmptyState(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.no_conversations), stringResource(R.string.no_conversations_detail))
                } else if (empty && state.sessionsLoading && state.sessions.isEmpty()) item("loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { ActivitySpinner(28.dp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchBar(value: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_search)) } },
        title = {
            TextField(
                value, onChange, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                placeholder = { Text(stringResource(R.string.search_conversations)) },
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
            )
        },
        actions = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AgentFilter(agents: List<AgentInfo>, selected: String, onSelect: (String) -> Unit) {
    val options = listOf("all" to stringResource(R.string.all)) + agents.map { it.id to it.label }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        options.forEachIndexed { i, (id, label) ->
            SegmentedButton(selected == id, { onSelect(id) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, detail: String?, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/** Not paired, the connection failed, an agent is down, or the list could not load. */
@Composable
private fun StatusCard(state: AppState, model: AppModel, onSettings: () -> Unit) {
    if (!state.settingsLoaded) return
    val scheme = MaterialTheme.colorScheme
    if (!state.isConfigured) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = scheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.pair_with_mac), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.pair_with_mac_detail), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Button(onClick = onSettings) { Text(stringResource(R.string.settings)) }
            }
        }
        return
    }
    val failed = state.connection as? LinkState.Failed
    if (failed != null) {
        Notice(Icons.Filled.ErrorOutline, failed.reason, scheme.error)
        return
    }
    if (state.isConnected) {
        val down = if (state.agents.isEmpty()) {
            if (state.agentState == "ready") emptyList() else listOf(AgentInfo("agy", "Antigravity", state.agentState, state.agentError))
        } else state.agents.filter { !it.isReady }
        if (down.isNotEmpty()) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainer) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    down.forEach { a ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (a.state == "starting") ActivitySpinner(18.dp) else AgentIcon(a.id, 18.dp)
                            Column(Modifier.weight(1f)) {
                                Text(if (a.state == "starting") stringResource(R.string.agent_starting, a.label) else stringResource(R.string.agent_not_running, a.label), style = MaterialTheme.typography.bodyMedium)
                                a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            }
                            if (a.state == "down") OutlinedButton(onClick = { model.restartAgent(a.id) }) { Text(stringResource(R.string.restart)) }
                        }
                    }
                }
            }
            return
        }
    }
    state.sessionsError?.let { Notice(Icons.Filled.ErrorOutline, it, LeashTheme.colors.attention) }
}

@Composable
private fun Notice(icon: ImageVector, text: String, tint: Color) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = tint)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
        }
    }
}

/** The collapsed header also says how many conversations the folder holds and whether one is working. Long-press: collapse or expand all. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(
    g: FolderGroup, state: AppState, model: AppModel, showAgent: Boolean, open: Boolean, searching: Boolean,
    onToggle: () -> Unit, onCollapseAll: () -> Unit, onExpandAll: () -> Unit,
) {
    val (title, general) = SessionGrouping.folderTitle(g.folder, state.home(g.agent))
    val running = g.items.count { it.running || it.id in state.running }
    val unread = g.items.count { model.isUnread(it) }
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onToggle, onLongClick = { if (!searching) menu = true }).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAgent) { AgentIcon(g.agent, 14.dp); Spacer(Modifier.width(8.dp)) }
            Icon(if (general) Icons.Filled.Inbox else Icons.Filled.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(if (general) stringResource(R.string.general) else title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!open) {
                if (running > 0) { ActivitySpinner(12.dp); Spacer(Modifier.width(8.dp)) }
                if (unread > 0) {
                    StatusDot(MaterialTheme.colorScheme.primary, 7.dp, Modifier.semantics { contentDescription = "$unread" })
                    Spacer(Modifier.width(8.dp))
                }
                Text(g.items.size.toString(), style = MaterialTheme.typography.bodyMedium, color = LeashTheme.colors.textTertiary)
                Spacer(Modifier.width(4.dp))
            }
            val rotation by androidx.compose.animation.core.animateFloatAsState(if (open) 0f else -90f, label = "chevron")
            Icon(Icons.Filled.ExpandMore, null, Modifier.size(20.dp).graphicsRotate(rotation), tint = LeashTheme.colors.textTertiary)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.collapse_all)) }, onClick = { menu = false; onCollapseAll() })
            DropdownMenuItem(text = { Text(stringResource(R.string.expand_all)) }, onClick = { menu = false; onExpandAll() })
        }
    }
}

private fun Modifier.graphicsRotate(deg: Float) = this.then(Modifier.graphicsLayer { rotationZ = deg })

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(s: SessionSummary, state: AppState, model: AppModel, showAgent: Boolean, onOpen: (String) -> Unit) {
    val unread = model.isUnread(s)
    var menu by remember { mutableStateOf(false) }
    Box {
        SessionRow(
            s, showAgent, state.drafts[s.id], unread, running = s.running || s.id in state.running,
            modifier = Modifier.combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { menu = true }),
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (unread) R.string.mark_read else R.string.mark_unread)) },
                onClick = { menu = false; model.setUnread(s.id, !unread) },
            )
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** One conversation: the unread dot in a gutter every row keeps, the title, "Draft: …" and the age, and a status glyph. */
@Composable
fun SessionRow(s: SessionSummary, showAgent: Boolean, draft: String?, unread: Boolean, running: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val attention = LeashTheme.colors.attention
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(if (unread) scheme.primary else Color.Transparent, CircleShape).semantics { if (unread) contentDescription = "Unread" })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.displayTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showAgent) { AgentIcon(s.agent, 12.dp); Spacer(Modifier.width(5.dp)) }
                val first = draft?.lineSequence()?.firstOrNull()?.trim()?.take(120)
                if (!first.isNullOrEmpty()) {
                    Text(stringResource(R.string.draft_prefix, first), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (s.updatedAtMs != null) Text(" · ", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                s.updatedAtMs?.let {
                    Text(
                        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                s.lockedBy?.let {
                    Text(" · " + stringResource(R.string.open_in_other, it), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            s.pendingCount > 0 -> Box(Modifier.size(26.dp).background(attention.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PanTool, stringResource(R.string.needs_you), Modifier.size(14.dp), tint = attention)
            }
            running -> ActivitySpinner(18.dp)
            s.lockedBy != null -> Icon(Icons.Filled.Lock, stringResource(R.string.locked_on_computer), Modifier.size(18.dp), tint = LeashTheme.colors.textTertiary)
        }
    }
}
