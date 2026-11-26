package dev.leash.personal.ui.newsession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BrowseResult
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.components.LazyScreen
import dev.leash.personal.ui.components.pluralText
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.glass
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.components.GroupRow
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LoadingState
import dev.leash.personal.ui.design.components.SectionHeader
import dev.leash.personal.ui.design.components.SegmentedControl
import dev.leash.personal.ui.design.components.groupItem
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
@Composable
fun NewSessionScreen(model: AppModel, controller: NewSessionController, onBack: () -> Unit, onBrowse: (agent: String) -> Unit, onCreated: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val c = Leash.colors
    val agentIds = remember(state.agents) { if (state.agents.isEmpty()) listOf("agy") else state.agents.map { it.id } }
    LaunchedEffect(agentIds) { if (controller.agent !in agentIds) controller.agent = agentIds.first() }
    val agent = controller.agent
    LazyScreen(
        stringResource(R.string.new_conversation_title), onBack,
        overlay = { if (controller.creating != null) StartingCapsule(Modifier.align(Alignment.Center)) },
    ) {
        if (agentIds.size > 1) item("agents") {
            SegmentedControl(
                agentIds.map { state.agentLabel(it) }, agentIds.indexOf(agent).coerceAtLeast(0), { controller.agent = agentIds[it] },
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(),
            )
        }
        controller.error?.let { e -> item("error") { Notice(LeashIcons.failed, stringResource(R.string.couldnt_start), e, c.danger) } }
        val ready = state.isReady(agent)
        if (!ready) item("not-ready") { Notice(LeashIcons.warning, stringResource(R.string.agent_not_running, state.agentLabel(agent)), null, c.attention) }
        val recent = state.recentFolders(agent)
        if (recent.isNotEmpty()) {
            item("recent-h") { SectionHeader(stringResource(R.string.recent_folders), Modifier.padding(top = 8.dp)) }
            itemsIndexed(recent, key = { _, f -> "f:$f" }) { i, f ->
                GroupRow(
                    Modifier.groupItem(first = i == 0, last = i == recent.lastIndex), last = i == recent.lastIndex, separatorInset = 48.dp,
                    onClick = if (controller.creating == null && ready) ({ controller.start(f, onCreated) }) else null,
                ) {
                    AgentIcon(agent, 18.dp)
                    Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(f.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, style = Leash.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.shortPath(f, agent), style = Leash.type.footnote, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                    if (controller.creating == f) Spinner(size = 16.dp)
                }
            }
        }
        item("browse") {
            GroupRow(Modifier.padding(top = 20.dp).groupItem(first = true, last = true), last = true, onClick = { onBrowse(agent) }) {
                Icon(LeashIcons.folder, null, Modifier.size(22.dp), tint = c.accent)
                Text(
                    if (agent == "agy") stringResource(R.string.browse_the_bridge) else stringResource(R.string.browse_agent_host, state.agentLabel(agent)),
                    Modifier.weight(1f).padding(start = 4.dp), style = Leash.type.body, color = c.textPrimary,
                )
                Icon(LeashIcons.chevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
            }
        }
    }
}

/** A notice card in a list: glyph, title and an optional detail line. */
@Composable
internal fun Notice(icon: ImageVector, title: String, detail: String?, tint: Color, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Row(
        modifier.padding(vertical = 6.dp).groupItem(first = true, last = true).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Leash.type.subheadline.medium(), color = c.textPrimary)
            if (detail != null) Text(detail, style = Leash.type.footnote, color = c.textSecondary)
        }
    }
}

/** The glass "Starting…" capsule while a conversation is being created. */
@Composable
internal fun StartingCapsule(modifier: Modifier = Modifier) {
    Row(
        modifier.glass(LeashShapes.capsule).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(size = 16.dp)
        Text(stringResource(R.string.starting), style = Leash.type.subheadline.medium(), color = Leash.colors.textPrimary)
    }
}

/** Folder and file rows of a browse result, shared by the new-conversation browser and the project browser. */
internal fun LazyListScope.browseRows(r: BrowseResult, onOpenFolder: (String) -> Unit, filesLimit: Int = Int.MAX_VALUE, filesHeader: Boolean = true) {
    itemsIndexed(r.dirs, key = { _, d -> "d:$d" }) { i, d ->
        val last = i == r.dirs.lastIndex
        GroupRow(Modifier.groupItem(first = i == 0, last = last), last = last, separatorInset = 52.dp, onClick = { onOpenFolder(r.path.trimEnd('/') + "/" + d) }) {
            Icon(LeashIcons.folder, null, Modifier.size(22.dp), tint = Leash.colors.accent)
            Text(d, Modifier.weight(1f).padding(start = 4.dp), style = Leash.type.body, color = Leash.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(LeashIcons.chevronRight, null, Modifier.size(18.dp), tint = Leash.colors.textTertiary)
        }
    }
    val files = r.files.take(filesLimit)
    if (files.isNotEmpty()) {
        if (filesHeader) item("files-h") { SectionHeader(pluralText(R.plurals.files_count, r.files.size), Modifier.padding(top = 12.dp)) }
        else item("files-gap") { Box(Modifier.padding(top = 20.dp)) }
        itemsIndexed(files, key = { _, f -> "x:$f" }) { i, f ->
            val last = i == files.lastIndex
            GroupRow(Modifier.groupItem(first = i == 0, last = last), last = last, separatorInset = 52.dp, minHeight = 48.dp) {
                Icon(LeashIcons.file, null, Modifier.size(22.dp), tint = Leash.colors.textTertiary)
                Text(f, Modifier.weight(1f).padding(start = 4.dp), style = Leash.type.body, color = Leash.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Pick a folder on the agent's host to start in (ios FolderBrowser). */
@Composable
fun FolderBrowserScreen(model: AppModel, controller: NewSessionController, path: String?, onBack: () -> Unit, onOpenFolder: (String) -> Unit, onCreated: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val c = Leash.colors
    var result by remember(path) { mutableStateOf<BrowseResult?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        try { result = model.browse(path, controller.agent) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val r = result
    LazyScreen(
        r?.path?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { "/" } ?: state.agentLabel(controller.agent),
        onBack,
        subtitle = r?.let { state.shortPath(it.path, controller.agent) },
        actions = {
            if (r?.isGit == true) Row(
                Modifier.padding(end = 8.dp).glass(LeashShapes.capsule, blur = false).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LeashIcons.subagent, null, Modifier.size(14.dp), tint = c.textSecondary)
                Text(stringResource(R.string.git_repository), style = Leash.type.caption.medium(), color = c.textSecondary)
            }
        },
        overlay = { if (controller.creating != null) StartingCapsule(Modifier.align(Alignment.Center)) },
    ) {
        error?.let { e -> item("err") { Notice(LeashIcons.failed, stringResource(R.string.cant_open_folder), e, c.danger) } }
        controller.error?.let { e -> item("err2") { Notice(LeashIcons.failed, stringResource(R.string.couldnt_start), e, c.danger) } }
        if (r != null) {
            item("start") {
                LeashButton(
                    stringResource(R.string.start_conversation_here), { controller.start(r.path, onCreated) },
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(),
                    icon = LeashIcons.squareAndPencil, enabled = controller.creating == null && state.isReady(controller.agent),
                )
            }
            item("dirs-h") { SectionHeader(stringResource(R.string.folders), Modifier.padding(top = 8.dp)) }
            if (r.dirs.isEmpty()) item("nodirs") {
                GroupRow(Modifier.groupItem(first = true, last = true), last = true) {
                    Text(stringResource(R.string.no_subfolders), style = Leash.type.body, color = c.textSecondary)
                }
            }
            browseRows(r, onOpenFolder, filesLimit = 30)
        } else if (error == null) item("spin") { LoadingState(stringResource(R.string.loading), Modifier.padding(top = 64.dp)) }
    }
}
