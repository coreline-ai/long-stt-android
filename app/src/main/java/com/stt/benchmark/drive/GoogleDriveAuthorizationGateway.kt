package com.stt.benchmark.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google Identity Services를 Drive 업로드 경계로 제한한다.
 * access token과 선택된 account는 이 객체의 호출 범위를 벗어나 저장하지 않는다.
 */
class GoogleDriveAuthorizationGateway(
    private val context: Context,
) {
    sealed interface Outcome {
        data class Granted internal constructor(
            internal val token: String,
            internal val result: AuthorizationResult,
        ) : Outcome

        data class NeedsUserAction(val pendingIntent: PendingIntent) : Outcome
        data class Failure(val code: String) : Outcome
    }

    suspend fun authorize(): Outcome = try {
        val result = client().authorize(request()).await()
        if (result.hasResolution()) {
            result.pendingIntent?.let(Outcome::NeedsUserAction) ?: Outcome.Failure("AUTH_RESOLUTION_MISSING")
        } else {
            result.toGranted()
        }
    } catch (error: Throwable) {
        Outcome.Failure(error.javaClass.simpleName.safeCode())
    }

    fun consumeAuthorizationResult(intent: Intent?): Outcome {
        if (intent == null) return Outcome.Failure("AUTH_CANCELLED")
        return try {
            client().getAuthorizationResultFromIntent(intent).toGranted()
        } catch (error: Throwable) {
            Outcome.Failure(error.javaClass.simpleName.safeCode())
        }
    }

    suspend fun revoke(granted: Outcome.Granted): Boolean {
        val account = granted.result.toGoogleSignInAccount()?.account ?: return false
        return runCatching {
            client().revokeAccess(
                RevokeAccessRequest.builder()
                    .setAccount(account)
                    .setScopes(listOf(DRIVE_FILE_SCOPE))
                    .build(),
            ).await()
            true
        }.getOrDefault(false)
    }

    suspend fun clearToken(token: String) {
        if (token.isBlank()) return
        runCatching {
            client().clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
    }

    private fun client() = Identity.getAuthorizationClient(context.applicationContext)

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(DRIVE_FILE_SCOPE))
        .build()

    private fun AuthorizationResult.toGranted(): Outcome {
        if (hasResolution()) return pendingIntent?.let(Outcome::NeedsUserAction)
            ?: Outcome.Failure("AUTH_RESOLUTION_MISSING")
        if (!grantedScopes.contains(DRIVE_FILE_SCOPE.scopeUri)) return Outcome.Failure("DRIVE_SCOPE_DENIED")
        val token = accessToken.orEmpty()
        return token.takeIf(String::isNotBlank)?.let { Outcome.Granted(it, this) }
            ?: Outcome.Failure("ACCESS_TOKEN_MISSING")
    }

    private fun String.safeCode(): String = take(64).filter { it.isLetterOrDigit() || it == '_' }

    companion object {
        const val DRIVE_FILE_SCOPE_URI = "https://www.googleapis.com/auth/drive.file"
        val DRIVE_FILE_SCOPE = Scope(DRIVE_FILE_SCOPE_URI)
    }
}
