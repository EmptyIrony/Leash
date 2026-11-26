package dev.leash.personal.ui.chat

import android.content.ClipData
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import dev.leash.personal.ui.components.pluralText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.ChatStore
import dev.leash.personal.core.Clock
import dev.leash.personal.core.DisplayItem
import dev.leash.personal.core.Haptic
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.components.LocalChatVersion
import dev.leash.personal.ui.theme.LeashRadius
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * A conversation: transcript, trays for what waits on the user (approval or question, running subagents, queued messages,
 * the command palette), the composer and the selector chips (ios ChatView). It stays attached while on screen, keeps
 * following the newest output until you scroll up, and shows load, locked and stop states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    model: AppModel,
    sessionId: String,
    onBack: () -> Unit,
    onOpenSubagent: (root: String, child: String) -> Unit,
    onBrowseFiles: (agent: String, cwd: String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val store = remember(sessionId) { model.store(sessionId) }
    val state by model.state.collectAsStateWithLifecycle()
    val version by store.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val laterText = stringResource(R.string.file_viewer_later)
    val context = LocalContext.current

    LaunchedEffect(store) { if (!store.attached) model.attach(store) }
    DisposableEffect(store) {
        model.watch(store)
        onDispose { model.unwatch(store) }
    }
    // a removed queued message stays removable a little longer with TalkBack on: reaching Undo takes more than a glance
    LaunchedEffect(Unit) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        model.queueUndoWindowMs = if (am?.isTouchExplorationEnabled == true) 20_000L else 6_000L
    }
    // on open and after each turn: a first turn here is what creates the transcript
    LaunchedEffect(store.running) { if (!store.running) model.refreshCliListing(store) }

    // everything the screen draws is read here, so a change of the store's version redraws it (nested items change in place)
    val items = remember(version, state.sessions) { store.displayItems() }
    val title = store.title ?: stringResource(R.string.conversation)
    val cwd = store.cwd
    val agent = store.agent
    val subtitle = (cwd?.let { state.shortPath(it, agent) } ?: "").let { if (state.agents.size > 1) state.agentLabel(agent) + " · " + it else it }
    val pending = state.pendingFor(sessionId)
    val permissions = pending.filter { it.isPermission }
    val questions = pending.filter { !it.isPermission }
    val trayVisible = pending.isNotEmpty()
    val blocked = permissions.isNotEmpty()
    val loading = store.loading
    val loadError = store.loadError
    val lockedBy = store.lockedBy
    val running = store.running
    val stopReason = store.lastStopReason
    val empty = store.entries.isEmpty()
    val attached = store.attached
    val queued = store.queued
    val removedQueued = store.removedQueued
    val subagents = store.runningSubagents
    val composerError = store.composerError
    val agentReady = state.isReady(agent)
    val composerEnabled = ComposerLogic.enabled(agentReady, attached)

    // MARK: transcript following
    val listState = rememberLazyListState()
    var pinned by remember { mutableStateOf(true) }
    // pinned means following the newest output; it ends when a scroll leaves the bottom and starts again when one returns
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { pinned = !listState.canScrollForward }
    }
    // The bottom bar and the keyboard change the viewport: following the newest output has to keep the last row in view
    val viewport by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    // Opening a step group or run grows the list below the header that was tapped: that is not new output to follow, and
    // following it would scroll the header away. Opening something ends the following (the jump button brings it back).
    val expandedCount = store.expandedGroups.size + store.expandedSteps.size
    var seenExpanded by remember { mutableIntStateOf(expandedCount) }
    LaunchedEffect(version, items.size, pinned, viewport) {
        val opened = expandedCount > seenExpanded
        seenExpanded = expandedCount
        if (opened) pinned = false
        else if (pinned && items.isNotEmpty()) listState.scrollToItem(items.lastIndex, scrollOffset = 1_000_000)
    }

    // MARK: composer
    var draft by remember(sessionId) { mutableStateOf(TextFieldValue(state.drafts[sessionId].orEmpty()).let { it.copy(selection = TextRange(it.text.length)) }) }
    val focusRequester = remember { FocusRequester() }
    var composerFocused by remember { mutableStateOf(false) }
    // what was typed stays with the conversation: left (Back, a notification tap) or the app put away, also across launches
    val draftText by rememberUpdatedState(draft.text)
    DisposableEffect(sessionId) { onDispose { model.setDraft(draftText, sessionId) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, sessionId) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) model.setDraft(draftText, sessionId) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // sent (or cleared): a crash after that mustn't bring it back
    LaunchedEffect(draft.text.isEmpty()) { if (draft.text.isEmpty()) model.setDraft("", sessionId) }
    // a queued message taken back goes in front of whatever is being typed
    val unsent = store.unsentDraft
    LaunchedEffect(unsent) {
        if (!unsent.isNullOrEmpty()) {
            val t = ComposerLogic.withUnsent(draft.text, unsent)
            draft = TextFieldValue(t, TextRange(t.length))
            store.unsentDraft = null
        }
    }
    val canSend = ComposerLogic.canSend(composerEnabled, draft.text)
    val commands = ComposerLogic.paletteMatches(draft.text, composerFocused, running, store.commands)
    val pickedCommand = ComposerLogic.pickedCommand(draft.text, store.commands)
    val placeholder = when (ComposerLogic.placeholder(agentReady, state.isConnected, running, state.steers(agent), agent)) {
        PlaceholderKind.AgentDown -> stringResource(R.string.placeholder_agent_down, state.agentLabel(agent))
        PlaceholderKind.Offline -> stringResource(R.string.placeholder_offline)
        PlaceholderKind.Steer -> stringResource(R.string.placeholder_steer)
        PlaceholderKind.Queue -> stringResource(R.string.placeholder_queue)
        PlaceholderKind.AskClaude -> stringResource(R.string.placeholder_claude)
        PlaceholderKind.AskAgent -> stringResource(R.string.placeholder_agent)
    }
    val buzz = { kind: Haptic -> model.hooks.haptic(kind) }
    // the message rises into the transcript and the scroll follows it
    fun sendDraft(interrupt: Boolean) {
        if (!canSend) return
        store.composerError = null
        model.send(store, draft.text.trim(), interrupt = interrupt && running)
        draft = TextFieldValue("")
        pinned = true
        buzz(if (interrupt) Haptic.Heavy else Haptic.Light)
    }
    fun stopTurn() { model.cancel(store); buzz(Haptic.Heavy) }
    fun sendQueuedNow() {
        store.composerError = null
        val interrupting = running
        model.sendQueued(store)
        pinned = true
        buzz(if (interrupting) Haptic.Heavy else Haptic.Light)
    }
    // a queue that moved on (a turn ended or started, the queue went out): what was removed stays removed
    LaunchedEffect(running, store.turnStartedAtMs) { store.forgetRemovedQueued() }
    // "Sending…" under the optimistic bubble once its echo has taken 2 s
    val echoId = store.pendingEcho?.id
    var sendingSlow by remember { mutableStateOf(false) }
    LaunchedEffect(echoId) {
        sendingSlow = false
        if (echoId != null) { delay(2_000); sendingSlow = true }
    }
    // the agent needs you: a nudge; a long task done: a pat
    var lastPending by remember { mutableIntStateOf(pending.size) }
    LaunchedEffect(pending.size) { if (pending.size > lastPending) buzz(Haptic.Warning); lastPending = pending.size }
    var wasRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        val started = store.turnStartedAtMs
        if (wasRunning && !running && started != null && Clock.now() - started >= 8_000 && (stopReason == null || stopReason == "end_turn")) buzz(Haptic.Success)
        wasRunning = running
    }

    // MARK: menu actions
    var menu by remember { mutableStateOf(false) }
    var startingNew by remember { mutableStateOf(false) }
    var showingInCli by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val startFailed = stringResource(R.string.couldnt_start)
    val cliFailed = stringResource(R.string.couldnt_show_on_computer)
    val cliDone = stringResource(R.string.shown_on_computer)
    fun startNewHere() {
        val folder = cwd ?: return
        if (startingNew) return
        startingNew = true
        scope.launch {
            try {
                val id = model.newSession(folder, agent)
                startingNew = false
                buzz(Haptic.Medium)
                // only if the user is still here: a late answer must not pull them out of wherever they went
                if (model.visibleSessionId == sessionId) onOpenChat(id)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                startingNew = false
                buzz(Haptic.Error)
                snackbar.showSnackbar(startFailed + ": " + (e.message ?: e.toString()))
            }
        }
    }
    fun showOnComputer() {
        if (showingInCli) return
        showingInCli = true
        scope.launch {
            try { model.showInCli(store); buzz(Haptic.Success); snackbar.showSnackbar(cliDone) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { buzz(Haptic.Error); snackbar.showSnackbar(cliFailed + ": " + (e.message ?: e.toString())) }
            finally { showingInCli = false }
        }
    }

    CompositionLocalProvider(
        LocalChatVersion provides version,
        LocalOpenFile provides { scope.launch { snackbar.showSnackbar(laterText) } },
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
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
                                    text = { Text(stringResource(R.string.new_conversation_here)) },
                                    enabled = cwd != null && !startingNew && agentReady,
                                    onClick = { menu = false; startNewHere() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browse_files)) },
                                    enabled = cwd != null && state.isConnected,
                                    onClick = { menu = false; cwd?.let { onBrowseFiles(agent, it) } },
                                )
                                if (cwd != null) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_folder_path)) },
                                    onClick = { menu = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", cwd))); buzz(Haptic.Selection) } },
                                )
                                if (state.hasCliList(agent)) {
                                    val listed = store.listedInCli == true
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stringResource(R.string.show_on_computer))
                                                Text(stringResource(if (listed) R.string.show_on_computer_done else R.string.show_on_computer_todo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        enabled = !listed && !showingInCli && state.isConnected,
                                        onClick = { menu = false; showOnComputer() },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                Column(
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RequestStack(model, pending, showsAgent = { true }, onOpenSubagent = onOpenSubagent)
                    if (subagents.isNotEmpty()) SubagentsTray(subagents, collapsed = trayVisible) { child -> onOpenSubagent(sessionId, child) }
                    if (queued.isNotEmpty() || removedQueued != null) QueueTray(
                        items = queued, rows = queueRows(queued, removedQueued), running = running, collapsed = trayVisible, enabled = composerEnabled,
                        edit = { q -> model.unqueue(store, q); focusRequester.requestFocus() },
                        delete = { q -> model.removeQueued(store, q); buzz(Haptic.Selection) },
                        sendNow = ::sendQueuedNow,
                        undo = { model.undoRemoveQueued(store); buzz(Haptic.Selection) },
                    )
                    if (commands.isNotEmpty()) CommandPalette(commands, compact = trayVisible || queued.isNotEmpty()) { c ->
                        val t = "/" + c.name + " "
                        draft = TextFieldValue(t, TextRange(t.length))
                        focusRequester.requestFocus()
                        buzz(Haptic.Selection)
                    }
                    ComposerSlab(
                        value = draft, onValueChange = { draft = it }, placeholder = placeholder, enabled = composerEnabled, running = running, canSend = canSend,
                        error = composerError, commandStatus = pickedCommand, focusRequester = focusRequester, onFocusChanged = { composerFocused = it },
                        onSend = { sendDraft(false) }, onStop = ::stopTurn, onInterruptAndSend = { sendDraft(true) },
                        onDismissError = { store.composerError = null },
                    )
                    if (!trayVisible) ChipRow(model, store, composerEnabled)
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                OtherSessionsBanner(state.pending.values.filter { it.sessionId != sessionId }.sortedBy { it.key }.mapNotNull { it.sessionId }.distinct(), onOpenChat)
                Box(Modifier.weight(1f).fillMaxWidth()) {
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
                            LoadError(loadError, enabled = agentReady) { scope.launch { model.attach(store) } }
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
                        if (sendingSlow && echoId != null) item("sending") {
                            Text(stringResource(R.string.sending), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = LeashTheme.colors.textTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                        if (stopReason != null && stopReason != "end_turn" && !running) item("stop") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.StopCircle, null, Modifier.size(16.dp), tint = LeashTheme.colors.textTertiary)
                                Spacer(Modifier.width(6.dp))
                                Text(stopText(stopReason), style = MaterialTheme.typography.bodySmall, color = LeashTheme.colors.textTertiary)
                            }
                        }
                        if (loadError != null && !empty) item("load-error-inline") { LoadError(loadError, enabled = agentReady) { scope.launch { model.attach(store) } } }
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
                    if (!pinned && listState.canScrollForward) SmallFloatingActionButton(
                        onClick = { pinned = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.scroll_to_bottom)) }
                    if (startingNew) Surface(Modifier.align(Alignment.Center), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActivitySpinner(18.dp); Text(stringResource(R.string.starting))
                        }
                    }
                }
            }
        }
    }
}

/** Other conversations waiting on you, above the transcript; it opens the first (ios otherSessionsBanner). */
@Composable
private fun OtherSessionsBanner(sessionIds: List<String>, open: (String) -> Unit) {
    val first = sessionIds.firstOrNull() ?: return
    val attention = LeashTheme.colors.attention
    Surface(
        onClick = { open(first) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = CircleShape, color = attention.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.PanTool, null, Modifier.size(16.dp), tint = attention)
            Text(
                if (sessionIds.size > 1) pluralText(R.plurals.others_need_you, sessionIds.size) else stringResource(R.string.another_needs_approval),
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = LeashTheme.colors.textTertiary)
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
