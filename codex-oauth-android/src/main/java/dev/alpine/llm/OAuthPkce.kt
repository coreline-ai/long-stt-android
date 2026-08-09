package dev.alpine.llm

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class OAuthPkceMode {
    STANDARD,
    BASE64URL_96_BYTES,
    HEX_32_BYTES,
}

object OAuthPkce {
    data class PairValue(val verifier: String, val challenge: String)

    fun create(byteLength: Int = 64): PairValue {
        require(byteLength >= 32) { "PKCE verifier must contain enough entropy" }
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PairValue(verifier, challenge)
    }

    fun create(mode: OAuthPkceMode): PairValue = when (mode) {
        OAuthPkceMode.STANDARD -> create()
        OAuthPkceMode.BASE64URL_96_BYTES -> create(byteLength = 96)
        OAuthPkceMode.HEX_32_BYTES -> createHex()
    }

    /** 32 random bytes encoded as 64 lowercase hex characters. */
    fun createHex(byteLength: Int = 32): PairValue {
        require(byteLength >= 32) { "PKCE verifier must contain enough entropy" }
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        val verifier = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PairValue(verifier, challenge)
    }

    fun state(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
