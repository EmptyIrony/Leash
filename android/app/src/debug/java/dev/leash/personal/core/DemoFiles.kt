package dev.leash.personal.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** The files of the demo bridge's host: generated (no assets), pure JVM, so the demo tests can read them too. */
object DemoFiles {
    private const val PROJECT = DemoScript.PROJECT
    const val WIDGET = "/Users/demo/.gemini/antigravity/brain/demo/widget.html"

    /** One file as bridge/readFile sees it. */
    class Entry(val bytes: ByteArray, val mime: String, val binary: Boolean)

    private val uploads = HashMap<String, Entry>()
    private val cache = HashMap<String, Entry>()

    fun absolute(path: String): String = when {
        path.startsWith("/") -> path
        path.startsWith("~/") -> DemoScript.HOME + path.substring(1)
        else -> PROJECT + "/" + path
    }

    fun store(path: String, bytes: ByteArray, mime: String) { uploads[path] = Entry(bytes, mime, mime != "text/plain") }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "pdf" -> "application/pdf"; "zip" -> "application/zip"
        "html" -> "text/html"; "md" -> "text/markdown"; "json" -> "application/json"; "txt", "log", "kt", "kts", "swift", "mjs" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** Null: no such file. */
    fun find(path: String): Entry? {
        val p = absolute(path)
        uploads[p]?.let { return it }
        cache[p]?.let { return it }
        val e: Entry? = when {
            p == "$PROJECT/docs/flow.png" -> Entry(png(480, 280, boxes = true), "image/png", true)
            p == "$PROJECT/docs/big-shot.png" -> Entry(png(1600, 2400, boxes = false), "image/png", true)
            p == "$PROJECT/docs/spec.pdf" -> Entry(pdf("Leash demo spec"), "application/pdf", true)
            p == "$PROJECT/build/archive.zip" -> Entry(ByteArray(300) { if (it < 2) "PK"[it].code.toByte() else (it * 7).toByte() }, "application/zip", true)
            p == "$PROJECT/data/large.bin" -> Entry(ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }, "application/octet-stream", true)
            p == "$PROJECT/logs/huge.log" -> Entry(("2026-11-26 line of a very large log\n").repeat(40).toByteArray(), "text/plain", false)
            p == "$PROJECT/src/Parser.kt" -> Entry(parserSource().toByteArray(), "text/plain", false)
            p == WIDGET -> Entry(WIDGET_HTML.toByteArray(), "text/html", false)
            p.startsWith("$PROJECT/") && p.substringAfterLast('.', "") in setOf("kt", "kts", "md", "txt", "json", "mjs", "swift") ->
                Entry(("// " + p.substringAfterLast('/') + "\n// scripted content of the demo bridge\n\nfun demo() = 42\n").toByteArray(), "text/plain", false)
            else -> null
        }
        if (e != null && e.bytes.size < 4 * 1024 * 1024) cache[p] = e
        return e
    }

    /** Past this the bridge refuses a whole download (-32007); text mode still shows the start. */
    fun tooLargeForDownload(path: String) = absolute(path).endsWith("/logs/huge.log")

    private fun parserSource(): String = buildString {
        appendLine("package demo.parser")
        appendLine()
        appendLine("/** The parser of the demo project: blank input is rejected early. */")
        for (i in 1..9) {
            appendLine("fun parse$i(s: String): Node {")
            appendLine("    require(s.isNotBlank()) { \"empty input\" }")
            appendLine("    val tokens = lex(s.trim())")
            appendLine("    return Node(tokens.map { it.text }.joinToString(\" \"))")
            appendLine("}")
            appendLine()
        }
        appendLine("fun parse(s: String): Node {")
        appendLine("    require(s.isNotBlank()) { \"empty input\" }")
        appendLine("    return Node(s.trim()) // a rather long trailing comment so this line is wider than a narrow phone screen, to check the horizontal scroll")
        appendLine("}")
    }

    // MARK: PNG (an encoder in a few lines: filter 0 rows, zlib, CRC)

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val body = type.toByteArray(Charsets.US_ASCII) + data
        out.write(body)
        val crc = CRC32().apply { update(body) }.value.toInt()
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
    }

    /** An indigo to cyan gradient; with [boxes] a small flow diagram of white boxes joined by lines. */
    fun png(w: Int, h: Int, boxes: Boolean): ByteArray {
        val raw = ByteArray((w * 3 + 1) * h)
        fun inBox(x: Int, y: Int): Boolean {
            if (!boxes) return false
            val cy = h / 2
            for (i in 0..2) {
                val bx = 40 + i * 150
                if (x in bx until bx + 100 && y in cy - 36 until cy + 36) return true
                if (i < 2 && x in bx + 100 until bx + 150 && y in cy - 2 until cy + 2) return true
            }
            return false
        }
        var p = 0
        for (y in 0 until h) {
            raw[p++] = 0
            for (x in 0 until w) {
                val t = (x + y).toFloat() / (w + h)
                val white = inBox(x, y)
                raw[p++] = (if (white) 245 else 75 + (79 * t).toInt()).toByte()
                raw[p++] = (if (white) 246 else 80 + (136 * t).toInt()).toByte()
                raw[p++] = (if (white) 252 else 230 - (0 * t).toInt()).toByte()
            }
        }
        val def = Deflater(6)
        def.setInput(raw)
        def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(out, "IHDR", byteArrayOf((w ushr 24).toByte(), (w ushr 16).toByte(), (w ushr 8).toByte(), w.toByte(), (h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte(), 8, 2, 0, 0, 0))
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    // MARK: PDF (one page, one line of text, a real cross-reference table)

    fun pdf(title: String): ByteArray {
        val objs = listOf(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>",
            "STREAM",
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>",
        )
        val content = "BT /F1 22 Tf 30 100 Td ($title) Tj ET"
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        objs.forEachIndexed { i, o ->
            offsets += out.length
            out.append(i + 1).append(" 0 obj\n")
            out.append(if (o == "STREAM") "<</Length " + content.length + ">>\nstream\n" + content + "\nendstream" else o)
            out.append("\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ").append(objs.size + 1).append("\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format(java.util.Locale.US, "%010d 00000 n \n", it)) }
        out.append("trailer\n<</Size ").append(objs.size + 1).append("/Root 1 0 R>>\nstartxref\n").append(xref).append("\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    // MARK: Antigravity widget (HTML for the embed host: theme variables, inline script, an inline SVG chart)

    private val WIDGET_HTML = """
<!doctype html>
<html><head><title>Benchmark</title>
<style>
  .card { background: var(--card); border: 1px solid var(--card-border); border-radius: 12px; padding: 14px; margin: 4px; }
  h3 { margin: 0 0 8px; font-size: 15px; color: var(--foreground); }
  .muted { color: var(--muted-foreground); font-size: 12px; }
  button { background: var(--primary); color: var(--primary-foreground); border: 0; border-radius: 8px; padding: 8px 14px; font-size: 14px; }
  .bar { height: 14px; border-radius: 4px; margin: 6px 0; }
</style></head>
<body>
<div class="card">
  <h3>Parser benchmark</h3>
  <div class="muted">ops per second, higher is better</div>
  <div class="bar" style="width:92%;background:var(--chart-1)"></div>
  <div class="bar" style="width:64%;background:var(--chart-2)"></div>
  <div class="bar" style="width:38%;background:var(--chart-4)"></div>
  <p class="muted" id="count">Tapped 0 times</p>
  <button id="b" onclick="n++;document.getElementById('count').textContent='Tapped '+n+' times'">Run again</button>
  <p class="muted" id="theme">theme: </p>
</div>
<script>
  var n = 0;
  function t() { document.getElementById('theme').textContent = 'theme: ' + (document.documentElement.classList.contains('dark') ? 'dark' : 'light'); }
  t(); window.addEventListener('themechange', t);
</script>
</body></html>
""".trimIndent()
}
