package dev.leash.personal.ui.chat

import android.content.ClipData
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.FileLimits
import dev.leash.personal.core.FileRef
import dev.leash.personal.ui.files.FileKinds
import dev.leash.personal.ui.files.LocalOpenImage
import dev.leash.personal.ui.files.icon
import dev.leash.personal.core.MessageItem
import dev.leash.personal.core.NoticeItem
import dev.leash.personal.core.PlanItem
import dev.leash.personal.core.TurnChanges
import dev.leash.personal.core.TurnStats
import dev.leash.personal.core.durationText
import dev.leash.personal.ui.components.ObserveChat
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.tabular
import dev.leash.personal.ui.design.components.CardHeader
import dev.leash.personal.ui.design.components.CommandCapsule
import dev.leash.personal.ui.design.components.RaisedCard
import dev.leash.personal.ui.design.components.ShellBubble
import dev.leash.personal.ui.design.components.ChangesRow as ChangesRowView
import dev.leash.personal.ui.design.components.StatsLine as StatsLineView
import dev.leash.personal.ui.design.components.UserBubble as UserBubbleView
import dev.leash.personal.ui.markdown.MarkdownView
import kotlinx.coroutines.launch

/** Opens a file of the agent's host in the viewer (tool paths, links in the markdown, attachments); [dev.leash.personal.ui.files.ProvideFiles] sets it. */
val LocalOpenFile = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Something to draw: tag-only messages (a slash-command caveat, ...) render nothing. */
fun hasUserContent(m: MessageItem): Boolean {
    if (m.images.isNotEmpty() || m.files.isNotEmpty()) return true
    val (text, refs) = FileRef.extractLinks(m.text)
    return refs.isNotEmpty() || UserSegment.parse(text).isNotEmpty()
}

/** The user's message: images, attached files, then its text (or "!" shell run, or slash command) as a bubble on the right. */
@Composable
fun UserBubble(item: MessageItem, modifier: Modifier = Modifier) {
    ObserveChat()
    val (text, linked) = FileRef.extractLinks(item.text)
    val files = item.files + linked.filter { r -> item.files.none { it.path == r.path } }
    val segments = UserSegment.parse(text)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.images.forEachIndexed { i, data -> ImageTile(data, Modifier.widthIn(max = 220.dp).heightIn(max = 220.dp), "image " + (i + 1), "image-" + (i + 1) + ".png") }
        if (files.isNotEmpty()) Column(Modifier.padding(start = 48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { FileChip(it) }
        }
        segments.forEach { s ->
            when (s) {
                is UserSegment.Text -> UserBubbleView(s.text, selectable = true)
                is UserSegment.ShellInput -> {
                    val desc = stringResource(R.string.shell_command_desc, s.command)
                    ShellBubble(s.command, Modifier.semantics { contentDescription = desc })
                }
                is UserSegment.ShellOutput -> ShellOutputView(s.stdout, s.stderr, s.exitCode)
                is UserSegment.Command -> CommandCapsule(s.command)
            }
        }
    }
}

/** A photo of a message: never upscaled; tap opens it in the viewer (zoom, share, save). */
@Composable
fun ImageTile(data: ByteArray, modifier: Modifier = Modifier, description: String, name: String = "image.png") {
    val bmp = remember(data) { decodeImage(data) } ?: return
    val open = LocalOpenImage.current
    val c = Leash.colors
    val label = stringResource(R.string.open)
    Image(
        bmp.asImageBitmap(), description,
        modifier.clip(LeashShapes.card).border(1.dp, c.hairline, LeashShapes.card).pressable(scale = PressScale.Row, onClickLabel = label) { open(name, data) },
        contentScale = ContentScale.Fit,
    )
}

/** An attached or linked file (ios FileChip): an accent-tinted chip with the kind's glyph, the name and the size; tap opens the viewer. */
@Composable
fun FileChip(ref: FileRef, modifier: Modifier = Modifier) {
    val open = LocalOpenFile.current
    val c = Leash.colors
    val desc = stringResource(R.string.attachment_desc, ref.name)
    val hint = stringResource(R.string.attachment_hint)
    Row(
        modifier.widthIn(max = 260.dp).heightIn(min = 48.dp).clip(LeashShapes.row).background(c.accent.copy(alpha = 0.16f))
            .pressable(scale = PressScale.Compact, onClickLabel = hint) { open(ref.path) }
            .semantics { contentDescription = desc }.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FileKinds.glyph(ref.name).icon(), null, Modifier.size(24.dp), tint = c.accent)
        Column(Modifier.weight(1f, fill = false)) {
            Text(ref.name, style = Leash.type.footnote.medium(), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            ref.size?.let { Text(FileLimits.sizeText(it), style = Leash.type.caption2, color = c.textSecondary) }
        }
    }
}

/** Result of a "!" shell command: a terminal-style block, long output collapsed. */
@Composable
fun ShellOutputView(stdout: String, stderr: String, exitCode: Int?) {
    val c = Leash.colors
    val out = stdout.trim('\n')
    val err = stderr.trim('\n')
    fun count(s: String) = if (s.isEmpty()) 0 else s.split('\n').size
    val lines = count(out) + count(err)
    val failed = (exitCode ?: 0) != 0
    var expanded by rememberSaveable { mutableStateOf(false) }
    val budget = 12
    fun clip(s: String, n: Int) = if (expanded) s else s.split('\n').take(n).joinToString("\n")
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.copy)
    Column(
        Modifier.fillMaxWidth().clip(LeashShapes.inset).background(c.bgSunken).border(1.dp, c.hairline, LeashShapes.inset).padding(start = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(LeashIcons.terminal, null, Modifier.size(13.dp), tint = c.textTertiary)
            Text(stringResource(R.string.output), style = Leash.type.caption2, color = c.textTertiary)
            if (failed && exitCode != null) Text(stringResource(R.string.exit_code, exitCode), style = Leash.type.caption2.tabular(), color = c.danger)
            Box(Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).pressable(onClickLabel = copyLabel) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("output", listOf(out, err).filter { it.isNotEmpty() }.joinToString("\n")))) }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(LeashIcons.copy, copyLabel, Modifier.size(15.dp), tint = c.textTertiary) }
        }
        if (out.isEmpty() && err.isEmpty()) Text(stringResource(R.string.no_output), style = Leash.type.mono, color = c.textTertiary)
        else {
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp)) {
                    val mono = Leash.type.mono
                    if (out.isNotEmpty()) Text(clip(out, budget), style = mono, color = c.textPrimary, softWrap = false)
                    if (err.isNotEmpty()) Text(clip(err, maxOf(3, budget - count(out))), style = mono, color = if (failed) c.danger else c.attention, softWrap = false)
                }
            }
            if (lines > budget) Text(
                if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_all_lines, lines),
                Modifier.pressable(scale = PressScale.Text) { expanded = !expanded }.padding(vertical = 4.dp), style = Leash.type.caption.medium(), color = c.accent,
            )
        }
    }
}

@Composable
fun NoticeView(item: NoticeItem) {
    val c = Leash.colors
    val color = when (item.severity) { "error" -> c.danger; "warning" -> c.attention; else -> c.textSecondary }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (item.severity == "error") LeashIcons.failed else if (item.severity == "warning") LeashIcons.warning else LeashIcons.info, null, Modifier.size(16.dp), tint = color)
            Text(item.title, style = Leash.type.subheadline.medium(), color = if (item.severity == "info" || item.severity.isNullOrEmpty()) c.textSecondary else c.textPrimary)
        }
        item.detail?.let { Text(it, style = Leash.type.footnote, color = c.textSecondary, modifier = Modifier.padding(start = 22.dp)) }
    }
}

/** Tool output and similar plain text in a sunken box, long text clipped with "Show all N lines". */
@Composable
fun CodeText(text: String, maxLines: Int = 200) {
    val c = Leash.colors
    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }
    val lines = text.split('\n')
    val shown = if (expanded) text else lines.take(maxLines).joinToString("\n")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().clip(LeashShapes.inset).background(c.bgSunken).border(1.dp, c.hairline, LeashShapes.inset)) {
            SelectionContainer {
                Text(shown, Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), style = Leash.type.monoSmall, color = c.textSecondary, softWrap = false)
            }
        }
        if (lines.size > maxLines && !expanded) Text(
            stringResource(R.string.show_all_lines, lines.size), Modifier.pressable(scale = PressScale.Text) { expanded = true }.padding(vertical = 4.dp),
            style = Leash.type.caption.medium(), color = c.accent,
        )
    }
}

@Composable
fun PlanCard(item: PlanItem) {
    ObserveChat()
    val c = Leash.colors
    var open by rememberSaveable(item.id.toString()) { mutableStateOf(true) }
    val onOpen = LocalOpenFile.current
    RaisedCard {
        CardHeader(
            stringResource(R.string.plan), LeashIcons.modePlan,
            count = if (item.entries.isNotEmpty()) item.entries.count { it.status == "completed" }.toString() + "/" + item.entries.size else null,
            open = open, onClick = { open = !open },
        )
        if (open) {
            item.entries.forEach { e ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        when (e.status) { "completed" -> LeashIcons.doneFilled; "in_progress" -> LeashIcons.radioOn; else -> LeashIcons.radioOff }, null,
                        Modifier.padding(top = 2.dp).size(16.dp),
                        tint = when (e.status) { "completed" -> c.success; "in_progress" -> c.accent; else -> c.textTertiary },
                    )
                    Text(
                        e.content, style = Leash.type.callout, color = if (e.status == "completed") c.textSecondary else c.textPrimary,
                        textDecoration = if (e.status == "completed") TextDecoration.LineThrough else null,
                    )
                }
            }
            item.markdown?.let { MarkdownView(it, onOpen) }
        }
    }
}

/** The agent's answer: markdown, then its attached images and files. */
@Composable
fun AnswerView(item: MessageItem) {
    ObserveChat()
    val open = LocalOpenFile.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectionContainer { MarkdownView(item.text, open) }
        item.images.forEachIndexed { i, data -> ImageTile(data, Modifier.widthIn(max = 320.dp), "image " + (i + 1), "image-" + (i + 1) + ".png") }
        item.files.forEach { FileChip(it) }
    }
}

/** "Sonnet 5.5 · High · ↑12.4k ↓1.8k · 62 tok/s" under the final answer; tap for the details. */
@Composable
fun StatsLine(stats: TurnStats) {
    var open by rememberSaveable(stats.id.toString()) { mutableStateOf(false) }
    val summary = buildList {
        stats.model?.let { add(it.replace("Gemini 4 ", "").replace("Claude ", "")) }
        stats.effort?.let { add(it) }
        var tok = ""
        stats.inputTokens?.let { tok += "↑" + compactNumber(it) }
        stats.outputTokens?.let { tok += (if (tok.isEmpty()) "" else " ") + "↓" + compactNumber(it + (stats.thoughtTokens ?: 0)) }
        if (tok.isNotEmpty()) add(tok)
        stats.tokensPerSecond?.let { add(String.format(java.util.Locale.US, "%.0f tok/s", it)) }
    }.joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatsLineView(summary, onClick = { open = !open })
        if (open) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            stats.ttftMs?.let { StatRow(stringResource(R.string.stats_first_token), durationText(it / 1000.0)) }
            stats.durationMs?.let { StatRow(stringResource(R.string.stats_duration), durationText(it / 1000.0)) }
            stats.thoughtTokens?.let { StatRow(stringResource(R.string.stats_thinking), stringResource(R.string.tokens_unit, compactNumber(it))) }
            stats.cachedTokens?.takeIf { it > 0 }?.let { StatRow(stringResource(R.string.stats_cached), stringResource(R.string.tokens_unit, compactNumber(it))) }
            stats.stopReason?.let { StatRow(stringResource(R.string.stats_stop), it) }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(96.dp), style = Leash.type.caption2, color = Leash.colors.textTertiary)
        Text(value, style = Leash.type.monoSmall, color = Leash.colors.textSecondary)
    }
}

/** Opens a finished turn's changes, file by file (the sheet over the conversation). */
val LocalOpenChanges = staticCompositionLocalOf<(TurnChanges) -> Unit> { {} }

/** Under a finished turn's answer: how many files it changed and the line counts; tap for the per-file diffs. */
@Composable
fun ChangesRow(changes: TurnChanges) {
    val n = changes.files.size
    val open = LocalOpenChanges.current
    ChangesRowView(
        if (n == 1) stringResource(R.string.n_files_changed_one) else stringResource(R.string.n_files_changed, n),
        changes.adds, changes.dels, onClick = { open(changes) },
    )
}

@Composable
fun ChangeCounts(adds: Int, dels: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("+" + adds, style = Leash.type.monoSmall.tabular(), color = Leash.colors.success)
        Text("−" + dels, style = Leash.type.monoSmall.tabular(), color = Leash.colors.danger)
    }
}
