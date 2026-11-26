package dev.leash.personal.ui.files

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BridgeError
import dev.leash.personal.core.FileLimits
import dev.leash.personal.core.fetchFile
import dev.leash.personal.core.readTextPreview
import dev.leash.personal.core.splitLineSuffix
import dev.leash.personal.ui.chat.LocalOpenFile
import dev.leash.personal.ui.chat.decodeImage
import dev.leash.personal.ui.components.LeashFullScreen
import dev.leash.personal.ui.components.LocalAppModel
import dev.leash.personal.ui.components.LocalTranscript
import dev.leash.personal.ui.components.Transcript
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.EmptyState
import dev.leash.personal.ui.design.components.GlassCircleButton
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.LeashMenu
import dev.leash.personal.ui.design.components.LeashMenuItem
import dev.leash.personal.ui.design.components.LoadingState
import dev.leash.personal.ui.design.components.TitleAlign
import dev.leash.personal.ui.design.semibold
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the file viewer was asked to show: a file on the agent's host, or an image the transcript already holds. */
sealed interface ViewTarget {
    data class Path(val path: String) : ViewTarget
    class Bytes(val name: String, val data: ByteArray) : ViewTarget
}

/** A longer line is cut when drawn (a minified bundle on one line would cost more than it shows). */
private const val MAX_LINE_CHARS = 2_000

internal sealed interface Loaded {
    class Text(val lines: List<String>, val raw: String, val file: File?, val previewOnly: Boolean, val startLine: Int?) : Loaded
    class Image(val bitmap: Bitmap, val file: File?, val name: String, val data: ByteArray?) : Loaded
    class Pdf(val file: File) : Loaded
    class Other(val file: File, val size: Long, val mime: String) : Loaded
    class TooLarge(val message: String) : Loaded
}

internal fun textLines(raw: String): List<String> = raw.split('\n').map { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…" else it }

/** Fetches and classifies the file ([ViewTarget.Path]); a Text file the bridge can only preview comes back with [Loaded.Text.previewOnly]. */
internal suspend fun loadTarget(context: Context, model: AppModel, target: ViewTarget, agent: String, sessionId: String?): Loaded = when (target) {
    is ViewTarget.Bytes -> withContext(Dispatchers.Default) {
        val bmp = decodeImage(target.data, 4096)
        if (bmp != null) Loaded.Image(bmp, null, target.name, target.data)
        else Loaded.Other(FileCache.write(context, target.name, target.data), target.data.size.toLong(), FileKinds.mimeFor(target.name, null))
    }
    is ViewTarget.Path -> {
        val (path, line) = splitLineSuffix(target.path)
        try {
            val f = model.fetchFile(path, agent, sessionId)
            withContext(Dispatchers.Default) {
                val file = FileCache.write(context, f.name, f.data)
                val mime = FileKinds.mimeFor(f.name, f.mimeType)
                when (FileKinds.viewerKind(f.name, f.mimeType, f.binary, f.data.size)) {
                    ViewerKind.Image -> decodeImage(f.data, 4096)?.let { Loaded.Image(it, file, f.name, null) } ?: Loaded.Other(file, f.data.size.toLong(), mime)
                    ViewerKind.Pdf -> Loaded.Pdf(file)
                    ViewerKind.Text -> FileKinds.decodeUtf8(f.data)?.let { Loaded.Text(textLines(it), it, file, false, line) } ?: Loaded.Other(file, f.data.size.toLong(), mime)
                    ViewerKind.Other -> Loaded.Other(file, f.data.size.toLong(), mime)
                }
            }
        } catch (e: BridgeError) {
            if (e.code != FileLimits.FILE_TOO_LARGE) throw e
            // can't hand over the whole file; a text file still shows its start
            val r = runCatching { model.readTextPreview(path, agent, sessionId) }.getOrNull()
            if (r?.text != null && !r.binary) Loaded.Text(textLines(r.text), r.text, null, true, line) else Loaded.TooLarge(e.message)
        }
    }
}

private fun ViewTarget.title(): String = when (this) {
    is ViewTarget.Path -> splitLineSuffix(path).first.trimEnd('/').substringAfterLast('/')
    is ViewTarget.Bytes -> name
}

/**
 * Gives a screen's transcript views a way to open files: tool paths, links in markdown, attachment chips, the project
 * browser. [LocalOpenFile] shows the viewer above the screen; [LocalTranscript] says whose host relative paths come from.
 */
@Composable
fun ProvideFiles(model: AppModel, agent: String, sessionId: String?, content: @Composable () -> Unit) {
    var target by remember { mutableStateOf<ViewTarget?>(null) }
    CompositionLocalProvider(
        LocalAppModel provides model,
        LocalTranscript provides Transcript(agent, sessionId),
        LocalOpenFile provides { target = ViewTarget.Path(it) },
        LocalOpenImage provides { name, data -> target = ViewTarget.Bytes(name, data) },
        content = content,
    )
    target?.let { FileViewerPage(it, model, agent, sessionId) { target = null } }
}

/** Opens an image the transcript holds in memory (a photo in a message) in the viewer. */
val LocalOpenImage = androidx.compose.runtime.staticCompositionLocalOf<(String, ByteArray) -> Unit> { { _, _ -> } }

/** The viewer page: text with line numbers, images that zoom, PDF pages, or a card that hands the file to another app. */
@Composable
fun FileViewerPage(target: ViewTarget, model: AppModel, agent: String, sessionId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember(target) { mutableStateOf<Loaded?>(null) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(target, attempt) {
        loaded = null; error = null
        try { loaded = loadTarget(context, model, target, agent, sessionId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2_500); toast = null } }

    val title = target.title()
    // a file on the phone for Share / Open with / Save: the viewer's own copy, or one written on demand (a photo from a message)
    suspend fun fileOf(l: Loaded): File? = when (l) {
        is Loaded.Text -> l.file ?: withContext(Dispatchers.IO) { FileCache.write(context, title, l.raw.toByteArray()) }
        is Loaded.Image -> l.file ?: withContext(Dispatchers.IO) { l.data?.let { FileCache.write(context, l.name, it) } }
        is Loaded.Pdf -> l.file
        is Loaded.Other -> l.file
        is Loaded.TooLarge -> null
    }
    fun mimeOf(l: Loaded) = when (l) {
        is Loaded.Other -> l.mime
        is Loaded.Pdf -> "application/pdf"
        is Loaded.Image -> FileKinds.mimeFor(l.name, null)
        else -> FileKinds.mimeFor(title, null)
    }
    val savedText = stringResource(R.string.file_saved)
    val saveFailedText = stringResource(R.string.file_save_failed)
    val noAppText = stringResource(R.string.file_no_app)
    val shareTitle = stringResource(R.string.file_share)
    val openTitle = stringResource(R.string.file_open_with)
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingSave
        if (uri != null && f != null) scope.launch { toast = if (withContext(Dispatchers.IO) { FileCache.copyTo(context, f, uri) }) savedText else saveFailedText }
    }
    val l = loaded
    val hasFile = l != null && l !is Loaded.TooLarge
    val isText = l is Loaded.Text

    LeashFullScreen(
        title = title, onDismiss = onClose, toast = toast, titleAlign = TitleAlign.Start,
        edgeVisible = l is Loaded.Text || l is Loaded.Other,
        actions = {
            if (isText) GlassCircleButton(LeashIcons.wrapText, stringResource(if (wrap) R.string.file_no_wrap else R.string.file_wrap), { wrap = !wrap }, tint = if (wrap) Leash.colors.accentControl else Leash.colors.textPrimary)
            if (hasFile) GlassCircleButton(LeashIcons.share, shareTitle, { scope.launch { l?.let { fileOf(it) }?.let { FileCache.share(context, it, mimeOf(l), shareTitle) } } })
            Box {
                GlassCircleButton(LeashIcons.more, stringResource(R.string.file_more), { menu = true })
                LeashMenu(menu, { menu = false }) {
                    if (hasFile) LeashMenuItem(stringResource(R.string.file_save), { menu = false; scope.launch { l?.let { fileOf(it) }?.let { pendingSave = it; saver.launch(it.name) } } }, icon = LeashIcons.saveFile)
                    if (hasFile) LeashMenuItem(openTitle, {
                        menu = false
                        scope.launch { val f = l?.let { fileOf(it) } ?: return@launch; if (!FileCache.openWith(context, f, mimeOf(l), openTitle)) toast = noAppText }
                    }, icon = LeashIcons.openExternal)
                    if (target is ViewTarget.Path) LeashMenuItem(stringResource(R.string.file_copy_path), {
                        menu = false
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", splitLineSuffix(target.path).first))) }
                    }, icon = LeashIcons.copy)
                }
            }
        },
    ) { top ->
        when {
            error != null -> EmptyState(
                LeashIcons.failed, stringResource(R.string.file_cant_open), Modifier.fillMaxSize().padding(top = top + 80.dp),
                message = error, actionLabel = stringResource(R.string.try_again), onAction = { attempt++ },
            )
            l == null -> LoadingState(stringResource(R.string.file_loading), Modifier.fillMaxWidth().padding(top = top + 96.dp), delayMs = 150)
            l is Loaded.TooLarge -> EmptyState(
                LeashIcons.file, stringResource(R.string.file_too_large), Modifier.fillMaxSize().padding(top = top + 80.dp),
                message = l.message,
                actionLabel = stringResource(R.string.file_copy_path),
                onAction = { (target as? ViewTarget.Path)?.let { t -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", splitLineSuffix(t.path).first))) } } },
            )
            l is Loaded.Text -> TextLines(l.lines, wrap, l.startLine, top, l.previewOnly)
            l is Loaded.Image -> ZoomableImage(l.bitmap, title, Modifier.padding(top = top - 8.dp).navigationBarsPadding())
            l is Loaded.Pdf -> PdfPages(l.file, top)
            l is Loaded.Other -> OtherFile(title, l, top, onShare = { FileCache.share(context, l.file, l.mime, shareTitle) },
                onOpen = { if (!FileCache.openWith(context, l.file, l.mime, openTitle)) toast = noAppText },
                onSave = { pendingSave = l.file; saver.launch(l.file.name) })
        }
    }
}

/** Source and text files: a number gutter, a hairline of highlight on the line a path pointed at ("File.kt:42"), sideways scroll or wrapped lines. */
@Composable
private fun TextLines(lines: List<String>, wrap: Boolean, highlight: Int?, top: Dp, previewOnly: Boolean) {
    val c = Leash.colors
    val style = Leash.type.monoSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digits = lines.size.toString().length.coerceAtLeast(2)
    val gutter = with(density) { measurer.measure("0".repeat(digits), style).size.width.toDp() }
    val longest = remember(lines) { lines.maxByOrNull { it.length } ?: "" }
    val contentW = remember(longest, style) { measurer.measure(longest.replace("\t", "    "), style).size.width }
    val list = rememberLazyListState()
    LaunchedEffect(highlight, lines) { if (highlight != null && highlight in 1..lines.size) list.scrollToItem((highlight - 1 - 3).coerceAtLeast(0)) }
    val hl = c.attention.copy(alpha = 0.22f)
    val rows: @Composable (Modifier) -> Unit = { m ->
        LazyColumn(m, state = list, contentPadding = PaddingValues(top = top, bottom = 24.dp)) {
            itemsIndexed(lines) { i, line ->
                Row(Modifier.fillMaxWidth().background(if (highlight == i + 1) hl else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text((i + 1).toString(), Modifier.width(gutter), style = style, color = c.textTertiary, textAlign = TextAlign.End, softWrap = false)
                    Text(line.replace("\t", "    ").ifEmpty { " " }, if (wrap) Modifier.weight(1f) else Modifier, style = style, color = c.textPrimary, softWrap = wrap)
                }
            }
            if (previewOnly) item("note") {
                Text(stringResource(R.string.file_too_large_note), Modifier.fillMaxWidth().padding(16.dp), style = Leash.type.caption, color = c.textSecondary, textAlign = TextAlign.Center)
            }
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        if (wrap) rows(Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            rows(Modifier.fillMaxHeight().width(gutter + with(density) { contentW.toDp() } + 12.dp + 12.dp + 10.dp + 24.dp))
        }
    }
}

/** A file with no preview here: what it is and how to hand it over. */
@Composable
private fun OtherFile(name: String, f: Loaded.Other, top: Dp, onShare: () -> Unit, onOpen: () -> Unit, onSave: () -> Unit) {
    val c = Leash.colors
    Column(
        Modifier.fillMaxSize().padding(top = top + 56.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(FileKinds.glyph(name).icon(), null, Modifier.size(48.dp), tint = c.accent)
        Text(name, style = Leash.type.title3, color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.MiddleEllipsis)
        Text(FileLimits.sizeText(f.size) + " · " + f.mime, style = Leash.type.footnote, color = c.textSecondary)
        Text(stringResource(R.string.file_no_preview) + "\n" + stringResource(R.string.file_no_preview_detail), style = Leash.type.subheadline, color = c.textSecondary, textAlign = TextAlign.Center)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LeashButton(stringResource(R.string.file_open_with), onOpen, icon = LeashIcons.openExternal)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeashButton(stringResource(R.string.file_share), onShare, style = ButtonStyle.Tinted, size = ButtonSize.Regular, icon = LeashIcons.share)
                LeashButton(stringResource(R.string.file_save), onSave, style = ButtonStyle.Tinted, size = ButtonSize.Regular, icon = LeashIcons.saveFile)
            }
        }
    }
}

/** The glyph for a file kind. */
fun FileGlyph.icon(): ImageVector = when (this) {
    FileGlyph.Pdf -> LeashIcons.pdf
    FileGlyph.Image -> LeashIcons.photo
    FileGlyph.Video -> LeashIcons.video
    FileGlyph.Audio -> LeashIcons.audio
    FileGlyph.Archive -> LeashIcons.archive
    FileGlyph.Table -> LeashIcons.table
    FileGlyph.Slides -> LeashIcons.slides
    FileGlyph.Text -> LeashIcons.read
    FileGlyph.Doc -> LeashIcons.file
}
