package dev.leash.personal.ui.components

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.leash.personal.R
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.components.BackButton
import dev.leash.personal.ui.design.components.LeashSnackbar
import dev.leash.personal.ui.design.components.TitleAlign
import dev.leash.personal.ui.design.components.TopBar

/**
 * A page above the conversation that does not leave it (iOS presents these as sheets): its own window, edge to edge, in
 * Leash's page and top bar. Being a window of the same composition, it keeps the conversation on screen (and its
 * requests in the tray) underneath, and Back / the back gesture close it. [content] gets the space the top bar takes.
 */
@Composable
fun LeashFullScreen(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    titleAlign: TitleAlign = TitleAlign.Center,
    actions: @Composable RowScope.() -> Unit = {},
    edgeVisible: Boolean = true,
    toast: String? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.(top: Dp) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        val dark = Leash.colors.dark
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        GlassScope(Modifier.fillMaxSize()) {
            val top = topBarSpace()
            GlassSource(Modifier.fillMaxSize()) {
                SpaceBackground()
                content(top)
            }
            TopBar(
                title = title, subtitle = subtitle, titleAlign = titleAlign, edgeVisible = edgeVisible,
                navigation = { BackButton(stringResource(R.string.file_close), onDismiss) },
                actions = actions,
            )
            overlay()
            if (toast != null) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) { LeashSnackbar(toast) }
        }
    }
}
