package dev.leash.personal.core

sealed interface DiffLine {
    data class Same(val text: String) : DiffLine
    data class Add(val text: String) : DiffLine
    data class Del(val text: String) : DiffLine
    data class Gap(val skipped: Int) : DiffLine
}

/** Line diff of an edit's old and new text (a Myers-style LCS over the lines that differ). */
object LineDiff {
    /** Above this many cells of the table the middle is shown as a full replace instead of diffed. */
    private const val MAX_CELLS = 4_000_000

    fun compute(old: String?, new: String): List<DiffLine> {
        val a = if (old.isNullOrEmpty()) emptyList() else old.split("\n")
        val b = new.split("\n")
        if (a.isEmpty()) return b.map { DiffLine.Add(it) }
        // the common head and tail need no table
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val am = a.subList(head, a.size - tail)
        val bm = b.subList(head, b.size - tail)
        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until head) out += DiffLine.Same(a[i])
        if (am.size.toLong() * bm.size > MAX_CELLS) {
            am.forEach { out += DiffLine.Del(it) }
            bm.forEach { out += DiffLine.Add(it) }
        } else {
            val n = am.size
            val m = bm.size
            // lcs[i][j]: length of the longest common subsequence of am[i..] and bm[j..]
            val w = m + 1
            val lcs = IntArray((n + 1) * w)
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                lcs[i * w + j] = if (am[i] == bm[j]) lcs[(i + 1) * w + j + 1] + 1 else maxOf(lcs[(i + 1) * w + j], lcs[i * w + j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    am[i] == bm[j] -> { out += DiffLine.Same(am[i]); i++; j++ }
                    lcs[(i + 1) * w + j] >= lcs[i * w + j + 1] -> { out += DiffLine.Del(am[i]); i++ }
                    else -> { out += DiffLine.Add(bm[j]); j++ }
                }
            }
            while (i < n) out += DiffLine.Del(am[i++])
            while (j < m) out += DiffLine.Add(bm[j++])
        }
        for (i in a.size - tail until a.size) out += DiffLine.Same(a[i])
        return out
    }

    /** Keep [context] unchanged lines around each change, collapse the rest. */
    fun hunks(lines: List<DiffLine>, context: Int = 3): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        for ((idx, l) in lines.withIndex()) {
            if (l is DiffLine.Same) continue
            for (k in maxOf(0, idx - context)..minOf(lines.size - 1, idx + context)) keep[k] = true
        }
        val out = ArrayList<DiffLine>()
        var skipped = 0
        for ((idx, l) in lines.withIndex()) {
            if (keep[idx]) {
                if (skipped > 0) { out += DiffLine.Gap(skipped); skipped = 0 }
                out += l
            } else skipped++
        }
        if (skipped > 0) out += DiffLine.Gap(skipped)
        return out
    }

    /** (added, removed) line counts. */
    fun stats(lines: List<DiffLine>): Pair<Int, Int> = lines.count { it is DiffLine.Add } to lines.count { it is DiffLine.Del }
}
