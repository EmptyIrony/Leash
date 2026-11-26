package dev.leash.personal.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement

private val Context.leashStore by preferencesDataStore(name = "leash")

/** Preferences DataStore under the app's private files dir (data extraction rules exclude it from backup and transfer). */
class DataStoreSettings(private val context: Context) : SettingsStore {
    private object K {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val token = stringPreferencesKey("token")
        val bridgeName = stringPreferencesKey("bridgeName")
        val useDemo = booleanPreferencesKey("useDemo")
        val drafts = stringPreferencesKey("drafts")
        val lastSeen = stringPreferencesKey("lastSeen")
        val markedUnread = stringPreferencesKey("markedUnread")
        val unreadSince = longPreferencesKey("unreadSince")
    }

    override suspend fun load(): Settings {
        val p: Preferences = context.leashStore.data.first()
        return Settings(
            host = p[K.host] ?: "",
            port = p[K.port] ?: LeashLink.DEFAULT_PORT,
            token = p[K.token] ?: "",
            bridgeName = p[K.bridgeName] ?: "",
            useDemo = p[K.useDemo] ?: false,
            drafts = decode(p[K.drafts]).obj?.mapNotNull { (k, v) -> v.string?.let { k to it } }?.toMap() ?: emptyMap(),
            lastSeen = decode(p[K.lastSeen]).obj?.mapNotNull { (k, v) -> v.long?.let { k to it } }?.toMap() ?: emptyMap(),
            markedUnread = decode(p[K.markedUnread]).array?.mapNotNull { it.string }?.toSet() ?: emptySet(),
            unreadSinceMs = p[K.unreadSince],
        )
    }

    override suspend fun save(settings: Settings) {
        context.leashStore.edit { p ->
            p[K.host] = settings.host
            p[K.port] = settings.port
            p[K.token] = settings.token
            p[K.bridgeName] = settings.bridgeName
            p[K.useDemo] = settings.useDemo
            p[K.drafts] = j(settings.drafts).encoded()
            p[K.lastSeen] = j(settings.lastSeen).encoded()
            p[K.markedUnread] = j(settings.markedUnread.toList()).encoded()
            settings.unreadSinceMs?.let { p[K.unreadSince] = it }
        }
    }

    private fun decode(s: String?): JsonElement? = s?.let { runCatching { parseJson(it) }.getOrNull() }
}
