package dev.alpine.llm

/**
 * Codex account OAuth wire contract.
 *
 * The public client id is intentionally supplied by the host application. It
 * identifies an OAuth registration and must not be copied from another app.
 */
object CodexOAuthContract {
    const val AUTHORIZATION_ENDPOINT = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
    const val RESPONSES_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
    const val CALLBACK_PORT = 1455
    const val REDIRECT_PATH = "/auth/callback"
    const val REDIRECT_HOST = "localhost"

    val SCOPES = listOf("openid", "profile", "email", "offline_access")

    fun providerConfig(
        providerId: String,
        clientId: String,
        refreshSkewMs: Long = 5 * 60 * 1000L,
        callbackTimeoutMs: Long = 5 * 60 * 1000L,
    ): OAuthProviderConfig = OAuthProviderConfig(
        providerId = providerId,
        authorizationEndpoint = AUTHORIZATION_ENDPOINT,
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = clientId,
        scopes = SCOPES,
        callbackPort = CALLBACK_PORT,
        redirectPath = REDIRECT_PATH,
        redirectHost = REDIRECT_HOST,
        callbackFallbackPorts = emptyList(),
        extraAuthorizationParams = mapOf(
            "codex_cli_simplified_flow" to "true",
            "originator" to "codex_cli_rs",
            "id_token_add_organizations" to "true",
        ),
        // Match the current Codex CLI token endpoint wire contract. The
        // authorization endpoint accepts the same PKCE parameters regardless
        // of platform, but the first-party client posts token grants as form
        // data rather than JSON.
        tokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
        tokenRequestAdapter = OAuthTokenRequestAdapter { context ->
            if (context.grantType == OAuthTokenGrantType.REFRESH_TOKEN) {
                context.parameters + ("scope" to SCOPES.joinToString(" "))
            } else {
                context.parameters
            }
        },
        tokenResponseAdapter = JwtClaimMetadataTokenResponseAdapter(),
        // A callback code is short-lived. Retry only transport failures so a
        // brief DNS/network hand-off after the Custom Tab closes does not make
        // the whole login fail. HTTP/provider errors remain single-attempt.
        tokenRequestMaxAttempts = 3,
        tokenRetryInitialDelayMs = 1_000L,
        refreshSkewMs = refreshSkewMs,
        callbackTimeoutMs = callbackTimeoutMs,
    )
}
