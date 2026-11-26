package dev.leash.personal.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.PendingRequest
import dev.leash.personal.core.SubagentItem
import dev.leash.personal.core.ToolItem
import dev.leash.personal.core.bool
import dev.leash.personal.core.double
import dev.leash.personal.core.get
import dev.leash.personal.core.j
import dev.leash.personal.core.string
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.markdown.rememberInline
import dev.leash.personal.ui.theme.LeashRadius
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The oldest permission request, else the oldest question, with its "1 of 3" counter. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RequestTray(
    model: AppModel,
    request: PendingRequest,
    index: Int,
    total: Int,
    /** a subagent's request names it above the title (off inside that subagent's own screen) */
    showsAgent: Boolean,
    onOpenSubagent: (root: String, child: String) -> Unit,
) {
    TrayShell {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val agent = request.subagent
            if (showsAgent && agent != null) SubagentAttribution(agent.label, request.sessionId?.let { root -> { onOpenSubagent(root, agent.sessionId) } })
            // the glass stays put while the next request replaces this one inside it
            AnimatedContent(request, contentKey = { it.key }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "request") { r ->
                if (r.isPermission) ApprovalBody(model, r, index, total) else QuestionBody(model, r, index, total)
            }
        }
    }
}

/**
 * What is waiting on the user in a conversation (or in one subagent's lineage): the oldest permission request, else the oldest
 * question. Returns false (and draws nothing) when there is none, so callers can tell whether a tray is showing.
 */
@Composable
fun RequestStack(model: AppModel, requests: List<PendingRequest>, showsAgent: (PendingRequest) -> Boolean, onOpenSubagent: (root: String, child: String) -> Unit) {
    val permissions = requests.filter { it.isPermission }
    val questions = requests.filter { !it.isPermission }
    val first = permissions.firstOrNull() ?: questions.firstOrNull() ?: return
    RequestTray(model, first, 1, if (first.isPermission) permissions.size else questions.size, showsAgent(first), onOpenSubagent)
}

/** "⑂ Design the icon · Explore" above a subagent's request; it opens that subagent's transcript. */
@Composable
private fun SubagentAttribution(label: String, open: (() -> Unit)?) {
    Row(
        Modifier.then(if (open != null) Modifier.clickable(onClick = open) else Modifier).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CallSplit, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(label.ifEmpty { stringResource(R.string.subagent_unnamed) }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
private fun RequestCounter(index: Int, total: Int) {
    if (total > 1) Text(stringResource(R.string.request_of, index, total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun permissionName(o: PermOption): String = when (o.kind) {
    "allow_once" -> stringResource(R.string.allow)
    "allow_always" -> stringResource(R.string.always_allow)
    "reject_once" -> stringResource(R.string.deny)
    "reject_always" -> stringResource(R.string.always_deny)
    else -> o.name
}

// MARK: permission

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ApprovalBody(model: AppModel, request: PendingRequest, index: Int, total: Int) {
    val colors = LeashTheme.colors
    val scheme = MaterialTheme.colorScheme
    val tool = remember(request.key) { ToolItem(request.params["toolCall"]["toolCallId"].string ?: "?").also { it.apply(request.params["toolCall"] ?: JsonNull) } }
    val buttons = remember(request.key) { splitPermissionOptions(permissionOptions(request.params)) }
    var showDiff by remember(request.key) { mutableStateOf(false) }
    val diff = tool.firstDiff
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PanTool, null, Modifier.size(18.dp), tint = colors.attention)
            Spacer(Modifier.width(8.dp))
            Text(tool.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            RequestCounter(index, total)
        }
        val cmd = tool.commandPreview
        if (cmd != null && (cmd != tool.title || tool.kind == "execute")) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(LeashRadius.inset), color = colors.sunken) {
            Text(cmd, Modifier.padding(10.dp), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = scheme.onSurface), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (diff != null) Row(Modifier.clickable { showDiff = true }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Description, null, Modifier.size(16.dp))
            Text(diff.path.substringAfterLast('/'), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("+" + tool.diffAdds, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = colors.success)
            Text("−" + tool.diffDels, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = scheme.error)
            Text("· " + stringResource(R.string.view_diff), style = MaterialTheme.typography.bodySmall, color = scheme.primary)
        }
        // side by side when they fit; at large text sizes they stack, the primary action first
        PermissionButtonsRow(buttons, stacked = LocalDensity.current.fontScale > 1.15f || buttons.all.size > 3) { o -> model.answerPermission(request, o.id, o.kind) }
    }
    if (showDiff && diff != null) ModalBottomSheet(onDismissRequest = { showDiff = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(diff.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
            DiffBlock(diff.path, diff.old, diff.new, maxLines = 400)
        }
    }
}

@Composable
private fun PermissionButtonsRow(buttons: PermissionButtons, stacked: Boolean, answer: (PermOption) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val pad = PaddingValues(horizontal = 10.dp)
    val denyColors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error)
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in buttons.allow) Button(onClick = { answer(o) }, Modifier.fillMaxWidth(), contentPadding = pad) { Text(permissionName(o), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        for (o in buttons.always) OutlinedButton(onClick = { answer(o) }, Modifier.fillMaxWidth(), contentPadding = pad) { Text(permissionName(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        for (o in buttons.deny) OutlinedButton(onClick = { answer(o) }, Modifier.fillMaxWidth(), colors = denyColors, contentPadding = pad) { Text(permissionName(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // "Always allow" is the longest label: it gets the most room
        for (o in buttons.deny) OutlinedButton(onClick = { answer(o) }, Modifier.weight(0.85f), colors = denyColors, contentPadding = pad) { Text(permissionName(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        for (o in buttons.always) OutlinedButton(onClick = { answer(o) }, Modifier.weight(1.35f), contentPadding = pad) { Text(permissionName(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        for (o in buttons.allow) Button(onClick = { answer(o) }, Modifier.weight(0.9f), contentPadding = pad) { Text(permissionName(o), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// MARK: question

@Composable
private fun QuestionBody(model: AppModel, request: PendingRequest, index: Int, total: Int) {
    val scheme = MaterialTheme.colorScheme
    val spec = remember(request.key) { ElicitationSpec.of(request) }
    var answers by remember(request.key) { mutableStateOf(ElicitationAnswers()) }
    var showSheet by remember(request.key) { mutableStateOf(false) }
    val open = dev.leash.personal.ui.chat.LocalOpenFile.current
    val uri = LocalUriHandler.current
    val skip = { showSheet = false; model.answerElicitation(request, "decline") }
    val submit = { showSheet = false; model.answerElicitation(request, "accept", answers.content(spec)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, null, Modifier.size(18.dp), tint = scheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.agent_asks), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            RequestCounter(index, total)
        }
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            spec.message?.let { Text(rememberInline(it, open), style = MaterialTheme.typography.bodyLarge) }
            val url = spec.url
            val q = spec.questions.firstOrNull()
            if (url != null) {
                Row(Modifier.clickable { uri.openUri(url) }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_link), Modifier.size(16.dp), tint = scheme.primary)
                    Text(url, style = MaterialTheme.typography.bodySmall, color = scheme.primary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            } else if (spec.fitsTray && q != null) {
                val kind = q.kind
                if (kind is FieldKind.Single) OptionList(q, kind.choices, answers) { answers = it }
                if (kind is FieldKind.Multi) OptionList(q, kind.choices, answers) { answers = it }
                if (spec.custom(q) != null) TextButton(onClick = { showSheet = true }) { Text(stringResource(R.string.other_answer)) }
            } else {
                val n = spec.questions.size
                Text(pluralStringResource(R.plurals.questions_count, n, n), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spec.url != null) {
                OutlinedButton(onClick = { model.answerElicitation(request, "decline") }, Modifier.weight(1f)) { Text(stringResource(R.string.decline)) }
                Button(onClick = { model.answerElicitation(request, "accept") }, Modifier.weight(1f)) { Text(stringResource(R.string.done), fontWeight = FontWeight.Bold) }
            } else {
                OutlinedButton(onClick = skip, Modifier.weight(1f)) { Text(stringResource(R.string.skip)) }
                if (spec.fitsTray) Button(onClick = submit, Modifier.weight(1f), enabled = answers.hasAnswer) { Text(stringResource(R.string.submit), fontWeight = FontWeight.Bold) }
                else Button(onClick = { showSheet = true }, Modifier.weight(1f)) { Text(stringResource(R.string.answer_ellipsis), fontWeight = FontWeight.Bold) }
            }
        }
    }
    if (showSheet) QuestionSheet(spec, answers, { answers = it }, submit, skip) { showSheet = false }
}

@Composable
private fun OptionList(q: ElicitField, choices: List<Choice>, answers: ElicitationAnswers, onChange: (ElicitationAnswers) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { c -> OptionRow(c, multi = q.kind is FieldKind.Multi, selected = answers.isSelected(q, c)) { onChange(answers.toggle(q, c)) } }
    }
}

/** One choice: radio / checkbox symbol (selection never shown by colour alone), title, optional description. */
@Composable
private fun OptionRow(choice: Choice, multi: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LeashRadius.row)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.onSurface.copy(alpha = 0.04f))
            .then(if (multi) Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() }) else Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick))
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (multi) (if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank) else (if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked),
            null, tint = if (selected) scheme.primary else LeashTheme.colors.textTertiary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(choice.title, style = MaterialTheme.typography.bodyLarge)
            if (!choice.detail.isNullOrEmpty()) Text(choice.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Every question at once, with the keyboard for "Other" and free-text fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionSheet(spec: ElicitationSpec, answers: ElicitationAnswers, onChange: (ElicitationAnswers) -> Unit, submit: () -> Unit, skip: () -> Unit, dismiss: () -> Unit) {
    val open = LocalOpenFile.current
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          // the answers scroll; Skip and Submit stay in reach
          Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.agent_asks), style = MaterialTheme.typography.titleMedium)
            spec.message?.let { Text(rememberInline(it, open), style = MaterialTheme.typography.bodyLarge) }
            spec.questions.forEach { q ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(q.title, style = MaterialTheme.typography.titleSmall)
                    if (!q.detail.isNullOrEmpty() && spec.questions.size > 1) Text(q.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (val k = q.kind) {
                        is FieldKind.Single -> OptionList(q, k.choices, answers, onChange)
                        is FieldKind.Multi -> OptionList(q, k.choices, answers, onChange)
                        FieldKind.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.title, Modifier.weight(1f))
                            Switch(answers.values[q.name].bool ?: q.defaultValue.bool ?: false, { onChange(answers.with(q.name, JsonPrimitive(it))) })
                        }
                        FieldKind.Number -> {
                            // the typed text is kept as typed ("1." on the way to "1.5"); the parsed number goes into the answers
                            var typed by remember(q.name) { mutableStateOf(answers.values[q.name].double?.let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() } ?: "") }
                            OutlinedTextField(
                                typed, { t -> typed = t; onChange(answers.with(q.name, t.toDoubleOrNull()?.let { JsonPrimitive(it) })) },
                                Modifier.fillMaxWidth(), label = { Text(q.detail ?: q.title) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                            )
                        }
                        FieldKind.Text -> OutlinedTextField(answers.text(q), { onChange(answers.with(q.name, JsonPrimitive(it))) }, Modifier.fillMaxWidth(), label = { Text(q.detail ?: q.title) })
                    }
                    spec.custom(q)?.let { c ->
                        OutlinedTextField(answers.text(c), { onChange(answers.with(c.name, JsonPrimitive(it))) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.other_answer_optional)) })
                    }
                }
            }
          }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = skip, Modifier.weight(1f)) { Text(stringResource(R.string.skip)) }
                Button(onClick = submit, Modifier.weight(1f)) { Text(stringResource(R.string.submit), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// MARK: subagents

/** Subagents still running, docked above the composer; each row opens that subagent's transcript (ios SubagentsTray). */
@Composable
fun SubagentsTray(agents: List<SubagentItem>, collapsed: Boolean, onOpen: (String) -> Unit) {
    val shown = 3
    TrayShell(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.heightIn(min = 32.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { ActivitySpinner(11.dp) }
                Spacer(Modifier.width(8.dp))
                Text(pluralStringResource(R.plurals.agents_running, agents.size, agents.size), style = MaterialTheme.typography.titleSmall)
            }
            if (!collapsed) {
                agents.take(shown).forEach { SubagentStepRow(it, onOpen) }
                if (agents.size > shown) Text(stringResource(R.string.more_count, agents.size - shown), Modifier.padding(start = 30.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
