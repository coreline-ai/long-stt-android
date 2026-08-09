package dev.alpine.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class OAuthFailureKind {
    USER_DENIED,
    CALLBACK_TIMEOUT,
    STATE_MISMATCH,
    TRANSACTION_EXPIRED,
    INVALID_GRANT,
    STORAGE_INVALIDATED,
    STORAGE_FAILURE,
    PROVIDER_ERROR,
    NETWORK,
    PROTOCOL,
    CONFIGURATION,
}

class OAuthException(
    message: String,
    val kind: OAuthFailureKind = OAuthFailureKind.PROTOCOL,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val invalidGrant: Boolean
        get() = kind == OAuthFailureKind.INVALID_GRANT
}

sealed interface OAuthAuthenticationState {
    data object SignedOut : OAuthAuthenticationState

    data class Authenticated(
        val expiresAtMs: Long?,
        val metadata: Map<String, String>,
    ) : OAuthAuthenticationState

    data class ReauthenticationRequired(
        val reason: OAuthTokenStore.InvalidationReason,
    ) : OAuthAuthenticationState
}

internal object OAuthCallbackValidator {
    fun validate(
        callback: OAuthCallbackServer.Callback,
        transaction: OAuthTokenStore.Transaction?,
        nowMs: Long,
        timeoutMs: Long,
    ): String {
        if (!callback.error.isNullOrBlank()) {
            val description = callback.errorDescription?.takeIf { it.isNotBlank() }
            val suffix = description?.let { ": $it" }.orEmpty()
            val kind = if (callback.error == "access_denied") {
                OAuthFailureKind.USER_DENIED
            } else {
                OAuthFailureKind.PROVIDER_ERROR
            }
            throw OAuthException("authorization failed (${callback.error})$suffix", kind)
        }
        val code = callback.code?.takeIf { it.isNotBlank() }
            ?: throw OAuthException(
                "authorization callback did not contain a code",
                OAuthFailureKind.PROTOCOL,
            )
        val saved = transaction
            ?: throw OAuthException(
                "OAuth transaction is no longer available",
                OAuthFailureKind.TRANSACTION_EXPIRED,
            )
        if (nowMs - saved.createdAtMs > timeoutMs) {
            throw OAuthException("OAuth transaction expired", OAuthFailureKind.TRANSACTION_EXPIRED)
        }
        if (callback.state != saved.state) {
            throw OAuthException("OAuth state mismatch", OAuthFailureKind.STATE_MISMATCH)
        }
        return code
    }
}

internal object OAuthCallbackAwaiter {
    suspend fun await(
        callback: CompletableDeferred<OAuthCallbackServer.Callback>,
        timeoutMs: Long,
    ): OAuthCallbackServer.Callback = try {
        withTimeout(timeoutMs) { callback.await() }
    } catch (error: TimeoutCancellationException) {
        throw OAuthException(
            "OAuth callback timed out",
            OAuthFailureKind.CALLBACK_TIMEOUT,
            error,
        )
    }
}
