package dev.leash.personal.ui.chat

/**
 * Pieces of a user message. Claude Code writes "!" shell runs and slash commands into the transcript as tagged text
 * (bash-input, bash-stdout, command-name ... tags, bodies HTML-escaped); they render as commands, not prose
 * (ios Views/EntryViews.swift UserSegment).
 */
sealed interface UserSegment {
    data class Text(val text: String) : UserSegment
    data class ShellInput(val command: String) : UserSegment
    data class ShellOutput(val stdout: String, val stderr: String, val exitCode: Int?) : UserSegment
    data class Command(val command: String) : UserSegment

    companion object {
        private val tagRegex = Regex("<(bash-input|bash-stdout|bash-stderr|bash-exit-code|command-name|command-message|command-args|local-command-stdout|local-command-stderr|local-command-caveat|system-reminder)>([\\s\\S]*?)</\\1>")

        fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

        fun parse(text: String): List<UserSegment> {
            if (!text.contains("</")) return if (text.isEmpty()) emptyList() else listOf(Text(text))
            val out = mutableListOf<UserSegment>()
            var cursor = 0
            var command: Pair<String, String>? = null
            var output: Out? = null

            fun flushPending() {
                command?.let { (name, args) -> out += Command(listOf(name, args).filter { it.isNotEmpty() }.joinToString(" ")); command = null }
                output?.let { o ->
                    // an empty /command result is dropped, an empty "!" result shows "No output"
                    if (o.bash || o.out.isNotEmpty() || o.err.isNotEmpty()) out += ShellOutput(o.out, o.err, o.code)
                    output = null
                }
            }
            fun addText(s: String) {
                val t = s.trim()
                if (t.isNotEmpty()) { flushPending(); out += Text(t) }
            }
            for (m in tagRegex.findAll(text)) {
                addText(text.substring(cursor, m.range.first))
                cursor = m.range.last + 1
                val tag = m.groupValues[1]
                val body = unescape(m.groupValues[2])
                when (tag) {
                    "bash-input" -> { flushPending(); out += ShellInput(body.trim()) }
                    "bash-stdout", "local-command-stdout", "bash-stderr", "local-command-stderr" -> {
                        val o = output ?: Out()
                        if (tag.endsWith("stdout")) o.out += body else o.err += body
                        if (tag.startsWith("bash")) o.bash = true
                        output = o
                    }
                    "bash-exit-code" -> { val o = output ?: Out(bash = true); o.code = body.trim().toIntOrNull(); output = o }
                    "command-name" -> { flushPending(); command = body.trim() to (command?.second ?: "") }
                    "command-args" -> { val c = command ?: ("" to ""); command = c.first to body.trim() }
                    else -> {}   // command-message duplicates the name; caveats and reminders are for the model
                }
            }
            addText(text.substring(cursor))
            flushPending()
            return out
        }

        /** Output belongs to the command above it, so it doesn't open a new "turn" gap. */
        fun isContinuation(text: String) = text.startsWith("<bash-stdout>") || text.startsWith("<local-command-stdout>") || text.startsWith("<bash-stderr>")

        private class Out(var out: String = "", var err: String = "", var code: Int? = null, var bash: Boolean = false)
    }
}
