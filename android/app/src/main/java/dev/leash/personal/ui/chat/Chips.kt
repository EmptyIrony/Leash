package dev.leash.personal.ui.chat

import androidx.compose.foundation.background
import androidx.compose.ui.draw.dropShadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.text.quotaChipLabel
import dev.leash.personal.text.quotaShortLabel
import dev.leash.personal.text.res
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.ChatStore
import dev.leash.personal.core.ConfigChoice
import dev.leash.personal.core.ConfigOption
import dev.leash.personal.core.QuotaFormat
import dev.leash.personal.core.QuotaInfo
import dev.leash.personal.ui.quota.QuotaSheet
import dev.leash.personal.ui.quota.quotaRingTint
import dev.leash.personal.ui.quota.quotaTint
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.leash.personal.core.j
import dev.leash.personal.core.string
import dev.leash.personal.ui.components.ObserveChat
import dev.leash.personal.ui.components.Provider
import dev.leash.personal.ui.components.ProviderIcon
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.RingGauge
import dev.leash.personal.ui.design.tabular
import dev.leash.personal.ui.design.components.Chip
import dev.leash.personal.ui.design.components.ChipRow as ChipRowView
import dev.leash.personal.ui.design.components.GroupCard
import dev.leash.personal.ui.design.components.GroupRow
import dev.leash.personal.ui.design.components.LeashDialog
import dev.leash.personal.ui.design.components.LeashMenu
import dev.leash.personal.ui.design.components.LeashMenuItem
import dev.leash.personal.ui.design.components.LeashSheet
import dev.leash.personal.ui.design.components.SectionHeader

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
    "accept-edits", "acceptEdits" -> LeashIcons.modeEdits
    "bypassPermissions", "dontAsk" -> LeashIcons.modeBypass
    "auto" -> LeashIcons.modeAuto
    "plan" -> LeashIcons.modePlan
    "turbo" -> LeashIcons.turbo
    else -> LeashIcons.modeAsk
}

private fun shortModel(n: String) = n.replace("Gemini 4 ", "").replace("Claude ", "").replace("Gemini ", "")

@Composable
private fun providerName(p: Provider): String = when (p) {
    Provider.Gemini -> "Gemini"
    Provider.Anthropic -> "Anthropic"
    Provider.OpenAI -> "OpenAI"
    Provider.Other -> stringResource(R.string.provider_other)
}

/**
 * The selectors under the composer: model, thinking level, permission mode, quota, context usage. [confirmApproveAll] asks once before an approve-everything mode is turned on.
 */
@Composable
fun ChipRow(model: AppModel, store: ChatStore, enabled: Boolean) {
    ObserveChat()
    val state by model.state.collectAsStateWithLifecycle()
    val c = Leash.colors
    val agent = store.agent
    var modelSheet by remember { mutableStateOf(false) }
    var effortSheet by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var contextMenu by remember { mutableStateOf(false) }
    var quotaSheet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    ChipRowView {
        store.modelOption?.let { opt ->
            Chip(
                shortModel(opt.currentName), { modelSheet = true },
                leading = { ProviderIcon(if (agent == "claude") Provider.Anthropic else Provider.of(opt.current.string), 14.dp) },
            )
            if (modelSheet) ModelSheet(opt, agent, state.quotas[agent], onSelect = { v -> model.setConfig(store, opt, j(v)); model.hooks.haptic(dev.leash.personal.core.Haptic.Selection) }) { modelSheet = false }
        }
        store.effortOption?.let { opt ->
            Chip(effortName(opt.current.string ?: "", opt.currentName), { effortSheet = true }, icon = LeashIcons.effort, chevron = false)
            if (effortSheet) EffortSheet(opt, onSelect = { v -> model.setConfig(store, opt, j(v)) }) { effortSheet = false }
        }
        // mode
        val choices: List<ConfigChoice> = store.modeOption?.choices ?: store.modes.map { ConfigChoice(it.id, it.name, null, null) }
        if (choices.isNotEmpty()) {
            val turbo = state.hasTurbo(agent)
            val current = if (store.autoApprove && turbo) "turbo" else (store.modeOption?.current.string ?: store.currentMode ?: "default")
            val approveAll = AppModel.approvesAll(current)
            val tint = if (approveAll) c.turbo else if (current == "plan") c.running else Color.Unspecified
            val choose = { v: String -> modeMenu = false; if (model.needsApproveAllConfirmation(v)) confirm = v else model.choose(v, store) }
            Box {
                Chip(
                    modeName(current, agent, choices.firstOrNull { it.value == current }?.name ?: stringResource(R.string.mode)), { modeMenu = true },
                    icon = modeIcon(current), tint = tint, ink = if (tint == Color.Unspecified) c.textPrimary else tint,
                )
                LeashMenu(modeMenu, { modeMenu = false }) {
                    choices.forEach { ch ->
                        LeashMenuItem(modeName(ch.value, agent, ch.name), { choose(ch.value) }, icon = modeIcon(ch.value), checkable = true, checked = ch.value == current)
                    }
                    // Antigravity only: Claude Code's own list already has Bypass permissions
                    if (turbo) LeashMenuItem(stringResource(R.string.mode_turbo_all), { choose("turbo") }, icon = modeIcon("turbo"), checkable = true, checked = current == "turbo")
                }
            }
            confirm?.let { mode ->
                val bypass = mode == "bypassPermissions"
                LeashDialog(
                    onDismiss = { confirm = null },
                    title = stringResource(if (bypass) R.string.confirm_bypass_title else R.string.confirm_turbo_title),
                    message = stringResource(if (bypass) R.string.confirm_bypass_text else R.string.confirm_turbo_text),
                    confirmLabel = stringResource(if (bypass) R.string.confirm_bypass_action else R.string.confirm_turbo_action),
                    onConfirm = { model.confirmApproveAll(mode); confirm = null; model.choose(mode, store) },
                    dismissLabel = stringResource(R.string.cancel),
                    destructive = true,
                )
            }
        }
        // quota: what is left of the pool the selected model draws from (its tightest window)
        if (state.isConnected) {
            val limit = state.quotas[agent]?.limiting(store.modelOption?.current.string, agent)
            val b = limit?.second
            val f = b?.fraction
            val tight = QuotaFormat.isTight(f)
            val desc = limit?.first?.let { stringResource(R.string.quota_rings_desc, it.label, QuotaFormat.percent(f ?: 0.0)) } ?: stringResource(R.string.quota_chip)
            Chip(
                if (b != null && f != null) res().quotaChipLabel(b.window) + " " + QuotaFormat.percent(f) + "%" else stringResource(R.string.quota_chip),
                { quotaSheet = true },
                modifier = Modifier.semantics { contentDescription = desc },
                leading = { RingGauge(f?.toFloat(), size = 14.dp, tint = quotaRingTint(f)) },
                ink = if (tight) quotaTint(f) else if (f != null) c.textPrimary else c.textSecondary, chevron = false,
            )
            if (quotaSheet) QuotaSheet(model) { quotaSheet = false }
        }
        // context window
        store.usage?.takeIf { it.second > 0 }?.let { (used, size) ->
            val f = used.toDouble() / size
            Box {
                Chip(
                    "" + Math.round(f * 100) + "%", { contextMenu = true },
                    leading = { RingGauge(f.toFloat(), size = 14.dp, tint = if (f > 0.85) c.danger else if (f > 0.6) c.attention else c.accent) },
                    ink = if (f > 0.8) c.attention else c.textPrimary, chevron = false,
                )
                LeashMenu(contextMenu, { contextMenu = false }) {
                    LeashMenuItem(stringResource(R.string.context_usage, compactNumber(used), compactNumber(size)), { contextMenu = false }, icon = LeashIcons.context)
                }
            }
        }
    }
}

/** The model picker, grouped by provider, each group header with what is left of its quota (ios ModelPicker). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(option: ConfigOption, agent: String, quota: QuotaInfo?, onSelect: (String) -> Unit, dismiss: () -> Unit) {
    val c = Leash.colors
    fun providerOf(ch: ConfigChoice) = if (agent == "claude") Provider.Anthropic else Provider.of(ch.value)
    val groups = listOf(Provider.Gemini, Provider.Anthropic, Provider.OpenAI, Provider.Other).mapNotNull { p -> option.choices.filter { providerOf(it) == p }.takeIf { it.isNotEmpty() }?.let { p to it } }
    LeashSheet(onDismiss = dismiss, title = stringResource(R.string.model)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            groups.forEach { (p, choices) ->
                // the quota of the pool this group's models draw from
                val limit = quota?.limiting(if (agent == "claude") option.current.string else QuotaFormat.poolHint(p.name.lowercase()), agent)
                SectionHeader(
                    providerName(p), leading = { ProviderIcon(p, 16.dp) },
                    trailing = limit?.second?.let { b ->
                        val f = b.fraction
                        if (f == null) null else ({
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RingGauge(f.toFloat(), size = 11.dp, stroke = 1.6.dp, tint = quotaRingTint(f))
                                Text(res().quotaShortLabel(b.window) + " " + QuotaFormat.percent(f) + "%", style = Leash.type.caption2.tabular(), color = if (QuotaFormat.isTight(f)) quotaTint(f) else c.textTertiary)
                            }
                        })
                    },
                )
                GroupCard {
                    choices.forEachIndexed { i, ch ->
                        val selected = ch.value == option.current.string
                        GroupRow(last = i == choices.lastIndex, separatorInset = 48.dp, onClick = { onSelect(ch.value); dismiss() }) {
                            ProviderIcon(p, 20.dp)
                            Column(Modifier.weight(1f).padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(ch.name, style = Leash.type.body, color = c.textPrimary)
                                if (!ch.description.isNullOrEmpty()) Text(ch.description, style = Leash.type.footnote, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected) Icon(LeashIcons.check, null, Modifier.size(20.dp), tint = c.accent)
                        }
                    }
                }
                Box(Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** The thinking level: a slider with a detent per level (ios EffortPopover). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EffortSheet(option: ConfigOption, onSelect: (String) -> Unit, dismiss: () -> Unit) {
    val c = Leash.colors
    val levels = option.choices
    var index by remember { mutableFloatStateOf((levels.indexOfFirst { it.value == option.current.string }.coerceAtLeast(0)).toFloat()) }
    val level = levels.getOrNull(index.toInt())
    LeashSheet(onDismiss = dismiss, title = stringResource(R.string.thinking)) {
        Column(Modifier.navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LeashIcons.effort, null, Modifier.size(20.dp), tint = c.accent)
                        Text(level?.let { effortName(it.value, it.name) } ?: "", style = Leash.type.title3, color = c.textPrimary, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                    if (levels.size > 1) Slider(
                        value = index, onValueChange = { index = it }, valueRange = 0f..(levels.size - 1).toFloat(), steps = (levels.size - 2).coerceAtLeast(0),
                        onValueChangeFinished = { levels.getOrNull(index.toInt())?.let { onSelect(it.value) } },
                        // iOS's round white thumb instead of Material's bar
                        thumb = {
                            Box(
                                Modifier.size(26.dp)
                                    .dropShadow(androidx.compose.foundation.shape.CircleShape, androidx.compose.ui.graphics.shadow.Shadow(4.dp, androidx.compose.ui.graphics.Color.Black, offset = androidx.compose.ui.unit.DpOffset(0.dp, 1.dp), alpha = 0.2f))
                                    .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.CircleShape),
                            )
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = c.accentControl, activeTrackColor = c.accentControl, inactiveTrackColor = c.controlFill,
                            activeTickColor = c.onAccent.copy(alpha = 0.6f), inactiveTickColor = c.textTertiary,
                        ),
                    )
                    Row {
                        Text(levels.firstOrNull()?.let { effortName(it.value, it.name) } ?: "", style = Leash.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                        Text(levels.lastOrNull()?.let { effortName(it.value, it.name) } ?: "", style = Leash.type.caption, color = c.textSecondary)
                    }
                }
            }
            Text(effortBlurb(level?.value ?: ""), style = Leash.type.subheadline, color = c.textSecondary, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}
