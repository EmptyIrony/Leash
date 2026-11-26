package dev.leash.personal.core

import java.net.URLDecoder

/** What a `leash://pair?host=…&port=…&token=…&name=…` link carries (bridge: `pairingInfo()` in src/index.mjs). */
data class PairingInfo(val host: String, val port: Int, val token: String, val name: String)

/** The links the app handles: leash://pair (pairing), leash://session?id=…&agent=… (open a conversation), leash://open. */
sealed interface LeashLink {
    data class Pair(val info: PairingInfo) : LeashLink
    data class Session(val id: String, val agent: String?) : LeashLink
    data object Open : LeashLink

    companion object {
        const val DEFAULT_PORT = 8787

        fun parse(text: String): LeashLink? {
            val t = text.trim()
            val prefix = "leash://"
            if (!t.startsWith(prefix, ignoreCase = true)) return null
            val rest = t.substring(prefix.length).substringBefore('#')
            val host = rest.substringBefore('?').trimEnd('/').lowercase()
            val q = query(rest.substringAfter('?', ""))
            return when (host) {
                "pair" -> {
                    val h = q["host"]?.trim().orEmpty()
                    val token = q["token"]?.trim().orEmpty()
                    if (h.isEmpty() || token.isEmpty()) return null
                    val port = q["port"]?.trim()?.toIntOrNull() ?: DEFAULT_PORT
                    if (port !in 1..65535) return null
                    Pair(PairingInfo(h, port, token, q["name"]?.takeIf { it.isNotBlank() } ?: h))
                }
                "session" -> q["id"]?.takeIf { it.isNotEmpty() }?.let { Session(it, q["agent"]?.takeIf { a -> a.isNotEmpty() }) }
                "open" -> Open
                else -> null
            }
        }

        private fun query(s: String): Map<String, String> {
            if (s.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in s.split('&')) {
                if (part.isEmpty()) continue
                val k = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                out[dec(k)] = dec(v)
            }
            return out
        }

        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/** Only a pairing link (what "paste pairing link" accepts). */
fun parsePairingLink(text: String): PairingInfo? = (LeashLink.parse(text) as? LeashLink.Pair)?.info
