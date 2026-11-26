package dev.leash.personal.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.ActivityGroup
import dev.leash.personal.core.ChatStore
import dev.leash.personal.core.ChecklistStep
import dev.leash.personal.core.Clock
import dev.leash.personal.core.DiffLine
import dev.leash.personal.core.LineDiff
import dev.leash.personal.core.StepRowModel
import dev.leash.personal.core.StepSegment
import dev.leash.personal.core.SubagentItem
import dev.leash.personal.core.ToolItem
import dev.leash.personal.text.durationText
import dev.leash.personal.text.liveText
import dev.leash.personal.text.res
import dev.leash.personal.text.subagentLineText
import dev.leash.personal.text.summaryText
import dev.leash.personal.text.toolTitle
import dev.leash.personal.core.pretty
import dev.leash.personal.ui.components.ObserveChat
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.components.ActivityHeader
import dev.leash.personal.ui.design.components.ActivityState
import dev.leash.personal.ui.design.components.DiffLineModel
import dev.leash.personal.ui.design.components.StepKind
import dev.leash.personal.ui.design.components.StepRail
import dev.leash.personal.ui.design.components.DiffBlock as DiffBlockView
import dev.leash.personal.ui.design.components.StepRow as StepRowView
import dev.leash.personal.ui.markdown.rememberInline

private fun ChatStore.toggleGroup(id: java.util.UUID) { expandedGroups = if (id in expandedGroups) expandedGroups - id else expandedGroups + id }
private fun ChatStore.toggleStep(id: java.util.UUID) { expandedSteps = if (id in expandedSteps) expandedSteps - id else expandedSteps + id }

/** One collapsible row per agent turn: "Worked for 2m 14s" with "Explored 12 files, ran 6 commands" under it (ios ActivityRow). */
@Composable
fun ActivityRow(group: ActivityGroup, store: ChatStore, blocked: Boolean, onOpenSubagent: (String) -> Unit) {
    ObserveChat()
    val expanded = group.id in store.expandedGroups
    var showChecklist by rememberSaveable(group.id.toString()) { mutableStateOf(false) }
    val checklist = group.checklist
    val doneSteps = checklist.count { it.status == "completed" }
    // a live turn not opened in full lays its messages and runs out below the header: the header keeps a sub-line only for an approval wait or before any step
    val showsSubLine = !group.live || expanded || blocked || (group.steps.isEmpty() && !group.answerFollows)
    val startedAt = store.turnStartedAtMs
    val res = res()
    val header = if (group.live) {
        if (startedAt != null) {
            val now by produceState(Clock.now(), startedAt) { while (true) { value = Clock.now(); kotlinx.coroutines.delay(1000) } }
            stringResource(R.string.working_for, res.durationText((now - startedAt) / 1000.0))
        } else stringResource(R.string.working)
    } else {
        val ms = group.stats?.durationMs
        if (ms != null) {
            if (group.failedCount > 0) stringResource(R.string.worked_failed, res.durationText(ms / 1000.0), group.failedCount) else stringResource(R.string.worked_for, res.durationText(ms / 1000.0))
        } else {
            val n = group.visibleTools.size
            pluralStringResource(R.plurals.steps_count, n, n)
        }
    }
    val state = when {
        blocked -> ActivityState.Blocked
        group.live -> ActivityState.Live
        group.failedCount > 0 -> ActivityState.Failed
        group.stats?.stopReason == "cancelled" -> ActivityState.Stopped
        else -> ActivityState.Done
    }
    val subLine = if (!showsSubLine) null
        else if (blocked) stringResource(R.string.waiting_approval)
        else if (group.live) res.liveText(group.liveAction)
        else if (group.summary.isEmpty) null else res.summaryText(group.summary) + " ›"
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ActivityHeader(
            header, state,
            subLine = subLine,
            expanded = expanded,
            checklist = if (checklist.isNotEmpty()) doneSteps to checklist.size else null,
            checklistLabel = if (checklist.isNotEmpty()) stringResource(R.string.steps_progress, doneSteps, checklist.size) else null,
            onClick = { store.toggleGroup(group.id) },
            onChecklistClick = { showChecklist = !showChecklist },
        )
        if (showChecklist && checklist.isNotEmpty()) ChecklistView(checklist, Modifier.padding(start = 26.dp))
        if (expanded) StepList(StepRowModel.build(group.steps), store, onOpenSubagent, Modifier.padding(start = 8.dp))
    }
}

/** One run of tools in a live turn: "Explored 3 files, ran 2 commands", or the current action while it is the last run. */
@Composable
fun SegmentRow(segment: StepSegment, store: ChatStore, blocked: Boolean, onOpenSubagent: (String) -> Unit) {
    ObserveChat()
    val c = Leash.colors
    val open = segment.id in store.expandedGroups
    val rows = StepRowModel.build(segment.steps)
    val active = segment.liveLine != null
    val rot by animateFloatAsState(if (open) 180f else 0f, Motion.disclose(), label = "chevron")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().pressable(scale = PressScale.Text) { store.toggleGroup(segment.id) }.padding(start = 26.dp).heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                res().let { r -> segment.liveLine?.let(r::liveText) ?: r.summaryText(segment.summary) }, style = Leash.type.footnote,
                color = if (blocked) c.attention else if (active) c.textSecondary else c.textTertiary,
                // the live action is cut in the middle (a command or path); the summary of the run so far wraps
                maxLines = if (active) 1 else 2, overflow = if (active) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (rows.isNotEmpty()) Icon(LeashIcons.chevronDown, null, Modifier.size(14.dp).rotate(rot), tint = c.textTertiary)
        }
        if (open) StepList(rows, store, onOpenSubagent, Modifier.padding(start = 8.dp))
    }
}

/** Rows of an expanded run on the step rail; a long list shows its first three and last ten steps. */
@Composable
fun StepList(rows: List<StepRowModel>, store: ChatStore, onOpenSubagent: (String) -> Unit, modifier: Modifier = Modifier) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val collapsed = rows.size > 30 && !showAll
    StepRail(modifier) {
        val head = if (collapsed) rows.take(3) else rows
        head.forEach { StepRow(it, store, onOpenSubagent) }
        if (collapsed) {
            Text(
                pluralStringResource(R.plurals.show_earlier_steps, rows.size - 13, rows.size - 13),
                Modifier.pressable(scale = PressScale.Text) { showAll = true }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                style = Leash.type.footnote.medium(), color = Leash.colors.accent,
            )
            rows.takeLast(10).forEach { StepRow(it, store, onOpenSubagent) }
        }
    }
}

@Composable
fun StepRow(row: StepRowModel, store: ChatStore, onOpenSubagent: (String) -> Unit) {
    ObserveChat()
    val kind = row.kind
    if (kind is StepRowModel.Kind.Subagent) { SubagentStepRow(kind.item, onOpenSubagent); return }
    val c = Leash.colors
    val open = row.id in store.expandedSteps
    val openFile = LocalOpenFile.current
    val textRow = kind is StepRowModel.Kind.Thought || kind is StepRowModel.Kind.Note
    val icon: ImageVector = when (kind) {
        is StepRowModel.Kind.Thought -> LeashIcons.think
        is StepRowModel.Kind.Note -> LeashIcons.note
        is StepRowModel.Kind.Reads -> LeashIcons.read
        is StepRowModel.Kind.Tool -> LeashIcons.forTool(kind.item.kind)
        is StepRowModel.Kind.Subagent -> LeashIcons.subagent
    }
    var title = ""
    var stepKind = StepKind.Other
    var annotated: androidx.compose.ui.text.AnnotatedString? = null
    when (kind) {
        is StepRowModel.Kind.Thought -> { stepKind = StepKind.Thought; annotated = rememberInline(kind.item.text.trim(), openFile) }
        is StepRowModel.Kind.Note -> { stepKind = StepKind.Note; annotated = rememberInline(kind.item.text.trim(), openFile) }
        is StepRowModel.Kind.Reads -> title = pluralStringResource(R.plurals.read_files, kind.items.size, kind.items.size)
        is StepRowModel.Kind.Tool -> {
            val t = kind.item
            val cmd = t.commandPreview
            val p = if (t.kind in listOf("edit", "delete", "move")) (t.firstDiff?.path ?: t.locations.firstOrNull()) else null
            when {
                t.kind == "execute" && cmd != null -> { title = cmd; stepKind = StepKind.Command }
                p != null -> { title = p.substringAfterLast('/'); stepKind = StepKind.Edit }
                else -> title = res().toolTitle(t.title, t.asked)
            }
        }
        is StepRowModel.Kind.Subagent -> Unit
    }
    val tool = (kind as? StepRowModel.Kind.Tool)?.item
    StepRowView(
        icon, title, stepKind,
        open = open,
        adds = tool?.takeIf { it.firstDiff != null }?.diffAdds,
        dels = tool?.takeIf { it.firstDiff != null }?.diffDels,
        autoApproved = tool?.autoApproved == true,
        failed = tool?.status == "failed",
        running = tool?.status == "in_progress",
        onClick = { store.toggleStep(row.id) },
        annotatedTitle = annotated,
        detail = if (textRow) null else ({
            when (kind) {
                is StepRowModel.Kind.Reads -> kind.items.forEach { t ->
                    val p = t.locations.firstOrNull() ?: t.commandPreview ?: t.title
                    Text(p, Modifier.pressable(scale = PressScale.Text) { openFile(p) }, style = Leash.type.monoSmall, color = c.accent, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
                is StepRowModel.Kind.Tool -> ToolDetail(kind.item)
                else -> Unit
            }
        }),
    )
}

@Composable
private fun ToolDetail(t: ToolItem) {
    ObserveChat()
    val c = Leash.colors
    val openFile = LocalOpenFile.current
    val cmd = t.commandPreview
    if (t.kind != "execute" && cmd != null) Text(cmd, style = Leash.type.monoSmall, color = c.textSecondary)
    t.firstDiff?.let { d -> DiffBlock(d.path, d.old, d.new) }
    t.content.forEach { ct -> if (ct is dev.leash.personal.core.ToolContent.Text) CodeText(ct.text, 12) }
    if (t.content.isEmpty()) t.rawOutput.pretty().takeIf { it.isNotEmpty() }?.let { CodeText(it, 12) }
    t.locations.take(3).forEach { loc ->
        Text(loc, Modifier.pressable(scale = PressScale.Text) { openFile(loc) }, style = Leash.type.monoSmall, color = c.accent, maxLines = 1, overflow = TextOverflow.StartEllipsis)
    }
}

/**
 * A line diff of one edit: changed lines with their context, long ones clipped to [maxLines] ("Show all"). With [wholeFile]
 * (the changes sheet) a toggle swaps the changes for the whole file, up to 5000 lines (ios DiffView).
 */
@Composable
fun DiffBlock(path: String, old: String?, new: String, maxLines: Int = 12, wholeFile: Boolean = false) {
    val openFile = LocalOpenFile.current
    val full = remember(old, new) { LineDiff.compute(old, new) }
    val hunks = remember(full) { LineDiff.hunks(full) }
    var all by rememberSaveable(path, new.length) { mutableStateOf(false) }
    var whole by rememberSaveable(path, new.length, "whole") { mutableStateOf(false) }
    val lines = if (wholeFile && whole) full.take(5_000) else hunks
    val shown = if (all || (wholeFile && whole)) lines else lines.take(maxLines)
    val (adds, dels) = remember(full) { LineDiff.stats(full) }
    val models = shown.map { l ->
        when (l) {
            is DiffLine.Add -> DiffLineModel.Add(l.text)
            is DiffLine.Del -> DiffLineModel.Del(l.text)
            is DiffLine.Same -> DiffLineModel.Same(l.text)
            is DiffLine.Gap -> DiffLineModel.Gap("⋯ " + pluralStringResource(R.plurals.unchanged_lines, l.skipped, l.skipped))
        }
    }
    DiffBlockView(
        path.ifEmpty { "—" }, adds, dels, models,
        toggleLabel = when {
            wholeFile && (whole || hunks.size != full.size) -> stringResource(if (whole) R.string.show_changes_only else R.string.show_whole_file)
            lines.size > maxLines && !all -> stringResource(R.string.show_all_diff, lines.size)
            else -> null
        },
        onToggle = { if (wholeFile && (whole || hunks.size != full.size)) whole = !whole else all = true },
        onPath = { if (path.isNotEmpty()) openFile(path) },
    )
}

@Composable
fun ChecklistView(steps: List<ChecklistStep>, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when (s.status) { "completed" -> LeashIcons.doneFilled; "in_progress" -> LeashIcons.radioOn; else -> LeashIcons.radioOff },
                    null, Modifier.padding(top = 1.dp).size(15.dp), tint = when (s.status) { "in_progress" -> c.accent; "completed" -> c.success; else -> c.textTertiary },
                )
                Text(
                    s.title, style = Leash.type.footnote, color = if (s.status == "completed") c.textTertiary else c.textPrimary,
                    textDecoration = if (s.status == "completed") TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

/** A subagent in a step list: its title, then "type · model · current action"; it opens the subagent's own transcript. */
@Composable
fun SubagentStepRow(item: SubagentItem, onOpen: (String) -> Unit) {
    ObserveChat()
    val c = Leash.colors
    Row(
        Modifier.fillMaxWidth().pressable(scale = PressScale.Row) { onOpen(item.sessionId) }.padding(start = 6.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(LeashIcons.subagent, null, Modifier.size(16.dp), tint = c.textTertiary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.displayTitle ?: stringResource(R.string.subagent_unnamed), style = Leash.type.footnote, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(res().subagentLineText(item.secondaryLine), style = Leash.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        when {
            item.needsAction -> Icon(LeashIcons.needsYou, stringResource(R.string.needs_approval), Modifier.size(15.dp), tint = c.attention)
            item.isRunning -> Spinner(size = 11.dp)
            item.stopReason == "cancelled" -> Icon(LeashIcons.stopped, stringResource(R.string.stopped), Modifier.size(15.dp), tint = c.textTertiary)
            item.stopReason != null && item.stopReason != "end_turn" -> Icon(LeashIcons.close, stringResource(R.string.failed), Modifier.size(13.dp), tint = c.danger)
        }
        Icon(LeashIcons.chevronRight, null, Modifier.size(16.dp), tint = c.textTertiary)
    }
}

/** The tool glyph by kind (kept for callers outside the step list). */
fun toolIcon(kind: String): ImageVector = LeashIcons.forTool(kind)
