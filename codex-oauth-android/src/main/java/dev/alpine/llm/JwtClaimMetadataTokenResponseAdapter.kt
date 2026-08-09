package dev.alpine.llm

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Adds display-only claims from id_token to encrypted token metadata.
 *
 * JWT signature validation remains the Provider/OIDC client's responsibility.
 * Extracted claims must never be used for authorization decisions.
 */
class JwtClaimMetadataTokenResponseAdapter(
    private val delegate: OAuthTokenResponseAdapter = StandardOAuthTokenResponseAdapter(),
    private val claimMappings: Map<String, List<String>> = DEFAULT_CLAIM_MAPPINGS,
) : OAuthTokenResponseAdapter {
    override fun parse(response: JSONObject, nowMs: Long): OAuthTokenStore.Token {
        val token = delegate.parse(response, nowMs)
        val claims = parseClaims(response.optString("id_token"))
        if (claims == null) return token
        val metadata = token.metadata.toMutableMap()
        claimMappings.forEach { (metadataKey, claimNames) ->
            if (metadataKey !in metadata) {
                claimNames.firstNotNullOfOrNull { claim ->
                    claims.optString(claim).takeIf { it.isNotBlank() }
                }?.let { metadata[metadataKey] = it }
            }
        }
        return token.copy(metadata = metadata)
    }

    private fun parseClaims(idToken: String): JSONObject? = runCatching {
        val segments = idToken.split(".")
        if (segments.size < 2) return null
        val bytes = Base64.getUrlDecoder().decode(segments[1])
        JSONObject(String(bytes, StandardCharsets.UTF_8))
    }.getOrNull()

    private companion object {
        val DEFAULT_CLAIM_MAPPINGS = mapOf(
            "account_id" to listOf("chatgpt_account_id", "account_id", "sub"),
            "plan_type" to listOf("chatgpt_plan_type", "plan_type"),
            "email" to listOf("email"),
            "name" to listOf("name"),
        )
    }
}
