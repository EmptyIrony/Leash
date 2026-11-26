package dev.leash.personal.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.leash.personal.ui.components.pluralText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AgentCommand
import dev.leash.personal.core.QueuedMessage
import dev.leash.personal.ui.theme.LeashRadius
import dev.leash.personal.ui.theme.LeashTheme

/** The glass of Android: a raised, hairline-bordered surface that trays and the composer share. */
@Composable
fun TrayShell(modifier: Modifier = Modifier, contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(14.dp), content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(LeashRadius.tray),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
        border = BorderStroke(0.5.dp, LeashTheme.colors.hairline),
    ) { Box(Modifier.animateContentSize().padding(contentPadding)) { content() } }
}

/**
 * The message field with its send / stop circle (ios ChatView.slab). Mid-turn the circle sends a steering or queued
 * message; touch and hold offers "Interrupt and send". With a draft waiting, a small Stop sits next to it.
 */
@Composable
fun ComposerSlab(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean,
    running: Boolean,
    canSend: Boolean,
    error: String?,
    commandStatus: AgentCommand?,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInterruptAndSend: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = LeashTheme.colors
    val scheme = MaterialTheme.colorScheme
    val glyph = ComposerLogic.glyph(running, value.text, canSend)
    val secondaryStop = ComposerLogic.showsSecondaryStop(running, value.text)
    Surface(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(LeashRadius.composer),
        color = scheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
        border = BorderStroke(0.5.dp, colors.hairline),
    ) {
        Column(Modifier.animateContentSize().padding(horizontal = 6.dp, vertical = 2.dp)) {
            if (error != null) Row(Modifier.padding(start = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, null, Modifier.size(16.dp), tint = scheme.error)
                Spacer(Modifier.width(6.dp))
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onDismissError, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, stringResource(R.string.dismiss), Modifier.size(16.dp), tint = scheme.onSurfaceVariant) }
            }
            if (commandStatus != null) Text(
                stringResource(R.string.command_status, commandStatus.name, commandStatus.hint.orEmpty()),
                Modifier.padding(start = 10.dp, top = 6.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                val textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface)
                BasicTextField(
                    value, onValueChange,
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp, top = 12.dp, bottom = 12.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .onPreviewKeyEvent { e ->
                            // a hardware keyboard: Ctrl/Cmd + Enter sends
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && (e.isCtrlPressed || e.isMetaPressed)) { onSend(); true } else false
                        }
                        .semantics { contentDescription = placeholder },
                    enabled = enabled,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 8,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) Text(placeholder, style = textStyle, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                if (secondaryStop) Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = stringResource(R.string.stop), onClick = onStop),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(28.dp).border(1.5.dp, colors.hairline, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Stop, stringResource(R.string.stop), Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
                    }
                }
                PrimaryAction(glyph, running && secondaryStop, onSend, onStop, onInterruptAndSend)
            }
        }
    }
}

/** One circle that is Stop, Send or (greyed out) nothing; its fill and symbol change in place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrimaryAction(glyph: ActionGlyph, interruptible: Boolean, onSend: () -> Unit, onStop: () -> Unit, onInterruptAndSend: () -> Unit) {
    val colors = LeashTheme.colors
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        when (glyph) { ActionGlyph.Stop -> scheme.onSurface; ActionGlyph.Send -> scheme.primary; ActionGlyph.Disabled -> colors.hairline }, label = "actionFill",
    )
    val ink = when (glyph) { ActionGlyph.Stop -> scheme.surface; ActionGlyph.Send -> scheme.onPrimary; ActionGlyph.Disabled -> colors.textTertiary }
    var menu by remember { mutableStateOf(false) }
    val sendLabel = stringResource(R.string.send)
    val stopLabel = stringResource(R.string.stop)
    val interruptLabel = stringResource(R.string.interrupt_and_send)
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    enabled = glyph != ActionGlyph.Disabled,
                    onClickLabel = if (glyph == ActionGlyph.Stop) stopLabel else sendLabel,
                    onLongClickLabel = if (interruptible) interruptLabel else null,
                    onLongClick = if (interruptible) ({ menu = true }) else null,
                    onClick = { if (glyph == ActionGlyph.Stop) onStop() else onSend() },
                )
                .semantics {
                    contentDescription = if (glyph == ActionGlyph.Stop) stopLabel else sendLabel
                    if (interruptible) customActions = listOf(CustomAccessibilityAction(interruptLabel) { onInterruptAndSend(); true })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(36.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (glyph == ActionGlyph.Stop) Icons.Filled.Stop else Icons.Filled.ArrowUpward, null, Modifier.size(if (glyph == ActionGlyph.Stop) 18.dp else 20.dp), tint = ink)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(interruptLabel) }, onClick = { menu = false; onInterruptAndSend() })
        }
    }
}

/** The commands a bare "/word" could start, docked above the composer. Picking one puts "/name " in the field; it never sends. */
@Composable
fun CommandPalette(items: List<AgentCommand>, compact: Boolean, pick: (AgentCommand) -> Unit) {
    val rowHeight = 56.dp
    // a half row at the bottom says the list scrolls; fewer matches shrink it
    val rows = minOf(items.size.toFloat(), if (compact) 2.5f else 4.5f)
    TrayShell(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        LazyColumn(Modifier.height(rowHeight * rows)) {
            itemsIndexed(items, key = { _, c -> c.name }) { i, c ->
                Column(
                    Modifier.fillMaxWidth().height(rowHeight).clickable(onClickLabel = stringResource(R.string.command_insert)) { pick(c) }.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("/" + c.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                        if (!c.hint.isNullOrEmpty()) Text(c.hint, style = MaterialTheme.typography.bodyLarge, color = LeashTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.description.isNotEmpty()) Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Messages sent while the agent works, waiting on the bridge for the turn to end (ios QueueTray). Tap a row to take it back
 * into the composer, the cross drops it (its row offers Undo for a few seconds); the header button sends everything now.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueTray(
    items: List<QueuedMessage>,
    rows: List<QueueRow>,
    running: Boolean,
    collapsed: Boolean,
    enabled: Boolean,
    edit: (QueuedMessage) -> Unit,
    delete: (QueuedMessage) -> Unit,
    sendNow: () -> Unit,
    undo: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LeashTheme.colors
    val shown = 2
    val visible = rows.take(shown)
    val more = items.size - visible.count { it is QueueRow.Queued }
    TrayShell(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (items.isNotEmpty()) Row(Modifier.heightIn(min = 40.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(pluralText(R.plurals.queued_count, items.size), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Button(onClick = sendNow, enabled = enabled, contentPadding = ButtonDefaults.ContentPadding, modifier = Modifier.height(36.dp)) {
                    Text(stringResource(if (running) R.string.interrupt_and_send else R.string.send_now), fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            // with nothing left queued, the Undo row is all there is (no header to collapse to)
            if (!collapsed || items.isEmpty()) {
                visible.forEach { r ->
                    when (r) {
                        is QueueRow.Queued -> QueuedRow(r.message, enabled, edit, delete)
                        is QueueRow.Removed -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.removed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary)
                            TextButton(onClick = undo) { Text(stringResource(R.string.undo), fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                if (more > 0) Text(stringResource(R.string.more_count, more), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueuedRow(q: QueuedMessage, enabled: Boolean, edit: (QueuedMessage) -> Unit, delete: (QueuedMessage) -> Unit) {
    val colors = LeashTheme.colors
    var menu by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.remove_queued)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .combinedClickable(enabled = enabled, onClickLabel = stringResource(R.string.edit_queued_hint), onClick = { edit(q) }, onLongClick = { menu = true }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (q.imageCount > 0) Icon(Icons.Filled.Image, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (q.fileCount > 0) Icon(Icons.Filled.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    q.text.ifEmpty { stringResource(if (q.imageCount == 0 && q.fileCount > 0) R.string.queued_file else R.string.queued_photo) },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; edit(q) })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete(q) })
            }
        }
        IconButton(onClick = { delete(q) }, enabled = enabled) {
            Icon(Icons.Filled.Close, removeLabel, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
