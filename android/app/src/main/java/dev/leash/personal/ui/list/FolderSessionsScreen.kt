package dev.leash.personal.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.ui.components.pluralText
import dev.leash.personal.ui.design.components.GlassCircleButton
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LocalReduceMotion
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.components.BackButton
import dev.leash.personal.ui.design.components.BottomActions
import dev.leash.personal.ui.design.components.EmptyState
import dev.leash.personal.ui.design.components.LeashRefreshBox
import dev.leash.personal.ui.design.components.SearchField
import dev.leash.personal.ui.design.components.TopBar
import dev.leash.personal.ui.design.softBottomEdge
import kotlinx.coroutines.launch

/** Every conversation of one folder, newest first (the "Show all" destination). */
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
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reduced = LocalReduceMotion.current

    GlassScope(Modifier.fillMaxSize()) {
        LeashRefreshBox(state.sessionsLoading, { scope.launch { model.refreshSessions() } }, Modifier.fillMaxSize(), indicatorTop = top + 56.dp) {
            GlassSource(Modifier.fillMaxSize()) {
                // the page itself is part of what the glass blurs, or the bands would show a flat colour over the gradient
                SpaceBackground()
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top + 72.dp, bottom = bottom + 96.dp)) {
                    conversationItems(shown, "", state, model, false, onOpenChat)
                    if (shown.isEmpty() && search.isNotBlank()) item("no-match") {
                        EmptyState(LeashIcons.search, stringResource(R.string.no_matches), Modifier.padding(top = 48.dp), message = stringResource(R.string.no_matches_detail, search))
                    }
                }
            }
        }
        TopBar(
            title = title,
            subtitle = pluralText(R.plurals.conversations_count, all.size),
            edgeVisible = scrolled,
            navigation = { BackButton(stringResource(R.string.back), onBack) },
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom + 96.dp).softBottomEdge())
        AnimatedVisibility(!searchOpen, Modifier.align(Alignment.BottomCenter), enter = Motion.popEnter(reduced), exit = Motion.popExit(reduced)) {
            BottomActions(start = { GlassCircleButton(LeashIcons.search, stringResource(R.string.search), { searchOpen = true }, size = 52.dp) })
        }
        AnimatedVisibility(searchOpen, Modifier.align(Alignment.BottomCenter), enter = Motion.popEnter(reduced), exit = Motion.popExit(reduced)) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(search, { search = it }, stringResource(R.string.search_conversations), stringResource(R.string.clear), Modifier.weight(1f))
                GlassCircleButton(LeashIcons.close, stringResource(R.string.close_search), { searchOpen = false; search = "" }, size = 48.dp)
            }
        }
    }
}
