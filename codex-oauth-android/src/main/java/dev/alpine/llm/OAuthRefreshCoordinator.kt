package dev.alpine.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes refreshes per Provider and reloads storage after acquiring the
 * lock so a rotated refresh token is never reused by a waiting caller.
 */
internal object OAuthRefreshCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(
        providerId: String,
        refreshSkewMs: Long,
        clock: () -> Long = System::currentTimeMillis,
        loadLatest: () -> OAuthTokenStore.Token?,
        refresh: suspend (OAuthTokenStore.Token, String) -> OAuthTokenStore.Token,
        clearCredential: () -> Unit,
    ): OAuthTokenStore.Token? = locks.getOrPut(providerId) { Mutex() }.withLock {
        val latest = loadLatest() ?: return@withLock null
        val now = clock()
        if (!latest.isExpiringWithin(now, refreshSkewMs)) return@withLock latest
        val refreshToken = latest.refreshToken
            ?: return@withLock latest.takeIf {
                it.expiresAtMs == null || it.expiresAtMs > now
            }
        try {
            refresh(latest, refreshToken)
        } catch (error: OAuthException) {
            if (error.invalidGrant) {
                clearCredential()
                null
            } else {
                latest.takeIf { it.expiresAtMs == null || it.expiresAtMs > now }
                    ?: throw error
            }
        }
    }

    suspend fun resolveRejected(
        providerId: String,
        rejectedAccessToken: String,
        loadLatest: () -> OAuthTokenStore.Token?,
        refresh: suspend (OAuthTokenStore.Token, String) -> OAuthTokenStore.Token,
        clearCredential: () -> Unit,
    ): OAuthTokenStore.Token? = locks.getOrPut(providerId) { Mutex() }.withLock {
        val latest = loadLatest() ?: return@withLock null
        if (latest.accessToken != rejectedAccessToken) {
            return@withLock latest
        }
        val refreshToken = latest.refreshToken
        if (refreshToken.isNullOrBlank()) {
            clearCredential()
            return@withLock null
        }
        try {
            refresh(latest, refreshToken)
        } catch (error: OAuthException) {
            if (error.invalidGrant) {
                clearCredential()
                null
            } else {
                throw error
            }
        }
    }
}
