package dev.leash.personal.core

import kotlinx.serialization.json.JsonElement

data class QuotaBucket(
    val id: String,
    /** 5h | weekly | daily | monthly | other */
    val window: String,
    /** remaining, 0..1 */
    val fraction: Double?,
    val resetAtMs: Long?,
) {
    /** Compact window tag for chips: "5h", "wk". */
    val shortLabel: String
        get() = when (window) { "5h" -> "5h"; "weekly" -> "wk"; "daily" -> "day"; else -> window }

    val windowLabel: String
        get() = when (window) {
            "5h" -> "5-hour"; "weekly" -> "Weekly"; "daily" -> "Daily"; "monthly" -> "Monthly"
            else -> window.replaceFirstChar { it.uppercase() }
        }
}

data class QuotaPool(
    val id: String,
    val label: String,
    /** set for model-scoped pools (e.g. "Fable") */
    val model: String? = null,
    val buckets: List<QuotaBucket>,
) {
    val fiveHour: QuotaBucket? get() = buckets.firstOrNull { it.window == "5h" }
    val weekly: QuotaBucket? get() = buckets.firstOrNull { it.window == "weekly" }
    val minFraction: Double? get() = buckets.mapNotNull { it.fraction }.minOrNull()

    /** The bucket that limits you first (lowest remaining). */
    val tightest: QuotaBucket? get() = buckets.filter { it.fraction != null }.minByOrNull { it.fraction ?: 1.0 }
}

data class QuotaInfo(val planTier: String?, val pools: List<QuotaPool>, val fetchedAtMs: Long) {
    /** The pool a model draws from: Claude/GPT share "3p", Argon has its own weekly pool, the rest is Gemini. */
    fun poolForModel(value: String?): QuotaPool? {
        val v = (value ?: "").lowercase()
        fun byId(ids: List<String>): QuotaPool? {
            for (id in ids) pools.firstOrNull { it.id == id }?.let { return it }
            return null
        }
        if ("claude" in v || "gpt" in v || "oss" in v) return byId(listOf("3p")) ?: pools.firstOrNull { it.id.startsWith("3p") }
        if ("argon" in v) return byId(listOf("gemini-argon-wtu", "gemini-argon")) ?: byId(listOf("gemini"))
        return byId(listOf("gemini")) ?: pools.firstOrNull()
    }

    /**
     * The limiting (pool, bucket) for a model on a given agent. Claude: the general pool plus any model-scoped pool
     * (e.g. Fable); Antigravity: the pool the model draws from.
     */
    fun limiting(value: String?, agent: String): Pair<QuotaPool, QuotaBucket>? {
        val candidates: List<QuotaPool> = if (agent == "claude") {
            val v = (value ?: "").lowercase()
            pools.filter { it.model == null || v.contains(it.model.lowercase()) }
        } else listOfNotNull(poolForModel(value))
        var best: Pair<QuotaPool, QuotaBucket>? = null
        for (p in candidates) {
            val b = p.tightest ?: continue
            if (best == null || (b.fraction ?: 1.0) < (best.second.fraction ?: 1.0)) best = p to b
        }
        return best
    }

    /** The pool closest to running out. */
    val mostConstrained: QuotaPool? get() = pools.minByOrNull { it.minFraction ?: 1.0 }

    companion object {
        fun parse(j: JsonElement?): QuotaInfo? {
            val pools = j["pools"].array ?: return null
            if (j.isJsonNull) return null
            return QuotaInfo(
                planTier = j["planTier"].string,
                fetchedAtMs = j["fetchedAt"].long ?: Clock.now(),
                pools = pools.mapNotNull { p ->
                    val id = p["id"].string ?: return@mapNotNull null
                    val buckets = (p["buckets"].array ?: emptyList()).mapNotNull { b ->
                        val bid = b["id"].string ?: return@mapNotNull null
                        QuotaBucket(bid, b["window"].string ?: "other", b["remainingFraction"].double, parseIsoMillis(b["resetTime"].string))
                    }
                    QuotaPool(id, p["label"].string ?: id, p["model"].string, buckets)
                },
            )
        }
    }
}
