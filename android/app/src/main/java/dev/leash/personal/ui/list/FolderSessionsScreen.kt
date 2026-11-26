package dev.leash.personal.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.leash.personal.ui.components.pluralText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import kotlinx.coroutines.launch

/** Every conversation of one folder, newest first (the "Show all" destination). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderSessionsScreen(model: AppModel, agent: String, folder: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val all = remember(state.sessions) { state.sessions.filter { it.agent == agent && it.cwd == folder } }
    val shown = remember(all, search) { if (search.isBlank()) all else all.filter { it.displayTitle.contains(search.trim(), ignoreCase = true) } }
    val (folderName, general) = SessionGrouping.folderTitle(folder, state.home(agent))
    val title = if (general) stringResource(R.string.general) else folderName
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (searchOpen) SearchBar(search, { search = it }, onClose = { searchOpen = false; search = "" })
            else TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(pluralText(R.plurals.conversations_count, all.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = { searchOpen = true }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.sessionsLoading, onRefresh = { scope.launch { model.refreshSessions() } }, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { s ->
                    val unread = model.isUnread(s)
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        SessionRow(s, false, state.drafts[s.id], unread, s.running || s.id in state.running, Modifier.combinedClickable(onClick = { onOpenChat(s.id) }, onLongClick = { menu = true }))
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(if (unread) R.string.mark_read else R.string.mark_unread)) }, onClick = { menu = false; model.setUnread(s.id, !unread) })
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
