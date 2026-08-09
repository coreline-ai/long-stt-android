package dev.alpine.llm

import android.app.Activity
import android.os.Bundle
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * No-display Activity for providers that redirect the browser to loopback.
 * The callback server performs the state validation; this Activity only
 * forwards the browser request back to that server and immediately exits.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val port = uri?.port?.takeIf { it > 0 }
        val path = uri?.path
        val state = uri?.getQueryParameter("state")
        val expected = uri != null &&
            uri.scheme == "http" &&
            (uri.host == "127.0.0.1" || uri.host == "localhost") &&
            port != null &&
            path != null &&
            OAuthCallbackRegistry.matches(port, path, state)
        if (uri != null && expected &&
            (uri.getQueryParameter("code") != null || uri.getQueryParameter("error") != null)
        ) {
            Thread {
                runCatching {
                    val callbackPort = requireNotNull(port)
                    val callbackPath = requireNotNull(path)
                    val query = uri.encodedQuery?.let { "?$it" } ?: ""
                    val target = "$callbackPath$query"
                    if (target.contains('\r') || target.contains('\n')) return@runCatching
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", callbackPort), 5_000)
                        socket.soTimeout = 5_000
                        val request = "GET $target HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:$callbackPort\r\n" +
                            "Connection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(request.toByteArray(StandardCharsets.US_ASCII))
                            flush()
                        }
                        BufferedReader(
                            InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII),
                        ).use { it.readLine() }
                    }
                }
            }.start()
        }
        finish()
    }
}
