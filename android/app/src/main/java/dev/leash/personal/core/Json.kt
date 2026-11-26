package dev.leash.personal.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// The protocol is dynamic (ACP evolves quickly), so the app reads fields leniently from JsonElement instead of binding to
// rigid classes. Every accessor is null-safe and chains like Swift's optional chaining: `u["a"]["b"].string`.

/** Object field, or null when this is not an object or has no such key (a JSON null value is returned as JsonNull). */
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Array element, or null when out of range or not an array. */
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
val JsonElement?.long: Long? get() = double?.toLong()
val JsonElement?.int: Int? get() = double?.toInt()
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
val JsonElement?.array: List<JsonElement>? get() = this as? JsonArray
val JsonElement?.obj: Map<String, JsonElement>? get() = this as? JsonObject

/** True only for an explicit JSON null (a missing key is a Kotlin null). */
val JsonElement?.isJsonNull: Boolean get() = this is JsonNull

/** Missing and explicit null both read as "no value". */
val JsonElement?.orNull: JsonElement? get() = if (this is JsonNull) null else this

/** Wraps plain Kotlin values; JsonElement passes through, collections recurse. */
fun j(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is Float -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to j(x) })
    is Iterable<*> -> JsonArray(v.map { j(it) })
    else -> throw IllegalArgumentException("not JSON: " + v::class)
}

fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { it.first to j(it.second) })
fun jsonArr(vararg items: Any?): JsonArray = JsonArray(items.map { j(it) })

private val compact = Json
private val prettyJson = Json { prettyPrint = true }

fun parseJson(text: String): JsonElement = compact.parseToJsonElement(text)
fun JsonElement.encoded(): String = compact.encodeToString(JsonElement.serializer(), this)

/** Human readable rendering (raw tool input / output): strings as they are, null as empty, the rest pretty-printed with sorted keys. */
fun JsonElement?.pretty(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> if (isString) content else content
    else -> prettyJson.encodeToString(JsonElement.serializer(), sortedKeys())
}

private fun JsonElement.sortedKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.sortedKeys() })
    is JsonArray -> JsonArray(map { it.sortedKeys() })
    else -> this
}
