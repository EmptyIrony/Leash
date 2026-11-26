package dev.leash.personal.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.TailnetStatus
import dev.leash.personal.ui.components.SectionGap
import dev.leash.personal.ui.components.StatusKind
import dev.leash.personal.ui.components.color
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.components.FieldRow
import dev.leash.personal.ui.design.components.GroupCard
import dev.leash.personal.ui.design.components.LeashDialog
import dev.leash.personal.ui.design.components.SectionFooter
import dev.leash.personal.ui.design.components.SectionHeader
import dev.leash.personal.ui.design.components.StatusPill
import dev.leash.personal.ui.design.components.ToggleRow
import dev.leash.personal.ui.design.components.ValueRow
import dev.leash.personal.ui.design.pressable

/** The label and kind of the node's state, for the status pill (also used by the connection status in Settings). */
@Composable
internal fun tailnetStatusLabel(status: TailnetStatus): Pair<String, StatusKind> = when (status) {
    TailnetStatus.Off, TailnetStatus.Stopped -> stringResource(R.string.tailnet_status_stopped) to StatusKind.Idle
    TailnetStatus.Connecting -> stringResource(R.string.tailnet_status_connecting) to StatusKind.Warn
    is TailnetStatus.Connected -> stringResource(R.string.tailnet_status_connected) to StatusKind.Ok
    is TailnetStatus.NeedsKey -> stringResource(R.string.tailnet_status_needs_key) to StatusKind.Warn
    is TailnetStatus.Failed -> stringResource(R.string.tailnet_status_error) to StatusKind.Bad
}

/** Built-in Tailscale: Leash joins the tailnet itself, for the bridge connection only (ios TailnetSettingsSection). */
@Composable
fun TailnetSection(model: AppModel) {
    val tailnet = model.tailnet ?: return
    val t by tailnet.state.collectAsStateWithLifecycle()
    val c = Leash.colors
    // what was typed is not kept across a recreation: the key must not end up in the saved instance state
    var key by remember { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(t.loaded) {
        if (t.loaded && !seeded) { url = t.controlUrl; showAdvanced = t.controlUrl.isNotEmpty(); seeded = true }
    }
    val chevron by animateFloatAsState(if (showAdvanced) 180f else 0f, Motion.disclose(), label = "advanced")

    SectionGap()
    SectionHeader(stringResource(R.string.tailnet_section))
    GroupCard(Modifier.animateContentSize()) {
        if (!t.available) {
            NoteRow(stringResource(R.string.tailnet_unavailable), c.textSecondary)
        } else {
            ToggleRow(stringResource(R.string.tailnet_use), t.enabled, { model.setTailnetEnabled(it) }, last = !t.enabled)
        }
        if (t.enabled) {
            FieldRow(
                stringResource(R.string.tailnet_auth_key), key, { key = it },
                placeholder = stringResource(if (t.keySaved) R.string.tailnet_key_saved else R.string.tailnet_key_hint), mono = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailing = {
                    val label = stringResource(if (showKey) R.string.hide_token else R.string.show_token)
                    Box(Modifier.size(40.dp).pressable(onClickLabel = label) { showKey = !showKey }, contentAlignment = Alignment.Center) {
                        Icon(if (showKey) LeashIcons.hide else LeashIcons.show, label, Modifier.size(20.dp), tint = c.textSecondary)
                    }
                },
            )
            ValueRow(
                stringResource(R.string.tailnet_advanced), onClick = { showAdvanced = !showAdvanced },
                trailing = { Icon(LeashIcons.chevronDown, null, Modifier.size(18.dp).rotate(chevron), tint = c.textTertiary) },
            )
            if (showAdvanced) {
                FieldRow(
                    stringResource(R.string.tailnet_control_url), url, { url = it }, placeholder = stringResource(R.string.tailnet_control_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
            ValueRow(
                stringResource(R.string.tailnet_status),
                trailing = { tailnetStatusLabel(t.status).let { (text, kind) -> StatusPill(text, kind.color()) } },
            )
            when (val s = t.status) {
                is TailnetStatus.Connected -> NoteRow(listOf(s.name, s.address).filter { it.isNotEmpty() }.joinToString("  "), c.textSecondary)
                is TailnetStatus.NeedsKey -> NoteRow(s.reason ?: stringResource(R.string.tailnet_needs_key_detail), c.textSecondary)
                is TailnetStatus.Failed -> NoteRow(s.message, c.danger)
                else -> {}
            }
            val canSave = key.isNotBlank() || t.keySaved
            ValueRow(
                stringResource(R.string.save_connect), labelColor = if (canSave) c.accent else c.textTertiary,
                onClick = if (!canSave) null else ({ model.saveTailnet(key, url); key = "" }),
            )
            ValueRow(stringResource(R.string.tailnet_logout), labelColor = c.danger, onClick = { confirmLogout = true }, last = true)
        }
    }
    SectionFooter(stringResource(if (t.available) R.string.tailnet_detail else R.string.tailnet_unavailable_detail))
    t.logoutNote?.let { SectionFooter(it) }

    if (confirmLogout) LeashDialog(
        onDismiss = { confirmLogout = false },
        title = stringResource(R.string.tailnet_logout_title),
        message = stringResource(R.string.tailnet_logout_text),
        confirmLabel = stringResource(R.string.tailnet_logout),
        onConfirm = { confirmLogout = false; model.tailnetLogout(); key = "" },
        dismissLabel = stringResource(R.string.cancel),
        destructive = true,
    )
}
