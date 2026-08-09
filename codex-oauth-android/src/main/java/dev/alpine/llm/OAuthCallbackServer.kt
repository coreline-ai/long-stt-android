package dev.alpine.llm

import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/** Loopback callback server used by browser-based OAuth providers. */
class OAuthCallbackServer(
    private val requestedPort: Int,
    private val redirectPath: String,
    private val fallbackPorts: List<Int> = emptyList(),
    private val corsAllowedOrigins: Set<String> = emptySet(),
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxRequestLineBytes: Int = DEFAULT_MAX_REQUEST_LINE_BYTES,
    private val maxHeaderLineBytes: Int = DEFAULT_MAX_HEADER_LINE_BYTES,
    private val maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    private val maxHeaderCount: Int = DEFAULT_MAX_HEADER_COUNT,
    private val onCallback: (Callback) -> Unit,
) {
    data class Callback(
        val code: String?,
        val state: String?,
        val error: String?,
        val errorDescription: String? = null,
    )

    @Volatile
    private var running = false
    private var socket: ServerSocket? = null
    var boundPort: Int = requestedPort
        private set

    init {
        require(redirectPath.startsWith("/")) { "redirectPath must start with /" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(maxRequestLineBytes > 0) { "maxRequestLineBytes must be positive" }
        require(maxHeaderLineBytes > 0) { "maxHeaderLineBytes must be positive" }
        require(maxHeaderBytes > 0) { "maxHeaderBytes must be positive" }
        require(maxHeaderCount > 0) { "maxHeaderCount must be positive" }
    }

    fun start() {
        val ports = listOf(requestedPort) + fallbackPorts
        for (port in ports) {
            try {
                socket = ServerSocket(port, 1, InetAddress.getByName(LOOPBACK))
                boundPort = requireNotNull(socket).localPort
                break
            } catch (_: java.net.BindException) {
                // Try the next configured port.
            }
        }
        if (socket == null) {
            running = false
            error("OAuth callback ports are unavailable: $ports")
        }
        running = true
        Thread({ acceptLoop() }, "alpine-oauth-callback").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                socket?.accept() ?: return
            } catch (_: Exception) {
                return
            }
            runCatching { handle(client) }.also { runCatching { client.close() } }
        }
    }

    private fun handle(client: java.net.Socket) {
        client.soTimeout = readTimeoutMs
        val input = client.getInputStream()
        val requestLine = readLineLimited(input, maxRequestLineBytes) ?: return
        var headerBytes = 0
        var headerCount = 0
        val headers = linkedMapOf<String, String>()
        while (true) {
            val header = readLineLimited(input, maxHeaderLineBytes) ?: break
            if (header.isEmpty()) break
            headerCount++
            headerBytes += header.toByteArray(StandardCharsets.ISO_8859_1).size
            require(headerCount <= maxHeaderCount) { "OAuth callback has too many headers" }
            require(headerBytes <= maxHeaderBytes) { "OAuth callback headers exceed limit" }
            val separator = header.indexOf(':')
            if (separator > 0) {
                headers[header.substring(0, separator).trim().lowercase()] =
                    header.substring(separator + 1).trim()
            }
        }
        val parts = requestLine.split(" ")
        if (parts.size != 3) return
        val method = parts[0]
        val target = parts[1].takeIf { it.startsWith("/") } ?: return
        val uri = URI("http://$LOOPBACK$target")
        if (uri.path != redirectPath) return
        if (method == "OPTIONS") {
            handleCorsPreflight(client, headers["origin"])
            return
        }
        if (method != "GET") return

        val params = uri.rawQuery.orEmpty().split("&")
            .filter { it.isNotEmpty() }
            .associate {
                val pair = it.split("=", limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to
                    URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
        // Receiving the callback is not the same as completing sign-in: state
        // validation, token exchange, and encrypted persistence still happen
        // in OAuthManager after this response is sent.
        val html = "<html><body><h1>Authorization received</h1>" +
            "<p>Returning to the app to finish sign-in.</p>" +
            "<script>window.close()</script></body></html>"
        val response = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n$html"
        client.getOutputStream().use { it.write(response.toByteArray(StandardCharsets.UTF_8)) }
        onCallback(
            Callback(
                code = params["code"],
                state = params["state"],
                error = params["error"],
                errorDescription = params["error_description"],
            ),
        )
    }

    private fun handleCorsPreflight(client: java.net.Socket, origin: String?) {
        if (origin == null || origin !in corsAllowedOrigins) {
            val response = "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n" +
                "Content-Length: 0\r\n\r\n"
            client.getOutputStream().use {
                it.write(response.toByteArray(StandardCharsets.US_ASCII))
            }
            return
        }
        val response = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: $origin\r\n" +
            "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Max-Age: 600\r\n" +
            "Vary: Origin\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().use {
            it.write(response.toByteArray(StandardCharsets.US_ASCII))
        }
    }

    private fun readLineLimited(input: InputStream, limit: Int): String? {
        val bytes = ArrayList<Byte>(minOf(limit, 256))
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.isEmpty()) return null
                break
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) {
                require(bytes.size < limit) { "OAuth callback line exceeds limit" }
                bytes.add(value.toByte())
            }
        }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_READ_TIMEOUT_MS = 5_000
        const val DEFAULT_MAX_REQUEST_LINE_BYTES = 8 * 1024
        const val DEFAULT_MAX_HEADER_LINE_BYTES = 8 * 1024
        const val DEFAULT_MAX_HEADER_BYTES = 32 * 1024
        const val DEFAULT_MAX_HEADER_COUNT = 64
    }
}
