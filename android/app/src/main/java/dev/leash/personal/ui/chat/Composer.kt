package dev.leash.personal.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AgentCommand
import dev.leash.personal.core.QueuedMessage
import dev.leash.personal.ui.components.pluralText
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LocalReduceMotion
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.components.ComposerStatus
import dev.leash.personal.ui.design.components.LeashMenu
import dev.leash.personal.ui.design.components.LeashMenuItem
import dev.leash.personal.ui.design.components.QueueHeader
import dev.leash.personal.ui.design.components.QueueRow as QueueRowView
import dev.leash.personal.ui.design.components.RemovedRow
import dev.leash.personal.ui.design.components.SecondaryStopButton
import dev.leash.personal.ui.design.components.SendButton
import dev.leash.personal.ui.design.components.SendGlyph
import dev.leash.personal.ui.design.components.TrayShell as DesignTrayShell
import dev.leash.personal.ui.design.components.ComposerSlab as DesignComposerSlab

/** A glass tray above the composer (the design system's TrayShell with the chat's padding parameter). */
@Composable
fun TrayShell(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(14.dp), content: @Composable () -> Unit) =
    DesignTrayShell(modifier, contentPadding) { content() }

/**
 * The message field with its send / stop circle (ios ChatView.slab). Mid-turn the circle sends a steering or queued
 * message; touch and hold offers "Interrupt and send". With a draft waiting, a small Stop slides out next to it.
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
    val c = Leash.colors
    val reduced = LocalReduceMotion.current
    val glyph = ComposerLogic.glyph(running, value.text, canSend)
    val secondaryStop = ComposerLogic.showsSecondaryStop(running, value.text)
    val dismissLabel = stringResource(R.string.dismiss)
    DesignComposerSlab(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        blur = true,
        // attachments ("+") arrive with phase 4; until then the field starts at the slab's edge
        showLeading = false,
        fieldModifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .onPreviewKeyEvent { e ->
                // a hardware keyboard: Ctrl/Cmd + Enter sends
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && (e.isCtrlPressed || e.isMetaPressed)) { onSend(); true } else false
            }
            .semantics { contentDescription = placeholder },
        above = if (error == null && commandStatus == null) null else ({
            if (error != null) ComposerStatus(error, LeashIcons.failed, c.danger, onDismiss = onDismissError, dismissLabel = dismissLabel, maxLines = 4)
            if (commandStatus != null) ComposerStatus(
                stringResource(R.string.command_status, commandStatus.name, commandStatus.hint.orEmpty()), LeashIcons.command, c.textSecondary,
            )
        }),
    ) {
        AnimatedVisibility(secondaryStop, enter = Motion.trailingPopEnter(reduced), exit = Motion.trailingPopExit(reduced)) {
            SecondaryStopButton(stringResource(R.string.stop), onStop)
        }
        PrimaryAction(glyph, running && secondaryStop, onSend, onStop, onInterruptAndSend)
    }
}

/** One circle that is Stop, Send or (greyed out) nothing; its fill and symbol change in place. */
@Composable
private fun PrimaryAction(glyph: ActionGlyph, interruptible: Boolean, onSend: () -> Unit, onStop: () -> Unit, onInterruptAndSend: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val sendLabel = stringResource(R.string.send)
    val stopLabel = stringResource(R.string.stop)
    val interruptLabel = stringResource(R.string.interrupt_and_send)
    Box(Modifier.semantics { if (interruptible) customActions = listOf(CustomAccessibilityAction(interruptLabel) { onInterruptAndSend(); true }) }) {
        SendButton(
            when (glyph) { ActionGlyph.Stop -> SendGlyph.Stop; ActionGlyph.Send -> SendGlyph.Send; ActionGlyph.Disabled -> SendGlyph.Disabled },
            if (glyph == ActionGlyph.Stop) stopLabel else sendLabel,
            onClick = { if (glyph == ActionGlyph.Stop) onStop() else onSend() },
            onLongClick = if (interruptible) ({ menu = true }) else null,
        )
        LeashMenu(menu, { menu = false }) {
            LeashMenuItem(interruptLabel, { menu = false; onInterruptAndSend() }, icon = LeashIcons.send)
        }
    }
}

/** The commands a bare "/word" could start, docked above the composer. Picking one puts "/name " in the field; it never sends. */
@Composable
fun CommandPalette(items: List<AgentCommand>, compact: Boolean, pick: (AgentCommand) -> Unit) {
    val c = Leash.colors
    val rowHeight = 56.dp
    // a half row at the bottom says the list scrolls; fewer matches shrink it
    val rows = minOf(items.size.toFloat(), if (compact) 2.5f else 4.5f)
    TrayShell(contentPadding = PaddingValues(vertical = 4.dp)) {
        LazyColumn(Modifier.height(rowHeight * rows)) {
            itemsIndexed(items, key = { _, cmd -> cmd.name }) { _, cmd ->
                Column(
                    Modifier.fillMaxWidth().height(rowHeight)
                        .pressable(scale = PressScale.Row, highlight = true, onClickLabel = stringResource(R.string.command_insert)) { pick(cmd) }
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("/" + cmd.name, style = Leash.type.mono.copy(fontSize = Leash.type.callout.fontSize).semibold(), color = c.textPrimary, maxLines = 1)
                        if (!cmd.hint.isNullOrEmpty()) Text(cmd.hint, style = Leash.type.callout, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (cmd.description.isNotEmpty()) Text(cmd.description, style = Leash.type.footnote, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Messages sent while the agent works, waiting on the bridge for the turn to end (ios QueueTray). Tap a row to take it back
 * into the composer, the cross drops it (its row offers Undo for a few seconds); the header button sends everything now.
 */
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
    val shown = 2
    val visible = rows.take(shown)
    val more = items.size - visible.count { it is QueueRow.Queued }
    TrayShell(contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (items.isNotEmpty()) QueueHeader(
                pluralText(R.plurals.queued_count, items.size),
                stringResource(if (running) R.string.interrupt_and_send else R.string.send_now),
                sendNow, enabled = enabled,
            )
            // with nothing left queued, the Undo row is all there is (no header to collapse to)
            if (!collapsed || items.isEmpty()) {
                visible.forEach { r ->
                    when (r) {
                        is QueueRow.Queued -> QueuedRow(r.message, enabled, edit, delete)
                        is QueueRow.Removed -> RemovedRow(stringResource(R.string.removed), stringResource(R.string.undo), undo)
                    }
                }
                if (more > 0) Text(stringResource(R.string.more_count, more), Modifier.padding(vertical = 4.dp), style = Leash.type.footnote, color = Leash.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun QueuedRow(q: QueuedMessage, enabled: Boolean, edit: (QueuedMessage) -> Unit, delete: (QueuedMessage) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    QueueRowView(
        q.text.ifEmpty { stringResource(if (q.imageCount == 0 && q.fileCount > 0) R.string.queued_file else R.string.queued_photo) },
        onEdit = { edit(q) },
        onRemove = { delete(q) },
        removeLabel = stringResource(R.string.remove_queued),
        icons = listOfNotNull(if (q.imageCount > 0) LeashIcons.photo else null, if (q.fileCount > 0) LeashIcons.file else null),
        enabled = enabled,
        editLabel = stringResource(R.string.edit_queued_hint),
        onLongClick = { menu = true },
        menu = {
            LeashMenu(menu, { menu = false }) {
                LeashMenuItem(stringResource(R.string.edit), { menu = false; edit(q) }, icon = LeashIcons.edit)
                LeashMenuItem(stringResource(R.string.delete), { menu = false; delete(q) }, icon = LeashIcons.delete, destructive = true)
            }
        },
    )
}
