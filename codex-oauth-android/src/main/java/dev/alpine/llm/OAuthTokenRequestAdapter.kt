package dev.alpine.llm

import org.json.JSONObject

enum class OAuthTokenRequestEncoding {
    FORM_URLENCODED,
    JSON,
}

enum class OAuthTokenGrantType {
    AUTHORIZATION_CODE,
    REFRESH_TOKEN,
}

data class OAuthTokenRequestContext(
    val grantType: OAuthTokenGrantType,
    val parameters: Map<String, String>,
    val state: String? = null,
    val codeChallenge: String? = null,
)

/**
 * Provider extension point for dynamic token parameters. For example,
 * Anthropic can echo state and xAI can echo the PKCE challenge.
 */
fun interface OAuthTokenRequestAdapter {
    fun adapt(context: OAuthTokenRequestContext): Map<String, String>
}

object StandardOAuthTokenRequestAdapter : OAuthTokenRequestAdapter {
    override fun adapt(context: OAuthTokenRequestContext): Map<String, String> =
        context.parameters
}

internal data class EncodedOAuthTokenRequest(
    val contentType: String,
    val body: String,
)

internal object OAuthTokenRequestEncoder {
    fun encode(
        parameters: Map<String, String>,
        encoding: OAuthTokenRequestEncoding,
    ): EncodedOAuthTokenRequest = when (encoding) {
        OAuthTokenRequestEncoding.FORM_URLENCODED -> EncodedOAuthTokenRequest(
            contentType = "application/x-www-form-urlencoded",
            body = OAuthPkce.formEncode(parameters),
        )
        OAuthTokenRequestEncoding.JSON -> EncodedOAuthTokenRequest(
            contentType = "application/json",
            body = JSONObject(parameters).toString(),
        )
    }
}
