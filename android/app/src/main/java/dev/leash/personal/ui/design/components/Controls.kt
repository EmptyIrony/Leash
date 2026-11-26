package dev.leash.personal.ui.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import dev.leash.personal.ui.design.capScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.glass
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.rememberHaptics
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.tabular

/** Button sizes (DESIGN.md §11): heights are minimums so large font scales grow them. */
enum class ButtonSize(val height: Dp, val padding: Dp) { Large(50.dp, 20.dp), Regular(44.dp, 16.dp), Small(32.dp, 12.dp) }

enum class ButtonStyle { Prominent, Tinted, Destructive, Plain }

/**
 * Leash's button: a capsule with press-scale feedback instead of a ripple. Prominent = accentControl fill (one per
 * view), Tinted = iOS bordered (soft fill, accent text), Destructive = danger tinted, Plain = text only.
 */
@Composable
fun LeashButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Prominent,
    size: ButtonSize = ButtonSize.Regular,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Leash.colors
    // white on the light accent; in dark the accent is light, so its label is the page ink (7.4:1 instead of 2.7:1).
    // A disabled prominent button turns neutral instead of a muddy 40% accent.
    val (fill, ink) = when {
        style == ButtonStyle.Prominent && !enabled -> c.hairline to c.textTertiary
        style == ButtonStyle.Prominent -> c.accentControl to c.onAccent
        style == ButtonStyle.Tinted -> c.controlFill to c.accent
        style == ButtonStyle.Destructive -> c.danger.copy(alpha = 0.12f) to c.danger
        else -> Color.Transparent to c.accent
    }
    val text_ = if (size == ButtonSize.Small) Leash.type.subheadline.semibold() else Leash.type.body.semibold()
    Row(
        modifier
            .alpha(if (enabled || style == ButtonStyle.Prominent) 1f else 0.4f)
            .pressable(enabled, if (style == ButtonStyle.Plain) PressScale.Text else PressScale.Compact, onClick = onClick)
            .defaultMinSize(minHeight = if (style == ButtonStyle.Plain) 48.dp else size.height)
            .clip(LeashShapes.capsule)
            .background(fill)
            .padding(horizontal = if (style == ButtonStyle.Plain) 4.dp else size.padding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(if (size == ButtonSize.Small) 16.dp else 20.dp), tint = ink)
        Text(text, style = text_, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A glass circle (toolbar 44 dp in a 48 dp target, list bottom actions 52, scroll-to-bottom 36). It sits inside a
 * blurred band (top edge, bottom dock), so it draws tint and rim only unless [blur] is set (the blur budget, §6).
 */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Leash.colors.textPrimary,
    enabled: Boolean = true,
    blur: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
) = GlassCircleSlot(contentDescription, onClick, modifier, size, enabled, blur, badge) {
    Icon(icon, null, Modifier.size(if (size >= 44.dp) 22.dp else 18.dp), tint = tint)
}

/** A glass circle with anything inside it (the quota rings): the same size, touch target and press feedback as [GlassCircleButton]. */
@Composable
fun GlassCircleSlot(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    blur: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(maxOf(size, 48.dp))
            .semantics { this.contentDescription = contentDescription; role = Role.Button }
            .pressable(enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).glass(CircleShape, blur = blur, interactive = enabled), contentAlignment = Alignment.Center, content = content)
        if (badge != null) Box(Modifier.size(size), contentAlignment = Alignment.BottomEnd) { badge() }
    }
}

/** What the composer's primary circle shows; one control whose fill and glyph morph between these. */
enum class SendGlyph { Send, ShellRun, Disabled, ShellDisabled, Stop }

/**
 * The composer's 34 dp send / run / stop circle (iOS primaryGlyph): send = accent + arrow up, shell = shell colour +
 * return, disabled = hairline + tertiary glyph at 0.92, stop = ink fill + stop glyph in page colour.
 */
@Composable
fun SendButton(
    glyph: SendGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** TalkBack custom actions on the circle itself (e.g. "Interrupt and send", the long-press alternative) */
    accessibilityActions: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(),
) {
    val c = Leash.colors
    val fill by animateColorAsState(
        when (glyph) {
            SendGlyph.Send -> c.accent
            SendGlyph.ShellRun -> c.shell
            SendGlyph.Disabled, SendGlyph.ShellDisabled -> c.hairline
            SendGlyph.Stop -> c.textPrimary
        }, Motion.quick(), label = "fill",
    )
    val ink by animateColorAsState(
        when (glyph) {
            SendGlyph.Send, SendGlyph.ShellRun -> c.onAccent
            SendGlyph.Disabled, SendGlyph.ShellDisabled -> c.textTertiary
            SendGlyph.Stop -> c.bgBase
        }, Motion.quick(), label = "ink",
    )
    val disabled = glyph == SendGlyph.Disabled || glyph == SendGlyph.ShellDisabled
    val scale by animateFloatAsState(if (disabled) 0.92f else 1f, Motion.quick(), label = "scale")
    Box(
        modifier
            .size(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
            }
            .pressable(!disabled, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).scale(scale).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            androidx.compose.animation.Crossfade(glyph, animationSpec = Motion.quick(), label = "glyph") { g ->
                val icon = when (g) {
                    SendGlyph.Stop -> LeashIcons.stop
                    SendGlyph.ShellRun, SendGlyph.ShellDisabled -> LeashIcons.run
                    else -> LeashIcons.send
                }
                Icon(icon, null, Modifier.size(if (g == SendGlyph.Stop) 14.dp else 20.dp), tint = ink)
            }
        }
    }
}

/** The smaller stop that slides out of the send circle while a draft waits during a running turn. */
@Composable
fun SecondaryStopButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Box(
        modifier.size(44.dp).semantics { this.contentDescription = contentDescription }.pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).border(1.5.dp, c.hairline, CircleShape), contentAlignment = Alignment.Center) {
            Icon(LeashIcons.stop, null, Modifier.size(12.dp), tint = c.textSecondary)
        }
    }
}

/**
 * iOS's segmented picker: a capsule track with a raised thumb that slides (disclose spring) to the selected segment.
 * No check marks, no outline; selection changes play a Selection haptic.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Leash.colors
    val haptics = rememberHaptics()
    BoxWithConstraints(
        modifier
            .heightIn(min = 32.dp)
            .clip(LeashShapes.capsule)
            .background(c.segmentTrack)
            .padding(2.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(segment * selected, Motion.disclose(), label = "thumb")
        // labels grow with the font scale up to 1.3x, then shrink to fit their segment rather than cut off
        val labelStyle = Leash.type.subheadline.medium().capScale(1.3f)
        val rowHeight = maxOf(28.dp, with(androidx.compose.ui.platform.LocalDensity.current) { labelStyle.lineHeight.toDp() } + 8.dp)
        Box(
            Modifier
                .offset(x = x)
                .width(segment)
                .height(rowHeight)
                .dropShadow(LeashShapes.capsule, Shadow(3.dp, Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = if (c.dark) 0.3f else 0.12f))
                .clip(LeashShapes.capsule)
                .background(c.segmentThumb),
        )
        Row(Modifier.height(rowHeight)) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .width(segment)
                        .fillMaxHeight()
                        .semantics { this.selected = i == selected; role = Role.Tab }
                        .pressable(scale = PressScale.Text, role = Role.Tab) {
                            if (i != selected) { haptics.touch(dev.leash.personal.core.Haptic.Selection); onSelect(i) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.text.BasicText(
                        label,
                        style = labelStyle.copy(color = if (i == selected) c.textPrimary else c.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // sizes are sp: 11 visual dp at any font scale, never above the capped label size
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                            minFontSize = minOf((11f / androidx.compose.ui.platform.LocalDensity.current.fontScale).sp.value, labelStyle.fontSize.value).sp,
                            maxFontSize = labelStyle.fontSize,
                            stepSize = 0.25.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A free-text answer: a sunken well (inset radius, hairline) with body text and a secondary placeholder, growing up to
 * [maxLines]. Replaces Material's outlined text field.
 */
@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    maxLines: Int = 6,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    val c = Leash.colors
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines,
        textStyle = Leash.type.body.copy(color = c.textPrimary),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(LeashShapes.inset).background(c.bgSunken)
                    .border(1.dp, c.hairline, LeashShapes.inset).padding(horizontal = 12.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = Leash.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/** The stock M3 switch in Leash's colours (iOS uses the stock Toggle too). */
@Composable
fun LeashSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Leash.colors
    val haptics = rememberHaptics()
    Switch(
        checked = checked,
        onCheckedChange = { haptics.toggle(it); onCheckedChange(it) },
        modifier = modifier,
        enabled = enabled,
        thumbContent = { Box(Modifier.size(24.dp)) },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.accentControl,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = c.textPrimary.copy(alpha = 0.12f),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * A composer chip: glass capsule 32 dp, icon + footnote Medium + a small chevron. [tint] carries meaning only
 * (turbo, plan). Chips sit inside the bottom dock band, so they don't blur on their own.
 */
@Composable
fun Chip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    tint: Color = Color.Unspecified,
    ink: Color = Leash.colors.textPrimary,
    chevron: Boolean = true,
    blur: Boolean = false,
) {
    Row(
        modifier
            .pressable(onClick = onClick)
            .heightIn(min = 32.dp)
            .glass(LeashShapes.capsule, tint = if (tint.isSpecified) tint.copy(alpha = 0.25f) else Color.Unspecified, blur = blur, interactive = true)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (tint.isSpecified) tint else ink
        if (leading != null) leading() else if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Text(text, style = Leash.type.footnote.medium().tabular(), color = fg, maxLines = 1)
        if (chevron) Icon(LeashIcons.chevronDown, null, Modifier.size(12.dp).alpha(0.6f), tint = fg)
    }
}

/** Connection status pill: dot + caption. */
@Composable
fun StatusPill(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(LeashShapes.capsule).background(Leash.colors.hairline).padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, style = Leash.type.caption.medium(), color = Leash.colors.textPrimary, maxLines = 1)
    }
}

/** Mail's unread dot; keeps its gutter when hidden so titles never shift. */
@Composable
fun UnreadDot(visible: Boolean, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    val s by animateFloatAsState(if (visible) 1f else 0.4f, Motion.quick(), label = "dot")
    val a by animateFloatAsState(if (visible) 1f else 0f, Motion.quick(), label = "dotAlpha")
    Box(modifier.size(size).scale(s).alpha(a).clip(CircleShape).background(Leash.colors.accent))
}

/** The trailing "needs you" mark: a hand in an attention-tinted 26 dp circle. */
@Composable
fun NeedsYouBadge(modifier: Modifier = Modifier) {
    val c = Leash.colors
    Box(modifier.size(26.dp).clip(CircleShape).background(c.needsYouBadge), contentAlignment = Alignment.Center) {
        Icon(LeashIcons.needsYou, null, Modifier.size(13.dp), tint = c.attention)
    }
}

/** Another conversation needs you: an attention-tinted glass capsule under the top bar. */
@Composable
fun Banner(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Row(
        modifier
            .pressable(scale = PressScale.Row, onClick = onClick)
            .heightIn(min = 40.dp)
            .glass(LeashShapes.capsule, tint = c.attention.copy(alpha = 0.25f), interactive = true)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LeashIcons.needsYou, null, Modifier.size(16.dp), tint = c.attention)
        Text(text, style = Leash.type.subheadline.medium().capScale(1.3f), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(LeashIcons.chevronRight, null, Modifier.size(14.dp), tint = c.textTertiary)
    }
}
