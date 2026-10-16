package app.maestri.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.maestri.wire.WireClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "maestri_wire_pairing")

/**
 * What a client keeps from pairing: token (the only secret), deviceId, serverKeyHash and
 * the host list (hosts[] tried in order when the primary stops answering).
 *
 * The token is encrypted at rest with an AES-256/GCM key held in the Android Keystore;
 * DataStore holds only base64(iv ‖ ciphertext).
 */
data class Pairing(
    val host: String,
    val port: Int,
    val serverKeyHashB64: String,
    val token: String,
    val deviceId: String,
    val deviceName: String,
    val hosts: List<String>,
)

class PairingStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = stringPreferencesKey("port")
        val KEY_HASH = stringPreferencesKey("serverKeyHash")
        val TOKEN_ENC = stringPreferencesKey("token_enc")
        val DEVICE_ID = stringPreferencesKey("deviceId")
        val DEVICE_NAME = stringPreferencesKey("deviceName")
        val HOSTS = stringSetPreferencesKey("hosts")
        val DEVICE_IDENTIFIER = stringPreferencesKey("ourIdentifier")
    }

    // --------------------------------------------------------------- Keystore crypt

    private val keystoreAlias = "maestri_wire_token_key"

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keystoreAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    private fun encrypt(plain: ByteArray): String {
        val key = obtainKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return WireClient.b64(iv + ct)
    }

    @Suppress("unused")
    private val rng = SecureRandom()

    @Synchronized
    private fun decrypt(encoded: String): String? {
        val key = obtainKey()
        val all = try {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (all.size < 13) return null
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------- API

    val pairing: Flow<Pairing?> = context.dataStore.data.map { p ->
        val host = p[Keys.HOST] ?: return@map null
        val port = p[Keys.PORT]?.toIntOrNull() ?: 7434
        val hash = p[Keys.KEY_HASH] ?: return@map null
        val token = p[Keys.TOKEN_ENC]?.let { decrypt(it) } ?: return@map null
        Pairing(
            host = host,
            port = port,
            serverKeyHashB64 = hash,
            token = token,
            deviceId = p[Keys.DEVICE_ID] ?: "",
            deviceName = p[Keys.DEVICE_NAME] ?: "",
            hosts = (p[Keys.HOSTS] ?: emptySet()).toList().sorted(),
        )
    }

    suspend fun snapshot(): Pairing? = pairing.first()

    suspend fun save(
        host: String,
        port: Int,
        serverKeyHashB64: String,
        token: String,
        deviceId: String,
        deviceName: String,
        hosts: List<String>,
    ) {
        context.dataStore.edit { p ->
            p[Keys.HOST] = host
            p[Keys.PORT] = port.toString()
            p[Keys.KEY_HASH] = serverKeyHashB64
            p[Keys.TOKEN_ENC] = encrypt(token.toByteArray(Charsets.UTF_8))
            p[Keys.DEVICE_ID] = deviceId
            p[Keys.DEVICE_NAME] = deviceName
            p[Keys.HOSTS] = hosts.toSet()
        }
    }

    /** Token revoked/forgotten (persistent 401): forget the credential, keep the pin. */
    suspend fun forgetToken() {
        context.dataStore.edit { p ->
            p.remove(Keys.TOKEN_ENC)
            p.remove(Keys.DEVICE_ID)
        }
    }

    /** Full unpair (user chose "forget server"). */
    suspend fun clearAll() {
        context.dataStore.edit { p ->
            p.clear()
        }
    }

    suspend fun saveHosts(extra: List<String>) {
        context.dataStore.edit { p ->
            val current = (p[Keys.HOSTS] ?: emptySet()).toMutableSet()
            current.addAll(extra)
            p[Keys.HOSTS] = current
        }
    }

    /** Stable id WE send with /pair: re-pairing with it replaces our own host record. */
    suspend fun deviceIdentifier(): String {
        val p = context.dataStore.data.first()
        val existing = p[Keys.DEVICE_IDENTIFIER]
        if (!existing.isNullOrBlank()) return existing
        val fresh = java.util.UUID.randomUUID().toString().uppercase()
        context.dataStore.edit { it[Keys.DEVICE_IDENTIFIER] = fresh }
        return fresh
    }

    companion object {
        fun randomDeviceIdentifier(): String =
            java.util.UUID.randomUUID().toString().uppercase()
    }
}