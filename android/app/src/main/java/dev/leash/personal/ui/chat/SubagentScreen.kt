package dev.leash.personal.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.text.liveText
import dev.leash.personal.text.res
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.DisplayItem
import dev.leash.personal.core.StepRowModel
import dev.leash.personal.ui.components.LocalChatVersion
import dev.leash.personal.ui.components.topBarSpace
import dev.leash.personal.ui.design.components.DockFade
import dev.leash.personal.ui.design.components.dockInsets
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.LiveDots
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.rememberLivePhase
import dev.leash.personal.ui.design.shimmer
import dev.leash.personal.ui.design.components.BackButton
import dev.leash.personal.ui.design.components.EmptyState
import dev.leash.personal.ui.design.components.TopBar
import dev.leash.personal.ui.files.ProvideFiles

/** A subagent's own transcript, opened from its row: the parent's prompt as a quote, its steps (flat, already open), its reply. */
@Composable
fun SubagentScreen(model: AppModel, rootId: String, childId: String, onBack: () -> Unit, onOpenSubagent: (String) -> Unit) {
    val store = remember(rootId) { model.store(rootId) }
    val state by model.state.collectAsStateWithLifecycle()
    val version by store.version.collectAsStateWithLifecycle()
    val c = Leash.colors
    // live updates keep coming while the chat underneath is hidden
    LaunchedEffect(store) { if (!store.attached) model.attach(store) }
    DisposableEffect(store) {
        model.watch(store)
        onDispose { model.unwatch(store) }
    }
    val item = store.subagent(childId)
    val rows = remember(version) { item?.let { foldSubagent(it) } ?: emptyList() }
    val title = item?.displayTitle ?: stringResource(R.string.subagent_unnamed)
    val subtitle = item?.let { listOfNotNull(it.agentType.takeIf { t -> t != it.title }, it.model).joinToString(" · ") } ?: ""
    val needsAction = item?.needsAction == true
    val liveAction = item?.activity?.liveAction
    // this agent's requests and those of agents it launched (the bridge files them under the root conversation)
    val requests = if (item == null) emptyList() else state.pendingFor(rootId).filter { r -> r.subagent?.sessionId in item.lineage }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    var dockPx by remember { mutableIntStateOf(0) }
    val dockHeight = with(LocalDensity.current) { dockPx.toDp() }

    ProvideFiles(model, store.agent, rootId) {
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            if (item == null) EmptyState(LeashIcons.subagent, stringResource(R.string.agent_not_loaded), Modifier.fillMaxSize().padding(top = 200.dp))
            else CompositionLocalProvider(LocalChatVersion provides version) {
                LazyColumn(
                    Modifier.fillMaxSize(), state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topBarSpace(), bottom = dockHeight + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(rows, key = { it.id }) { d ->
                        when (d) {
                            is DisplayItem.User -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (d.item.text.isNotBlank() || d.item.images.isEmpty()) PromptQuote(d.item.text)
                                d.item.images.forEachIndexed { i, image -> MessageImage(image, i + 1, maxWidth = 220.dp, maxHeight = 220.dp) }
                            }
                            is DisplayItem.Activity -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val g = d.group
                                if (g.live) LiveLine(if (needsAction) stringResource(R.string.waiting_approval) else res().liveText(liveAction ?: g.liveAction), needsAction)
                                val checklist = g.checklist
                                if (checklist.isNotEmpty()) ChecklistView(checklist)
                                val steps = StepRowModel.build(g.steps)
                                if (steps.isNotEmpty()) StepList(steps, store, onOpenSubagent)
                            }
                            is DisplayItem.Answer -> AnswerView(d.item)
                            is DisplayItem.Notice -> NoticeView(d.notice)
                            is DisplayItem.PlanDoc -> PlanCard(d.plan)
                            is DisplayItem.Stats, is DisplayItem.Segment, is DisplayItem.Changes -> {}
                        }
                    }
                }
            }
        }
        TopBar(
            title = title, subtitle = subtitle.ifEmpty { null }, edgeVisible = scrolled,
            navigation = { BackButton(stringResource(R.string.back), onBack) },
        )
        if (requests.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { dockPx = it.height }) {
            DockFade()
            Column(Modifier.dockInsets().padding(start = 12.dp, end = 12.dp, top = 16.dp)) {
                RequestStack(model, requests, showsAgent = { it.subagent?.sessionId != childId }, onOpenSubagent = { _, child -> onOpenSubagent(child) })
            }
        }
    }
    }
}

/** The subagent's current action: live dots and the shimmer band on one clock, or the hand while it waits for you. */
@Composable
private fun LiveLine(text: String, blocked: Boolean) {
    val c = Leash.colors
    val phase = rememberLivePhase(!blocked)
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            if (blocked) Icon(LeashIcons.needsYou, null, Modifier.size(15.dp), tint = c.attention)
            else LiveDots(phase, with(density) { width.toDp() })
        }
        Text(
            text, style = Leash.type.footnote.medium(), color = if (blocked) c.attention else c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.onSizeChanged { width = it.width }.shimmer(!blocked, phase, leadingInset = 26.dp),
        )
    }
}

/** The parent agent's instructions to a subagent: a quote, not a user bubble (the user didn't write it). */
@Composable
fun PromptQuote(text: String) {
    dev.leash.personal.ui.components.ObserveChat()
    val c = Leash.colors
    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }
    val trimmed = text.trim()
    val long = trimmed.length > 240 || trimmed.count { it == '\n' } > 2
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.padding(vertical = 2.dp).width(2.dp).fillMaxHeight().clip(LeashShapes.capsule).background(c.accent.copy(alpha = 0.35f)))
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.prompt_label), style = Leash.type.caption.medium(), color = c.textTertiary)
            Text(trimmed, style = Leash.type.subheadline, color = c.textSecondary, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            if (long) Text(
                stringResource(if (expanded) R.string.show_less else R.string.show_more), Modifier.pressable(scale = PressScale.Text) { expanded = !expanded }.padding(vertical = 4.dp),
                style = Leash.type.caption.medium(), color = c.accent,
            )
        }
    }
}
