package dev.leash.personal.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.PendingRequest
import dev.leash.personal.core.SubagentItem
import dev.leash.personal.core.ToolItem
import dev.leash.personal.core.bool
import dev.leash.personal.core.double
import dev.leash.personal.core.get
import dev.leash.personal.core.string
import dev.leash.personal.ui.components.pluralText
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.tabular
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.CommandPreview
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LeashSheet
import dev.leash.personal.ui.design.components.LeashSwitch
import dev.leash.personal.ui.design.components.OptionRow as OptionRowView
import dev.leash.personal.ui.design.components.TextArea
import dev.leash.personal.ui.design.components.TrayHeader
import dev.leash.personal.ui.markdown.InlineText
import dev.leash.personal.ui.markdown.rememberInline
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The oldest permission request, else the oldest question, with its "1 of 3" counter. */
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
    val swap = Motion.quick<Float>()
    TrayShell {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val agent = request.subagent
            if (showsAgent && agent != null) SubagentAttribution(agent.label, request.sessionId?.let { root -> { onOpenSubagent(root, agent.sessionId) } })
            // the glass stays put while the next request replaces this one inside it
            AnimatedContent(
                request, contentKey = { it.key },
                transitionSpec = { androidx.compose.animation.fadeIn(swap) togetherWith androidx.compose.animation.fadeOut(swap) }, label = "request",
            ) { r ->
                if (r.isPermission) ApprovalBody(model, r, index, total) else QuestionBody(model, r, index, total)
            }
        }
    }
}

/**
 * What is waiting on the user in a conversation (or in one subagent's lineage): the oldest permission request, else the oldest
 * question. Draws nothing when there is none.
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
    val c = Leash.colors
    Row(
        Modifier.then(if (open != null) Modifier.pressable(scale = PressScale.Text, onClick = open) else Modifier).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(LeashIcons.subagent, null, Modifier.size(14.dp), tint = c.textSecondary)
        Text(label.ifEmpty { stringResource(R.string.subagent_unnamed) }, style = Leash.type.caption.medium(), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        if (open != null) Icon(LeashIcons.chevronRight, null, Modifier.size(12.dp), tint = c.textTertiary)
    }
}

@Composable
private fun counter(index: Int, total: Int): String? = if (total > 1) stringResource(R.string.request_of, index, total) else null

@Composable
private fun permissionName(o: PermOption): String = when (o.kind) {
    "allow_once" -> stringResource(R.string.allow)
    "allow_always" -> stringResource(R.string.always_allow)
    "reject_once" -> stringResource(R.string.deny)
    "reject_always" -> stringResource(R.string.always_deny)
    else -> o.name
}

// MARK: permission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalBody(model: AppModel, request: PendingRequest, index: Int, total: Int) {
    val c = Leash.colors
    val tool = remember(request.key) { ToolItem(request.params["toolCall"]["toolCallId"].string ?: "?").also { it.apply(request.params["toolCall"] ?: JsonNull) } }
    val buttons = remember(request.key) { splitPermissionOptions(permissionOptions(request.params)) }
    var showDiff by remember(request.key) { mutableStateOf(false) }
    val diff = tool.firstDiff
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TrayHeader(tool.title, LeashIcons.needsYou, c.attention, counter = counter(index, total))
        val cmd = tool.commandPreview
        if (cmd != null && (cmd != tool.title || tool.kind == "execute")) CommandPreview(cmd)
        if (diff != null) Row(
            Modifier.pressable(scale = PressScale.Text) { showDiff = true }.heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(LeashIcons.file, null, Modifier.size(16.dp), tint = c.textSecondary)
            Text(diff.path.substringAfterLast('/'), style = Leash.type.mono, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("+" + tool.diffAdds, style = Leash.type.monoSmall.tabular(), color = c.success)
            Text("−" + tool.diffDels, style = Leash.type.monoSmall.tabular(), color = c.danger)
            Text("· " + stringResource(R.string.view_diff), style = Leash.type.footnote.medium(), color = c.accent)
        }
        // side by side when they fit (at most two across); at large text sizes or with more they stack, the primary action first
        PermissionButtonsRow(buttons, stacked = LocalDensity.current.fontScale > 1.15f || buttons.all.size > 2) { o -> model.answerPermission(request, o.id, o.kind) }
    }
    if (showDiff && diff != null) LeashSheet(
        onDismiss = { showDiff = false }, title = diff.path.substringAfterLast('/'),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            DiffBlock(diff.path, diff.old, diff.new, maxLines = 400)
        }
    }
}

@Composable
private fun PermissionButtonsRow(buttons: PermissionButtons, stacked: Boolean, answer: (PermOption) -> Unit) {
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in buttons.allow) LeashButton(permissionName(o), { answer(o) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), style = ButtonStyle.Prominent)
        for (o in buttons.always) LeashButton(permissionName(o), { answer(o) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), style = ButtonStyle.Tinted)
        for (o in buttons.deny) LeashButton(permissionName(o), { answer(o) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), style = ButtonStyle.Destructive)
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in buttons.deny) LeashButton(permissionName(o), { answer(o) }, Modifier.weight(1f).heightIn(min = 48.dp), style = ButtonStyle.Destructive)
        for (o in buttons.always) LeashButton(permissionName(o), { answer(o) }, Modifier.weight(1f).heightIn(min = 48.dp), style = ButtonStyle.Tinted)
        for (o in buttons.allow) LeashButton(permissionName(o), { answer(o) }, Modifier.weight(1f).heightIn(min = 48.dp), style = ButtonStyle.Prominent)
    }
}

// MARK: question

@Composable
private fun QuestionBody(model: AppModel, request: PendingRequest, index: Int, total: Int) {
    val c = Leash.colors
    val spec = remember(request.key) { ElicitationSpec.of(request) }
    var answers by remember(request.key) { mutableStateOf(ElicitationAnswers()) }
    var showSheet by remember(request.key) { mutableStateOf(false) }
    val open = LocalOpenFile.current
    val uri = LocalUriHandler.current
    val skip = { showSheet = false; model.answerElicitation(request, "decline") }
    val submit = { showSheet = false; model.answerElicitation(request, "accept", answers.content(spec)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TrayHeader(stringResource(R.string.agent_asks), LeashIcons.question, c.accent, counter = counter(index, total))
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            spec.message?.let { InlineText(rememberInline(it, open), Leash.type.callout, c.textPrimary) }
            val url = spec.url
            val q = spec.questions.firstOrNull()
            if (url != null) {
                Row(
                    Modifier.pressable(scale = PressScale.Text) { uri.openUri(url) }.heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(LeashIcons.openExternal, stringResource(R.string.open_link), Modifier.size(16.dp), tint = c.accent)
                    Text(url, style = Leash.type.footnote, color = c.accent, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            } else if (spec.fitsTray && q != null) {
                val kind = q.kind
                if (kind is FieldKind.Single) OptionList(q, kind.choices, answers) { answers = it }
                if (kind is FieldKind.Multi) OptionList(q, kind.choices, answers) { answers = it }
                if (spec.custom(q) != null) LeashButton(stringResource(R.string.other_answer), { showSheet = true }, style = ButtonStyle.Plain, size = ButtonSize.Small)
            } else {
                val n = spec.questions.size
                Text(pluralText(R.plurals.questions_count, n), style = Leash.type.subheadline, color = c.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spec.url != null) {
                LeashButton(stringResource(R.string.decline), { model.answerElicitation(request, "decline") }, Modifier.weight(1f).heightIn(min = 48.dp), style = ButtonStyle.Tinted)
                LeashButton(stringResource(R.string.done), { model.answerElicitation(request, "accept") }, Modifier.weight(1f))
            } else {
                LeashButton(stringResource(R.string.skip), skip, Modifier.weight(1f).heightIn(min = 48.dp), style = ButtonStyle.Tinted)
                if (spec.fitsTray) LeashButton(stringResource(R.string.submit), submit, Modifier.weight(1f), enabled = answers.hasAnswer)
                else LeashButton(stringResource(R.string.answer_ellipsis), { showSheet = true }, Modifier.weight(1f))
            }
        }
    }
    if (showSheet) QuestionSheet(spec, answers, { answers = it }, submit, skip) { showSheet = false }
}

@Composable
private fun OptionList(q: ElicitField, choices: List<Choice>, answers: ElicitationAnswers, onChange: (ElicitationAnswers) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { ch ->
            OptionRowView(ch.title, answers.isSelected(q, ch), { onChange(answers.toggle(q, ch)) }, detail = ch.detail?.takeIf { it.isNotEmpty() }, multi = q.kind is FieldKind.Multi)
        }
    }
}

/** Every question at once, with the keyboard for "Other" and free-text fields. Skip and Submit stay pinned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionSheet(spec: ElicitationSpec, answers: ElicitationAnswers, onChange: (ElicitationAnswers) -> Unit, submit: () -> Unit, skip: () -> Unit, dismiss: () -> Unit) {
    val c = Leash.colors
    val open = LocalOpenFile.current
    LeashSheet(onDismiss = dismiss, title = stringResource(R.string.agent_asks), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // the answers scroll; Skip and Submit stay in reach
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                spec.message?.let { InlineText(rememberInline(it, open), Leash.type.reading, c.textPrimary) }
                spec.questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(q.title, style = Leash.type.subheadline.semibold(), color = c.textPrimary)
                        if (!q.detail.isNullOrEmpty() && spec.questions.size > 1) Text(q.detail, style = Leash.type.footnote, color = c.textSecondary)
                        when (val k = q.kind) {
                            is FieldKind.Single -> OptionList(q, k.choices, answers, onChange)
                            is FieldKind.Multi -> OptionList(q, k.choices, answers, onChange)
                            FieldKind.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(q.title, Modifier.weight(1f), style = Leash.type.body, color = c.textPrimary)
                                LeashSwitch(answers.values[q.name].bool ?: q.defaultValue.bool ?: false, { onChange(answers.with(q.name, JsonPrimitive(it))) })
                            }
                            FieldKind.Number -> {
                                // the typed text is kept as typed ("1." on the way to "1.5"); the parsed number goes into the answers
                                var typed by remember(q.name) { mutableStateOf(answers.values[q.name].double?.let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() } ?: "") }
                                TextArea(
                                    typed, { t -> typed = t; onChange(answers.with(q.name, t.toDoubleOrNull()?.let { JsonPrimitive(it) })) },
                                    q.detail ?: q.title, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                )
                            }
                            FieldKind.Text -> TextArea(answers.text(q), { onChange(answers.with(q.name, JsonPrimitive(it))) }, q.detail ?: q.title)
                        }
                        spec.custom(q)?.let { cf ->
                            TextArea(answers.text(cf), { onChange(answers.with(cf.name, JsonPrimitive(it))) }, stringResource(R.string.other_answer_optional))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeashButton(stringResource(R.string.skip), skip, Modifier.weight(1f).heightIn(min = 48.dp), style = ButtonStyle.Tinted)
                LeashButton(stringResource(R.string.submit), submit, Modifier.weight(1f))
            }
        }
    }
}

// MARK: subagents

/** Subagents still running, docked above the composer; each row opens that subagent's transcript (ios SubagentsTray). */
@Composable
fun SubagentsTray(agents: List<SubagentItem>, collapsed: Boolean, onOpen: (String) -> Unit) {
    val c = Leash.colors
    val shown = 3
    TrayShell(contentPadding = PaddingValues(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.heightIn(min = 32.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { Spinner(size = 11.dp) }
                Text(pluralText(R.plurals.agents_running, agents.size), style = Leash.type.subheadline.semibold(), color = c.textPrimary)
            }
            if (!collapsed) {
                agents.take(shown).forEach { SubagentStepRow(it, onOpen) }
                if (agents.size > shown) Text(stringResource(R.string.more_count, agents.size - shown), Modifier.padding(start = 30.dp, top = 4.dp, bottom = 4.dp), style = Leash.type.footnote, color = c.textSecondary)
            }
        }
    }
}
