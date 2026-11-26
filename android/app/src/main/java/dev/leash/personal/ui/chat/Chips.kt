package dev.leash.personal.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.ui.components.ObserveChat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.ChatStore
import dev.leash.personal.core.ConfigChoice
import dev.leash.personal.core.ConfigOption
import dev.leash.personal.core.SessionMode
import dev.leash.personal.core.j
import dev.leash.personal.core.string
import dev.leash.personal.ui.components.Provider
import dev.leash.personal.ui.components.ProviderIcon
import dev.leash.personal.ui.theme.LeashRadius
import dev.leash.personal.ui.theme.LeashTheme

/** Each CLI's own names for its permission modes (both agents send English names). */
@Composable
fun modeName(id: String, agent: String, fallback: String): String {
    val res = if (agent == "claude") when (id) {
        "default" -> R.string.mode_manual
        "acceptEdits" -> R.string.mode_accept_edits
        "plan" -> R.string.mode_plan
        "auto" -> R.string.mode_auto
        "dontAsk" -> R.string.mode_dont_ask
        "bypassPermissions" -> R.string.mode_bypass
        else -> null
    } else when (id) {
        "default" -> R.string.mode_default
        "accept-edits" -> R.string.mode_accept_edits
        "auto" -> R.string.mode_auto
        "plan" -> R.string.mode_plan
        "turbo" -> R.string.mode_turbo
        else -> null
    }
    return res?.let { stringResource(it) } ?: fallback
}

@Composable
fun effortName(id: String, fallback: String): String = when (id) {
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_extra)
    "max" -> stringResource(R.string.effort_max)
    "default" -> stringResource(R.string.effort_default)
    else -> fallback
}

@Composable
private fun effortBlurb(id: String): String = when (id) {
    "default" -> stringResource(R.string.effort_blurb_default)
    "low" -> stringResource(R.string.effort_blurb_low)
    "medium" -> stringResource(R.string.effort_blurb_medium)
    "high" -> stringResource(R.string.effort_blurb_high)
    "xhigh", "max" -> stringResource(R.string.effort_blurb_max)
    else -> ""
}

private fun modeIcon(v: String): ImageVector = when (v) {
    "accept-edits", "acceptEdits" -> Icons.Filled.EditNote
    "bypassPermissions" -> Icons.Filled.Shield
    "dontAsk" -> Icons.Filled.Block
    "auto" -> Icons.Filled.AutoFixHigh
    "plan" -> Icons.Filled.Checklist
    "turbo" -> Icons.Filled.Bolt
    else -> Icons.Filled.PanTool
}

private fun shortModel(n: String) = n.replace("Gemini 4 ", "").replace("Claude ", "").replace("Gemini ", "")

@Composable
private fun providerName(p: Provider): String = when (p) {
    Provider.Gemini -> "Gemini"
    Provider.Anthropic -> "Anthropic"
    Provider.OpenAI -> "OpenAI"
    Provider.Other -> stringResource(R.string.provider_other)
}

/** A capsule that opens a picker (ios ChipLabel in a glass capsule). */
@Composable
private fun Chip(onClick: () -> Unit, tint: Color = Color.Unspecified, container: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape, color = container,
        border = BorderStroke(0.5.dp, LeashTheme.colors.hairline),
        contentColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
    ) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { content() }
    }
}

/**
 * The selectors under the composer: model, thinking level, permission mode, context usage (quota comes with the quota sheet in a
 * later phase). [confirmApproveAll] asks once before an approve-everything mode is turned on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipRow(model: AppModel, store: ChatStore, enabled: Boolean) {
    ObserveChat()
    val state by model.state.collectAsStateWithLifecycle()
    val colors = LeashTheme.colors
    val agent = store.agent
    var modelSheet by remember { mutableStateOf(false) }
    var effortSheet by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var contextMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        store.modelOption?.let { opt ->
            Chip(onClick = { modelSheet = true }) {
                ProviderIcon(if (agent == "claude") Provider.Anthropic else Provider.of(opt.current.string), 15.dp)
                Text(shortModel(opt.currentName), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ExpandMore, null, Modifier.size(14.dp))
            }
            if (modelSheet) ModelSheet(opt, agent, onSelect = { v -> model.setConfig(store, opt, j(v)); model.hooks.haptic(dev.leash.personal.core.Haptic.Selection) }) { modelSheet = false }
        }
        store.effortOption?.let { opt ->
            Chip(onClick = { effortSheet = true }) {
                Icon(Icons.Filled.Speed, null, Modifier.size(16.dp))
                Text(effortName(opt.current.string ?: "", opt.currentName), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            if (effortSheet) EffortSheet(opt, onSelect = { v -> model.setConfig(store, opt, j(v)) }) { effortSheet = false }
        }
        // mode
        val choices: List<ConfigChoice> = store.modeOption?.choices ?: store.modes.map { ConfigChoice(it.id, it.name, null, null) }
        if (choices.isNotEmpty()) {
            val turbo = state.hasTurbo(agent)
            val current = if (store.autoApprove && turbo) "turbo" else (store.modeOption?.current.string ?: store.currentMode ?: "default")
            val approveAll = AppModel.approvesAll(current)
            val tint = if (approveAll) colors.turbo else if (current == "plan") colors.running else Color.Unspecified
            val choose = { v: String -> modeMenu = false; if (model.needsApproveAllConfirmation(v)) confirm = v else model.choose(v, store) }
            Box {
                Chip(onClick = { modeMenu = true }, tint = tint, container = if (approveAll) colors.turbo.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh) else MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(modeIcon(current), null, Modifier.size(16.dp))
                    Text(modeName(current, agent, choices.firstOrNull { it.value == current }?.name ?: stringResource(R.string.mode)), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Icon(Icons.Filled.ExpandMore, null, Modifier.size(14.dp))
                }
                DropdownMenu(modeMenu, { modeMenu = false }) {
                    choices.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(modeName(c.value, agent, c.name)) },
                            leadingIcon = { Icon(modeIcon(c.value), null) },
                            trailingIcon = { if (c.value == current) Icon(Icons.Filled.Check, null) },
                            onClick = { choose(c.value) },
                        )
                    }
                    // Antigravity only: Claude Code's own list already has Bypass permissions
                    if (turbo) DropdownMenuItem(
                        text = { Text(stringResource(R.string.mode_turbo_all)) },
                        leadingIcon = { Icon(modeIcon("turbo"), null) },
                        trailingIcon = { if (current == "turbo") Icon(Icons.Filled.Check, null) },
                        onClick = { choose("turbo") },
                    )
                }
            }
            confirm?.let { mode ->
                val bypass = mode == "bypassPermissions"
                AlertDialog(
                    onDismissRequest = { confirm = null },
                    title = { Text(stringResource(if (bypass) R.string.confirm_bypass_title else R.string.confirm_turbo_title)) },
                    text = { Text(stringResource(if (bypass) R.string.confirm_bypass_text else R.string.confirm_turbo_text)) },
                    confirmButton = {
                        TextButton(onClick = { model.confirmApproveAll(mode); confirm = null; model.choose(mode, store) }) {
                            Text(stringResource(if (bypass) R.string.confirm_bypass_action else R.string.confirm_turbo_action), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
        // context window
        store.usage?.takeIf { it.second > 0 }?.let { (used, size) ->
            val f = used.toDouble() / size
            Box {
                Chip(onClick = { contextMenu = true }, tint = if (f > 0.8) colors.attention else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Icon(Icons.Filled.TextFields, null, Modifier.size(14.dp))
                    Text("" + Math.round(f * 100) + "%", style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(contextMenu, { contextMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.context_usage, compactNumber(used), compactNumber(size))) }, onClick = { contextMenu = false })
                }
            }
        }
    }
}

/** The model picker, grouped by provider (ios ModelPicker; the quota in the group headers comes with the quota sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(option: ConfigOption, agent: String, onSelect: (String) -> Unit, dismiss: () -> Unit) {
    fun providerOf(c: ConfigChoice) = if (agent == "claude") Provider.Anthropic else Provider.of(c.value)
    val groups = listOf(Provider.Gemini, Provider.Anthropic, Provider.OpenAI, Provider.Other).mapNotNull { p -> option.choices.filter { providerOf(it) == p }.takeIf { it.isNotEmpty() }?.let { p to it } }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            groups.forEach { (p, choices) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProviderIcon(p, 15.dp)
                        Text(providerName(p), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    choices.forEach { c ->
                        val selected = c.value == option.current.string
                        Row(
                            Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(LeashRadius.row))
                                .clickable { onSelect(c.value); dismiss() }.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ProviderIcon(p, 18.dp)
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                if (!c.description.isNullOrEmpty()) Text(c.description, style = MaterialTheme.typography.bodySmall, color = LeashTheme.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** The thinking level: a slider with a detent per level (ios EffortPopover). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortSheet(option: ConfigOption, onSelect: (String) -> Unit, dismiss: () -> Unit) {
    val levels = option.choices
    var index by remember { mutableFloatStateOf((levels.indexOfFirst { it.value == option.current.string }.coerceAtLeast(0)).toFloat()) }
    val level = levels.getOrNull(index.toInt())
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.thinking), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(level?.let { effortName(it.value, it.name) } ?: "", style = MaterialTheme.typography.titleMedium)
            }
            if (levels.size > 1) Slider(
                value = index, onValueChange = { index = it }, valueRange = 0f..(levels.size - 1).toFloat(), steps = (levels.size - 2).coerceAtLeast(0),
                onValueChangeFinished = { levels.getOrNull(index.toInt())?.let { onSelect(it.value) } },
                colors = androidx.compose.material3.SliderDefaults.colors(inactiveTrackColor = LeashTheme.colors.hairline),
            )
            Row {
                Text(levels.firstOrNull()?.let { effortName(it.value, it.name) } ?: "", style = MaterialTheme.typography.labelSmall, color = LeashTheme.colors.textTertiary, modifier = Modifier.weight(1f))
                Text(levels.lastOrNull()?.let { effortName(it.value, it.name) } ?: "", style = MaterialTheme.typography.labelSmall, color = LeashTheme.colors.textTertiary)
            }
            Text(effortBlurb(level?.value ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
