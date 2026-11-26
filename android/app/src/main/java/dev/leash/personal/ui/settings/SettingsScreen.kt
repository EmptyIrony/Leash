package dev.leash.personal.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.LinkState
import dev.leash.personal.core.parsePairingLink
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.components.ScrollScreen
import dev.leash.personal.ui.components.SectionGap
import dev.leash.personal.ui.components.color
import dev.leash.personal.ui.components.connectionStatus
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.components.FieldRow
import dev.leash.personal.ui.design.components.GroupCard
import dev.leash.personal.ui.design.components.GroupRow
import dev.leash.personal.ui.design.components.Hairline
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LeashDialog
import dev.leash.personal.ui.design.components.SectionFooter
import dev.leash.personal.ui.design.components.SectionHeader
import dev.leash.personal.ui.design.components.StatusPill
import dev.leash.personal.ui.design.components.ToggleRow
import dev.leash.personal.ui.design.components.ValueRow
import kotlinx.coroutines.launch

internal fun hasCamera(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** Pairing (QR, paste, manual), the bridge's status and reconnect (ios SettingsView; the Lock Screen and Watch sections have no Android counterpart yet). */
@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit, onScan: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val c = Leash.colors
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    // the stored values fill the fields once they are loaded
    LaunchedEffect(state.settingsLoaded) {
        if (state.settingsLoaded && !seeded) { host = state.host; port = state.port.toString(); token = state.token; seeded = true }
    }
    // a pairing (paste, scan, deep link) changes the stored values: show them
    LaunchedEffect(state.host, state.port, state.token) {
        if (state.settingsLoaded) { host = state.host; port = state.port.toString(); token = state.token }
    }

    ScrollScreen(stringResource(R.string.settings_title), onBack) {
        SectionHeader(stringResource(R.string.pair_section))
        GroupCard {
            if (hasCamera(context)) {
                Box(Modifier.padding(12.dp)) { LeashButton(stringResource(R.string.scan_qr), onScan, Modifier.fillMaxWidth(), icon = LeashIcons.scan) }
                Hairline(Modifier.padding(start = 52.dp, end = 16.dp))
            }
            ValueRow(
                stringResource(R.string.paste_link), icon = LeashIcons.paste, labelColor = c.accent, last = true,
                onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        val info = text?.let { parsePairingLink(it) }
                        if (info == null) error = context.getString(R.string.clipboard_no_link) else { error = null; model.pair(info) }
                    }
                },
            )
        }
        SectionFooter(stringResource(R.string.pair_hint))

        SectionGap()
        SectionHeader(stringResource(R.string.bridge_section))
        GroupCard {
            FieldRow(
                stringResource(R.string.address), host, { host = it }, placeholder = stringResource(R.string.address_hint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            FieldRow(
                stringResource(R.string.port), port, { port = it.filter(Char::isDigit).take(5) }, placeholder = "8787",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            FieldRow(
                stringResource(R.string.token), token, { token = it }, mono = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailing = {
                    val label = stringResource(if (showToken) R.string.hide_token else R.string.show_token)
                    Box(Modifier.size(40.dp).pressable(onClickLabel = label) { showToken = !showToken }, contentAlignment = Alignment.Center) {
                        Icon(if (showToken) LeashIcons.hide else LeashIcons.show, label, Modifier.size(20.dp), tint = c.textSecondary)
                    }
                },
            )
            val canSave = host.isNotBlank() && token.isNotBlank()
            ValueRow(
                stringResource(R.string.save_connect), labelColor = if (canSave) c.accent else c.textTertiary, last = true,
                onClick = if (!canSave) null else ({
                    val p = port.toIntOrNull()
                    if (host.isBlank() || token.isBlank() || p == null || p !in 1..65535) error = context.getString(R.string.enter_address_token)
                    else { error = null; model.setConnection(host, p, token) }
                }),
            )
        }
        error?.let { Text(it, style = Leash.type.footnote, color = c.danger, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp)) }

        TailnetSection(model)

        if (state.isConfigured) {
            SectionGap()
            SectionHeader(stringResource(R.string.status_section))
            GroupCard {
                val st = connectionStatus(state)
                ValueRow(stringResource(R.string.connection), trailing = { StatusPill(st.text, st.kind.color(), Modifier.padding(start = 8.dp)) })
                model.tailnet?.let { tn ->
                    val ts by tn.state.collectAsStateWithLifecycle()
                    if (ts.enabled) ValueRow(
                        stringResource(R.string.tailnet_row),
                        trailing = { tailnetStatusLabel(ts.status).let { (text, kind) -> StatusPill(text, kind.color(), Modifier.padding(start = 8.dp)) } },
                    )
                }
                (state.connection as? LinkState.Waiting)?.let { NoteRow(it.reason, c.textSecondary) }
                (state.connection as? LinkState.Failed)?.let { NoteRow(it.reason, c.danger) }
                state.agents.forEach { a ->
                    GroupRow {
                        AgentIcon(a.id, 18.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(a.label, style = Leash.type.body, color = c.textPrimary)
                            a.name?.let { Text(it, style = Leash.type.footnote, color = c.textSecondary) }
                            a.error?.let { Text(it, style = Leash.type.footnote, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                        Text(a.state, style = Leash.type.subheadline, color = if (a.isReady) c.success else c.attention)
                    }
                }
                if (state.agents.isEmpty() && state.isConnected) {
                    ValueRow(stringResource(R.string.agent_none), value = state.agentState)
                    state.agentError?.let { NoteRow(it, c.textSecondary) }
                }
                ValueRow(stringResource(R.string.reconnect), labelColor = c.accent, onClick = { model.reconfigure() }, last = state.agents.isEmpty())
                state.agents.forEachIndexed { i, a ->
                    ValueRow(
                        stringResource(R.string.restart_agent, a.label), labelColor = if (state.isConnected) c.accent else c.textTertiary,
                        onClick = if (state.isConnected) ({ model.restartAgent(a.id) }) else null, last = i == state.agents.lastIndex,
                    )
                }
            }
        }

        NotificationsSection(model, state.keepAlive)

        if (model.demoAvailable) {
            SectionGap()
            GroupCard { ToggleRow(stringResource(R.string.demo_bridge), state.useDemo, { model.setUseDemo(it) }, last = true) }
            SectionFooter(stringResource(R.string.demo_bridge_detail))
        }
        if (state.host.isNotEmpty() || state.token.isNotEmpty()) {
            SectionGap()
            GroupCard { ValueRow(stringResource(R.string.forget_bridge), labelColor = c.danger, onClick = { confirmForget = true }, last = true) }
        }
    }
    if (confirmForget) LeashDialog(
        onDismiss = { confirmForget = false },
        title = stringResource(R.string.forget_bridge_title),
        message = stringResource(R.string.forget_bridge_text),
        confirmLabel = stringResource(R.string.forget),
        onConfirm = { confirmForget = false; model.unpair(); host = ""; token = ""; port = "8787" },
        dismissLabel = stringResource(R.string.cancel),
        destructive = true,
    )
}

/** A line of explanation inside a grouped card. */
@Composable
internal fun NoteRow(text: String, color: androidx.compose.ui.graphics.Color) {
    GroupRow(minHeight = 40.dp) { Text(text, style = Leash.type.footnote, color = color, modifier = Modifier.weight(1f)) }
}
