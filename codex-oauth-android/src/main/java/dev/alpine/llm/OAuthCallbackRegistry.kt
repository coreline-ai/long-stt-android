package dev.alpine.llm

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process allowlist used by the exported redirect Activity. It prevents an
 * explicit external intent from turning the Activity into a generic loopback
 * HTTP forwarder.
 */
internal object OAuthCallbackRegistry {
    private data class Expected(val path: String, val state: String)

    private val active = ConcurrentHashMap<Int, Expected>()

    fun register(port: Int, path: String, state: String) {
        val previous = active.putIfAbsent(port, Expected(path, state))
        check(previous == null) { "OAuth callback port is already registered: $port" }
    }

    fun matches(port: Int, path: String, state: String?): Boolean {
        val expected = active[port] ?: return false
        if (path != expected.path || state == null) return false
        return MessageDigest.isEqual(
            state.toByteArray(StandardCharsets.UTF_8),
            expected.state.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun unregister(port: Int, state: String) {
        active.remove(port, Expected(path = active[port]?.path ?: return, state = state))
    }
}
