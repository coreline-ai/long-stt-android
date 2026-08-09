package dev.alpine.llm

/** Provider-neutral OAuth 2.0 Authorization Code + PKCE configuration. */
data class OAuthProviderConfig(
    val providerId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
    val callbackPort: Int,
    val redirectPath: String = "/oauth/callback",
    val redirectHost: String = "127.0.0.1",
    val callbackFallbackPorts: List<Int> = listOf(callbackPort + 1, callbackPort + 2),
    val clientSecret: String? = null,
    val clientAuthMethod: ClientAuthMethod = ClientAuthMethod.NONE,
    val extraAuthorizationParams: Map<String, String> = emptyMap(),
    val includeAuthorizationNonce: Boolean = false,
    val pkceMode: OAuthPkceMode = OAuthPkceMode.STANDARD,
    val extraTokenParams: Map<String, String> = emptyMap(),
    val tokenRequestEncoding: OAuthTokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
    val tokenRequestAdapter: OAuthTokenRequestAdapter = StandardOAuthTokenRequestAdapter,
    val tokenResponseAdapter: OAuthTokenResponseAdapter = StandardOAuthTokenResponseAdapter(),
    val tokenRequestMaxAttempts: Int = 1,
    val tokenRetryInitialDelayMs: Long = 0L,
    val discoveryEndpoint: String? = null,
    val trustedDiscoveryEndpointHosts: Set<String> = emptySet(),
    val callbackCorsAllowedOrigins: Set<String> = emptySet(),
    val refreshSkewMs: Long = 5 * 60 * 1000L,
    val callbackTimeoutMs: Long = 5 * 60 * 1000L,
) {
    enum class ClientAuthMethod { NONE, BODY, BASIC }

    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(authorizationEndpoint.startsWith("https://")) {
            "authorizationEndpoint must use HTTPS"
        }
        require(tokenEndpoint.startsWith("https://")) { "tokenEndpoint must use HTTPS" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(callbackPort in 1..65535) { "callbackPort must be a valid TCP port" }
        require(redirectHost in LOOPBACK_HOSTS) {
            "redirectHost must be localhost or 127.0.0.1"
        }
        require(callbackFallbackPorts.all { it in 1..65535 }) {
            "callbackFallbackPorts must contain valid TCP ports"
        }
        require(callbackFallbackPorts.distinct().size == callbackFallbackPorts.size) {
            "callbackFallbackPorts must not contain duplicates"
        }
        require(callbackPort !in callbackFallbackPorts) {
            "callbackFallbackPorts must not contain callbackPort"
        }
        require(redirectPath.startsWith("/")) { "redirectPath must start with /" }
        require(tokenRequestMaxAttempts > 0) { "tokenRequestMaxAttempts must be positive" }
        require(tokenRetryInitialDelayMs >= 0) { "tokenRetryInitialDelayMs must not be negative" }
        if (discoveryEndpoint != null) {
            require(discoveryEndpoint.startsWith("https://")) {
                "discoveryEndpoint must use HTTPS"
            }
            require(trustedDiscoveryEndpointHosts.isNotEmpty()) {
                "trustedDiscoveryEndpointHosts is required with discoveryEndpoint"
            }
        }
        require(trustedDiscoveryEndpointHosts.all(::isValidHost)) {
            "trustedDiscoveryEndpointHosts must contain normalized host names"
        }
        require(callbackCorsAllowedOrigins.all(::isValidHttpsOrigin)) {
            "callbackCorsAllowedOrigins must contain HTTPS origins without paths"
        }
        require(refreshSkewMs >= 0) { "refreshSkewMs must not be negative" }
        require(callbackTimeoutMs > 0) { "callbackTimeoutMs must be positive" }
        if (clientAuthMethod != ClientAuthMethod.NONE) {
            require(!clientSecret.isNullOrBlank()) {
                "clientSecret is required by the selected clientAuthMethod"
            }
        }
    }

    fun redirectUri(port: Int = callbackPort): String =
        "http://$redirectHost:$port$redirectPath"

    private companion object {
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")

        fun isValidHost(value: String): Boolean =
            value.isNotBlank() && value == value.lowercase() &&
                value.none { it == '/' || it == ':' || it.isWhitespace() }

        fun isValidHttpsOrigin(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") && uri.query == null &&
                uri.fragment == null
        }.getOrDefault(false)
    }
}
