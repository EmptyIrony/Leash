package dev.leash.personal.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.BridgeError
import dev.leash.personal.core.ChatImage
import dev.leash.personal.core.Clock
import dev.leash.personal.core.Issue
import dev.leash.personal.text.errorText
import dev.leash.personal.text.res
import dev.leash.personal.ui.components.LocalAppModel
import dev.leash.personal.ui.components.LocalContentGrew
import dev.leash.personal.ui.components.LocalTranscript
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.files.LocalOpenImage
import dev.leash.personal.ui.markdown.ImageFailureRow
import dev.leash.personal.ui.markdown.ImageLoadingBox
import dev.leash.personal.ui.markdown.InlineImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** A row that is only flung past asks the bridge for nothing. */
private const val SETTLE_MS = 100L

/** The bridge has no image for the ref (restarted and not attached again, or trimmed from its log). */
private const val IMAGE_NOT_AVAILABLE = -32004

private class ImageFailure(val reason: String, /** the attach count when the fetch began */ val generation: Int, val at: Long)

/** Images the bridge could not give, remembered briefly so a row scrolled back in keeps its height and doesn't ask again. */
private object ImageFailures {
    private val map = HashMap<String, ImageFailure>()
    fun get(ref: String, generation: Int): ImageFailure? =
        map[ref]?.takeIf { it.generation == generation && Clock.now() - it.at < InlineImages.FRESH_MS }
    fun put(ref: String, f: ImageFailure) { map[ref] = f }
    fun forget(ref: String) { map.remove(ref) }
}

/** The link was down or too slow: the next time the view appears, it asks again. */
private fun isLinkFailure(e: Exception) =
    e is BridgeError && (e.code == BridgeError.CLOSED || e.code == BridgeError.TIMEOUT || e.code == BridgeError.NO_REPLY)

private val noAttaches = MutableStateFlow(0)

/**
 * One image of a message or a tool result, within [maxWidth] x [maxHeight]: the bytes the phone holds, or a referenced one,
 * fetched only while this is composed. Tap opens it in the viewer.
 */
@Composable
fun MessageImage(image: ChatImage, number: Int, maxWidth: Dp, maxHeight: Dp = Dp.Unspecified) {
    val description = stringResource(R.string.image_numbered, number)
    val bounds = Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight)
    when (image) {
        is ChatImage.Local -> ImageTile(image.data, bounds, description, "image-$number.png")
        is ChatImage.Remote -> {
            val ext = when (image.mimeType) { "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "png" }
            RemoteImage(image, bounds, maxWidth, description, "image-$number.$ext")
        }
    }
}

@Composable
private fun RemoteImage(image: ChatImage.Remote, bounds: Modifier, maxWidth: Dp, description: String, name: String) {
    val model = LocalAppModel.current
    val transcript = LocalTranscript.current
    val grew = LocalContentGrew.current
    val key = "ref|" + image.ref
    val generation by (model?.attachGeneration ?: noAttaches).collectAsState()
    // read synchronously: a cached image has its size and picture in the first layout
    var shown by remember(image.ref) { mutableStateOf(InlineImages.get(key)) }
    var failure by remember(image.ref) { mutableStateOf(ImageFailures.get(image.ref, generation)) }
    var attempt by remember(image.ref) { mutableIntStateOf(0) }
    // with the size known, the placeholder and the picture are laid out from the same numbers: nothing moves
    val pixels = if (image.width != null && image.height != null) decodedSize(image.width, image.height) else null
    val res = res()
    val unreadable = stringResource(R.string.image_failed_format)
    val unavailable = stringResource(R.string.image_unavailable)

    LaunchedEffect(image.ref, attempt) {
        if (shown != null || failure != null) return@LaunchedEffect
        InlineImages.get(key)?.let { shown = it; return@LaunchedEffect }
        delay(SETTLE_MS)
        val started = model?.attachGeneration?.value ?: 0
        try {
            val m = model ?: throw BridgeError.notConnected()
            val entry = InlineImages.get(key) ?: run {
                val data = m.images.load(image.ref, transcript.sessionId ?: throw BridgeError.notConnected(), transcript.agent)
                val bitmap = withContext(Dispatchers.Default) { decodeImage(data) } ?: throw BridgeError(-3, unreadable, issue = Issue.UnsupportedImage)
                InlineImages.Entry(bitmap, data, name, Clock.now()).also { InlineImages.put(key, it) }
            }
            shown = entry
            if (pixels == null) grew()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val reason = if (e is BridgeError && e.code == IMAGE_NOT_AVAILABLE) unavailable else res.errorText(e)
            val f = ImageFailure(reason, started, Clock.now())
            if (!isLinkFailure(e)) ImageFailures.put(image.ref, f)
            failure = f
            grew()
        }
    }
    // it failed while the link was down, or before the conversation was attached again: after the next attach, try once more
    LaunchedEffect(generation, failure) {
        val f = failure
        if (f != null && f.generation != generation) { ImageFailures.forget(image.ref); failure = null; attempt++ }
    }

    val entry = shown
    val failed = failure
    val c = Leash.colors
    when {
        entry != null -> {
            val open = LocalOpenImage.current
            val label = stringResource(R.string.open)
            Image(
                entry.bitmap.asImageBitmap(), description,
                bounds.pixelSize(pixels ?: (entry.bitmap.width to entry.bitmap.height)).clip(LeashShapes.card).border(1.dp, c.hairline, LeashShapes.card)
                    .pressable(scale = PressScale.Row, onClickLabel = label) { entry.data?.let { open(name, it) } },
                contentScale = ContentScale.Fit,
            )
        }
        failed != null -> ImageFailureRow(failed.reason, description, null, { ImageFailures.forget(image.ref); failure = null; attempt++ }, Modifier.widthIn(max = maxWidth))
        pixels != null -> Box(
            bounds.pixelSize(pixels).clip(LeashShapes.card).background(c.bgSunken).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Spinner(size = 20.dp, label = stringResource(R.string.image_loading)) }
        else -> ImageLoadingBox(Modifier.widthIn(max = maxWidth))
    }
}

/** Lays out as an image of these pixels does: never scaled up, scaled down to fit the bounds. */
private fun Modifier.pixelSize(pixels: Pair<Int, Int>): Modifier = layout { measurable, constraints ->
    val (w, h) = fitInside(pixels.first, pixels.second, constraints.maxWidth, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
