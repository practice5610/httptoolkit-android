package tech.httptoolkit.android.intercept

import android.util.Log
import tech.httptoolkit.android.ca.GeneratedCa
import tech.httptoolkit.android.ca.issueServerCert
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIServerName
import javax.net.ssl.X509ExtendedKeyManager
import java.security.PrivateKey
import java.security.cert.X509Certificate

private const val TAG = "LocalMitmProxy"

/**
 * Local MITM proxy: listens on 127.0.0.1:LOCAL_PROXY_PORT, terminates TLS with dynamically
 * generated certs, parses HTTP to extract Ludoking Authorization header, then forwards to origin.
 */
class LocalMitmProxy(private val ca: GeneratedCa) : Runnable {

    private val running = AtomicBoolean(false)
    private var sslServerSocket: javax.net.ssl.SSLServerSocket? = null

    private val sslContext: SSLContext by lazy {
        val km = object : X509ExtendedKeyManager() {
            private val threadLocalCert = ThreadLocal<Pair<PrivateKey, Array<X509Certificate>>>()

            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?): String? {
                if (engine == null) return null
                val session = engine.handshakeSession
                if (session !is ExtendedSSLSession) return null
                val names = session.requestedServerNames ?: return null
                val sni = names.find { it.type == 0 } as? SNIServerName ?: return null
                val hostname = String(sni.encoded, StandardCharsets.UTF_8)
                val (privateKey, cert) = issueServerCert(ca, hostname)
                val chain = arrayOf(cert, ca.certificate)
                threadLocalCert.set(privateKey to chain)
                return hostname
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                return threadLocalCert.get()?.second
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return threadLocalCert.get()?.first
            }

            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> = emptyArray()
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> = emptyArray()
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
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
            val targetHost = host ?: return

            val originSocket = java.net.Socket()
            originSocket.soTimeout = 15_000
            originSocket.connect(java.net.InetSocketAddress(targetHost, 443), 10_000)
            val originSsl = javax.net.ssl.SSLSocketFactory.getDefault().createSocket(originSocket, targetHost, 443, true) as javax.net.ssl.SSLSocket
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
        } catch (e: Exception) {
            Log.w(TAG, "Connection handling error", e)
        } finally {
            try { sslSocket.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        running.set(false)
        sslServerSocket?.close()
        sslServerSocket = null
    }
}
