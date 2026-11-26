package dev.leash.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.LinkState
import dev.leash.personal.core.SessionSummary
import dev.leash.personal.ui.theme.LeashTheme

/** Phase 1b shell: connection state and the conversation list, proving the core loop. Real screens arrive in phase 2. */
@Composable
fun LeashRoot(model: AppModel) {
    val scheme = MaterialTheme.colorScheme
    val colors = LeashTheme.colors
    val state by model.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, colors.bgGradientEnd)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = 320.dp.toPx(),
                    ),
                )
            }
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Leash", style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
                    ConnectionLine(state.connection, state.macName, state.useDemo)
                }
                TextButton(onClick = { showSettings = true }) { Text("Settings") }
            }
            Spacer(Modifier.size(8.dp))
            when {
                !state.settingsLoaded -> Unit
                !state.isConfigured -> Empty("Not paired", "Open Settings and paste the pairing link, or enter the address and token of your Mac.")
                state.sessions.isEmpty() && state.sessionsLoading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.sessions.isEmpty() && state.sessionsError != null -> Empty("Couldn't load conversations", state.sessionsError ?: "")
                state.sessions.isEmpty() && state.isConnected -> Empty("No conversations yet", "Start one from your Mac or from here.")
                state.sessions.isEmpty() -> Unit
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.id }) { row ->
                        SessionRow(row, agentLabel = state.agentLabel(row.agent), unread = model.isUnread(row), running = row.running || row.id in state.running)
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = scheme.outlineVariant)
                    }
                }
            }
        }
    }
    if (showSettings) SettingsSheet(model, onDismiss = { showSettings = false })
}

@Composable
private fun ConnectionLine(connection: LinkState, macName: String, demo: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (text, color) = when (connection) {
        LinkState.Idle -> "Not connected" to scheme.onSurfaceVariant
        LinkState.Connecting -> "Connecting…" to scheme.onSurfaceVariant
        LinkState.Connected -> (if (demo) "Demo bridge" else if (macName.isNotEmpty()) "Connected to $macName" else "Connected") to scheme.primary
        is LinkState.Waiting -> "Reconnecting · ${connection.reason}" to scheme.onSurfaceVariant
        is LinkState.Failed -> connection.reason to scheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Empty(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SessionRow(s: SessionSummary, agentLabel: String, unread: Boolean, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(10.dp).background(if (unread) scheme.primary else Color.Transparent, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(s.displayTitle, style = MaterialTheme.typography.titleMedium, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                add(s.folderName.ifEmpty { "~" })
                add(agentLabel)
                s.lockedBy?.let { add("open in $it") }
            }.joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.pendingCount > 0) Badge(containerColor = scheme.tertiary) { Text(s.pendingCount.toString()) }
        if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}
