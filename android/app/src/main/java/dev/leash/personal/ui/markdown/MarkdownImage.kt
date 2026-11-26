package dev.leash.personal.ui.markdown

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.Issue
import dev.leash.personal.text.errorText
import dev.leash.personal.text.res
import dev.leash.personal.core.Clock
import dev.leash.personal.core.FileLimits
import dev.leash.personal.core.fetchFile
import dev.leash.personal.ui.chat.LocalOpenFile
import dev.leash.personal.ui.chat.decodeImage
import dev.leash.personal.ui.components.LocalAppModel
import dev.leash.personal.ui.components.LocalContentGrew
import dev.leash.personal.ui.components.LocalTranscript
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.files.LocalOpenImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loaded inline images per agent|conversation|src, so a lazy transcript doesn't refetch or jump while scrolling (ios
 * InlineImageCache). Agents rewrite the same file (screenshot.png), so a copy older than [FRESH_MS] is fetched again;
 * a failure is remembered for the same time so scrolling past a path that doesn't exist (yet) isn't a request each time.
 */
object InlineImages {
    class Entry(val bitmap: Bitmap, val data: ByteArray?, val name: String, val fetchedAt: Long)

    const val FRESH_MS = 30_000L

    private val images = object : LruCache<String, Entry>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry) = value.bitmap.allocationByteCount + (value.data?.size ?: 0)
    }
    private val failures = HashMap<String, Pair<String, Long>>()

    fun get(key: String): Entry? = images.get(key)
    fun put(key: String, e: Entry) { images.put(key, e); failures.remove(key) }
    fun failure(key: String): String? = failures[key]?.takeIf { Clock.now() - it.second < FRESH_MS }?.first
    fun fail(key: String, reason: String) { failures[key] = reason to Clock.now() }
}

private val http by lazy { OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }

private class Fetched(val data: ByteArray, val name: String)

private suspend fun fetchImage(src: String, agent: String, sessionId: String?, model: dev.leash.personal.core.AppModel?): Fetched = when (val s = ImageSource.of(src)) {
    is ImageSource.Remote -> withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(s.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw dev.leash.personal.core.BridgeError(r.code, r.message.ifEmpty { "HTTP " + r.code })
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            val body = r.body?.byteStream() ?: throw dev.leash.personal.core.BridgeError(-3, "Empty response", issue = Issue.EmptyResponse)
            while (true) {
                val n = body.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > FileLimits.MAX_INLINE_IMAGE_BYTES) throw dev.leash.personal.core.BridgeError(FileLimits.FILE_TOO_LARGE, "Image is larger than 16 MB", issue = Issue.ImageTooLarge)
            }
            Fetched(out.toByteArray(), ImageSource.fallbackName(s.url))
        }
    }
    is ImageSource.Data -> Fetched(s.bytes, "image")
    is ImageSource.Local -> {
        val m = model ?: throw dev.leash.personal.core.BridgeError(-3, "Not connected to the bridge", issue = Issue.NotConnected)
        val f = m.fetchFile(s.path, agent, sessionId, FileLimits.MAX_INLINE_IMAGE_BYTES)
        Fetched(f.data, f.name)
    }
    ImageSource.Unsupported -> throw dev.leash.personal.core.BridgeError(-3, "Unsupported image source", issue = Issue.UnsupportedImage)
}

/**
 * An image of an answer: `![alt](src)` (web, data: or a path on the agent's host), or a path the agent merely mentions
 * ([quiet]: drawn only when it loads, so a false match never shows an error). Never upscaled (1 px = 1 dp, like iOS points),
 * at most 480 x 320 dp; tap opens the viewer.
 */
@Composable
fun MarkdownImage(src: String, alt: String = "", quiet: Boolean = false, modifier: Modifier = Modifier) {
    val transcript = LocalTranscript.current
    val model = LocalAppModel.current
    val grew = LocalContentGrew.current
    val key = transcript.agent + "|" + (transcript.sessionId ?: "") + "|" + src
    val source = remember(src) { ImageSource.of(src) }
    // read synchronously: a lazy row scrolled back in starts with fresh state, and its first layout must already have the image's height
    var entry by remember(key) { mutableStateOf(InlineImages.get(key)) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    val unsupported = stringResource(R.string.image_failed_format)
    val res = res()
    LaunchedEffect(key, attempt) {
        val cached = InlineImages.get(key)
        if (cached != null && entry?.bitmap !== cached.bitmap) entry = cached
        if (attempt == 0 && cached != null && Clock.now() - cached.fetchedAt < InlineImages.FRESH_MS) return@LaunchedEffect
        if (attempt == 0 && cached == null) InlineImages.failure(key)?.let { failure = it; return@LaunchedEffect }
        failure = null
        try {
            val f = fetchImage(src, transcript.agent, transcript.sessionId, model)
            val bmp = withContext(Dispatchers.Default) { decodeImage(f.data, 1600) } ?: throw dev.leash.personal.core.BridgeError(-3, unsupported)
            // web and data: images keep their bytes for the viewer; a host path is fetched again by path
            val e = InlineImages.Entry(bmp, f.data.takeIf { source !is ImageSource.Local }, f.name, Clock.now())
            InlineImages.put(key, e)
            val grewTaller = entry == null || entry!!.bitmap.height != bmp.height || entry!!.bitmap.width != bmp.width
            entry = e
            if (grewTaller) grew()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val reason = res.errorText(e)
            InlineImages.fail(key, reason)
            if (entry == null) failure = reason
        }
    }

    val shown = entry
    val c = Leash.colors
    when {
        shown != null -> ImageTile(shown, alt, source, modifier)
        quiet -> Box(Modifier.height(0.dp))
        failure != null -> FailureRow(failure!!, alt.ifEmpty { ImageSource.fallbackName(src) }, source as? ImageSource.Local, { attempt++ }, modifier)
        else -> Box(
            modifier.widthIn(max = 480.dp).fillMaxWidth().height(180.dp).clip(LeashShapes.row).background(c.bgSunken)
                .semantics { contentDescription = "" }, contentAlignment = Alignment.Center,
        ) { Spinner(size = 20.dp, label = stringResource(R.string.image_loading)) }
    }
}

@Composable
private fun ImageTile(e: InlineImages.Entry, alt: String, source: ImageSource, modifier: Modifier) {
    val c = Leash.colors
    val openFile = LocalOpenFile.current
    val openImage = LocalOpenImage.current
    val bmp = e.bitmap
    // iOS points: the image's pixels are dp, never scaled up, within 480 x 320
    var w = minOf(bmp.width.toFloat(), 480f)
    var h = w * bmp.height / bmp.width
    if (h > 320f) { h = 320f; w = h * bmp.width / bmp.height }
    val desc = alt.ifEmpty { stringResource(R.string.image) }
    val label = stringResource(R.string.open)
    Image(
        bmp.asImageBitmap(), desc,
        modifier.sizeIn(maxWidth = w.dp, maxHeight = h.dp).aspectRatio(bmp.width.toFloat() / bmp.height)
            .clip(LeashShapes.row).border(1.dp, c.hairline, LeashShapes.row)
            .pressable(scale = PressScale.Row, onClickLabel = label) {
                if (source is ImageSource.Local) openFile(source.path) else openImage(e.name, e.data ?: return@pressable)
            },
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun FailureRow(reason: String, name: String, local: ImageSource.Local?, retry: () -> Unit, modifier: Modifier) {
    val c = Leash.colors
    val openFile = LocalOpenFile.current
    Row(
        modifier.widthIn(max = 480.dp).fillMaxWidth().clip(LeashShapes.inset).background(c.bgSunken).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(LeashIcons.warning, null, Modifier.size(18.dp), tint = c.attention)
        Column(Modifier.weight(1f)) {
            Text(name, style = Leash.type.footnote.medium(), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(reason, style = Leash.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (local != null) LeashButton(stringResource(R.string.open), { openFile(local.path) }, style = ButtonStyle.Plain, size = ButtonSize.Small)
        LeashButton(stringResource(R.string.retry), retry, style = ButtonStyle.Plain, size = ButtonSize.Small)
    }
}
