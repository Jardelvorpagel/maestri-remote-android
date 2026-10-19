package app.maestri.live

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.piRelayDataStore by preferencesDataStore(name = "maestri_pi_relay")

/**
 * Pi relay endpoint settings behind Loom's PiRelayStore interface, so LiveViewModel
 * wiring is untouched: the composition swaps InMemoryPiRelayStore for this.
 * Default-off (blank url or token means [PiRelayClient] never dials). Shape mirrors
 * AgentSettingsStore (StateFlow pair + DataStore-backed setters); the token encrypts
 * at rest with an Android Keystore AES-256/GCM key, the PairingStore idiom for
 * secrets — DataStore holds only base64(iv ‖ ciphertext).
 */
class DataStorePiRelayStore(private val context: Context) : PiRelayStore {

    private object Keys {
        val RELAY_URL = stringPreferencesKey("relay_url")
        val TOKEN_ENC = stringPreferencesKey("relay_token_enc")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val urlState = MutableStateFlow("")
    private val tokenState = MutableStateFlow("")

    override val url: StateFlow<String> =
        urlState.stateIn(scope, SharingStarted.Eagerly, "")

    override val token: StateFlow<String> =
        tokenState.stateIn(scope, SharingStarted.Eagerly, "")

    val isConfigured: StateFlow<Boolean> =
        combine(urlState, tokenState) { url, token ->
            url.isNotBlank() && token.isNotBlank()
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            context.piRelayDataStore.data.collect { prefs ->
                urlState.value = prefs[Keys.RELAY_URL] ?: ""
                tokenState.value = prefs[Keys.TOKEN_ENC]?.let { decrypt(it) } ?: ""
            }
        }
    }

    override fun setUrl(value: String) {
        val trimmed = value.trim()
        urlState.value = trimmed
        scope.launch {
            context.piRelayDataStore.edit {
                if (trimmed.isEmpty()) it.remove(Keys.RELAY_URL) else it[Keys.RELAY_URL] = trimmed
            }
        }
    }

    override fun setToken(value: String) {
        val trimmed = value.trim()
        tokenState.value = trimmed
        scope.launch {
            context.piRelayDataStore.edit {
                if (trimmed.isEmpty()) it.remove(Keys.TOKEN_ENC)
                else it[Keys.TOKEN_ENC] = encrypt(trimmed.toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun clear() {
        urlState.value = ""
        tokenState.value = ""
        scope.launch {
            context.piRelayDataStore.edit {
                it.remove(Keys.RELAY_URL)
                it.remove(Keys.TOKEN_ENC)
            }
        }
    }

    suspend fun snapshotUrl(): String = context.piRelayDataStore.data.first()[Keys.RELAY_URL] ?: ""

    suspend fun snapshotToken(): String =
        context.piRelayDataStore.data.first()[Keys.TOKEN_ENC]?.let { decrypt(it) } ?: ""

    private val keystoreAlias = "maestri_pi_relay_token_key"

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keystoreAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    private fun encrypt(plain: ByteArray): String {
        val key = obtainKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val combined = cipher.iv + cipher.doFinal(plain)
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    @Synchronized
    private fun decrypt(encoded: String): String? {
        val key = obtainKey()
        val all = try {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (all.size < 13) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
