package dev.leash.personal.ui.markdown

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.leash.personal.R
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.components.CodeBlock as CodeBlockView
import dev.leash.personal.ui.design.components.Hairline
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.embed.AgentEmbedView
import kotlinx.coroutines.launch

/** The annotation tag that marks inline code; [InlineText] paints its rounded background. */
internal const val CODE_TAG = "leash.code"

/** Inline markdown as styled text; links open in the browser, file paths call [onOpenFile]. Inline code is tagged [CODE_TAG]. */
@Composable
fun rememberInline(text: String, onOpenFile: (String) -> Unit, baseColor: Color = Color.Unspecified): AnnotatedString {
    val c = Leash.colors
    val spans = remember(text) { MarkdownInline.parse(text) }
    val linkStyle = TextLinkStyles(SpanStyle(color = c.accent))
    return remember(spans, c, baseColor) {
        buildAnnotatedString {
            for (s in spans) {
                val style = SpanStyle(
                    color = if (s.code) c.codeInk else baseColor,
                    fontWeight = if (s.bold) FontWeight.SemiBold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null,
                    fontSize = if (s.code) 0.92.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                )
                val emit: AnnotatedString.Builder.() -> Unit = {
                    if (s.code) withAnnotation(CODE_TAG, "") { withStyle(style) { append(s.text) } } else withStyle(style) { append(s.text) }
                }
                val path = s.filePath
                when {
                    path != null -> withLink(LinkAnnotation.Clickable("file", linkStyle) { onOpenFile(path) }) { emit() }
                    s.href != null -> withLink(LinkAnnotation.Url(s.href, linkStyle)) { emit() }
                    else -> emit()
                }
            }
        }
    }
}

/**
 * Text whose inline code gets iOS's look: a codeBg fill with 4 dp continuous-looking corners, inset from the line box
 * so wrapped lines don't fuse into one block (a SpanStyle background can't do either).
 */
@Composable
fun InlineText(text: AnnotatedString, style: TextStyle, color: Color, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val codeBg = Leash.colors.codeBg
    val ranges = remember(text) { text.getStringAnnotations(CODE_TAG, 0, text.length).map { it.start to it.end } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val m = if (ranges.isEmpty()) modifier else modifier.drawBehind {
        val l = layout ?: return@drawBehind
        val r = 4.dp.toPx()
        val vInset = 2.dp.toPx()
        val hPad = 2.dp.toPx()
        for ((start, end) in ranges) {
            if (start >= end || end > l.layoutInput.text.length) continue
            val first = l.getLineForOffset(start)
            val last = l.getLineForOffset(end - 1)
            for (line in first..last) {
                val s = maxOf(start, l.getLineStart(line))
                val e = minOf(end, l.getLineEnd(line, visibleEnd = true))
                if (s >= e) continue
                val x1 = l.getHorizontalPosition(s, usePrimaryDirection = true)
                val x2 = l.getHorizontalPosition(e, usePrimaryDirection = true)
                val left = minOf(x1, x2) - hPad
                val right = maxOf(x1, x2) + hPad
                val top = l.getLineTop(line) + vInset
                val bottom = l.getLineBottom(line) - vInset
                drawRoundRect(codeBg, Offset(left, top), Size(right - left, bottom - top), CornerRadius(r, r))
            }
        }
    }
    Text(text, m, style = style, color = color, textAlign = textAlign, onTextLayout = { layout = it })
}

/** An agent's markdown: headings, lists, quotes, code, tables, rules, images and widgets (ios MarkdownView). */
@Composable
fun MarkdownView(text: String, onOpenFile: (String) -> Unit, modifier: Modifier = Modifier, textColor: Color = Leash.colors.textPrimary) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b -> MarkdownBlock(b, onOpenFile, textColor) }
    }
}

@Composable
private fun MarkdownBlock(b: Block, onOpenFile: (String) -> Unit, color: Color) {
    val c = Leash.colors
    val body = Leash.type.reading
    when (b) {
        is Block.Heading -> {
            val style = when (b.level) {
                1 -> Leash.type.title3
                2 -> Leash.type.headline.copy(fontSize = Leash.type.headline.fontSize * 1.0625f, lineHeight = Leash.type.title3.lineHeight)
                else -> Leash.type.headline
            }
            InlineText(rememberInline(b.text, onOpenFile), style.semibold(), color, Modifier.padding(top = 6.dp))
        }
        is Block.Paragraph -> InlineText(rememberInline(b.text, onOpenFile), body, color)
        is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
            // a 4 dp tertiary dot centred on the first line
            Box(Modifier.width(20.dp).height(with(androidx.compose.ui.platform.LocalDensity.current) { body.lineHeight.toDp() }), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.padding(start = 4.dp).size(4.dp).clip(LeashShapes.capsule).background(c.textTertiary))
            }
            InlineText(rememberInline(b.text, onOpenFile), body, color, Modifier.weight(1f))
        }
        is Block.Numbered -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
            Text(b.number + ".", Modifier.width(26.dp), style = body.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
            InlineText(rememberInline(b.text, onOpenFile), body, color, Modifier.weight(1f))
        }
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.padding(vertical = 2.dp).width(2.dp).fillMaxHeight().clip(LeashShapes.capsule).background(c.accent.copy(alpha = 0.35f)))
            InlineText(rememberInline(b.text, onOpenFile), body, c.textSecondary, Modifier.padding(start = 12.dp))
        }
        is Block.Code -> CodeBlock(b.code, b.lang)
        is Block.Table -> MarkdownTable(b, onOpenFile, color)
        Block.Rule -> Hairline(Modifier.padding(vertical = 8.dp))
        is Block.Embed -> AgentEmbedView(b.src, Modifier.padding(vertical = 2.dp))
        is Block.Image -> MarkdownImage(b.src, b.alt, modifier = Modifier.padding(vertical = 2.dp))
        is Block.MentionedImages -> b.srcs.forEach { MarkdownImage(it, quiet = true) }   // shown only when they load
    }
}

/** A fenced code block: the design system's sunken block with the language and a copy button. */
@Composable
fun CodeBlock(code: String, lang: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    CodeBlockView(
        code, modifier, language = lang.ifEmpty { stringResource(R.string.code_label) }, copyLabel = stringResource(R.string.copy),
        onCopy = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code))) } },
    )
}

/** GFM table as a raised card: columns sized to their longest cell, scrolls sideways when it must. */
@Composable
private fun MarkdownTable(t: Block.Table, onOpenFile: (String) -> Unit, color: Color) {
    val c = Leash.colors
    val n = t.header.size
    val widths = remember(t) {
        (0 until n).map { col ->
            val longest = (listOf(t.header) + t.rows).maxOf { r -> (r.getOrNull(col) ?: "").length }
            (longest * 8 + 28).coerceIn(64, 260).dp
        }
    }
    val numeric = remember(t) {
        (0 until n).map { col -> t.align.getOrElse(col) { 0 } == 0 && t.rows.isNotEmpty() && t.rows.all { r -> (r.getOrNull(col) ?: "").trim().let { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch in ".,%+-−kMB " } } } }
    }
    Box(Modifier.clip(LeashShapes.card).background(c.bgRaised).border(1.dp, c.hairline, LeashShapes.card)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(t.header) + t.rows).forEachIndexed { ri, row ->
                Row {
                    for (col in 0 until n) {
                        val a = t.align.getOrElse(col) { 0 }
                        val align = when { a == 1 -> TextAlign.Center; a == 2 || numeric[col] -> TextAlign.End; else -> TextAlign.Start }
                        InlineText(
                            rememberInline(row.getOrNull(col) ?: "", onOpenFile),
                            if (ri == 0) Leash.type.subheadline.semibold() else Leash.type.subheadline.copy(fontFeatureSettings = if (numeric[col]) "tnum" else null),
                            if (ri == 0) c.textSecondary else color,
                            Modifier.width(widths[col]).padding(horizontal = 12.dp, vertical = 9.dp),
                            textAlign = align,
                        )
                    }
                }
                if (ri < t.rows.size) Box(Modifier.width(widths.fold(0.dp) { acc, w -> acc + w }).height(with(androidx.compose.ui.platform.LocalDensity.current) { 1f.toDp() }).background(c.hairline))
            }
        }
    }
}
