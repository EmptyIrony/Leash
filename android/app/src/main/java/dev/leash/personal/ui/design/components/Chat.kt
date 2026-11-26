package dev.leash.personal.ui.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.LiveDots
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.RingGauge
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.bold
import dev.leash.personal.ui.design.italic
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.rememberLivePhase
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.shimmer
import dev.leash.personal.ui.design.tabular

/**
 * The user's message: accent-tinted bubble, tail corner bottom-end, ≥ 48 dp free on its leading side. [selectable]
 * lets a long-press select its text (the platform's selection) instead of [onLongClick].
 */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, selectable: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        var m: Modifier = Modifier
        if (onLongClick != null) m = m.pressable(scale = PressScale.Row, role = null, onLongClick = onLongClick, onClick = {})
        val bubble: @Composable () -> Unit = {
            Text(
                text,
                style = Leash.type.reading,
                color = Leash.colors.textPrimary,
                modifier = m.clip(LeashShapes.bubble).background(Leash.colors.bubbleFill).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (selectable) androidx.compose.foundation.text.selection.SelectionContainer { bubble() } else bubble()
    }
}

/** A "!" shell command the user ran: "!" in shell colour + the command in mono, on a shell-tinted bubble. */
@Composable
fun ShellBubble(command: String, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Row(modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.clip(LeashShapes.bubble).background(c.shellBubbleFill).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("!", style = Leash.type.mono.copy(fontSize = Leash.type.callout.fontSize).bold(), color = c.shell)
            Text(command, style = Leash.type.mono.copy(fontSize = Leash.type.callout.fontSize), color = c.textPrimary)
        }
    }
}

/** A slash command the user sent: a quiet capsule. */
@Composable
fun CommandCapsule(command: String, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Row(modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.clip(LeashShapes.capsule).background(c.hairline).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LeashIcons.command, null, Modifier.size(14.dp), tint = c.textSecondary)
            Text(command, style = Leash.type.mono.copy(fontSize = Leash.type.callout.fontSize), color = c.textSecondary)
        }
    }
}

/** The activity header's state (its leading glyph and colours). */
enum class ActivityState { Live, Blocked, Done, Failed, Stopped }

/**
 * One collapsible row per agent turn (iOS ActivityRow header): an 18 dp glyph column (live dots / done ring / failed /
 * stopped / blocked hand), the header words with the shimmer while live, a rotating chevron, an optional checklist
 * ring + "2/4 steps", and a one-line sub-line. [phase] can pin the shimmer for screenshots.
 */
@Composable
fun ActivityHeader(
    title: String,
    state: ActivityState,
    modifier: Modifier = Modifier,
    subLine: String? = null,
    expanded: Boolean = false,
    checklist: Pair<Int, Int>? = null,
    checklistLabel: String? = null,
    fixedPhase: Float? = null,
    onClick: () -> Unit = {},
    onChecklistClick: () -> Unit = {},
) {
    val c = Leash.colors
    val live = state == ActivityState.Live
    val phase = rememberLivePhase(live, fixedPhase)
    var titleWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val rot by animateFloatAsState(if (expanded) 180f else 0f, Motion.disclose(), label = "chevron")
    val swap = Motion.quick<Float>()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Row(
            Modifier.weight(1f).pressable(scale = PressScale.Text, onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(18.dp).height(19.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(state, transitionSpec = { fadeIn(swap) togetherWith fadeOut(swap) }, label = "glyph") { s ->
                    when (s) {
                        ActivityState.Live -> LiveDots(phase, with(density) { titleWidth.toDp() })
                        ActivityState.Blocked -> Icon(LeashIcons.needsYou, null, Modifier.size(15.dp), tint = c.attention)
                        ActivityState.Done -> Icon(LeashIcons.done, null, Modifier.size(16.dp), tint = c.textTertiary)
                        ActivityState.Failed -> Icon(LeashIcons.failed, null, Modifier.size(16.dp), tint = c.attention)
                        ActivityState.Stopped -> Icon(LeashIcons.stopped, null, Modifier.size(16.dp), tint = c.textTertiary)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = Leash.type.subheadline.medium().tabular(),
                        color = c.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.onSizeChanged { titleWidth = it.width }.shimmer(live, phase, leadingInset = 26.dp),
                    )
                    Icon(LeashIcons.chevronDown, null, Modifier.size(14.dp).rotate(rot), tint = c.textTertiary)
                }
                if (subLine != null) {
                    AnimatedContent(subLine, transitionSpec = { fadeIn(swap) togetherWith fadeOut(swap) }, label = "sub") { s ->
                        Text(
                            s,
                            style = Leash.type.footnote,
                            color = when (state) {
                                ActivityState.Blocked -> c.attention
                                else -> c.textSecondary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
            }
        }
        if (checklist != null) {
            Row(
                Modifier.pressable(scale = PressScale.Text, onClick = onChecklistClick).padding(start = 6.dp, top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RingGauge(checklist.first.toFloat() / checklist.second.coerceAtLeast(1), size = 13.dp, stroke = 1.8.dp, tint = if (live) c.accent else c.textTertiary)
                Text(checklistLabel ?: "${checklist.first}/${checklist.second}", style = Leash.type.footnote.tabular(), color = c.textSecondary)
            }
        }
    }
}

/** What a step row's title is: drives its type style. */
enum class StepKind { Thought, Note, Command, Edit, Other }

/**
 * A row in an opened activity: 16 dp tertiary glyph, title (thought italic secondary, command/edit mono, other
 * footnote), trailing +a −d / bolt / failure / spinner. Opens in place ([open] drops the line limit).
 */
@Composable
fun StepRow(
    icon: ImageVector,
    title: String,
    kind: StepKind,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    adds: Int? = null,
    dels: Int? = null,
    autoApproved: Boolean = false,
    failed: Boolean = false,
    running: Boolean = false,
    onClick: () -> Unit = {},
    annotatedTitle: androidx.compose.ui.text.AnnotatedString? = null,
    detail: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Leash.colors
    val style = when (kind) {
        StepKind.Thought -> Leash.type.footnote.italic().copy(color = c.textSecondary)
        StepKind.Note -> Leash.type.footnote.copy(color = c.textSecondary)
        StepKind.Command, StepKind.Edit -> Leash.type.mono.copy(color = c.textPrimary)
        StepKind.Other -> Leash.type.footnote.copy(color = c.textPrimary)
    }
    Column(modifier.padding(start = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().pressable(scale = if (open) PressScale.Text else PressScale.Row, onClick = onClick).heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = c.textTertiary)
            if (annotatedTitle != null) Text(annotatedTitle, style = style, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            else Text(title, style = style, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (adds != null) Text("+$adds", style = Leash.type.monoSmall, color = c.success)
                if (dels != null) Text("−$dels", style = Leash.type.monoSmall, color = c.danger)
                if (autoApproved) Icon(LeashIcons.turbo, null, Modifier.size(11.dp), tint = c.turbo)
                if (failed) Icon(LeashIcons.close, null, Modifier.size(12.dp), tint = c.danger)
                else if (running) Spinner(size = 11.dp)
            }
        }
        if (open && detail != null) {
            Column(Modifier.padding(start = 24.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = detail)
        }
    }
}

/**
 * The rail along an opened activity's steps: a 1.5 dp hairline at x = 9 dp, drawn behind the rows (no intrinsic
 * measurement, so rows may hold lazy or constraint-reading content like a diff).
 */
@Composable
fun StepRail(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val hairline = Leash.colors.hairline
    Column(
        modifier.fillMaxWidth()
            .drawBehind {
                val x = 9.75.dp.toPx()
                drawLine(hairline, androidx.compose.ui.geometry.Offset(x, 4.dp.toPx()), androidx.compose.ui.geometry.Offset(x, size.height - 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
            .padding(start = 10.5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** A line in a [DiffBlock]. */
sealed interface DiffLineModel {
    data class Same(val text: String) : DiffLineModel
    data class Add(val text: String) : DiffLineModel
    data class Del(val text: String) : DiffLineModel
    data class Gap(val label: String) : DiffLineModel
}

/**
 * A compact diff: sunken inset box with a hairline; header strip (path, head-truncated, and +a −d), lines in monoSmall
 * on add/del tints with a sign column, gap labels, and an optional plain toggle at the bottom.
 */
@Composable
fun DiffBlock(
    path: String,
    adds: Int,
    dels: Int,
    lines: List<DiffLineModel>,
    modifier: Modifier = Modifier,
    toggleLabel: String? = null,
    onToggle: () -> Unit = {},
    onPath: () -> Unit = {},
) {
    val c = Leash.colors
    Column(modifier.fillMaxWidth().clip(LeashShapes.inset).background(c.bgSunken).border(1.dp, c.hairline, LeashShapes.inset)) {
        Row(Modifier.fillMaxWidth().background(c.hairline).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                path, style = Leash.type.monoSmall.copy(fontSize = Leash.type.caption.fontSize), color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f).pressable(scale = PressScale.Text, onClick = onPath),
            )
            Text("+$adds", style = Leash.type.monoSmall.copy(fontSize = Leash.type.caption.fontSize), color = c.success)
            Text("−$dels", style = Leash.type.monoSmall.copy(fontSize = Leash.type.caption.fontSize), color = c.danger)
        }
        // every line as wide as the widest (or the box), so the add/del tints run edge to edge while it scrolls sideways
        androidx.compose.foundation.layout.BoxWithConstraints {
          val boxWidth = maxWidth
          Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp).widthIn(min = boxWidth).width(IntrinsicSize.Max)) {
            lines.forEach { l ->
                when (l) {
                    is DiffLineModel.Gap -> Text(l.label, style = Leash.type.caption2, color = c.textTertiary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    else -> {
                        val (sign, text, bg) = when (l) {
                            is DiffLineModel.Add -> Triple("+", l.text, c.diffAddBg)
                            is DiffLineModel.Del -> Triple("−", l.text, c.diffDelBg)
                            is DiffLineModel.Same -> Triple(" ", l.text, Color.Transparent)
                            else -> Triple("", "", Color.Transparent)
                        }
                        Text(
                            "$sign $text", style = Leash.type.monoSmall, color = c.textPrimary, softWrap = false,
                            modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
            }
          }
        }
        if (toggleLabel != null) {
            Text(toggleLabel, style = Leash.type.caption, color = c.accent, modifier = Modifier.pressable(scale = PressScale.Text, onClick = onToggle).padding(8.dp))
        }
    }
}

/** A fenced code block: sunken inset box, language + copy header, mono 13 that scrolls sideways and never wraps. */
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, language: String? = null, copyLabel: String = "Copy", onCopy: () -> Unit = {}) {
    val c = Leash.colors
    Column(modifier.fillMaxWidth().clip(LeashShapes.inset).background(c.bgSunken).border(1.dp, c.hairline, LeashShapes.inset)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(language ?: "code", style = Leash.type.caption2, color = c.textTertiary, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).pressable(onClickLabel = copyLabel, onClick = onCopy),
                contentAlignment = Alignment.Center,
            ) { Icon(LeashIcons.copy, copyLabel, Modifier.size(15.dp), tint = c.textTertiary) }
        }
        Text(
            code, style = Leash.type.mono.copy(lineHeight = Leash.type.mono.lineHeight), color = c.textPrimary, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )
    }
}

/** A raised card (plan, table, quota, notices): raised fill, card radius, 1 dp hairline, 14 dp padding. */
@Composable
fun RaisedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Leash.colors
    Column(
        modifier.fillMaxWidth().clip(LeashShapes.card).background(c.bgRaised).border(1.dp, c.hairline, LeashShapes.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A card's header: glyph + subheadline SemiBold, trailing count, disclosure chevron. */
@Composable
fun CardHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier, count: String? = null, open: Boolean? = null, onClick: () -> Unit = {}) {
    val c = Leash.colors
    val rot by animateFloatAsState(if (open == true) 90f else 0f, Motion.disclose(), label = "chev")
    Row(modifier.fillMaxWidth().pressable(scale = PressScale.Text, onClick = onClick), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(17.dp), tint = c.textPrimary)
        Text(title, style = Leash.type.subheadline.semibold(), color = c.textPrimary, modifier = Modifier.weight(1f))
        if (count != null) Text(count, style = Leash.type.caption.tabular(), color = c.textTertiary)
        if (open != null) Icon(LeashIcons.chevronRight, null, Modifier.size(14.dp).rotate(rot), tint = c.textTertiary)
    }
}

/** "N files changed +a −d": sunken row, row radius, hairline, 44 dp; a chevron only when it opens something. */
@Composable
fun ChangesRow(text: String, adds: Int, dels: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Leash.colors
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(scale = PressScale.Row, onClick = onClick) else Modifier).heightIn(min = 44.dp)
            .clip(LeashShapes.row).background(c.bgSunken).border(1.dp, c.hairline, LeashShapes.row).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LeashIcons.diff, null, Modifier.size(17.dp), tint = c.textSecondary)
        Text(text, style = Leash.type.subheadline, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text("+$adds", style = Leash.type.footnote.tabular(), color = c.success)
        Text("−$dels", style = Leash.type.footnote.tabular(), color = c.danger)
        if (onClick != null) Icon(LeashIcons.chevronRight, null, Modifier.size(16.dp), tint = c.textTertiary)
    }
}

/** "Neon · High · ↑12.4k ↓1.8k · 62 tok/s" under an answer. */
@Composable
fun StatsLine(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Text(
        text, style = Leash.type.monoSmall, color = Leash.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.pressable(scale = PressScale.Text, onClick = onClick),
    )
}
