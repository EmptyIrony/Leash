package dev.leash.personal.ui.chat

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leash.personal.R
import dev.leash.personal.core.FileRef
import dev.leash.personal.core.MessageItem
import dev.leash.personal.core.NoticeItem
import dev.leash.personal.core.PlanItem
import dev.leash.personal.core.TurnChanges
import dev.leash.personal.core.TurnStats
import dev.leash.personal.core.durationText
import dev.leash.personal.ui.components.ObserveChat
import dev.leash.personal.ui.markdown.MarkdownView
import dev.leash.personal.ui.theme.LeashRadius
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.coroutines.launch

/** Opens a file of the agent's host (tool paths, links in the markdown). The viewer arrives in phase 4. */
val LocalOpenFile = staticCompositionLocalOf<(String) -> Unit> { {} }

private val bubbleShape = RoundedCornerShape(LeashRadius.bubble, LeashRadius.bubble, LeashRadius.bubbleTail, LeashRadius.bubble)

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
    val scheme = MaterialTheme.colorScheme
    val (text, linked) = FileRef.extractLinks(item.text)
    val files = item.files + linked.filter { r -> item.files.none { it.path == r.path } }
    val segments = UserSegment.parse(text)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.images.forEachIndexed { i, data -> ImageTile(data, Modifier.widthIn(max = 220.dp).heightIn(max = 220.dp), "image " + (i + 1)) }
        if (files.isNotEmpty()) Column(Modifier.padding(start = 48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { FileChip(it) }
        }
        segments.forEach { s ->
            when (s) {
                is UserSegment.Text -> Surface(shape = bubbleShape, color = scheme.primary.copy(alpha = 0.16f), modifier = Modifier.padding(start = 48.dp)) {
                    SelectionContainer { Text(s.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)) }
                }
                is UserSegment.ShellInput -> {
                    val desc = stringResource(R.string.shell_command_desc, s.command)
                    Surface(shape = bubbleShape, color = LeashTheme.colors.shell.copy(alpha = 0.13f), modifier = Modifier.padding(start = 48.dp).semantics { contentDescription = desc }) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                            Text("!", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Bold), color = LeashTheme.colors.shell)
                            Spacer(Modifier.width(7.dp))
                            SelectionContainer { Text(s.command, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)) }
                        }
                    }
                }
                is UserSegment.ShellOutput -> ShellOutputView(s.stdout, s.stderr, s.exitCode)
                is UserSegment.Command -> Surface(shape = MaterialTheme.shapes.large, color = LeashTheme.colors.hairline, modifier = Modifier.padding(start = 48.dp)) {
                    Text(s.command, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp), color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ImageTile(data: ByteArray, modifier: Modifier = Modifier, description: String) {
    val bmp = remember(data) { decodeImage(data) } ?: return
    Image(bmp.asImageBitmap(), description, modifier.clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit)
}

@Composable
fun FileChip(ref: FileRef) {
    val open = LocalOpenFile.current
    Surface(shape = MaterialTheme.shapes.small, color = LeashTheme.colors.sunken, border = BorderStroke(0.5.dp, LeashTheme.colors.hairline), modifier = Modifier.clickable { open(ref.path) }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(ref.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

/** Result of a "!" shell command: a terminal-style block, long output collapsed. */
@Composable
fun ShellOutputView(stdout: String, stderr: String, exitCode: Int?) {
    val colors = LeashTheme.colors
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
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraSmall, color = colors.sunken, border = BorderStroke(0.5.dp, colors.hairline)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Terminal, null, Modifier.size(14.dp), tint = colors.textTertiary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.output), style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                if (failed && exitCode != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.exit_code, exitCode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("output", listOf(out, err).filter { it.isNotEmpty() }.joinToString("\n")))) } }, Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy), Modifier.size(15.dp), tint = colors.textTertiary)
                }
            }
            if (out.isEmpty() && err.isEmpty()) Text(stringResource(R.string.no_output), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = colors.textTertiary)
            else {
                SelectionContainer {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
                        if (out.isNotEmpty()) Text(clip(out, budget), style = mono, softWrap = false)
                        if (err.isNotEmpty()) Text(clip(err, maxOf(3, budget - count(out))), style = mono, color = if (failed) MaterialTheme.colorScheme.error else colors.attention, softWrap = false)
                    }
                }
                if (lines > budget) Text(
                    if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_all_lines, lines),
                    Modifier.clickable { expanded = !expanded }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun NoticeView(item: NoticeItem) {
    val color = when (item.severity) { "error" -> MaterialTheme.colorScheme.error; "warning" -> LeashTheme.colors.attention; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.severity == "error") Icons.Filled.ErrorOutline else Icons.Filled.Info, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(item.title, style = MaterialTheme.typography.labelLarge, color = color)
        }
        item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
    }
}

/** Tool output and similar plain text in a sunken box, long text clipped with "Show all N lines". */
@Composable
fun CodeText(text: String, maxLines: Int = 200) {
    val colors = LeashTheme.colors
    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }
    val lines = text.split('\n')
    val shown = if (expanded) text else lines.take(maxLines).joinToString("\n")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraSmall, color = colors.sunken) {
            SelectionContainer {
                Text(shown, Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = false)
            }
        }
        if (lines.size > maxLines && !expanded) Text(stringResource(R.string.show_all_lines, lines.size), Modifier.clickable { expanded = true }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun PlanCard(item: PlanItem) {
    ObserveChat()
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(item.id.toString()) { mutableStateOf(true) }
    val onOpen = LocalOpenFile.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainer, border = BorderStroke(1.dp, LeashTheme.colors.hairline)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Checklist, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.plan), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (item.entries.isNotEmpty()) Text(item.entries.count { it.status == "completed" }.toString() + "/" + item.entries.size, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            if (open) {
                item.entries.forEach { e ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            when (e.status) { "completed" -> Icons.Filled.CheckCircle; "in_progress" -> Icons.Filled.PlayCircleOutline; else -> Icons.Filled.RadioButtonUnchecked }, null, Modifier.size(16.dp).padding(top = 2.dp),
                            tint = when (e.status) { "completed" -> LeashTheme.colors.success; "in_progress" -> scheme.primary; else -> scheme.onSurfaceVariant },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(e.content, style = MaterialTheme.typography.bodyMedium, textDecoration = if (e.status == "completed") TextDecoration.LineThrough else null)
                    }
                }
                item.markdown?.let { MarkdownView(it, onOpen) }
            }
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
        item.images.forEachIndexed { i, data -> ImageTile(data, Modifier.widthIn(max = 320.dp), "image " + (i + 1)) }
        item.files.forEach { FileChip(it) }
    }
}

/** "Sonnet 5.5 · High · ↑12.4k ↓1.8k · 62 tok/s" under the final answer; tap for the details. */
@Composable
fun StatsLine(stats: TurnStats) {
    val colors = LeashTheme.colors
    var open by rememberSaveable(stats.id.toString()) { mutableStateOf(false) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val summary = buildList {
        stats.model?.let { add(it.replace("Gemini 4 ", "").replace("Claude ", "")) }
        stats.effort?.let { add(it) }
        var tok = ""
        stats.inputTokens?.let { tok += "↑" + compactNumber(it) }
        stats.outputTokens?.let { tok += (if (tok.isEmpty()) "" else " ") + "↓" + compactNumber(it + (stats.thoughtTokens ?: 0)) }
        if (tok.isNotEmpty()) add(tok)
        stats.tokensPerSecond?.let { add(String.format(java.util.Locale.US, "%.0f tok/s", it)) }
    }.joinToString(" · ")
    Column(Modifier.clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary, style = mono, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.labelSmall, color = LeashTheme.colors.textTertiary)
        Text(value, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Under a finished turn's answer: how many files it changed and the line counts. The per-file diffs arrive in phase 4. */
@Composable
fun ChangesRow(changes: TurnChanges) {
    val colors = LeashTheme.colors
    val n = changes.files.size
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = colors.sunken, border = BorderStroke(1.dp, colors.hairline)) {
        Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Difference, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(if (n == 1) stringResource(R.string.n_files_changed_one) else stringResource(R.string.n_files_changed, n), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (changes.adds + changes.dels > 0) ChangeCounts(changes.adds, changes.dels)
        }
    }
}

@Composable
fun ChangeCounts(adds: Int, dels: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("+" + adds, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = LeashTheme.colors.success)
        Text("−" + dels, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = MaterialTheme.colorScheme.error)
    }
}
