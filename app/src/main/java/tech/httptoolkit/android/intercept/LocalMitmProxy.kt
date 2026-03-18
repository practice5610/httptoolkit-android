package tech.httptoolkit.android.intercept

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tech.httptoolkit.android.ca.GeneratedCa
import tech.httptoolkit.android.ca.issueServerCert
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

private const val TAG = "LocalMitmProxy"
private data class KeyMaterial(
    val privateKey: PrivateKey,
    val chain: Array<X509Certificate>
)

private val JSON = "application/json; charset=utf-8".toMediaType()
private val tokenHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .build()

/**
 * Local MITM proxy: listens on 127.0.0.1:LOCAL_PROXY_PORT, terminates TLS with dynamically
 * generated certs, parses HTTP to extract Ludoking Authorization header, then forwards to origin.
 * When [apiKey] and [serverBaseUrl] are set, captured auth tokens are POSTed to the server.
 */
class LocalMitmProxy(
    private val ca: GeneratedCa,
    private val apiKey: String? = null,
    private val serverBaseUrl: String? = null
) : Runnable {

    private val running = AtomicBoolean(false)
    private var sslServerSocket: javax.net.ssl.SSLServerSocket? = null
    private val tokenSenderExecutor = Executors.newSingleThreadExecutor()

    private val sslContext: SSLContext by lazy {
        val km = object : X509ExtendedKeyManager() {
            private val keyByAlias = ConcurrentHashMap<String, KeyMaterial>()

            private fun createAliasForHost(hostname: String): String {
                val (privateKey, cert) = issueServerCert(ca, hostname)
                val alias = "$hostname-${UUID.randomUUID()}"
                keyByAlias[alias] = KeyMaterial(privateKey, arrayOf(cert, ca.certificate))
                return alias
            }

            private fun extractSniHost(session: ExtendedSSLSession?): String? {
                if (session == null) return null
                val names = session.requestedServerNames ?: return null
                val sni = names.find { it.type == 0 } ?: return null
                return when (sni) {
                    is SNIHostName -> sni.asciiName
                    is SNIServerName -> String(sni.encoded, StandardCharsets.UTF_8)
                    else -> null
                }
            }

            override fun chooseEngineServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                engine: javax.net.ssl.SSLEngine?
            ): String {
                val session = engine?.handshakeSession as? ExtendedSSLSession
                val host = extractSniHost(session) ?: LudoInterceptorConfig.SOCKET_HOST
                return createAliasForHost(host)
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                return alias?.let { keyByAlias[it]?.chain } ?: keyByAlias.values.firstOrNull()?.chain
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return alias?.let { keyByAlias[it]?.privateKey } ?: keyByAlias.values.firstOrNull()?.privateKey
            }

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
                return keyByAlias.keys.toTypedArray()
            }

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: java.net.Socket?
            ): String {
                val session = (socket as? SSLSocket)?.handshakeSession as? ExtendedSSLSession
                val host = extractSniHost(session) ?: LudoInterceptorConfig.SOCKET_HOST
                return createAliasForHost(host)
            }

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = emptyArray()
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
        }
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(km), null, null)
        }
    }

    override fun run() {
        if (!running.compareAndSet(false, true)) return
        try {
            sslServerSocket = sslContext.serverSocketFactory.createServerSocket(
                LudoInterceptorConfig.LOCAL_PROXY_PORT,
                50,
                java.net.InetAddress.getByName("127.0.0.1")
            ) as? javax.net.ssl.SSLServerSocket
            if (sslServerSocket == null) throw IllegalStateException("SSLServerSocketFactory did not return SSLServerSocket")
            Log.i(TAG, "Listening on 127.0.0.1:${LudoInterceptorConfig.LOCAL_PROXY_PORT}")
            while (running.get()) {
                try {
                    val sslSocket = sslServerSocket?.accept() as? javax.net.ssl.SSLSocket ?: break
                    Thread { handleConnection(sslSocket) }.start()
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Accept error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy failed", e)
        } finally {
            running.set(false)
            sslServerSocket?.close()
            sslServerSocket = null
        }
    }

    private fun handleConnection(sslSocket: javax.net.ssl.SSLSocket) {
        try {
            sslSocket.soTimeout = 30_000
            sslSocket.startHandshake()
            val input = sslSocket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            var path = parts[1]
            var host: String? = null
            val headers = mutableMapOf<String, MutableList<String>>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line != "") {
                val l = line!!
                val colon = l.indexOf(':')
                if (colon > 0) {
                    val key = l.substring(0, colon).trim()
                    val value = l.substring(colon + 1).trim()
                    headers.getOrPut(key) { mutableListOf() }.add(value)
                    if (key.equals("Host", ignoreCase = true)) host = value
                }
            }
            if (host?.contains(':') == true) host = host.substringBefore(':')
            extractAndLogLudoAuth(host, path, headers)
            sendLudoAuthTokenIfConfigured(host, path, headers)
            extractAndLogLudoSocketHandshake(method, host, path, headers)
            val targetHost = host ?: return

            val originSocket = java.net.Socket()
            originSocket.soTimeout = 15_000
            originSocket.connect(java.net.InetSocketAddress(targetHost, 443), 10_000)
            val originSslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val originSsl = originSslFactory.createSocket(originSocket, targetHost, 443, true) as javax.net.ssl.SSLSocket
            originSsl.startHandshake()
            val originSslOut = originSsl.getOutputStream()
            val fullRequest = buildString {
                append(requestLine).append("\r\n")
                headers.forEach { (k, vs) -> vs.forEach { append("$k: $it\r\n") } }
                append("\r\n")
            }
            originSslOut.write(fullRequest.toByteArray(StandardCharsets.UTF_8))
            originSslOut.flush()
            val originIn = originSsl.getInputStream()
            val clientOut = sslSocket.getOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (originIn.read(buf).also { n = it } >= 0) {
                clientOut.write(buf, 0, n)
            }
            clientOut.flush()
            originSsl.close()
            sslSocket.close()
        } catch (e: SSLHandshakeException) {
            // Expected for some clients/flows (e.g. pinning or nonstandard handshakes).
            Log.w(TAG, "TLS handshake failed: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Connection handling error", e)
        } finally {
            try { sslSocket.close() } catch (_: Exception) {}
        }
    }

    private fun sendLudoAuthTokenIfConfigured(host: String?, path: String?, headers: Map<String, List<String>>) {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return
        val baseUrl = serverBaseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return
        if (host != LudoInterceptorConfig.TARGET_HOST) return
        val pathWithoutQuery = path?.substringBefore('?') ?: return
        if (pathWithoutQuery != LudoInterceptorConfig.TARGET_PATH) return
        val auth = headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        tokenSenderExecutor.execute {
            try {
                val url = "$baseUrl/api/token"
                val body = JSONObject().put("apiKey", key).put("authToken", auth).toString()
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(JSON))
                    .build()
                tokenHttpClient.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send auth token to server", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        sslServerSocket?.close()
        sslServerSocket = null
        tokenSenderExecutor.shutdown()
    }
}
