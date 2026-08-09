package dev.alpine.llm

import java.net.URI
import org.json.JSONObject

internal data class OAuthResolvedEndpoints(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
)

/** Parses OIDC discovery without accepting endpoints outside the configured trust boundary. */
internal object OAuthDiscoveryDocument {
    fun parse(body: String, trustedHosts: Set<String>): OAuthResolvedEndpoints {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw OAuthException(
                "OAuth discovery returned invalid JSON",
                OAuthFailureKind.PROTOCOL,
                it,
            )
        }
        val authorizationEndpoint = json.optString("authorization_endpoint")
        val tokenEndpoint = json.optString("token_endpoint")
        if (authorizationEndpoint.isBlank() || tokenEndpoint.isBlank()) {
            throw OAuthException(
                "OAuth discovery did not contain required endpoints",
                OAuthFailureKind.PROTOCOL,
            )
        }
        requireTrustedHttpsEndpoint(authorizationEndpoint, trustedHosts)
        requireTrustedHttpsEndpoint(tokenEndpoint, trustedHosts)
        return OAuthResolvedEndpoints(authorizationEndpoint, tokenEndpoint)
    }

    private fun requireTrustedHttpsEndpoint(value: String, trustedHosts: Set<String>) {
        val uri = runCatching { URI(value) }.getOrNull()
        val host = uri?.host?.lowercase()
        if (
            uri == null || uri.scheme != "https" || host !in trustedHosts ||
            uri.userInfo != null || uri.fragment != null
        ) {
            throw OAuthException(
                "OAuth discovery returned an untrusted endpoint",
                OAuthFailureKind.CONFIGURATION,
            )
        }
    }
}
