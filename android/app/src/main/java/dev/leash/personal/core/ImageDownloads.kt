package dev.leash.personal.core

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Fetches referenced images with bridge/media, a slice of the base64 at a time, so no frame is large enough to hold back the
 * socket's pings. At most [maxActive] download at once, each with one chunk request in flight; the others wait in the order
 * asked. Asking again for a ref that waits or downloads joins it. A download every caller has left is dropped: at once while
 * it waits, else after the chunk in flight (whose answer is on its way down the socket anyway, so its slot stays taken until
 * then; a caller that comes back meanwhile keeps what was downloaded).
 *
 * [scope] must run on one thread (Main), and [load] is called on it.
 */
class ImageDownloads(
    private val scope: CoroutineScope,
    /** one bridge/media call: (ref, root session id, agent, offset) */
    private val chunk: suspend (ref: String, sessionId: String, agent: String, offset: Int) -> JsonElement,
    private val maxActive: Int = MAX_ACTIVE,
    /** where the finished base64 is decoded */
    private val decodeOn: CoroutineContext = Dispatchers.Default,
) {
    private class Download(val ref: String, val sessionId: String, val agent: String) {
        val result = CompletableDeferred<ByteArray>()
        var callers = 0
        var started = false
    }

    /** waiting and running, oldest first */
    private val downloads = LinkedHashMap<String, Download>()
    private var active = 0

    /** The image's bytes. Cancel the calling coroutine when nothing shows the image any more. */
    suspend fun load(ref: String, sessionId: String, agent: String): ByteArray {
        val d = downloads.getOrPut(ref) { Download(ref, sessionId, agent) }
        d.callers++
        try {
            startWaiting()
            return d.result.await()
        } finally {
            d.callers--
            if (d.callers == 0 && !d.started) downloads.remove(ref, d)
        }
    }

    private fun startWaiting() {
        while (active < maxActive) {
            val next = downloads.values.firstOrNull { !it.started } ?: return
            next.started = true
            active++
            scope.launch { run(next) }
        }
    }

    private suspend fun run(d: Download) {
        try {
            val base64 = StringBuilder()
            while (true) {
                if (d.callers == 0) return
                val r = chunk(d.ref, d.sessionId, d.agent, base64.length)
                val data = r["data"].string.orEmpty()
                val total = r["total"].int
                // an empty slice before the end would be asked for again and again
                if (total == null || data.isEmpty()) throw BridgeError(-3, "The bridge sent no image data", issue = Issue.NoFileData)
                base64.append(data)
                if (base64.length >= total) break
            }
            val bytes = withContext(decodeOn) { java.util.Base64.getMimeDecoder().decode(base64.toString()) }
            downloads.remove(d.ref, d)   // before the callers resume: one that asks again starts afresh
            d.result.complete(bytes)
        } catch (e: CancellationException) {
            downloads.remove(d.ref, d)
            d.result.cancel(e)
            throw e
        } catch (e: Exception) {
            downloads.remove(d.ref, d)
            d.result.completeExceptionally(e)
        } finally {
            active--
            downloads.remove(d.ref, d)
            startWaiting()
        }
    }

    companion object {
        const val MAX_ACTIVE = 2
        const val CHUNK_TIMEOUT_MS = 60_000L
    }
}
