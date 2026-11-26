package dev.leash.personal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.components.BackButton
import dev.leash.personal.ui.design.components.TopBar

/** Space for the floating top bar above a screen's content (status bar + the 48 dp bar + a little air). */
@Composable
fun topBarSpace() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp

/** Space below a screen's content for the navigation bar. */
@Composable
fun navBarSpace() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * A pushed screen whose content scrolls in one column under Leash's floating top bar (back circle, centred title,
 * optional trailing circles). The page is inside the glass source, so the top edge blurs it as it scrolls under.
 */
@Composable
fun ScrollScreen(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(top = topBarSpace(), bottom = navBarSpace() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                content = content,
            )
        }
        TopBar(
            title = title,
            subtitle = subtitle,
            edgeVisible = scroll.value > 0,
            navigation = { BackButton(stringResource(R.string.back), onBack) },
            actions = actions,
        )
    }
}

/**
 * A pushed screen whose content is a lazy list under the floating top bar; [overlay] draws above everything (a
 * centred "Starting…" capsule, say).
 */
@Composable
fun LazyScreen(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topBarSpace(), bottom = navBarSpace() + 32.dp),
                content = content,
            )
        }
        TopBar(
            title = title,
            subtitle = subtitle,
            edgeVisible = scrolled,
            navigation = { BackButton(stringResource(R.string.back), onBack) },
            actions = actions,
        )
        overlay()
    }
}

/** The gap between two grouped sections. */
@Composable
fun SectionGap() = Box(Modifier.padding(top = 10.dp))
