package dev.leash.personal.core

/** What survives a restart. The token is a secret: it lives in the app's private storage and is excluded from backups. */
data class Settings(
    val host: String = "",
    val port: Int = LeashLink.DEFAULT_PORT,
    val token: String = "",
    val macName: String = "",
    /** Debug builds: talk to the scripted demo bridge instead of the Mac. */
    val useDemo: Boolean = false,
    /** What was typed but not sent in each conversation, by session id. */
    val drafts: Map<String, String> = emptyMap(),
    /** When each conversation was last on screen (epoch ms), and the ones marked unread by hand. */
    val lastSeen: Map<String, Long> = emptyMap(),
    val markedUnread: Set<String> = emptySet(),
    /** Turns that ended before the first launch don't count as unread. */
    val unreadSinceMs: Long? = null,
)

interface SettingsStore {
    suspend fun load(): Settings
    suspend fun save(settings: Settings)
}

class InMemorySettingsStore(var value: Settings = Settings()) : SettingsStore {
    var saves = 0
    override suspend fun load() = value
    override suspend fun save(settings: Settings) { value = settings; saves++ }
}
