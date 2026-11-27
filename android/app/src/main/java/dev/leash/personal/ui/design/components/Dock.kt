package dev.leash.personal.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leash.personal.ui.design.glassLensActive
import dev.leash.personal.ui.design.softBottomEdge
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
    val keyboard = keyboardHeight()
    return this.imePadding().padding(bottom = max(restingSpace() - keyboard, DockKeyboardGap))
}

@Composable
private fun keyboardHeight(): Dp = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }

@Composable
private fun restingSpace(): Dp = max(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DockKeyboardGap, DockFloat)

/** How far above the lowest control's bottom edge the fade under liquid glass begins: just over the chips' top. */
private val DockFadeReach = 40.dp

/**
 * The fade behind the chat dock, put in the dock's box. Under liquid glass it is anchored low, as on the iPhone: it
 * begins about the chips row and grows to the screen edge (or the keyboard), however tall the composer and trays above
 * it are, so the transcript shows through them. Blurred and solid glass need the whole dock faded.
 */
@Composable
fun BoxScope.DockFade() {
    if (glassLensActive()) {
        val belowControls = max(restingSpace(), keyboardHeight() + DockKeyboardGap)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(belowControls + DockFadeReach).softBottomEdge())
    } else {
        Box(Modifier.matchParentSize().softBottomEdge())
    }
}
