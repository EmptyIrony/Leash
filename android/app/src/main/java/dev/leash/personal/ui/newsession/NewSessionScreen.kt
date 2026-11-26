package dev.leash.personal.ui.newsession

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BrowseResult
import dev.leash.personal.ui.components.ActivitySpinner
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Starting a conversation is shared by the new-conversation screen and the folder browser it pushes. */
class NewSessionController(private val model: AppModel, private val scope: CoroutineScope) {
    var creating by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    /** the agent chosen on the first screen; the browser continues with it */
    var agent by mutableStateOf("agy")

    fun start(cwd: String, onCreated: (String) -> Unit) {
        if (creating != null) return
        creating = cwd
        error = null
        val a = agent
        scope.launch {
            try {
                val id = model.newSession(cwd, a)
                creating = null
                model.hooks.haptic(dev.leash.personal.core.Haptic.Medium)
                onCreated(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                creating = null
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                creating = null
                model.hooks.haptic(dev.leash.personal.core.Haptic.Error)
            }
        }
    }
}

/** Agent, recent folders, and the way into the folder browser (ios NewSessionView). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(model: AppModel, controller: NewSessionController, onBack: () -> Unit, onBrowse: (agent: String) -> Unit, onCreated: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val agentIds = remember(state.agents) { if (state.agents.isEmpty()) listOf("agy") else state.agents.map { it.id } }
    LaunchedEffect(agentIds) { if (controller.agent !in agentIds) controller.agent = agentIds.first() }
    val agent = controller.agent
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.new_conversation_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (agentIds.size > 1) item("agents") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        agentIds.forEachIndexed { i, id ->
                            SegmentedButton(agent == id, { controller.agent = id }, SegmentedButtonDefaults.itemShape(i, agentIds.size)) { Text(state.agentLabel(id), maxLines = 1) }
                        }
                    }
                }
                controller.error?.let { e ->
                    item("error") {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(stringResource(R.string.couldnt_start), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                                Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val ready = state.isReady(agent)
                if (!ready) item("not-ready") {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, null, tint = LeashTheme.colors.attention)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.agent_not_running, state.agentLabel(agent)), color = LeashTheme.colors.attention)
                    }
                }
                val recent = state.recentFolders(agent)
                if (recent.isNotEmpty()) {
                    item("recent-h") { SectionLabel(stringResource(R.string.recent_folders)) }
                    items(recent, key = { "f:$it" }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = controller.creating == null && ready) { controller.start(f, onCreated) }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AgentIcon(agent, 18.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, style = MaterialTheme.typography.bodyLarge)
                                Text(state.shortPath(f, agent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                            }
                            if (controller.creating == f) ActivitySpinner(18.dp)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item("browse") {
                    Row(Modifier.fillMaxWidth().clickable { onBrowse(agent) }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (agent == "agy") stringResource(R.string.browse_the_bridge) else stringResource(R.string.browse_agent_host, state.agentLabel(agent)),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        )
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LeashTheme.colors.textTertiary)
                    }
                }
            }
            if (controller.creating != null) StartingCapsule(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun StartingCapsule(modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ActivitySpinner(18.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.starting))
        }
    }
}

/** Pick a folder on the agent's host to start in (ios FolderBrowser). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(model: AppModel, controller: NewSessionController, path: String?, onBack: () -> Unit, onOpenFolder: (String) -> Unit, onCreated: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var result by remember(path) { mutableStateOf<BrowseResult?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        try { result = model.browse(path, controller.agent) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val r = result
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(r?.path?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { "/" } ?: state.agentLabel(controller.agent)) },
                actions = { if (r?.isGit == true) Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.CallSplit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.git_repository), style = MaterialTheme.typography.labelMedium) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { e -> item("err") { Text(e, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
                controller.error?.let { e -> item("err2") { Text(e, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
                if (r != null) {
                    item("start") {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { controller.start(r.path, onCreated) }, enabled = controller.creating == null && state.isReady(controller.agent), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.AddComment, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.start_conversation_here))
                            }
                            Text(state.shortPath(r.path, controller.agent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item("dirs-h") { SectionLabel(stringResource(R.string.folders)) }
                    items(r.dirs, key = { "d:$it" }) { d ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenFolder(r.path.trimEnd('/') + "/" + d) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(14.dp))
                            Text(d, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LeashTheme.colors.textTertiary)
                        }
                    }
                    if (r.dirs.isEmpty()) item("nodirs") { Text(stringResource(R.string.no_subfolders), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (r.files.isNotEmpty()) {
                        item("files-h") { SectionLabel(pluralStringResource(R.plurals.files_count, r.files.size, r.files.size)) }
                        items(r.files.take(30), key = { "x:$it" }) { f ->
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(20.dp), tint = LeashTheme.colors.textTertiary)
                                Spacer(Modifier.width(14.dp))
                                Text(f, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else if (error == null) item("spin") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ActivitySpinner(24.dp) } }
            }
            if (controller.creating != null) StartingCapsule(Modifier.align(Alignment.Center))
        }
    }
}
