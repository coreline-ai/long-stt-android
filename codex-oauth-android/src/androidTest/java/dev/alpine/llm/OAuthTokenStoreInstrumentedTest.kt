package dev.alpine.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class OAuthTokenStoreInstrumentedTest {
    @Test
    fun encryptedTokenSurvivesStoreRecreationAndCanBeDeleted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providerId = "instrumented-${System.nanoTime()}"
        val token = OAuthTokenStore.Token(
            accessToken = "instrumented-access",
            refreshToken = "instrumented-refresh",
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            metadata = mapOf("account_id" to "instrumented-account"),
        )

        OAuthTokenStore(context).save(providerId, token)
        val restored = OAuthTokenStore(context).load(providerId)

        assertEquals(token, restored)
        OAuthTokenStore(context).delete(providerId)
        assertNull(OAuthTokenStore(context).load(providerId))
    }

    @Test
    fun persistedPreferencesContainCiphertextAndKeystoreKeyIsNotExportable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providerId = "ciphertext-${System.nanoTime()}"
        val accessToken = "device-access-${System.nanoTime()}"
        val refreshToken = "device-refresh-${System.nanoTime()}"
        val verifier = "device-verifier-${System.nanoTime()}"
        val store = OAuthTokenStore(context)

        try {
            store.save(
                providerId,
                OAuthTokenStore.Token(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                ),
            )
            store.saveTransaction(
                providerId,
                OAuthTokenStore.Transaction(
                    state = "device-state",
                    verifier = verifier,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )

            val preferences = context.getSharedPreferences(
                "alpine_llm_oauth",
                android.content.Context.MODE_PRIVATE,
            )
            val storedValues = preferences.all.values.joinToString(separator = "\n")
            assertFalse(storedValues.contains(accessToken))
            assertFalse(storedValues.contains(refreshToken))
            assertFalse(storedValues.contains(verifier))
            assertTrue(storedValues.contains(":"))

            val preferencesFile = File(
                context.applicationInfo.dataDir,
                "shared_prefs/alpine_llm_oauth.xml",
            )
            val persisted = waitForFileContents(preferencesFile)
            assertFalse(persisted.contains(accessToken))
            assertFalse(persisted.contains(refreshToken))
            assertFalse(persisted.contains(verifier))

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey("alpine_llm_oauth_aes", null)
            assertTrue(key != null)
            assertNull(key?.encoded)
        } finally {
            store.delete(providerId)
        }
    }

    private fun waitForFileContents(file: File): String {
        repeat(50) {
            if (file.isFile && file.length() > 0L) return file.readText()
            Thread.sleep(20)
        }
        throw AssertionError("OAuth preferences were not persisted")
    }
}
