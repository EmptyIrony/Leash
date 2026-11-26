package dev.leash.personal.ui.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.glass
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.tabular

/**
 * A glass tray docked above the composer (approval, question, queue, subagents, side question, commands): tray
 * radius, 14 dp padding, height animating on the layout spring when its contents change. [blur] is off when it sits
 * inside the blurred bottom dock.
 */
@Composable
fun TrayShell(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(14.dp),
    blur: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().glass(LeashShapes.tray, blur = blur).animateContentSize(Motion.layout()).padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A tray's title line: glyph in its meaning colour, subheadline SemiBold title (2 lines), "1 of 3" counter. */
@Composable
fun TrayHeader(title: String, icon: ImageVector, iconTint: Color, modifier: Modifier = Modifier, counter: String? = null) {
    val c = Leash.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(17.dp), tint = iconTint)
        Text(title, style = Leash.type.subheadline.semibold(), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (counter != null) Text(counter, style = Leash.type.caption.tabular(), color = c.textSecondary)
    }
}

/** A command or path shown for approval: sunken mono box, up to 4 lines. */
@Composable
fun CommandPreview(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = Leash.type.mono, color = Leash.colors.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().clip(LeashShapes.inset).background(Leash.colors.bgSunken).padding(10.dp),
    )
}

/** One answer button in an approval tray. */
data class TrayAction(val label: String, val style: ButtonStyle, val onClick: () -> Unit)

/**
 * Approval buttons (DESIGN.md §11): 48 dp; at most two side by side (deny-ish first, Allow last); three or more stack
 * full width with the prominent one first.
 */
@Composable
fun TrayActions(actions: List<TrayAction>, modifier: Modifier = Modifier) {
    if (actions.size <= 2) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { a -> LeashButton(a.label, a.onClick, Modifier.weight(1f), style = a.style, size = ButtonSize.Regular) }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.sortedBy { if (it.style == ButtonStyle.Prominent) 0 else 1 }.forEach { a ->
                LeashButton(a.label, a.onClick, Modifier.fillMaxWidth(), style = a.style, size = ButtonSize.Regular)
            }
        }
    }
}

/**
 * A question's choice: radio / checkbox glyph (selection never by colour alone), title, optional detail, on a resting
 * fill that turns accent-tinted when selected.
 */
@Composable
fun OptionRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null, multi: Boolean = false) {
    val c = Leash.colors
    Row(
        modifier.fillMaxWidth().pressable(scale = PressScale.Row, onClick = onClick).heightIn(min = 48.dp)
            .clip(LeashShapes.row).background(if (selected) c.selectedFill else c.restFill).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val icon = if (multi) (if (selected) LeashIcons.checkboxOn else LeashIcons.checkboxOff) else (if (selected) LeashIcons.radioOn else LeashIcons.radioOff)
        Icon(icon, null, Modifier.padding(top = 1.dp).size(20.dp), tint = if (selected) c.accent else c.textTertiary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Leash.type.body, color = c.textPrimary)
            if (detail != null) Text(detail, style = Leash.type.footnote, color = c.textSecondary)
        }
    }
}

/** The queue tray's header: clock + "2 queued" + a small prominent "Send Now" / "Interrupt and Send". */
@Composable
fun QueueHeader(title: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(LeashIcons.queued, null, Modifier.size(17.dp), tint = Leash.colors.textSecondary)
        Text(title, style = Leash.type.subheadline.semibold().tabular(), color = Leash.colors.textPrimary, modifier = Modifier.weight(1f))
        LeashButton(actionLabel, onAction, style = ButtonStyle.Prominent, size = ButtonSize.Small, enabled = enabled)
    }
}

/** A queued message: tap to edit (back into the composer), × to remove (48 dp target). */
@Composable
fun QueueRow(text: String, onEdit: () -> Unit, onRemove: () -> Unit, removeLabel: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Leash.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).pressable(scale = PressScale.Row, onClick = onEdit).heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = c.textSecondary)
            Text(text, style = Leash.type.callout, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).pressable(onClickLabel = removeLabel, onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(LeashIcons.close, removeLabel, Modifier.size(14.dp), tint = c.textSecondary)
        }
    }
}

/** What a removed queued message leaves until its undo window ends. */
@Composable
fun RemovedRow(text: String, undoLabel: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Leash.type.callout, color = Leash.colors.textTertiary, modifier = Modifier.weight(1f))
        Text(undoLabel, style = Leash.type.callout.semibold(), color = Leash.colors.accent,
            modifier = Modifier.pressable(scale = PressScale.Text, onClick = onUndo).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 12.dp))
    }
}

/** The composer's mode (it tints the slab and swaps the leading control). */
enum class ComposerMode { Normal, Shell, SideQuestion }

/**
 * The composer slab (iOS slab): glass, composer radius, ≥ 48 dp; leading 34 dp control, the text field (1–8 lines,
 * growing upward, mono in shell mode), trailing [action] (the send/stop circle). Status lines and attachments go in
 * [above], inside the slab. Disabled = 50% alpha. The caller puts it in the bottom dock and applies imePadding.
 */
@Composable
fun ComposerSlab(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mode: ComposerMode = ComposerMode.Normal,
    enabled: Boolean = true,
    blur: Boolean = false,
    leadingLabel: String = "",
    onLeading: () -> Unit = {},
    above: (@Composable ColumnScope.() -> Unit)? = null,
    action: @Composable RowScope.() -> Unit,
) {
    val c = Leash.colors
    val tint = when (mode) {
        ComposerMode.Shell -> c.shell.copy(alpha = 0.16f)
        ComposerMode.SideQuestion -> c.accent.copy(alpha = 0.14f)
        ComposerMode.Normal -> Color.Unspecified
    }
    val textStyle = (if (mode == ComposerMode.Shell) Leash.type.mono.copy(fontSize = Leash.type.body.fontSize, lineHeight = Leash.type.body.lineHeight) else Leash.type.body)
        .copy(color = c.textPrimary)
    Column(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).glass(LeashShapes.composer, tint = tint, blur = blur)
            .animateContentSize(Motion.quick()).heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        above?.invoke(this)
        Row(verticalAlignment = Alignment.Bottom) {
            val (icon, ink) = when (mode) {
                ComposerMode.Shell -> LeashIcons.terminal to c.shell
                ComposerMode.SideQuestion -> LeashIcons.sideQuestion to c.accent
                ComposerMode.Normal -> LeashIcons.plus to c.textSecondary
            }
            Box(Modifier.size(40.dp).pressable(enabled, onClickLabel = leadingLabel, onClick = onLeading), contentAlignment = Alignment.Center) {
                Icon(icon, leadingLabel, Modifier.size(22.dp), tint = ink)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = textStyle,
                cursorBrush = SolidColor(if (mode == ComposerMode.Shell) c.shell else c.accent),
                maxLines = 8,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (mode == ComposerMode.Shell) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = mode != ComposerMode.Shell,
                ),
                modifier = Modifier.weight(1f).padding(top = 9.dp, bottom = 9.dp, start = 2.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = c.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, content = action)
        }
    }
}

/** A status line inside the composer slab (shell cwd, side-question note, error with dismiss). */
@Composable
fun ComposerStatus(text: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null, dismissLabel: String = "") {
    Row(modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Text(text, style = Leash.type.caption.medium(), color = if (onDismiss != null) Leash.colors.textPrimary else color, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
        if (onDismiss != null) {
            Box(Modifier.size(32.dp).pressable(onClickLabel = dismissLabel, onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(LeashIcons.close, dismissLabel, Modifier.size(12.dp), tint = Leash.colors.textSecondary)
            }
        }
    }
}

/**
 * The chips under the composer, scrolling sideways. Give it the full screen width: [edge] pads the chips inside the
 * scroll, so they scroll on to the screen edge instead of being cut at the dock's inset (IMG_5522).
 */
@Composable
fun ChipRow(modifier: Modifier = Modifier, edge: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = edge),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
