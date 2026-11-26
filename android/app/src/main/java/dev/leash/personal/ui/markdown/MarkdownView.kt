package dev.leash.personal.ui.markdown

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leash.personal.R
import dev.leash.personal.ui.theme.LeashTheme
import kotlinx.coroutines.launch

/** Inline markdown as styled text; links open in the browser, file paths call [onOpenFile]. */
@Composable
fun rememberInline(text: String, onOpenFile: (String) -> Unit, baseColor: Color = Color.Unspecified): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    val colors = LeashTheme.colors
    val spans = remember(text) { MarkdownInline.parse(text) }
    val linkStyle = TextLinkStyles(SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline))
    return remember(spans, scheme, colors, baseColor) {
        buildAnnotatedString {
            for (s in spans) {
                val style = SpanStyle(
                    color = if (s.code) colors.codeInk else baseColor,
                    fontWeight = if (s.bold) FontWeight.SemiBold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null,
                    fontSize = if (s.code) 14.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    background = if (s.code) colors.codeBg else Color.Unspecified,
                )
                val path = s.filePath
                when {
                    path != null -> withLink(LinkAnnotation.Clickable("file", linkStyle) { onOpenFile(path) }) { withStyle(style) { append(s.text) } }
                    s.href != null -> withLink(LinkAnnotation.Url(s.href, linkStyle)) { withStyle(style) { append(s.text) } }
                    else -> withStyle(style) { append(s.text) }
                }
            }
        }
    }
}

/** An agent's markdown: headings, lists, quotes, code, tables, rules (ios MarkdownView). Images and embeds are placeholders until phase 4. */
@Composable
fun MarkdownView(text: String, onOpenFile: (String) -> Unit, modifier: Modifier = Modifier, textColor: Color = MaterialTheme.colorScheme.onBackground) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { b -> MarkdownBlock(b, onOpenFile, textColor) }
    }
}

private val bodyStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)

@Composable
private fun MarkdownBlock(b: Block, onOpenFile: (String) -> Unit, color: Color) {
    val scheme = MaterialTheme.colorScheme
    when (b) {
        is Block.Heading -> {
            val size = when (b.level) { 1 -> 22.sp; 2 -> 19.sp; 3 -> 17.sp; else -> 16.sp }
            Text(rememberInline(b.text, onOpenFile), Modifier.padding(top = 4.dp), style = bodyStyle.copy(fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.SemiBold), color = color)
        }
        is Block.Paragraph -> Text(rememberInline(b.text, onOpenFile), style = bodyStyle, color = color)
        is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
            Text("•", Modifier.width(20.dp), style = bodyStyle, color = scheme.onSurfaceVariant)
            Text(rememberInline(b.text, onOpenFile), Modifier.weight(1f), style = bodyStyle, color = color)
        }
        is Block.Numbered -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
            Text(b.number + ".", Modifier.width(26.dp), style = bodyStyle, color = scheme.onSurfaceVariant)
            Text(rememberInline(b.text, onOpenFile), Modifier.weight(1f), style = bodyStyle, color = color)
        }
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(LeashTheme.colors.hairline, MaterialTheme.shapes.extraSmall))
            Spacer(Modifier.width(10.dp))
            Text(rememberInline(b.text, onOpenFile), style = bodyStyle, color = scheme.onSurfaceVariant)
        }
        is Block.Code -> CodeBlock(b.code, b.lang)
        is Block.Table -> MarkdownTable(b, onOpenFile, color)
        Block.Rule -> HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
        is Block.Embed -> Placeholder(Icons.Filled.Widgets, stringResource(R.string.embed_placeholder))
        is Block.Image -> Placeholder(Icons.Filled.Image, stringResource(R.string.image_placeholder, b.alt.ifBlank { b.src.substringAfterLast('/') }))
        is Block.MentionedImages -> Unit   // shown only when they load (phase 4)
    }
}

@Composable
private fun Placeholder(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(shape = MaterialTheme.shapes.small, color = LeashTheme.colors.sunken) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CodeBlock(code: String, lang: String, modifier: Modifier = Modifier) {
    val colors = LeashTheme.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = colors.codeBg) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lang, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code))) } }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy), Modifier.size(16.dp), tint = colors.textTertiary)
                }
            }
            Text(
                code, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = if (lang.isEmpty()) 12.dp else 0.dp),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), color = colors.codeInk, softWrap = false,
            )
        }
    }
}

/** GFM table as an inset card: columns sized to their longest cell, scrolls sideways when it must. */
@Composable
private fun MarkdownTable(t: Block.Table, onOpenFile: (String) -> Unit, color: Color) {
    val n = t.header.size
    val widths = remember(t) {
        (0 until n).map { c ->
            val longest = (listOf(t.header) + t.rows).maxOf { r -> (r.getOrNull(c) ?: "").length }
            (longest * 8 + 28).coerceIn(64, 260).dp
        }
    }
    val numeric = remember(t) {
        (0 until n).map { c -> t.align.getOrElse(c) { 0 } == 0 && t.rows.isNotEmpty() && t.rows.all { r -> (r.getOrNull(c) ?: "").trim().let { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch in ".,%+-−kMB " } } } }
    }
    Surface(shape = MaterialTheme.shapes.small, color = LeashTheme.colors.sunken, border = androidx.compose.foundation.BorderStroke(0.5.dp, LeashTheme.colors.hairline)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(t.header) + t.rows).forEachIndexed { ri, row ->
                Row {
                    for (c in 0 until n) {
                        val a = t.align.getOrElse(c) { 0 }
                        val align = when { a == 1 -> TextAlign.Center; a == 2 || numeric[c] -> TextAlign.End; else -> TextAlign.Start }
                        Text(
                            rememberInline(row.getOrNull(c) ?: "", onOpenFile), Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 8.dp),
                            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (ri == 0) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (ri == 0) MaterialTheme.colorScheme.onSurfaceVariant else color, textAlign = align,
                        )
                    }
                }
                if (ri < t.rows.size) HorizontalDivider(color = LeashTheme.colors.hairline, thickness = 0.5.dp)
            }
        }
    }
}
