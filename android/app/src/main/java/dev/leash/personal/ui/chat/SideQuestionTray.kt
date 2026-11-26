package dev.leash.personal.ui.chat

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.SideQuestion
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LeashSheet
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.tabular
import dev.leash.personal.ui.markdown.MarkdownView
import kotlinx.coroutines.launch

private val AnswerCap: Dp = 176.dp

/**
 * /btw answers, docked above the composer like the approval tray: a side question about the conversation that never enters
 * it (ios SideQuestionTray). One card at a time ("1 of 2"); closing it shows the next. An answer taller than the card
 * fades out at its bottom and opens in a sheet. [collapsed]: an approval or question tray is showing, so just the header line.
 */
@Composable
fun SideQuestionTray(item: SideQuestion, index: Int, total: Int, collapsed: Boolean, close: () -> Unit) {
    val c = Leash.colors
    var showFull by remember(item.id) { mutableStateOf(false) }
    var naturalPx by remember(item.id) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val overflows = with(density) { naturalPx.toDp() } > AnswerCap + 1.dp
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val open = LocalOpenFile.current
    TrayShell(contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // the header: the question, "1 of 2", the cross
            Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(LeashIcons.sideQuestion, null, Modifier.size(18.dp), tint = c.accent)
                val label = stringResource(R.string.sq_header, item.question)
                Text(item.question, Modifier.weight(1f).semantics { contentDescription = label }, style = Leash.type.subheadline.semibold(), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (total > 1) Text(stringResource(R.string.request_of, index, total), style = Leash.type.caption.tabular(), color = c.textSecondary)
                val closeLabel = stringResource(R.string.sq_close)
                Box(Modifier.size(44.dp).pressable(onClickLabel = closeLabel, onClick = close), contentAlignment = Alignment.Center) {
                    Icon(LeashIcons.close, closeLabel, Modifier.size(14.dp), tint = c.textSecondary)
                }
            }
            if (!collapsed) when {
                item.answer != null -> Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // the card shows the top of the answer; the natural height says whether there is more
                    Box(
                        Modifier.heightIn(max = AnswerCap).clipToBounds()
                            .then(if (overflows) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                                drawContent()
                                drawRect(Brush.verticalGradient(0.72f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                            } else Modifier),
                    ) {
                        Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { naturalPx = it.height }) { MarkdownView(item.answer, open) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LeashButton(stringResource(R.string.copy), { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("answer", item.answer))) } }, style = ButtonStyle.Tinted, size = ButtonSize.Small, icon = LeashIcons.copy)
                        if (overflows) LeashButton(stringResource(R.string.sq_show_all), { showFull = true }, style = ButtonStyle.Tinted, size = ButtonSize.Small, icon = LeashIcons.fullscreen)
                    }
                }
                item.error != null -> Row(Modifier.padding(end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(LeashIcons.failed, null, Modifier.size(18.dp), tint = c.danger)
                    Text(item.error, style = Leash.type.callout, color = c.danger)
                }
                else -> Row(Modifier.heightIn(min = 32.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(size = 16.dp)
                    Text(stringResource(R.string.sq_answering), style = Leash.type.callout, color = c.textSecondary)
                }
            }
        }
    }
    if (showFull && item.answer != null) SideAnswerSheet(item, open) { showFull = false }
}

/** The whole answer of a side question, for answers taller than the card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SideAnswerSheet(item: SideQuestion, open: (String) -> Unit, dismiss: () -> Unit) {
    val c = Leash.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LeashSheet(
        onDismiss = dismiss, title = stringResource(R.string.sq_sheet_title), doneLabel = stringResource(R.string.done), onDone = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(item.question, style = Leash.type.headline, color = c.textSecondary)
            MarkdownView(item.answer.orEmpty(), open)
            LeashButton(stringResource(R.string.copy), { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("answer", item.answer.orEmpty()))) } }, style = ButtonStyle.Tinted, size = ButtonSize.Small, icon = LeashIcons.copy)
        }
    }
}

