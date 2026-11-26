package dev.leash.personal.ui.chat

import dev.leash.personal.core.PendingRequest
import dev.leash.personal.core.array
import dev.leash.personal.core.get
import dev.leash.personal.core.isJsonNull
import dev.leash.personal.core.obj
import dev.leash.personal.core.string
import dev.leash.personal.core.double
import dev.leash.personal.core.j
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** One pick of a question (a oneOf / enum entry). */
data class Choice(val value: String, val title: String, val detail: String?)

sealed interface FieldKind {
    data class Single(val choices: List<Choice>) : FieldKind
    data class Multi(val choices: List<Choice>) : FieldKind
    data object Text : FieldKind
    data object Number : FieldKind
    data object Bool : FieldKind
}

data class ElicitField(
    val name: String,
    val title: String,
    val detail: String?,
    val kind: FieldKind,
    val defaultValue: JsonElement?,
    /** "question_0_custom" is question_0's free-text "Other" answer (claude-agent-acp's convention). */
    val customFor: String?,
)

/** An ACP form elicitation (Claude's AskUserQuestion, agy's ask_question, MCP forms), parsed once (ios ElicitationSpec). */
class ElicitationSpec(val fields: List<ElicitField>, val message: String?, val url: String?) {
    val questions: List<ElicitField> get() = fields.filter { it.customFor == null }
    fun custom(f: ElicitField): ElicitField? = fields.firstOrNull { it.customFor == f.name }

    /**
     * One pick-from-a-few question without typing fits the tray; anything bigger opens a sheet (keep inline UI short, use a
     * sheet for multi-step input and the keyboard).
     */
    val fitsTray: Boolean
        get() {
            val q = questions.singleOrNull()
            if (url != null || q == null) return url != null
            return when (val k = q.kind) {
                is FieldKind.Single -> k.choices.size <= 4
                is FieldKind.Multi -> k.choices.size <= 4
                else -> false
            }
        }

    companion object {
        fun of(request: PendingRequest): ElicitationSpec = of(request.params)

        fun of(p: JsonElement): ElicitationSpec {
            val message = p["message"].string
            val url = if (p["mode"].string == "url") p["url"].string else null
            val schema = p["requestedSchema"]
            val props = schema["properties"].obj ?: emptyMap()
            val required = (schema["required"].array ?: emptyList()).mapNotNull { it.string }.toSet()
            val names = props.keys.sortedWith { a, b ->
                val ra = a in required
                val rb = b in required
                if (ra != rb) (if (ra) -1 else 1) else naturalCompare(a, b)
            }
            val fields = names.map { name ->
                val f = props[name]
                val kind: FieldKind = when (f["type"].string) {
                    "boolean" -> FieldKind.Bool
                    "number", "integer" -> FieldKind.Number
                    "array" -> FieldKind.Multi(choices(f["items"]))
                    else -> choices(f).let { if (it.isEmpty()) FieldKind.Text else FieldKind.Single(it) }
                }
                val base = if (name.endsWith("_custom")) name.dropLast("_custom".length) else null
                ElicitField(name, f["title"].string ?: name, f["description"].string, kind, f["default"], base?.takeIf { props[it] != null })
            }
            return ElicitationSpec(fields, message, url)
        }

        private fun choices(p: JsonElement?): List<Choice> {
            val one = p["oneOf"].array ?: p["anyOf"].array
            if (one != null) return one.mapNotNull { o ->
                val v = o["const"].string ?: return@mapNotNull null
                Choice(v, o["title"].string ?: v, o["description"].string)
            }
            return (p["enum"].array ?: emptyList()).mapNotNull { e -> e.string?.let { Choice(it, it, null) } }
        }

        /** "question_2" before "question_10". */
        fun naturalCompare(a: String, b: String): Int {
            val ra = Regex("""\d+|\D+""").findAll(a).map { it.value }.toList()
            val rb = Regex("""\d+|\D+""").findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    val d = x.trimStart('0').length.compareTo(y.trimStart('0').length)
                    if (d != 0) d else x.trimStart('0').compareTo(y.trimStart('0'))
                } else x.compareTo(y, ignoreCase = true)
                if (c != 0) return c
            }
            return ra.size.compareTo(rb.size)
        }
    }
}

/** Answers being filled in, shared by the tray and the sheet. Immutable: every change returns the next state. */
data class ElicitationAnswers(
    val values: Map<String, JsonElement> = emptyMap(),
    val multi: Map<String, Set<String>> = emptyMap(),
) {
    fun isSelected(f: ElicitField, c: Choice): Boolean =
        if (f.kind is FieldKind.Multi) c.value in (multi[f.name] ?: emptySet())
        else (values[f.name].string ?: f.defaultValue.string) == c.value

    fun toggle(f: ElicitField, c: Choice): ElicitationAnswers {
        if (f.kind is FieldKind.Multi) {
            val s = multi[f.name] ?: emptySet()
            return copy(multi = multi + (f.name to (if (c.value in s) s - c.value else s + c.value)))
        }
        return if (isSelected(f, c)) copy(values = values - f.name) else copy(values = values + (f.name to j(c.value)))
    }

    fun with(name: String, v: JsonElement?): ElicitationAnswers = copy(values = if (v == null || v.isJsonNull) values - name else values + (name to v))

    fun text(f: ElicitField): String = values[f.name].string ?: f.defaultValue.string ?: ""

    val hasAnswer: Boolean get() = values.values.any { !it.isJsonNull && it.string != "" } || multi.values.any { it.isNotEmpty() }

    /** What goes back to the agent: the answers, plus the defaults of fields left alone. */
    fun content(spec: ElicitationSpec): Map<String, JsonElement> {
        val out = values.filterValues { !it.isJsonNull && it.string != "" }.toMutableMap()
        for ((k, s) in multi) if (s.isNotEmpty()) out[k] = j(s.sorted())
        for (f in spec.fields) if (f.name !in out) f.defaultValue?.let { if (!it.isJsonNull) out[f.name] = it }
        return out
    }
}
