package dev.leash.personal.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.leash.personal.core.durationText
import dev.leash.personal.core.pretty
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.components.ObserveChat
import dev.leash.personal.ui.components.pulse
import dev.leash.personal.ui.markdown.rememberInline
import dev.leash.personal.ui.theme.LeashTheme

private val glyphColumn = 18.dp

private fun ChatStore.toggleGroup(id: java.util.UUID) { expandedGroups = if (id in expandedGroups) expandedGroups - id else expandedGroups + id }
private fun ChatStore.toggleStep(id: java.util.UUID) { expandedSteps = if (id in expandedSteps) expandedSteps - id else expandedSteps + id }

/** One collapsible row per agent turn: "Worked for 2m 14s" with "Explored 12 files, ran 6 commands" under it (ios ActivityRow). */
@Composable
fun ActivityRow(group: ActivityGroup, store: ChatStore, blocked: Boolean, onOpenSubagent: (String) -> Unit) {
    ObserveChat()
    val colors = LeashTheme.colors
    val scheme = MaterialTheme.colorScheme
    val expanded = group.id in store.expandedGroups
    var showChecklist by rememberSaveable(group.id.toString()) { mutableStateOf(false) }
    val checklist = group.checklist
    val doneSteps = checklist.count { it.status == "completed" }
    // a live turn not opened in full lays its messages and runs out below the header: the header keeps a sub-line only for an approval wait or before any step
    val showsSubLine = !group.live || expanded || blocked || (group.steps.isEmpty() && !group.answerFollows)
    val startedAt = store.turnStartedAtMs
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Row(Modifier.weight(1f).clickable { store.toggleGroup(group.id) }.heightIn(min = 36.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(glyphColumn).padding(top = 3.dp), contentAlignment = Alignment.Center) {
                    when {
                        blocked -> Icon(Icons.Filled.PanTool, null, Modifier.size(15.dp).pulse(true), tint = colors.attention)
                        group.live -> ActivitySpinner(14.dp)
                        group.failedCount > 0 -> Icon(Icons.Filled.ErrorOutline, stringResource(R.string.failed), Modifier.size(16.dp), tint = colors.attention)
                        group.stats?.stopReason == "cancelled" -> Icon(Icons.Filled.StopCircle, stringResource(R.string.stopped), Modifier.size(16.dp), tint = colors.textTertiary)
                        else -> Icon(Icons.Filled.CheckCircleOutline, null, Modifier.size(16.dp), tint = colors.textTertiary)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val header = if (group.live) {
                            if (startedAt != null) {
                                val now by produceState(Clock.now(), startedAt) { while (true) { value = Clock.now(); kotlinx.coroutines.delay(1000) } }
                                stringResource(R.string.working_for, durationText((now - startedAt) / 1000.0))
                            } else stringResource(R.string.working)
                        } else {
                            val ms = group.stats?.durationMs
                            if (ms != null) {
                                if (group.failedCount > 0) stringResource(R.string.worked_failed, durationText(ms / 1000.0), group.failedCount) else stringResource(R.string.worked_for, durationText(ms / 1000.0))
                            } else {
                                val n = group.visibleTools.size
                                if (n == 1) stringResource(R.string.steps_one) else stringResource(R.string.steps_n, n)
                            }
                        }
                        Text(header, Modifier.pulse(group.live && !blocked), style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp).rotate(if (expanded) 180f else 0f), tint = colors.textTertiary)
                    }
                    if (showsSubLine) {
                        val line = if (blocked) stringResource(R.string.waiting_approval) else if (group.live) group.liveAction else (if (group.summary.isEmpty()) " " else group.summary + "  ›")
                        Text(line, style = MaterialTheme.typography.bodySmall, color = if (blocked) colors.attention else if (group.live) scheme.onSurfaceVariant else colors.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            }
            if (checklist.isNotEmpty()) Row(Modifier.clickable { showChecklist = !showChecklist }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(progress = { doneSteps.toFloat() / maxOf(1, checklist.size) }, Modifier.size(13.dp), color = if (group.live) scheme.primary else colors.textTertiary, trackColor = colors.hairline, strokeWidth = 1.8.dp)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.steps_progress, doneSteps, checklist.size), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
        if (showChecklist && checklist.isNotEmpty()) ChecklistView(checklist, Modifier.padding(start = 26.dp))
        if (expanded) StepList(StepRowModel.build(group.steps), store, onOpenSubagent, Modifier.padding(start = 8.dp))
    }
}

/** One run of tools in a live turn: "Explored 3 files, ran 2 commands", or the current action while it is the last run. */
@Composable
fun SegmentRow(segment: StepSegment, store: ChatStore, blocked: Boolean, onOpenSubagent: (String) -> Unit) {
    ObserveChat()
    val colors = LeashTheme.colors
    val open = segment.id in store.expandedGroups
    val rows = StepRowModel.build(segment.steps)
    val active = segment.liveLine != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { store.toggleGroup(segment.id) }.padding(start = 26.dp).heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                segment.liveLine ?: segment.summary, Modifier.pulse(active && !blocked, ), style = MaterialTheme.typography.bodySmall,
                color = if (active) MaterialTheme.colorScheme.onSurfaceVariant else colors.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
            if (rows.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ExpandMore, null, Modifier.size(15.dp).rotate(if (open) 180f else 0f), tint = colors.textTertiary)
            }
        }
        if (open) StepList(rows, store, onOpenSubagent, Modifier.padding(start = 8.dp))
    }
}

/** Rows of an expanded run; a long list shows its first three and last ten steps. */
@Composable
fun StepList(rows: List<StepRowModel>, store: ChatStore, onOpenSubagent: (String) -> Unit, modifier: Modifier = Modifier) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val collapsed = rows.size > 30 && !showAll
    val hairline = LeashTheme.colors.hairline
    Column(
        modifier.drawBehind { drawLine(hairline, Offset(9.dp.toPx(), 4.dp.toPx()), Offset(9.dp.toPx(), size.height - 4.dp.toPx()), strokeWidth = 1.5.dp.toPx()) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val head = if (collapsed) rows.take(3) else rows
        head.forEach { StepRow(it, store, onOpenSubagent) }
        if (collapsed) {
            Text(stringResource(R.string.show_earlier_steps, rows.size - 13), Modifier.clickable { showAll = true }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            rows.takeLast(10).forEach { StepRow(it, store, onOpenSubagent) }
        }
    }
}

@Composable
fun StepRow(row: StepRowModel, store: ChatStore, onOpenSubagent: (String) -> Unit) {
    ObserveChat()
    val kind = row.kind
    if (kind is StepRowModel.Kind.Subagent) { SubagentStepRow(kind.item, onOpenSubagent); return }
    val colors = LeashTheme.colors
    val scheme = MaterialTheme.colorScheme
    val open = row.id in store.expandedSteps
    val openFile = LocalOpenFile.current
    val textRow = kind is StepRowModel.Kind.Thought || kind is StepRowModel.Kind.Note
    Column(Modifier.fillMaxWidth().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { store.toggleStep(row.id) }.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon: ImageVector = when (kind) {
                is StepRowModel.Kind.Thought -> Icons.Filled.Psychology
                is StepRowModel.Kind.Note -> Icons.AutoMirrored.Filled.Notes
                is StepRowModel.Kind.Reads -> Icons.Filled.Description
                is StepRowModel.Kind.Tool -> toolIcon(kind.item.kind)
                is StepRowModel.Kind.Subagent -> Icons.Filled.CallSplit
            }
            Icon(icon, null, Modifier.width(16.dp).size(15.dp), tint = colors.textTertiary)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                val maxLines = if (open) Int.MAX_VALUE else 1
                when (kind) {
                    is StepRowModel.Kind.Thought -> Text(rememberInline(kind.item.text.trim(), openFile), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = scheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.MiddleEllipsis)
                    is StepRowModel.Kind.Note -> Text(rememberInline(kind.item.text.trim(), openFile), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.MiddleEllipsis)
                    is StepRowModel.Kind.Reads -> Text(stringResource(R.string.read_n_files, kind.items.size), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    is StepRowModel.Kind.Tool -> {
                        val t = kind.item
                        val cmd = t.commandPreview
                        val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        if (t.kind == "execute" && cmd != null) Text(cmd, style = mono, maxLines = maxLines, overflow = TextOverflow.MiddleEllipsis)
                        else {
                            val p = if (t.kind in listOf("edit", "delete", "move")) (t.firstDiff?.path ?: t.locations.firstOrNull()) else null
                            if (p != null) Text(p.substringAfterLast('/'), style = mono, maxLines = maxLines, overflow = TextOverflow.MiddleEllipsis)
                            else Text(t.title, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                    is StepRowModel.Kind.Subagent -> Unit
                }
            }
            if (kind is StepRowModel.Kind.Tool) {
                val t = kind.item
                Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.firstDiff != null) ChangeCounts(t.diffAdds, t.diffDels)
                    if (t.autoApproved) Icon(Icons.Filled.Bolt, null, Modifier.size(12.dp), tint = colors.turbo)
                    if (t.status == "failed") Icon(Icons.Filled.Close, stringResource(R.string.failed), Modifier.size(13.dp), tint = scheme.error)
                    else if (t.status == "in_progress") ActivitySpinner(11.dp)
                }
            }
        }
        if (open && !textRow) Column(Modifier.padding(start = 24.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (kind) {
                is StepRowModel.Kind.Reads -> kind.items.forEach { t ->
                    val p = t.locations.firstOrNull() ?: t.commandPreview ?: t.title
                    Text(p, Modifier.clickable { openFile(p) }, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = scheme.primary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
                is StepRowModel.Kind.Tool -> ToolDetail(kind.item)
                else -> Unit
            }
        }
    }
}

@Composable
private fun ToolDetail(t: ToolItem) {
    val scheme = MaterialTheme.colorScheme
    val openFile = LocalOpenFile.current
    val cmd = t.commandPreview
    if (t.kind != "execute" && cmd != null) Text(cmd, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = scheme.onSurfaceVariant)
    t.firstDiff?.let { d -> DiffBlock(d.path, d.old, d.new) }
    t.content.forEach { c -> if (c is dev.leash.personal.core.ToolContent.Text) CodeText(c.text, 12) }
    if (t.content.isEmpty()) t.rawOutput.pretty().takeIf { it.isNotEmpty() }?.let { CodeText(it, 12) }
    t.locations.take(3).forEach { loc ->
        Text(loc, Modifier.clickable { openFile(loc) }, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = scheme.primary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
    }
}

/** A compact line diff of one edit: changed lines with their context, long ones clipped (the full diff and changes sheet are phase 4). */
@Composable
fun DiffBlock(path: String, old: String?, new: String, maxLines: Int = 12) {
    val colors = LeashTheme.colors
    val scheme = MaterialTheme.colorScheme
    val lines = remember(old, new) { LineDiff.hunks(LineDiff.compute(old, new)) }
    var all by rememberSaveable(path, new.length) { mutableStateOf(false) }
    val shown = if (all) lines else lines.take(maxLines)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraSmall, color = colors.sunken) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (path.isNotEmpty()) Text(path.substringAfterLast('/'), Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                shown.forEach { l ->
                    val (prefix, text, bg) = when (l) {
                        is DiffLine.Add -> Triple("+", l.text, colors.success.copy(alpha = 0.14f))
                        is DiffLine.Del -> Triple("−", l.text, scheme.error.copy(alpha = 0.14f))
                        is DiffLine.Same -> Triple(" ", l.text, Color.Transparent)
                        is DiffLine.Gap -> Triple("⋯", stringResource(R.string.more_lines, l.skipped), Color.Transparent)
                    }
                    Text(prefix + " " + text, Modifier.background(bg).padding(horizontal = 10.dp), style = mono, softWrap = false, color = if (l is DiffLine.Gap) colors.textTertiary else scheme.onBackground)
                }
            }
            if (lines.size > maxLines && !all) Text(stringResource(R.string.show_all_diff, lines.size), Modifier.clickable { all = true }.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

fun toolIcon(kind: String): ImageVector = when (kind) {
    "read" -> Icons.Filled.Description
    "edit" -> Icons.Filled.Edit
    "delete" -> Icons.Filled.Delete
    "move" -> Icons.Filled.DriveFileMove
    "search" -> Icons.Filled.Search
    "execute" -> Icons.Filled.Terminal
    "think" -> Icons.Filled.Psychology
    "fetch" -> Icons.Filled.Language
    else -> Icons.Filled.Build
}

@Composable
fun ChecklistView(steps: List<ChecklistStep>, modifier: Modifier = Modifier) {
    val colors = LeashTheme.colors
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    when (s.status) { "completed" -> Icons.Filled.CheckCircle; "in_progress" -> Icons.Filled.FiberManualRecord; else -> Icons.Filled.RadioButtonUnchecked },
                    null, Modifier.size(15.dp).padding(top = 2.dp), tint = if (s.status == "in_progress") MaterialTheme.colorScheme.primary else colors.textTertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(s.title, style = MaterialTheme.typography.bodySmall, color = if (s.status == "completed") colors.textTertiary else MaterialTheme.colorScheme.onBackground, textDecoration = if (s.status == "completed") TextDecoration.LineThrough else null)
            }
        }
    }
}

/** A subagent in a step list: its title, then "type · model · current action"; it opens the subagent's own transcript. */
@Composable
fun SubagentStepRow(item: SubagentItem, onOpen: (String) -> Unit) {
    ObserveChat()
    val colors = LeashTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable { onOpen(item.sessionId) }.padding(start = 6.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CallSplit, null, Modifier.width(16.dp).size(15.dp), tint = colors.textTertiary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.displayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.secondaryLine, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Spacer(Modifier.width(4.dp))
        when {
            item.needsAction -> Icon(Icons.Filled.ErrorOutline, stringResource(R.string.needs_approval), Modifier.size(15.dp), tint = colors.attention)
            item.isRunning -> ActivitySpinner(11.dp)
            item.stopReason == "cancelled" -> Icon(Icons.Filled.StopCircle, stringResource(R.string.stopped), Modifier.size(15.dp), tint = colors.textTertiary)
            item.stopReason != null && item.stopReason != "end_turn" -> Icon(Icons.Filled.Close, stringResource(R.string.failed), Modifier.size(13.dp), tint = scheme.error)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.textTertiary)
    }
}
