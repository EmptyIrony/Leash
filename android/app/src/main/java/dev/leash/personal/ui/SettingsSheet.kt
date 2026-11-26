package dev.leash.personal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.parsePairingLink

/** Pairing and connection settings: paste the pairing link, or type the address and token. The QR scanner arrives in phase 2a. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(model: AppModel, onDismiss: () -> Unit) {
    val current = model.state.value
    var host by remember { mutableStateOf(current.host) }
    var port by remember { mutableStateOf(current.port.toString()) }
    var token by remember { mutableStateOf(current.token) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var demo by remember { mutableStateOf(current.useDemo) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        val info = text?.let { parsePairingLink(it) }
                        if (info == null) error = "The clipboard has no leash://pair link" else { error = null; model.pair(info); onDismiss() }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Paste pairing link") }
            OutlinedTextField(host, { host = it }, label = { Text("Mac address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val p = port.toIntOrNull()
                        if (host.isBlank() || token.isBlank() || p == null || p !in 1..65535) error = "Enter the address, a port and the token"
                        else { error = null; model.setConnection(host, p, token); onDismiss() }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Connect") }
                OutlinedButton(onClick = { model.unpair(); host = ""; token = ""; onDismiss() }, modifier = Modifier.weight(1f)) { Text("Forget Mac") }
            }
            if (model.demoAvailable) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Demo bridge", style = MaterialTheme.typography.titleMedium)
                        Text("Scripted conversations, no Mac needed (debug builds).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = demo, onCheckedChange = { demo = it; model.setUseDemo(it) })
                }
            }
        }
    }
}
