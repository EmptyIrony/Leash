package dev.leash.personal.ui.chat

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.ChatStore
import dev.leash.personal.core.DisplayItem
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.components.LocalChatVersion
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * A conversation's transcript, read-only in phase 2a (ios ChatView without its composer and trays). It stays attached while on
 * screen, keeps following the newest output until you scroll up, and shows load, locked and stop states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(model: AppModel, sessionId: String, onBack: () -> Unit, onOpenSubagent: (root: String, child: String) -> Unit, onBrowseFiles: (agent: String, cwd: String) -> Unit) {
    val store = remember(sessionId) { model.store(sessionId) }
    val state by model.state.collectAsStateWithLifecycle()
    val version by store.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val laterText = stringResource(R.string.file_viewer_later)

    LaunchedEffect(store) { if (!store.attached) model.attach(store) }
    DisposableEffect(store) {
        model.watch(store)
        onDispose { model.unwatch(store) }
    }

    // everything the screen draws is read here, so a change of the store's version redraws it (nested items change in place)
    val items = remember(version, state.sessions) { store.displayItems() }
    val title = store.title ?: stringResource(R.string.conversation)
    val cwd = store.cwd
    val agent = store.agent
    val subtitle = (cwd?.let { state.shortPath(it, agent) } ?: "").let { if (state.agents.size > 1) state.agentLabel(agent) + " · " + it else it }
    val pending = state.pendingFor(sessionId)
    val blocked = pending.any { it.isPermission }
    val loading = store.loading
    val loadError = store.loadError
    val lockedBy = store.lockedBy
    val running = store.running
    val stopReason = store.lastStopReason
    val empty = store.entries.isEmpty()
    val attached = store.attached

    val listState = rememberLazyListState()
    var pinned by remember { mutableStateOf(true) }
    // pinned means following the newest output; it ends when a scroll leaves the bottom and starts again when one returns
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { pinned = !listState.canScrollForward }
    }
    LaunchedEffect(version, items.size, pinned) {
        if (pinned && items.isNotEmpty()) listState.scrollToItem(items.lastIndex, scrollOffset = 1_000_000)
    }

    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browse_files)) },
                                enabled = cwd != null && state.isConnected,
                                onClick = { menu = false; cwd?.let { onBrowseFiles(agent, it) } },
                            )
                            if (cwd != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_folder_path)) },
                                onClick = { menu = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", cwd))) } },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            if (pending.isNotEmpty()) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PanTool, null, Modifier.size(16.dp), tint = LeashTheme.colors.attention)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.requests_waiting, pending.size), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            CompositionLocalProvider(
                LocalChatVersion provides version,
                LocalOpenFile provides { scope.launch { snackbar.showSnackbar(laterText) } },
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading) item("loading") {
                        Row(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            ActivitySpinner(18.dp); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.loading_conversation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (loadError != null && empty) item("load-error") {
                        LoadError(loadError, enabled = state.isReady(agent)) { scope.launch { model.attach(store) } }
                    } else if (attached && empty && !running) item("empty") {
                        Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AgentIcon(agent, 28.dp)
                            Text(stringResource(if (agent == "claude") R.string.ask_claude else R.string.ask_agent), style = MaterialTheme.typography.titleMedium)
                            cwd?.let { Text(state.shortPath(it, agent), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = LeashTheme.colors.textTertiary) }
                        }
                    }
                    itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                        DisplayRow(item, idx == 0, store, blocked) { child -> onOpenSubagent(sessionId, child) }
                    }
                    if (stopReason != null && stopReason != "end_turn" && !running) item("stop") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.StopCircle, null, Modifier.size(16.dp), tint = LeashTheme.colors.textTertiary)
                            Spacer(Modifier.width(6.dp))
                            Text(stopText(stopReason), style = MaterialTheme.typography.bodySmall, color = LeashTheme.colors.textTertiary)
                        }
                    }
                    if (loadError != null && !empty) item("load-error-inline") { LoadError(loadError, enabled = state.isReady(agent)) { scope.launch { model.attach(store) } } }
                    if (lockedBy != null) item("locked") {
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(lockText(lockedBy), style = MaterialTheme.typography.bodyMedium)
                                }
                                TextButton(onClick = { scope.launch { model.attach(store) } }) { Text(stringResource(R.string.retry), fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
            if (!pinned && listState.canScrollForward) SmallFloatingActionButton(
                onClick = { pinned = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.scroll_to_bottom)) }
        }
    }
}

@Composable
private fun LoadError(message: String, enabled: Boolean, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.couldnt_load_conversation), style = MaterialTheme.typography.titleSmall)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onRetry, enabled = enabled) { Text(stringResource(R.string.try_again)) }
        }
    }
}

/** One row of the transcript. */
@Composable
fun DisplayRow(item: DisplayItem, isFirst: Boolean, store: ChatStore, blocked: Boolean, onOpenSubagent: (String) -> Unit) {
    when (item) {
        is DisplayItem.User -> if (hasUserContent(item.item)) {
            val gap = if (isFirst || UserSegment.isContinuation(item.item.text)) 0.dp else 12.dp
            UserBubble(item.item, Modifier.padding(top = gap))
        }
        is DisplayItem.Activity -> ActivityRow(item.group, store, blocked = item.group.live && blocked, onOpenSubagent = onOpenSubagent)
        is DisplayItem.Segment -> SegmentRow(item.segment, store, blocked = item.segment.liveLine != null && blocked, onOpenSubagent = onOpenSubagent)
        is DisplayItem.Answer -> AnswerView(item.item)
        is DisplayItem.Stats -> StatsLine(item.stats)
        is DisplayItem.Notice -> NoticeView(item.notice)
        is DisplayItem.PlanDoc -> PlanCard(item.plan)
        is DisplayItem.Changes -> ChangesRow(item.changes)
    }
}

@Composable
private fun stopText(reason: String) = stringResource(
    when (reason) {
        "max_tokens" -> R.string.stop_max_tokens
        "max_turn_requests" -> R.string.stop_max_turn
        "refusal" -> R.string.stop_refusal
        else -> R.string.stop_generic
    },
)

@Composable
private fun lockText(by: String) = stringResource(
    when (by) {
        "cli" -> R.string.lock_cli
        "claude-desktop" -> R.string.lock_desktop
        "antigravity" -> R.string.lock_agy
        else -> R.string.lock_other
    },
)
