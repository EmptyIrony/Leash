package dev.leash.personal.ui.design.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/** How far the floating bottom controls stand off the screen edge at least: where iOS's home indicator area puts them. */
private val DockFloat = 28.dp
/** The gap to the keyboard. */
private val DockKeyboardGap = 8.dp

/**
 * The space under floating bottom controls (the chat dock, the list's circles): they hover [DockFloat] above the screen
 * edge, or 8 dp above a navigation bar taller than that, and content scrolls on underneath. Over the keyboard the gap
 * shrinks to 8 dp, following its animation without a jump.
 */
@Composable
fun Modifier.dockInsets(): Modifier {
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val resting = max(navigation + DockKeyboardGap, DockFloat)
    return this.imePadding().padding(bottom = max(resting - keyboard, DockKeyboardGap))
}
