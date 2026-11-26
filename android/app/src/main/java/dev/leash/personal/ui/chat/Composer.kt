package dev.leash.personal.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AgentCommand
import dev.leash.personal.core.QueuedMessage
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LocalReduceMotion
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.components.ComposerMode
import dev.leash.personal.ui.design.components.ComposerStatus
import dev.leash.personal.ui.design.components.LeashMenuDivider
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

/** What the composer's "+" offers; null entries are left out. */
class ComposerAddActions(
    val onPhoto: (() -> Unit)?,
    val onFile: () -> Unit,
    val onSideQuestion: (() -> Unit)?,
    val onShell: () -> Unit,
)

/** The line under the attachments that says what the mode is or why it can't go yet (shell, side question). */
class ModeStatus(val text: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: androidx.compose.ui.graphics.Color, val busy: Boolean = false)

/**
 * The message field with its leading "+" and its send / stop circle (ios ChatView.slab). Mid-turn the circle sends a steering
 * or queued message; touch and hold offers "Interrupt and send". With a draft waiting, a small Stop slides out next to it.
 * "!" turns it into a shell prompt and "/btw" into a side-question box: the leading control then leaves the mode.
 */
@Composable
fun ComposerSlab(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean,
    running: Boolean,
    canSend: Boolean,
    kind: DraftKind,
    hasAttachments: Boolean,
    error: String?,
    commandStatus: AgentCommand?,
    modeStatus: ModeStatus?,
    attachments: (@Composable () -> Unit)?,
    add: ComposerAddActions,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInterruptAndSend: () -> Unit,
    onDismissError: () -> Unit,
    onLeaveMode: () -> Unit,
) {
    val c = Leash.colors
    val reduced = LocalReduceMotion.current
    val glyph = ComposerLogic.glyph(running, value.text, canSend, hasAttachments, kind)
    val secondaryStop = ComposerLogic.showsSecondaryStop(running, value.text, hasAttachments)
    val interruptible = ComposerLogic.offersInterrupt(running, value.text, hasAttachments, kind)
    val dismissLabel = stringResource(R.string.dismiss)
    var addMenu by remember { mutableStateOf(false) }
    val mode = when (kind) { DraftKind.Shell -> ComposerMode.Shell; DraftKind.SideQuestion -> ComposerMode.SideQuestion; DraftKind.Message -> ComposerMode.Normal }
    DesignComposerSlab(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        mode = mode,
        blur = true,
        leadingLabel = stringResource(when (kind) { DraftKind.Shell -> R.string.leave_shell; DraftKind.SideQuestion -> R.string.leave_side_question; DraftKind.Message -> R.string.add_menu }),
        onLeading = { if (kind == DraftKind.Message) addMenu = true else onLeaveMode() },
        leadingMenu = if (kind != DraftKind.Message) null else ({
            LeashMenu(addMenu, { addMenu = false }) {
                add.onPhoto?.let { LeashMenuItem(stringResource(R.string.photo_library), { addMenu = false; it() }, icon = LeashIcons.photo) }
                LeashMenuItem(stringResource(R.string.choose_files), { addMenu = false; add.onFile() }, icon = LeashIcons.folder)
                if (add.onSideQuestion != null) {
                    LeashMenuDivider()
                    LeashMenuItem(stringResource(R.string.ask_side_question), { addMenu = false; add.onSideQuestion.invoke() }, icon = LeashIcons.sideQuestion)
                }
                LeashMenuItem(stringResource(R.string.run_shell_command), { addMenu = false; add.onShell() }, icon = LeashIcons.terminal)
            }
        }),
        fieldModifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .onPreviewKeyEvent { e ->
                // a hardware keyboard: Ctrl/Cmd + Enter sends
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && (e.isCtrlPressed || e.isMetaPressed)) { onSend(); true } else false
            }
            .semantics { contentDescription = placeholder },
        above = if (error == null && commandStatus == null && modeStatus == null && attachments == null) null else ({
            if (error != null) ComposerStatus(error, LeashIcons.failed, c.danger, onDismiss = onDismissError, dismissLabel = dismissLabel, maxLines = 4)
            if (modeStatus != null) ComposerStatus(modeStatus.text, modeStatus.icon, modeStatus.color, busy = modeStatus.busy)
            if (commandStatus != null && modeStatus == null) ComposerStatus(
                stringResource(R.string.command_status, commandStatus.name, commandStatus.hint.orEmpty()), LeashIcons.command, c.textSecondary,
            )
            attachments?.let { a -> Box(Modifier.padding(start = 4.dp)) { a() } }
        }),
    ) {
        AnimatedVisibility(secondaryStop, enter = Motion.trailingPopEnter(reduced), exit = Motion.trailingPopExit(reduced)) {
            SecondaryStopButton(stringResource(R.string.stop), onStop)
        }
        PrimaryAction(glyph, interruptible, onSend, onStop, onInterruptAndSend)
    }
}

/** One circle that is Stop, Send or (greyed out) nothing; its fill and symbol change in place. */
@Composable
private fun PrimaryAction(glyph: ActionGlyph, interruptible: Boolean, onSend: () -> Unit, onStop: () -> Unit, onInterruptAndSend: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val sendLabel = stringResource(R.string.send)
    val stopLabel = stringResource(R.string.stop)
    val interruptLabel = stringResource(R.string.interrupt_and_send)
    Box {
        SendButton(
            when (glyph) {
                ActionGlyph.Stop -> SendGlyph.Stop; ActionGlyph.Send -> SendGlyph.Send; ActionGlyph.Disabled -> SendGlyph.Disabled
                ActionGlyph.Run -> SendGlyph.ShellRun; ActionGlyph.RunDisabled -> SendGlyph.ShellDisabled
            },
            when (glyph) { ActionGlyph.Stop -> stopLabel; ActionGlyph.Run, ActionGlyph.RunDisabled -> stringResource(R.string.run); else -> sendLabel },
            onClick = { if (glyph == ActionGlyph.Stop) onStop() else onSend() },
            onLongClick = if (interruptible) ({ menu = true }) else null,
            accessibilityActions = if (interruptible) listOf(CustomAccessibilityAction(interruptLabel) { onInterruptAndSend(); true }) else emptyList(),
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
                pluralStringResource(R.plurals.queued_count, items.size, items.size),
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
