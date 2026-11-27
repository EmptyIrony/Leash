package dev.leash.personal.core

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the real bridge does with images: what goes to the phone carries a reference instead of the bytes, and bridge/media
 * hands them back a slice at a time (smaller slices than the bridge's, so the demo's small images take several).
 */
class DemoMedia {
    private class Stored(val mimeType: String, val base64: String)

    private val images = HashMap<String, Stored>()

    /** [e] with every inline image block replaced by its reference form. */
    fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> reference(e) ?: JsonObject(e.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private fun reference(b: JsonObject): JsonElement? {
        if (b["type"].string != "image") return null
        val data = b["data"].string ?: b["source"]["data"].string ?: return null
        val bytes = decodeBase64(data) ?: return null
        val mime = b["mimeType"].string ?: b["source"]["media_type"].string ?: "image/png"
        val ref = "m_" + MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        images[ref] = Stored(mime, data)
        val out = linkedMapOf<String, Any?>("type" to "image", "mimeType" to mime, "ref" to ref, "size" to bytes.size)
        pngSize(bytes)?.let { out["width"] = it.first; out["height"] = it.second }
        return j(out)
    }

    /** Only the demo's own PNGs; for anything else the size is left out, as the bridge does when it can't tell. */
    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte() || b[3] != 'G'.code.toByte()) return null
        val header = java.nio.ByteBuffer.wrap(b, 16, 8)
        return header.int to header.int
    }

    fun media(params: JsonElement): JsonElement {
        val ref = params["ref"].string ?: throw BridgeError(-32602, "ref required")
        val image = images[ref] ?: throw BridgeError(-32004, "image not available")
        val offset = params["offset"].int ?: 0
        if (offset < 0 || offset >= image.base64.length) throw BridgeError(-32602, "offset out of range")
        return jsonObj("ref" to ref, "mimeType" to image.mimeType, "offset" to offset, "total" to image.base64.length,
            "data" to image.base64.substring(offset, minOf(offset + SLICE, image.base64.length)))
    }

    private companion object {
        const val SLICE = 1024
    }
}
