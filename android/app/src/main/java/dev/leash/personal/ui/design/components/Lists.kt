package dev.leash.personal.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashRadius
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.tabular

/**
 * A section header outside a grouped card (DESIGN.md §11): optional glyph + subheadline SemiBold in secondary ink,
 * sentence case, with a trailing disclosure chevron (when [expanded] is given) or [trailing] content.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Leash.colors
    val rot by animateFloatAsState(if (expanded == true) 0f else -90f, Motion.disclose(), label = "chevron")
    var m = modifier.fillMaxWidth()
    if (onClick != null) m = m.pressable(scale = PressScale.Text, onLongClick = onLongClick, onClick = onClick)
    Row(
        m.heightIn(min = 44.dp).padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = c.textSecondary)
        Text(title, style = Leash.type.subheadline.semibold(), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
        if (expanded != null) Icon(LeashIcons.chevronDown, null, Modifier.size(18.dp).rotate(rot), tint = c.textSecondary)
    }
}

/** Footnote under a grouped card. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Leash.type.footnote,
        color = Leash.colors.textSecondary,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
    )
}

/** The inset-grouped card: raised fill, group radius, 16 dp from the screen edges. Rows go inside via [GroupRow]. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(LeashShapes.group)
            .background(groupFill()),
        content = content,
    )
}

/**
 * One row inside a [GroupCard]: 16 dp horizontal padding, [minHeight] (52 settings, 64 session rows), a 1 px separator
 * below it inset to [separatorInset] from the start and 16 dp from the end unless [last]. Pressable rows highlight.
 */
@Composable
fun GroupRow(
    modifier: Modifier = Modifier,
    last: Boolean = false,
    minHeight: Dp = 52.dp,
    separatorInset: Dp = 16.dp,
    verticalPadding: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        var m: Modifier = Modifier.fillMaxWidth()
        if (onClick != null) m = m.pressable(scale = PressScale.Row, highlight = true, onLongClick = onLongClick, onClick = onClick)
        Row(
            m.heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (!last) Hairline(Modifier.padding(start = separatorInset, end = 16.dp))
    }
}

/**
 * One piece of a grouped card that a lazy list splits into items: the card's fill and inset on every piece, its
 * corners only on the [first] and [last] one, so the pieces read as one card (DESIGN.md §11, inset grouped).
 */
@Composable
fun Modifier.groupItem(first: Boolean, last: Boolean): Modifier {
    val r = LeashRadius.group
    val shape = androidx.compose.runtime.remember(first, last) {
        dev.leash.personal.ui.design.ContinuousShape(if (first) r else 0.dp, if (first) r else 0.dp, if (last) r else 0.dp, if (last) r else 0.dp)
    }
    return this.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(groupFill())
}

/** A grouped card's fill: raised on the page; on a dark sheet the panel colour, so the card still reads as one. */
@Composable
private fun groupFill(): Color = if (Leash.colors.dark && LocalOnSheet.current) Leash.colors.panel else Leash.colors.bgRaised

/** A 1 px separator. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Leash.colors.hairline) {
    val onePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(onePx).background(color))
}

/** The session list's row status (iOS SessionRow.statusGlyph). */
enum class RowStatus { None, NeedsYou, Running, Locked }

/**
 * A conversation row: unread-dot gutter, title (2 lines, Regular — weight never carries hierarchy), a meta line
 * ([meta]: agent icon, draft, relative time) and the trailing status (needs you, spinner, lock, or a chevron).
 */
@Composable
fun SessionRow(
    title: String,
    modifier: Modifier = Modifier,
    unread: Boolean = false,
    status: RowStatus = RowStatus.None,
    last: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    meta: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val c = Leash.colors
    GroupRow(modifier, last = last, minHeight = 64.dp, separatorInset = 32.dp, verticalPadding = 14.dp, onClick = onClick, onLongClick = onLongClick) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            UnreadDot(unread, Modifier.padding(top = 7.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = Leash.type.body, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides Leash.type.footnote,
                        androidx.compose.material3.LocalContentColor provides c.textSecondary,
                    ) { meta() }
                }
            }
        }
        androidx.compose.animation.Crossfade(status, animationSpec = Motion.quick(), label = "status") { s ->
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                when (s) {
                    RowStatus.NeedsYou -> NeedsYouBadge()
                    RowStatus.Running -> dev.leash.personal.ui.design.Spinner(size = 16.dp)
                    RowStatus.Locked -> Icon(LeashIcons.locked, null, Modifier.size(14.dp), tint = c.textTertiary)
                    RowStatus.None -> Icon(LeashIcons.chevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
                }
            }
        }
    }
}

/** "Draft: first line" (Messages style): the word in SemiBold primary, the snippet Regular secondary. */
@Composable
fun DraftText(label: String, snippet: String, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.textPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            append(label)
            pop()
            append(" ")
            append(snippet)
        },
        style = Leash.type.footnote,
        color = c.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** "Show all (15)" at the end of a group. */
@Composable
fun ShowAllRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GroupRow(modifier, last = true, minHeight = 52.dp, onClick = onClick) {
        Text(text, style = Leash.type.body.tabular(), color = Leash.colors.accent, modifier = Modifier.weight(1f))
        Icon(LeashIcons.chevronRight, null, Modifier.size(18.dp), tint = Leash.colors.textTertiary)
    }
}

/** A settings row: label at the start, value (or [trailing]) at the end, optional chevron when it navigates. */
@Composable
fun ValueRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    labelColor: Color = Leash.colors.textPrimary,
    last: Boolean = false,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Leash.colors
    GroupRow(modifier, last = last, separatorInset = if (icon != null) 52.dp else 16.dp, onClick = onClick) {
        // label starts 52 dp in, where the separator does: 16 padding + 22 glyph + 14 gap
        if (icon != null) Icon(icon, null, Modifier.padding(end = 4.dp).size(22.dp), tint = labelColor)
        Text(label, style = Leash.type.body, color = labelColor, modifier = Modifier.weight(1f), maxLines = 2)
        if (value != null) Text(value, style = Leash.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        trailing?.invoke()
        if (chevron) Icon(LeashIcons.chevronRight, null, Modifier.size(18.dp), tint = c.textTertiary)
    }
}

/** A settings row edited in place: label at the start, the value right-aligned and editable (no outlined field). */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    last: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Leash.colors
    val style = (if (mono) Leash.type.mono.copy(fontSize = Leash.type.body.fontSize) else Leash.type.body).copy(color = c.textPrimary, textAlign = TextAlign.End)
    GroupRow(modifier, last = last) {
        Text(label, style = Leash.type.body, color = c.textPrimary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.textTertiary), maxLines = 1)
                    inner()
                }
            },
        )
        trailing?.invoke()
    }
}

/** A settings row with a switch. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, last: Boolean = false) {
    GroupRow(modifier, last = last, verticalPadding = 4.dp, onClick = { onCheckedChange(!checked) }) {
        Text(label, style = Leash.type.body, color = Leash.colors.textPrimary, modifier = Modifier.weight(1f))
        LeashSwitch(checked, onCheckedChange)
    }
}
