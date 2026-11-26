package dev.leash.personal.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
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
import dev.leash.personal.ui.components.StatusDot
import dev.leash.personal.ui.components.color
import dev.leash.personal.ui.components.connectionStatus
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.coroutines.launch

internal fun hasCamera(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** Pairing (QR, paste, manual), the bridge's status and reconnect (ios SettingsView; the Lock Screen and Watch sections have no Android counterpart yet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit, onScan: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section(stringResource(R.string.pair_section)) {
                if (hasCamera(context)) Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_qr))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                            val info = text?.let { parsePairingLink(it) }
                            if (info == null) error = context.getString(R.string.clipboard_no_link) else { error = null; model.pair(info) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.paste_link)) }
                Text(stringResource(R.string.pair_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section(stringResource(R.string.bridge_section)) {
                OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.address)) }, placeholder = { Text(stringResource(R.string.address_hint)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.token)) }, singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showToken = !showToken }) { Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (showToken) R.string.hide_token else R.string.show_token)) } })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        val p = port.toIntOrNull()
                        if (host.isBlank() || token.isBlank() || p == null || p !in 1..65535) error = context.getString(R.string.enter_address_token)
                        else { error = null; model.setConnection(host, p, token) }
                    },
                    enabled = host.isNotBlank() && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.save_connect)) }
            }
            if (state.isConfigured) Section(stringResource(R.string.status_section)) {
                val st = connectionStatus(state)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.connection), Modifier.weight(1f))
                    Surface(shape = MaterialTheme.shapes.large, color = LeashTheme.colors.hairline) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(st.kind.color(), 8.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(st.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                (state.connection as? LinkState.Waiting)?.let { Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                (state.connection as? LinkState.Failed)?.let { Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                state.agents.forEach { a ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgentIcon(a.id, 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(a.label, Modifier.weight(1f))
                            Text(a.state, color = if (a.isReady) LeashTheme.colors.success else LeashTheme.colors.attention)
                        }
                        a.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LeashTheme.colors.textTertiary) }
                        a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (state.agents.isEmpty() && state.isConnected) {
                    Row { Text(stringResource(R.string.agent_none), Modifier.weight(1f)); Text(state.agentState) }
                    state.agentError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                OutlinedButton(onClick = { model.reconfigure() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reconnect)) }
                state.agents.forEach { a ->
                    OutlinedButton(onClick = { model.restartAgent(a.id) }, enabled = state.isConnected, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restart_agent, a.label)) }
                }
            }
            NotificationsSection(model, state.keepAlive)
            if (model.demoAvailable) Section(null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.demo_bridge), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.demo_bridge_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.useDemo, onCheckedChange = { model.setUseDemo(it) })
                }
            }
            if (state.host.isNotEmpty() || state.token.isNotEmpty()) TextButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.forget_bridge), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text(stringResource(R.string.forget_bridge_title)) },
        text = { Text(stringResource(R.string.forget_bridge_text)) },
        confirmButton = { TextButton(onClick = { confirmForget = false; model.unpair(); host = ""; token = ""; port = "8787" }) { Text(stringResource(R.string.forget), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun Section(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
