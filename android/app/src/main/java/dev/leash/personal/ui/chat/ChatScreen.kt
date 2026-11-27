package dev.leash.personal.ui.chat

import android.content.ClipData
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import dev.leash.personal.ui.files.ProvideFiles
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.Problem
import dev.leash.personal.core.toProblem
import dev.leash.personal.text.errorText
import dev.leash.personal.text.problemText
import dev.leash.personal.text.res
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.leash.personal.core.AgentCommand
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.FileLimits
import dev.leash.personal.core.PendingFile
import dev.leash.personal.core.upload
import dev.leash.personal.core.ChatStore
import dev.leash.personal.core.Clock
import dev.leash.personal.core.DisplayItem
import dev.leash.personal.core.Haptic
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.components.LocalChatVersion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import dev.leash.personal.ui.components.topBarSpace
import dev.leash.personal.ui.design.components.DockFade
import dev.leash.personal.ui.design.components.dockInsets
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LocalReduceMotion
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.softTopEdge
import dev.leash.personal.ui.design.components.BackButton
import dev.leash.personal.ui.design.components.Banner
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.GlassCircleButton
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LeashMenu
import dev.leash.personal.ui.design.components.LeashMenuItem
import dev.leash.personal.ui.design.components.LeashSnackbar
import dev.leash.personal.ui.design.components.LoadingState
import dev.leash.personal.ui.design.components.RaisedCard
import dev.leash.personal.ui.design.components.ScrollToBottomButton
import dev.leash.personal.ui.design.components.TopBar
import dev.leash.personal.ui.newsession.StartingCapsule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * A conversation: transcript, trays for what waits on the user (approval or question, running subagents, queued messages,
 * the command palette), the composer and the selector chips (ios ChatView). It stays attached while on screen, keeps
 * following the newest output until you scroll up, and shows load, locked and stop states.
 */
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
    val context = LocalContext.current

    LaunchedEffect(store) { if (!store.attached) model.attach(store) }
    // quota for the chip and the model picker (the bridge reuses what is younger than two minutes)
    LaunchedEffect(store) { model.refreshQuota() }
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
    val res = res()
    val loadError = store.loadError?.let(res::problemText)
    val lockedBy = store.lockedBy
    val running = store.running
    val stopReason = store.lastStopReason
    val empty = store.entries.isEmpty()
    val attached = store.attached
    val queued = store.queued
    val removedQueued = store.removedQueued
    val subagents = store.runningSubagents
    val composerError = store.composerError?.let(res::problemText)
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

    // an image or widget that finished loading makes its row taller: the following chat keeps the bottom in view
    var growTick by remember { mutableIntStateOf(0) }
    val onGrew = remember<() -> Unit> { { growTick++ } }
    LaunchedEffect(growTick) {
        if (growTick > 0 && pinned && items.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            listState.scrollToItem(items.lastIndex, scrollOffset = 1_000_000)
        }
    }

    // a finished turn's changes, file by file (kept across rotation by the turn's id)
    var changesId by rememberSaveable { mutableStateOf<String?>(null) }
    val changesShown = items.firstNotNullOfOrNull { (it as? DisplayItem.Changes)?.changes?.takeIf { c -> c.id.toString() == changesId } }

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
    // photos and files waiting in the composer (what was sent or removed leaves; a failed upload keeps them with the reason)
    var images by remember(sessionId) { mutableStateOf<List<ByteArray>>(emptyList()) }
    var files by remember(sessionId) { mutableStateOf<List<PendingFile>>(emptyList()) }
    var uploading by remember(sessionId) { mutableStateOf(false) }
    var uploadError by remember(sessionId) { mutableStateOf<Problem?>(null) }
    val asksSideQuestions = state.asksSideQuestions(agent)
    val kind = ComposerLogic.kind(draft.text, asksSideQuestions)
    val hasAttachments = images.isNotEmpty() || files.isNotEmpty()
    val sideQuestions = store.sideQuestions
    val sideQuestionsFull = sideQuestions.size >= model.maxSideQuestions
    val shellRunning = store.shellRunning
    val canSend = ComposerLogic.canSend(composerEnabled, draft.text, kind, hasAttachments, uploading, running, shellRunning, sideQuestionsFull)
    val sideQuestionCommand = AgentCommand("btw", stringResource(R.string.btw_command_desc), stringResource(R.string.btw_command_hint))
    val imageInput = state.agent(agent)?.imageInput ?: state.imageInput
    val modeStatus = when (kind) {
        DraftKind.Shell -> when {
            shellRunning -> ModeStatus(stringResource(R.string.shell_running), LeashIcons.terminal, Leash.colors.shell, busy = true)
            running -> ModeStatus(stringResource(R.string.shell_waits), LeashIcons.waiting, Leash.colors.textSecondary)
            else -> ModeStatus(stringResource(R.string.shell_in, cwd?.let { state.shortPath(it, agent) } ?: "~"), LeashIcons.terminal, Leash.colors.shell)
        }
        DraftKind.SideQuestion ->
            if (sideQuestionsFull) ModeStatus(stringResource(R.string.btw_full), LeashIcons.waiting, Leash.colors.textSecondary)
            else ModeStatus(stringResource(R.string.btw_status), LeashIcons.sideQuestion, Leash.colors.accent)
        DraftKind.Message -> null
    }
    val commands = ComposerLogic.paletteMatches(draft.text, composerFocused, running, store.commands, sideQuestionCommand.takeIf { asksSideQuestions })
    val pickedCommand = if (kind == DraftKind.SideQuestion) null else ComposerLogic.pickedCommand(draft.text, store.commands)
    val placeholder = when (ComposerLogic.placeholder(agentReady, state.isConnected, running, state.steers(agent), agent)) {
        PlaceholderKind.AgentDown -> stringResource(R.string.placeholder_agent_down, state.agentLabel(agent))
        PlaceholderKind.Offline -> stringResource(R.string.placeholder_offline)
        PlaceholderKind.Steer -> stringResource(R.string.placeholder_steer)
        PlaceholderKind.Queue -> stringResource(R.string.placeholder_queue)
        PlaceholderKind.AskClaude -> stringResource(R.string.placeholder_claude)
        PlaceholderKind.AskAgent -> stringResource(R.string.placeholder_agent)
    }
    val buzz = { kind: Haptic -> model.hooks.haptic(kind) }
    // a photo or file picked: the first that fit are added; what doesn't is named in the composer's error line
    val appContext = LocalContext.current
    val tooLargeText = { names: List<String> -> appContext.getString(R.string.files_too_large, FileLimits.MAX_UPLOAD_TEXT, names.joinToString(", ")) }
    val unreadableText = { names: List<String> -> appContext.getString(R.string.files_unreadable, names.joinToString(", ")) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentPolicy.MAX_PHOTOS)) { uris ->
        scope.launch {
            val added = mutableListOf<ByteArray>()
            var failed = 0
            for (u in uris.take(AttachmentPolicy.room(images.size, uris.size))) AttachmentLoading.photo(appContext, u)?.let { added += it } ?: run { failed++ }
            if (added.isNotEmpty()) { images = images + added; buzz(Haptic.Selection) }
            if (failed > 0) { store.composerError = Problem(appContext.getString(R.string.photo_unreadable)); buzz(Haptic.Error) }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val r = AttachmentLoading.files(appContext, uris)
            if (r.files.isNotEmpty()) { files = files + r.files; buzz(Haptic.Selection) }
            if (r.tooLarge.isNotEmpty()) { store.composerError = Problem(tooLargeText(r.tooLarge)); buzz(Haptic.Error) }
            else if (r.unreadable.isNotEmpty()) { store.composerError = Problem(unreadableText(r.unreadable)); buzz(Haptic.Error) }
        }
    }
    // photos that went back after a refused send (locked elsewhere, ...) return to the front of the strip
    val unsentImages = store.unsentImages
    LaunchedEffect(unsentImages) {
        if (!unsentImages.isNullOrEmpty()) { images = unsentImages + images; store.unsentImages = null }
    }
    fun clearComposerError() { store.composerError = null; uploadError = null }
    // Text, photos and files go out as one message; picked files are stored on the agent's host first and the prompt points at
    // them. The composer keeps them (and says why) if an upload fails. The message rises into the transcript and the scroll follows it.
    fun sendDraft(interrupt: Boolean) {
        if (!canSend || kind != DraftKind.Message) return
        clearComposerError()
        val text = draft.text.trim()
        val imgs = images
        val picked = files
        if (picked.isEmpty()) {
            model.send(store, text, imgs, interrupt = interrupt && running)
            draft = TextFieldValue("")
            images = emptyList()
            pinned = true
            buzz(if (interrupt) Haptic.Heavy else Haptic.Light)
            return
        }
        uploading = true
        scope.launch {
            try {
                val links = picked.map { model.upload(it, store) }
                model.send(store, text, imgs, links, interrupt = interrupt && store.running)
                if (draft.text.trim() == text) draft = TextFieldValue("")
                if (images == imgs) images = emptyList()
                files = files.filter { f -> picked.none { it.id == f.id } }
                pinned = true
                buzz(if (interrupt) Haptic.Heavy else Haptic.Light)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                store.composerError = e.toProblem()
                uploadError = store.composerError
                buzz(Haptic.Error)
            } finally { uploading = false }
        }
    }
    /** The primary circle: a message, a shell command, or a side question. */
    fun send() {
        if (!canSend) return
        when (kind) {
            DraftKind.SideQuestion -> {
                // answered beside the conversation: no steering, no queue, no scroll
                clearComposerError()
                model.askSideQuestion(store, ComposerLogic.sideQuestion(draft.text))
                draft = TextFieldValue("")
                buzz(Haptic.Light)
            }
            DraftKind.Shell -> {
                clearComposerError()
                model.runShell(store, ComposerLogic.shellCommand(draft.text))
                draft = TextFieldValue("")
                pinned = true
                buzz(Haptic.Light)
            }
            DraftKind.Message -> sendDraft(false)
        }
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
                snackbar.showSnackbar(startFailed + ": " + res.errorText(e))
            }
        }
    }
    fun showOnComputer() {
        if (showingInCli) return
        showingInCli = true
        scope.launch {
            try { model.showInCli(store); buzz(Haptic.Success); snackbar.showSnackbar(cliDone) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { buzz(Haptic.Error); snackbar.showSnackbar(cliFailed + ": " + res.errorText(e)) }
            finally { showingInCli = false }
        }
    }

    // the dock (trays, composer, chips) floats over the transcript; the list leaves room for it and follows its height
    var dockPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val dockHeight = with(density) { dockPx.toDp() }
    LaunchedEffect(dockPx) { if (pinned && items.isNotEmpty()) listState.scrollToItem(items.lastIndex, scrollOffset = 1_000_000) }
    val c = Leash.colors
    val reduced = LocalReduceMotion.current
    val others = state.pending.values.filter { it.sessionId != sessionId }.sortedBy { it.key }.mapNotNull { it.sessionId }.distinct()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    CompositionLocalProvider(LocalChatVersion provides version, dev.leash.personal.ui.components.LocalContentGrew provides onGrew, LocalOpenChanges provides { changesId = it.id.toString() }) {
      ProvideFiles(model, agent, sessionId) {
        GlassScope(Modifier.fillMaxSize()) {
            GlassSource(Modifier.fillMaxSize()) {
                SpaceBackground()
                LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topBarSpace() + if (others.isNotEmpty()) 52.dp else 0.dp, bottom = dockHeight + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading) item("loading") {
                        LoadingState(stringResource(R.string.loading_conversation), Modifier.fillMaxWidth().padding(top = 96.dp), delayMs = 0)
                    } else if (loadError != null && empty) item("load-error") {
                        LoadError(loadError, enabled = agentReady) { scope.launch { model.attach(store) } }
                    } else if (attached && empty && !running) item("empty") {
                        Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AgentIcon(agent, 32.dp)
                            Text(stringResource(if (agent == "claude") R.string.ask_claude else R.string.ask_agent), style = Leash.type.title3, color = c.textPrimary)
                            cwd?.let { Text(state.shortPath(it, agent), style = Leash.type.mono, color = c.textSecondary) }
                        }
                    }
                    itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                        DisplayRow(item, idx == 0, store, blocked) { child -> onOpenSubagent(sessionId, child) }
                    }
                    if (sendingSlow && echoId != null) item("sending") {
                        Text(stringResource(R.string.sending), Modifier.fillMaxWidth(), style = Leash.type.caption, color = c.textTertiary, textAlign = TextAlign.End)
                    }
                    if (stopReason != null && stopReason != "end_turn" && !running) item("stop") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(LeashIcons.stopped, null, Modifier.size(16.dp), tint = c.textTertiary)
                            Text(stopText(stopReason), style = Leash.type.footnote, color = c.textSecondary)
                        }
                    }
                    if (loadError != null && !empty) item("load-error-inline") { LoadError(loadError, enabled = agentReady) { scope.launch { model.attach(store) } } }
                    if (lockedBy != null) item("locked") {
                        RaisedCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(LeashIcons.locked, null, Modifier.size(18.dp), tint = c.textSecondary)
                                Text(lockText(lockedBy), style = Leash.type.subheadline, color = c.textPrimary)
                            }
                            LeashButton(stringResource(R.string.retry), { scope.launch { model.attach(store) } }, style = ButtonStyle.Plain)
                        }
                    }
                }
            }

            // top: the floating bar, and under it the other conversations that wait on you; with the banner the soft
            // edge reaches below it, so the transcript fades out before it passes under the capsule
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            if (others.isNotEmpty()) Box(Modifier.matchParentSize().softTopEdge())
            Column(Modifier.fillMaxWidth()) {
                TopBar(
                    title = title,
                    subtitle = subtitle.ifEmpty { null },
                    edgeVisible = (scrolled || items.isNotEmpty()) && others.isEmpty(),
                    navigation = { BackButton(stringResource(R.string.back), onBack) },
                    actions = {
                        Box {
                            GlassCircleButton(LeashIcons.more, stringResource(R.string.more_options), { menu = true }, tint = c.accentControl)
                            LeashMenu(menu, { menu = false }) {
                                LeashMenuItem(
                                    stringResource(R.string.new_conversation_here), { menu = false; startNewHere() }, icon = LeashIcons.squareAndPencil,
                                    enabled = cwd != null && !startingNew && agentReady,
                                )
                                LeashMenuItem(
                                    stringResource(R.string.browse_files), { menu = false; cwd?.let { onBrowseFiles(agent, it) } }, icon = LeashIcons.folder,
                                    enabled = cwd != null && state.isConnected,
                                )
                                if (cwd != null) LeashMenuItem(
                                    stringResource(R.string.copy_folder_path),
                                    { menu = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", cwd))); buzz(Haptic.Selection) } },
                                    icon = LeashIcons.copy,
                                )
                                if (state.hasCliList(agent)) {
                                    val listed = store.listedInCli == true
                                    LeashMenuItem(
                                        stringResource(R.string.show_on_computer), { menu = false; showOnComputer() }, icon = LeashIcons.computer,
                                        enabled = !listed && !showingInCli && state.isConnected,
                                        supporting = stringResource(if (listed) R.string.show_on_computer_done else R.string.show_on_computer_todo),
                                    )
                                }
                            }
                        }
                    },
                )
                AnimatedVisibility(others.isNotEmpty(), enter = Motion.bannerEnter(reduced), exit = Motion.bannerExit(reduced)) {
                    Banner(
                        if (others.size > 1) pluralStringResource(R.plurals.others_need_you, others.size, others.size) else stringResource(R.string.another_needs_approval),
                        { others.firstOrNull()?.let(onOpenChat) },
                        Modifier.padding(horizontal = 16.dp).offset(y = (-20).dp),
                    )
                }
            }
            }

            // bottom: the dock rides the keyboard; content keeps scrolling under it, faded
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { dockPx = it.height }) {
                DockFade()
                Column(
                    Modifier.fillMaxWidth().dockInsets().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RequestStack(model, pending, showsAgent = { true }, onOpenSubagent = onOpenSubagent)
                        if (subagents.isNotEmpty()) SubagentsTray(subagents, collapsed = trayVisible) { child -> onOpenSubagent(sessionId, child) }
                        if (queued.isNotEmpty() || removedQueued != null) QueueTray(
                            items = queued, rows = queueRows(queued, removedQueued), running = running, collapsed = trayVisible, enabled = composerEnabled,
                            edit = { q -> model.unqueue(store, q); focusRequester.requestFocus() },
                            delete = { q -> model.removeQueued(store, q); buzz(Haptic.Selection) },
                            sendNow = ::sendQueuedNow,
                            undo = { model.undoRemoveQueued(store); buzz(Haptic.Selection) },
                        )
                        if (commands.isNotEmpty()) CommandPalette(commands, compact = trayVisible || queued.isNotEmpty() || sideQuestions.isNotEmpty()) { cmd ->
                            val t = "/" + cmd.name + " "
                            draft = TextFieldValue(t, TextRange(t.length))
                            focusRequester.requestFocus()
                            buzz(Haptic.Selection)
                        }
                        sideQuestions.firstOrNull()?.let { sq ->
                            SideQuestionTray(sq, 1, sideQuestions.size, collapsed = trayVisible, close = { model.closeSideQuestion(store, sq.id); buzz(Haptic.Selection) })
                        }
                        ComposerSlab(
                            value = draft, onValueChange = { draft = it }, placeholder = placeholder, enabled = composerEnabled, running = running, canSend = canSend,
                            kind = kind, hasAttachments = hasAttachments,
                            error = composerError, commandStatus = pickedCommand, modeStatus = modeStatus,
                            attachments = if (hasAttachments && kind == DraftKind.Message) ({
                                ComposerAttachments(
                                    images, files, uploading,
                                    onRemoveImage = { i -> images = images.filterIndexed { j, _ -> j != i } },
                                    onRemoveFile = { f ->
                                        files = files.filter { it.id != f.id }
                                        if (uploadError != null && store.composerError == uploadError) store.composerError = null
                                        uploadError = null
                                    },
                                )
                            }) else null,
                            add = ComposerAddActions(
                                onPhoto = if (imageInput && images.size < AttachmentPolicy.MAX_PHOTOS) ({ photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) else null,
                                onFile = { filePicker.launch(arrayOf("*/*")) },
                                onSideQuestion = if (asksSideQuestions) ({ val t = "/btw " + draft.text; draft = TextFieldValue(t, TextRange(t.length)); focusRequester.requestFocus() }) else null,
                                onShell = { val t = "!" + draft.text; draft = TextFieldValue(t, TextRange(t.length)); focusRequester.requestFocus() },
                            ),
                            focusRequester = focusRequester, onFocusChanged = { composerFocused = it },
                            onSend = ::send, onStop = ::stopTurn, onInterruptAndSend = { sendDraft(true) },
                            onDismissError = { clearComposerError() },
                            onLeaveMode = { val t = ComposerLogic.leaveMode(draft.text, kind); draft = TextFieldValue(t, TextRange(t.length)); buzz(Haptic.Selection) },
                        )
                    }
                    AnimatedVisibility(!trayVisible, enter = Motion.layoutEnter(reduced), exit = Motion.layoutExit(reduced)) { ChipRow(model, store, composerEnabled) }
                }
            }

            AnimatedVisibility(
                !pinned && listState.canScrollForward,
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = dockHeight + 8.dp),
                enter = Motion.popEnter(reduced), exit = Motion.popExit(reduced),
            ) { ScrollToBottomButton(stringResource(R.string.scroll_to_bottom), { pinned = true }) }
            if (startingNew) StartingCapsule(Modifier.align(Alignment.Center))
            changesShown?.let { ch ->
                val commitText = stringResource(R.string.commit_message, ch.files.joinToString(", ") { it.relative(cwd) })
                ChangesSheet(
                    ch, cwd, onDismiss = { changesId = null },
                    onCommit = if (ch.canCommit) ({ changesId = null; store.composerError = null; model.send(store, commitText); pinned = true; buzz(Haptic.Light) }) else null,
                    commitEnabled = composerEnabled && !running && pending.isEmpty(),
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = dockHeight + 8.dp)) { data -> LeashSnackbar(data.visuals.message) }
        }
      }
    }
}

/** Couldn't load the transcript: a raised card with the reason and Try again. */
@Composable
private fun LoadError(message: String, enabled: Boolean, onRetry: () -> Unit) {
    val c = Leash.colors
    RaisedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(LeashIcons.failed, null, Modifier.size(18.dp), tint = c.danger)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.couldnt_load_conversation), style = Leash.type.subheadline.semibold(), color = c.textPrimary)
                Text(message, style = Leash.type.footnote, color = c.textSecondary)
            }
        }
        LeashButton(stringResource(R.string.try_again), onRetry, style = ButtonStyle.Tinted, size = ButtonSize.Small, enabled = enabled)
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
