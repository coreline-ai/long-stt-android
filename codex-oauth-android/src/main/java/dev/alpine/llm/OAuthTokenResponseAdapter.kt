package dev.alpine.llm

import org.json.JSONObject

/**
 * Provider extension point for token responses that do not use the standard
 * top-level OAuth field layout.
 */
fun interface OAuthTokenResponseAdapter {
    fun parse(response: JSONObject, nowMs: Long): OAuthTokenStore.Token
}

class StandardOAuthTokenResponseAdapter(
    private val metadataFields: Set<String> = DEFAULT_METADATA_FIELDS,
) : OAuthTokenResponseAdapter {
    override fun parse(response: JSONObject, nowMs: Long): OAuthTokenStore.Token {
        val accessToken = response.optString("access_token").ifBlank {
            throw OAuthException(
                "token response did not contain access_token",
                OAuthFailureKind.PROTOCOL,
            )
        }
        val expiresIn = response.optLong("expires_in", 0L)
        val metadata = metadataFields.mapNotNull { field ->
            val value = response.opt(field)
            when (value) {
                null, JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotBlank() }?.let { field to it }
                is Number, is Boolean -> field to value.toString()
                else -> null
            }
        }.toMap()
        return OAuthTokenStore.Token(
            accessToken = accessToken,
            refreshToken = response.optString("refresh_token").ifBlank { null },
            tokenType = response.optString("token_type", "Bearer"),
            expiresAtMs = (nowMs + expiresIn * 1000L).takeIf { expiresIn > 0 },
            scope = response.optString("scope").ifBlank { null },
            metadata = metadata,
        )
    }

    private companion object {
        val DEFAULT_METADATA_FIELDS = setOf(
            "account_id",
            "plan_type",
            "email",
            "gcp_project",
        )
    }
}
