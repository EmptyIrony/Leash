package dev.leash.personal.ui.design.components

import androidx.compose.foundation.BorderStroke
import dev.leash.personal.ui.design.floatingShadow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashRadius
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.glass
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.softTopEdge

enum class TitleAlign { Center, Start }

/**
 * The floating top bar (DESIGN.md §11): transparent, over the soft top edge, with glass circles at both ends and an
 * optional inline title (headline + footnote subtitle). [titleVisible] lets a large-title screen fade the inline
 * title in once its large title has scrolled away. Content scrolls under it.
 */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    titleAlign: TitleAlign = TitleAlign.Center,
    titleVisible: Boolean = true,
    edgeVisible: Boolean = true,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Leash.colors
    Box(modifier.fillMaxWidth()) {
        // the edge reaches 24 dp below the bar; a large-title screen shows it only once content scrolls under the bar
        androidx.compose.animation.AnimatedVisibility(
            visible = edgeVisible,
            modifier = Modifier.matchParentSize(),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) { Box(Modifier.fillMaxSize().softTopEdge()) }
        val titleBlock: @Composable () -> Unit = {
            androidx.compose.animation.AnimatedVisibility(
                visible = title != null && titleVisible,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Column(horizontalAlignment = if (titleAlign == TitleAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
                    Text(title.orEmpty(), style = Leash.type.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = Leash.type.footnote, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // side slots size to their content; a centred title gets the width left between the wider slot and its mirror
        androidx.compose.ui.layout.Layout(
            contents = listOf<@Composable () -> Unit>({ navigation?.invoke() }, titleBlock, { Row(verticalAlignment = Alignment.CenterVertically, content = actions) }),
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp).padding(top = 4.dp, bottom = 28.dp).height(48.dp),
        ) { (navM, titleM, actM), constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val nav = navM.firstOrNull()?.measure(loose)
            val act = actM.firstOrNull()?.measure(loose)
            val navW = nav?.width ?: 0
            val actW = act?.width ?: 0
            val gap = 4.dp.roundToPx()
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val titleMax = if (titleAlign == TitleAlign.Center) (w - 2 * (maxOf(navW, actW) + gap)) else (w - navW - actW - 2 * gap)
            val t = titleM.firstOrNull()?.measure(loose.copy(maxWidth = titleMax.coerceAtLeast(0)))
            layout(w, h) {
                nav?.placeRelative(0, (h - nav.height) / 2)
                act?.placeRelative(w - actW, (h - act.height) / 2)
                if (t != null) {
                    val x = if (titleAlign == TitleAlign.Center) (w - t.width) / 2 else navW + gap
                    t.placeRelative(x, (h - t.height) / 2)
                }
            }
        }
    }
}

/**
 * The search field that the list's bottom search circle turns into (iOS bottom-bar search): a glass capsule with
 * the search glyph, the field and a clear button. Focuses itself when shown.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
) {
    val c = Leash.colors
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier.heightIn(min = 48.dp).glass(LeashShapes.capsule).padding(start = 14.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LeashIcons.search, null, Modifier.size(18.dp), tint = c.textSecondary)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Leash.type.body.copy(color = c.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = Leash.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(Modifier.size(40.dp).pressable(onClickLabel = clearLabel) { onValueChange("") }, contentAlignment = Alignment.Center) {
                Icon(LeashIcons.close, clearLabel, Modifier.size(16.dp), tint = c.textSecondary)
            }
        }
    }
}

/**
 * Pull to refresh with the platform gesture and Leash's indicator: a small panel circle with an accent arc, shown
 * [indicatorTop] below the top so it appears under the floating top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeashRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorTop: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val c = Leash.colors
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = indicatorTop),
                containerColor = c.panel,
                color = c.accent,
            )
        },
        content = content,
    )
}

/**
 * A confirmation (iOS alert / confirmation dialog): a solid panel with continuous corners, title, message and two
 * Leash buttons, side by side when both labels are short, else stacked with the confirming one first. Replaces the
 * stock Material AlertDialog look.
 */
@Composable
fun LeashDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    message: String? = null,
    destructive: Boolean = false,
) {
    val c = Leash.colors
    val shape = dev.leash.personal.ui.design.ContinuousShape(28.dp)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .floatingShadow(shape, panel = true)
                .clip(shape)
                .background(c.panel)
                .border(BorderStroke(androidx.compose.ui.unit.Dp.Hairline, c.hairline), shape)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
        ) {
            Text(title, style = Leash.type.title3, color = c.textPrimary)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, style = Leash.type.subheadline, color = c.textSecondary)
            }
            Spacer(Modifier.height(20.dp))
            val confirmStyle = if (destructive) ButtonStyle.Destructive else ButtonStyle.Prominent
            if (confirmLabel.length + dismissLabel.length <= 22) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LeashButton(dismissLabel, onDismiss, Modifier.weight(1f), style = ButtonStyle.Tinted)
                    LeashButton(confirmLabel, onConfirm, Modifier.weight(1f), style = confirmStyle)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LeashButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), style = confirmStyle)
                    LeashButton(dismissLabel, onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.Tinted)
                }
            }
        }
    }
}

/** A transient message above the dock (Material's snackbar host, Leash's look): a solid panel capsule. */
@Composable
fun LeashSnackbar(text: String, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            text, style = Leash.type.subheadline, color = c.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.floatingShadow(LeashShapes.capsule, panel = true).clip(LeashShapes.capsule).background(c.panel)
                .border(BorderStroke(androidx.compose.ui.unit.Dp.Hairline, c.hairline), LeashShapes.capsule)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

/** The large title block that scrolls with a list's content: largeTitle + footnote subtitle, 16 dp from the start. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title, style = Leash.type.largeTitle, color = Leash.colors.textPrimary)
        if (subtitle != null) Text(subtitle, style = Leash.type.footnote, color = Leash.colors.textSecondary)
    }
}

/** The back control: Android's arrow inside Leash's glass circle. */
@Composable
fun BackButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    GlassCircleButton(LeashIcons.back, contentDescription, onClick, modifier)

/** The list's bottom actions (search at the start, new conversation at the end) as 52 dp glass circles. */
@Composable
fun BottomActions(
    modifier: Modifier = Modifier,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        start?.invoke()
        Spacer(Modifier.weight(1f))
        end?.invoke()
    }
}

/** Jump to the newest output: a 36 dp glass circle. It floats alone over the transcript, so it blurs what is under it. */
@Composable
fun ScrollToBottomButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    GlassCircleButton(LeashIcons.scrollDown, contentDescription, onClick, modifier, size = 36.dp, blur = true)

/**
 * A bottom sheet in Leash's material: page colour (with an edge in dark mode), 32 dp continuous top corners, a small
 * grabber, no tonal elevation; optional centred [title] with a glass "Done" capsule when [onDone] is given. Back and
 * drag dismiss it (predictive back shrinks it, the platform's behaviour).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeashSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    doneLabel: String? = null,
    onDone: (() -> Unit)? = null,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Leash.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = LeashRadius.sheet, topEnd = LeashRadius.sheet),
        containerColor = c.bgSheet,
        contentColor = c.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = c.scrim,
        dragHandle = {
            Box(Modifier.padding(top = 6.dp, bottom = 4.dp).size(36.dp, 5.dp).clip(LeashShapes.capsule).background(c.textTertiary.copy(alpha = 0.4f)))
        },
    ) {
        if (c.dark) Hairline(color = c.sheetEdge)
        if (title != null || onDone != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp)) {
                if (title != null) {
                    Text(title, style = Leash.type.headline, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
                }
                if (onDone != null) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).pressable(onClick = onDone).height(36.dp)
                            .glass(LeashShapes.capsule, blur = false).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(doneLabel ?: "", style = Leash.type.body.semibold(), color = c.accentControl) }
                }
            }
        }
        content()
    }
}

/** A long-press / overflow menu: a solid panel (menus are separate windows; no fake glass). */
@Composable
fun LeashMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Leash.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(LeashRadius.tray),
        containerColor = c.panel,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(androidx.compose.ui.unit.Dp.Hairline, c.hairline),
        content = content,
    )
}

/**
 * A menu item: body text with the outline glyph at the end (iOS order); destructive items in danger. In a pick-one
 * menu ([checkable]) the current item carries a leading check, the others keep its space.
 */
@Composable
fun LeashMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    supporting: String? = null,
    checkable: Boolean = false,
    checked: Boolean = false,
) {
    val c = Leash.colors
    val ink = if (!enabled) c.textTertiary else if (destructive) c.danger else c.textPrimary
    DropdownMenuItem(
        text = {
            Column {
                Text(text, style = Leash.type.body, color = ink)
                if (supporting != null) Text(supporting, style = Leash.type.footnote, color = if (enabled) c.textSecondary else c.textTertiary)
            }
        },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = if (checkable) ({ Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (checked) Icon(LeashIcons.check, null, Modifier.size(18.dp), tint = c.accent) } }) else null,
        trailingIcon = icon?.let { { Icon(it, null, Modifier.size(20.dp), tint = ink) } },
        colors = MenuDefaults.itemColors(textColor = ink, trailingIconColor = ink, disabledTextColor = c.textTertiary, disabledTrailingIconColor = c.textTertiary),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** A gap between menu groups. */
@Composable
fun LeashMenuDivider() {
    Hairline(Modifier.padding(vertical = 4.dp))
}
