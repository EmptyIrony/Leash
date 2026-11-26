package dev.leash.personal.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.tailnetStore by preferencesDataStore(name = "tailnet")

/** AES-GCM with a non-exportable key in the Android Keystore; the sealed text is base64(iv + ciphertext). */
class KeystoreSecretBox(private val alias: String = "leash.tailnet.authkey") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    override fun decrypt(sealed: String): String? = try {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES)) }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * The built-in Tailscale settings in their own DataStore (under the app's private files dir, excluded from backups like the
 * rest); the auth key is stored sealed by [box], never in plain text.
 */
class DataStoreTailnetPrefs(private val context: Context, private val box: SecretBox) : TailnetPrefs {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val sealedKey = stringPreferencesKey("authKey")
        val controlUrl = stringPreferencesKey("controlUrl")
        val hostname = stringPreferencesKey("hostname")
    }

    override suspend fun load(): TailnetConfig {
        val p = context.tailnetStore.data.first()
        // Keystore work stays off the main thread
        val key = withContext(Dispatchers.IO) { p[K.sealedKey]?.let { box.decrypt(it) } ?: "" }
        return TailnetConfig(
            enabled = p[K.enabled] ?: false,
            authKey = key,
            controlUrl = p[K.controlUrl] ?: "",
            hostname = p[K.hostname],
        )
    }

    override suspend fun save(config: TailnetConfig) {
        // sealed first: a Keystore failure then leaves the previous value alone instead of throwing in the middle of the edit
        val sealed = if (config.authKey.isEmpty()) null else withContext(Dispatchers.IO) { runCatching { box.encrypt(config.authKey) }.getOrNull() }
        context.tailnetStore.edit { p ->
            p[K.enabled] = config.enabled
            if (sealed != null) p[K.sealedKey] = sealed else p.remove(K.sealedKey)
            p[K.controlUrl] = config.controlUrl
            config.hostname?.let { p[K.hostname] = it } ?: p.remove(K.hostname)
        }
    }
}
