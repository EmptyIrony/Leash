package dev.leash.personal.ui.chat

import android.content.ClipData
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.leash.personal.R
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.components.LeashMenu
import dev.leash.personal.ui.design.components.LeashMenuItem
import dev.leash.personal.ui.design.components.LeashSheet
import dev.leash.personal.ui.markdown.MarkdownPlain
import kotlinx.coroutines.launch

/**
 * A long press on a message: Copy (the original text) and Select text (a sheet with the platform's own selection handles and
 * loupe, since text inside a transcript row can't be selected across blocks). [markdown] messages offer their plain text.
 */
@Composable
fun MessageMenu(text: String, markdown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(DpOffset.Zero) }
    var heightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val view = LocalView.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Box(
        modifier.onSizeChanged { heightPx = it.height }.pointerInput(text) {
            // the menu opens where the finger is (a menu anchored to the whole row would sit at its corner)
            detectTapGestures(onLongPress = { p ->
                offset = with(density) { DpOffset(p.x.toDp(), p.y.toDp() - heightPx.toDp()) }
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                menu = true
            })
        },
    ) {
        content()
        LeashMenu(menu, { menu = false }, offset = offset) {
            LeashMenuItem(stringResource(R.string.copy), { menu = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))) } }, icon = LeashIcons.copy)
            LeashMenuItem(stringResource(R.string.select_text), { menu = false; selecting = true }, icon = LeashIcons.selectText)
        }
    }
    if (selecting) SelectTextSheet(if (markdown) MarkdownPlain.text(text) else text) { selecting = false }
}

/** The message's text in a sheet where the system's selection (handles, loupe, toolbar) works over the whole text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectTextSheet(text: String, onDismiss: () -> Unit) {
    val ink = Leash.colors.textPrimary
    val size = Leash.type.body.fontSize.value
    LeashSheet(
        onDismiss = onDismiss, title = stringResource(R.string.select_text), doneLabel = stringResource(R.string.done), onDone = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),   // at half height, dragging a selection handle would fight the sheet
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
            AndroidView(
                factory = { ctx ->
                    android.widget.ScrollView(ctx).apply {
                        addView(android.widget.TextView(ctx).apply {
                            setTextIsSelectable(true)
                            textSize = size
                            setLineSpacing(0f, 1.15f)
                            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                            setPadding(pad, pad / 2, pad, pad * 2)
                        })
                    }
                },
                update = { sv ->
                    val tv = sv.getChildAt(0) as android.widget.TextView
                    if (tv.text.toString() != text) tv.text = text
                    tv.setTextColor(ink.toArgb())
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}
