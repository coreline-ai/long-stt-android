package dev.alpine.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted token and OAuth transaction storage.
 *
 * This is deliberately independent from OpenMinis' preference factory so the
 * module can be embedded in another Android application.
 */
class OAuthTokenStore(context: Context) {
    data class Token(
        val accessToken: String,
        val refreshToken: String? = null,
        val tokenType: String = "Bearer",
        val expiresAtMs: Long? = null,
        val scope: String? = null,
        val metadata: Map<String, String> = emptyMap(),
    ) {
        fun isExpiringWithin(nowMs: Long, skewMs: Long): Boolean =
            expiresAtMs != null && expiresAtMs <= nowMs + skewMs
    }

    data class Transaction(
        val state: String,
        val verifier: String,
        val createdAtMs: Long,
        val challenge: String? = null,
    )

    enum class InvalidationReason {
        DECRYPTION_FAILED,
        MALFORMED_TOKEN,
    }

    sealed interface ReadResult {
        data class Available(val token: Token) : ReadResult
        data object Missing : ReadResult
        data class ReauthenticationRequired(val reason: InvalidationReason) : ReadResult
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(providerId: String, token: Token) {
        require(token.accessToken.isNotBlank()) { "accessToken must not be blank" }
        saveEncrypted(tokenKey(providerId), OAuthTokenJsonCodec.encode(token))
        prefs.edit().remove(invalidationKey(providerId)).apply()
    }

    fun read(providerId: String): ReadResult =
        when (val encrypted = loadEncrypted(tokenKey(providerId))) {
            EncryptedReadResult.Missing -> {
                val invalidation = prefs.getString(invalidationKey(providerId), null)
                    ?.let { runCatching { InvalidationReason.valueOf(it) }.getOrNull() }
                if (invalidation == null) ReadResult.Missing
                else ReadResult.ReauthenticationRequired(invalidation)
            }
            EncryptedReadResult.Failed -> invalidate(providerId, InvalidationReason.DECRYPTION_FAILED)
            is EncryptedReadResult.Available -> {
                val token = OAuthTokenJsonCodec.decode(encrypted.plaintext)
                if (token == null) invalidate(providerId, InvalidationReason.MALFORMED_TOKEN)
                else ReadResult.Available(token)
            }
        }

    fun load(providerId: String): Token? =
        (read(providerId) as? ReadResult.Available)?.token

    fun delete(providerId: String) {
        prefs.edit()
            .remove(tokenKey(providerId))
            .remove(transactionKey(providerId))
            .remove(invalidationKey(providerId))
            .apply()
    }

    fun saveTransaction(providerId: String, transaction: Transaction) {
        saveEncrypted(
            transactionKey(providerId),
            JSONObject()
                .put("state", transaction.state)
                .put("verifier", transaction.verifier)
                .put("created_at_ms", transaction.createdAtMs)
                .apply { transaction.challenge?.let { put("challenge", it) } }
                .toString(),
        )
    }

    fun loadTransaction(providerId: String): Transaction? {
        val encrypted = loadEncrypted(transactionKey(providerId))
        val raw = (encrypted as? EncryptedReadResult.Available)?.plaintext
            ?: run {
                if (encrypted == EncryptedReadResult.Failed) clearTransaction(providerId)
                return null
            }
        return runCatching {
            val json = JSONObject(raw)
            Transaction(
                state = json.optString("state").ifBlank { error("state is missing") },
                verifier = json.optString("verifier").ifBlank { error("verifier is missing") },
                createdAtMs = json.optLong("created_at_ms").takeIf { it > 0 }
                    ?: error("created_at_ms is missing"),
                challenge = json.optString("challenge").ifBlank { null },
            )
        }.getOrElse {
            clearTransaction(providerId)
            null
        }
    }

    fun clearTransaction(providerId: String) {
        prefs.edit().remove(transactionKey(providerId)).apply()
    }

    private fun saveEncrypted(name: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val value = Base64.getEncoder().encodeToString(cipher.iv) + ":" +
            Base64.getEncoder().encodeToString(encrypted)
        prefs.edit().putString(name, value).apply()
    }

    private fun loadEncrypted(name: String): EncryptedReadResult {
        val value = prefs.getString(name, null) ?: return EncryptedReadResult.Missing
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) return EncryptedReadResult.Failed
        return try {
            val iv = Base64.getDecoder().decode(parts[0])
            val encrypted = Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            EncryptedReadResult.Available(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            EncryptedReadResult.Failed
        }
    }

    private fun invalidate(providerId: String, reason: InvalidationReason): ReadResult {
        prefs.edit()
            .remove(tokenKey(providerId))
            .putString(invalidationKey(providerId), reason.name)
            .apply()
        return ReadResult.ReauthenticationRequired(reason)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun tokenKey(providerId: String) = "token_${safeId(providerId)}"
    private fun transactionKey(providerId: String) = "transaction_${safeId(providerId)}"
    private fun invalidationKey(providerId: String) = "invalid_${safeId(providerId)}"
    private fun safeId(value: String) = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private sealed interface EncryptedReadResult {
        data class Available(val plaintext: String) : EncryptedReadResult
        data object Missing : EncryptedReadResult
        data object Failed : EncryptedReadResult
    }

    private companion object {
        const val FILE = "alpine_llm_oauth"
        const val KEY_ALIAS = "alpine_llm_oauth_aes"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal object OAuthTokenJsonCodec {
    fun encode(token: OAuthTokenStore.Token): String {
        val json = JSONObject()
            .put("access_token", token.accessToken)
            .put("token_type", token.tokenType)
        token.refreshToken?.let { json.put("refresh_token", it) }
        token.expiresAtMs?.let { json.put("expires_at_ms", it) }
        token.scope?.let { json.put("scope", it) }
        if (token.metadata.isNotEmpty()) {
            json.put("metadata", JSONObject(token.metadata))
        }
        return json.toString()
    }

    fun decode(raw: String): OAuthTokenStore.Token? = runCatching {
        val json = JSONObject(raw)
        val metadataJson = json.optJSONObject("metadata")
        val metadata = buildMap {
            if (metadataJson != null) {
                val keys = metadataJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = metadataJson.optString(key)
                    if (value.isNotBlank()) put(key, value)
                }
            }
        }
        OAuthTokenStore.Token(
            accessToken = json.optString("access_token").ifBlank {
                error("access_token is missing")
            },
            refreshToken = json.optString("refresh_token").ifBlank { null },
            tokenType = json.optString("token_type", "Bearer"),
            expiresAtMs = json.optLong("expires_at_ms", 0L).takeIf { it > 0 },
            scope = json.optString("scope").ifBlank { null },
            metadata = metadata,
        )
    }.getOrNull()
}
