package dev.leash.personal.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.DisplayItem
import dev.leash.personal.core.StepRowModel
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.components.LocalChatVersion
import dev.leash.personal.ui.components.pulse
import dev.leash.personal.ui.theme.LeashTheme

/** A subagent's own transcript, opened from its row: the parent's prompt as a quote, its steps (flat, already open), its reply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentScreen(model: AppModel, rootId: String, childId: String, onBack: () -> Unit, onOpenSubagent: (String) -> Unit) {
    val store = remember(rootId) { model.store(rootId) }
    val state by model.state.collectAsStateWithLifecycle()
    val version by store.version.collectAsStateWithLifecycle()
    // live updates keep coming while the chat underneath is hidden
    LaunchedEffect(store) { if (!store.attached) model.attach(store) }
    DisposableEffect(store) {
        model.watch(store)
        onDispose { model.unwatch(store) }
    }
    val item = store.subagent(childId)
    val rows = remember(version) { item?.let { foldSubagent(it) } ?: emptyList() }
    val title = item?.displayTitle ?: stringResource(R.string.subagent)
    val subtitle = item?.let { listOfNotNull(it.agentType.takeIf { t -> t != it.title }, it.model).joinToString(" · ") } ?: ""
    val live = item?.isRunning == true
    val needsAction = item?.needsAction == true
    val liveAction = item?.activity?.liveAction
    // this agent's requests and those of agents it launched (the bridge files them under the root conversation)
    val requests = if (item == null) emptyList() else state.pendingFor(rootId).filter { r -> r.subagent?.sessionId in item.lineage }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (requests.isNotEmpty()) Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 8.dp)) {
                RequestStack(model, requests, showsAgent = { it.subagent?.sessionId != childId }, onOpenSubagent = { _, child -> onOpenSubagent(child) })
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (item == null) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CallSplit, null, tint = MaterialTheme.colorScheme.outline)
                Text(stringResource(R.string.agent_not_loaded), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
            } else CompositionLocalProvider(LocalChatVersion provides version, LocalOpenFile provides {}) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows, key = { it.id }) { d ->
                        when (d) {
                            is DisplayItem.User -> PromptQuote(d.item.text)
                            is DisplayItem.Activity -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val g = d.group
                                if (g.live) Row(verticalAlignment = Alignment.CenterVertically) {
                                    ActivitySpinner(11.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (needsAction) stringResource(R.string.waiting_approval) else (liveAction ?: g.liveAction),
                                        Modifier.pulse(!needsAction), style = MaterialTheme.typography.bodySmall,
                                        color = if (needsAction) LeashTheme.colors.attention else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                                    )
                                }
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
    }
}

/** The parent agent's instructions to a subagent: a quote, not a user bubble (the user didn't write it). */
@Composable
fun PromptQuote(text: String) {
    dev.leash.personal.ui.components.ObserveChat()
    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }
    val trimmed = text.trim()
    val long = trimmed.length > 240 || trimmed.count { it == '\n' } > 2
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(LeashTheme.colors.hairline, MaterialTheme.shapes.extraSmall))
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.prompt_label), style = MaterialTheme.typography.labelMedium, color = LeashTheme.colors.textTertiary)
            Text(trimmed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            if (long) Text(
                stringResource(if (expanded) R.string.show_less else R.string.show_more), Modifier.clickable { expanded = !expanded },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

