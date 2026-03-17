package tech.httptoolkit.android

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

// Use a simple, filterable tag for logcat
private const val TAG = "LudokingVPN"

/**
 * Local HTTPS proxy server that intercepts and decrypts HTTPS traffic
 */
class LocalProxyServer(
    private val port: Int,
    private val caCertificate: X509Certificate,
    private val caPrivateKey: PrivateKey,
    private val onTokenExtracted: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fakeCertGenerator: FakeCertificateGenerator

    init {
        // Initialize fake certificate generator
        fakeCertGenerator = FakeCertificateGenerator(caCertificate, caPrivateKey)
    }

    /**
     * Start the proxy server
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Proxy server is already running")
            return
        }

        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            Log.i(TAG, "[PROXY] *** Local proxy server started on port $port ***")

            scope.launch {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            scope.launch {
                                handleClient(clientSocket)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server", e)
            isRunning = false
            throw e
        }
    }

    /**
     * Stop the proxy server
     */
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            scope.cancel()
            Log.i(TAG, "Local proxy server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy server", e)
        }
    }

    /**
     * Handle a client connection
     */
    private suspend fun handleClient(clientSocket: Socket) {
        try {
            val inputStream = clientSocket.getInputStream()
            val outputStream = clientSocket.getOutputStream()
            
            // Read CONNECT request without buffering (to avoid consuming SSL handshake data)
            val requestLine = readLineUnbuffered(inputStream) ?: return
            Log.i(TAG, "[PROXY] Received CONNECT request: $requestLine")

            if (!requestLine.startsWith("CONNECT")) {
                // Not a CONNECT request, forward as HTTP
                val reader = BufferedReader(InputStreamReader(inputStream))
                val writer = PrintWriter(outputStream, true)
                handleHttpRequest(clientSocket, reader, writer, requestLine)
                return
            }

            // Parse CONNECT request: CONNECT host:port HTTP/1.1
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                val response = "HTTP/1.1 400 Bad Request\r\n\r\n"
                outputStream.write(response.toByteArray())
                outputStream.flush()
                clientSocket.close()
                return
            }

            val target = parts[1]
            val (host, targetPort) = parseHostPort(target)
            
            Log.i(TAG, "[PROXY] Connecting to: $host:$targetPort")
            
            // Skip MITM for Unity ads and other problematic domains
            // These domains often have certificate pinning or cause SSL issues
            val skipMitmDomains = listOf(
                "unityads.unity3d.com",
                "unity3d.com",
                "ads.unity3d.com",
                "analytics.unity3d.com",
                "config.unityads.unity3d.com",
                "webview.unityads.unity3d.com"
            )
            
            val shouldSkipMitm = skipMitmDomains.any { host.contains(it, ignoreCase = true) }
            
            if (shouldSkipMitm) {
                Log.i(TAG, "[PROXY] Skipping MITM for Unity ads domain: $host - forwarding directly")
                // Forward CONNECT request directly without MITM
                forwardConnectDirectly(clientSocket, host, targetPort, inputStream, outputStream)
                return
            }

            // Read remaining CONNECT request headers (important!)
            var line: String?
            while (true) {
                line = readLineUnbuffered(inputStream)
                if (line == null || line.isEmpty()) {
                    break // End of headers
                }
                Log.d(TAG, "[PROXY] CONNECT header: $line")
            }

            // Send 200 Connection Established response
            val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
            outputStream.write(response.toByteArray())
            outputStream.flush()
            
            Log.i(TAG, "[PROXY] Sent 200 Connection Established, starting SSL handshake")

            // Now we need to act as an SSL server
            // Generate fake certificate for this host
            val (fakeCert, fakeKeyPair) = fakeCertGenerator.generateFakeCertificate(host)
            
            // Create SSL context with the fake certificate
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry("fake", fakeKeyPair.private, "".toCharArray(), arrayOf(fakeCert))
            
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, "".toCharArray())
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())
            
            // Wrap the existing socket as an SSL server socket
            // Use createSocket with host=null, port=-1, autoClose=false to wrap existing socket
            val sslSocketFactory = sslContext.socketFactory
            val sslSocket = sslSocketFactory.createSocket(
                clientSocket,
                null, // hostname - not needed when wrapping
                -1,   // port - not needed when wrapping
                false // autoClose - don't close underlying socket
            ) as SSLSocket
            
            // CRITICAL: Set as server mode (not client mode)
            sslSocket.useClientMode = false
            
            // Set the hostname for SNI (Server Name Indication) - only for hostnames, not IPs
            // This is important for the client to verify the certificate matches the hostname
            if (!host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                try {
                    val sslParameters = sslSocket.sslParameters
                    val sniHostName = javax.net.ssl.SNIHostName(host)
                    sslParameters.serverNames = listOf(sniHostName)
                    sslSocket.sslParameters = sslParameters
                    Log.d(TAG, "[PROXY] Set SNI hostname: $host")
                } catch (e: Exception) {
                    Log.w(TAG, "[PROXY] Failed to set SNI hostname, continuing anyway", e)
                }
            } else {
                Log.d(TAG, "[PROXY] Skipping SNI for IP address: $host")
            }
            
            // Enable all cipher suites for compatibility
            sslSocket.enabledCipherSuites = sslSocket.supportedCipherSuites
            
            // Enable all protocols
            sslSocket.enabledProtocols = sslSocket.supportedProtocols
            
            Log.i(TAG, "[PROXY] Starting SSL handshake with client for $host")
            try {
                sslSocket.startHandshake()
                Log.i(TAG, "[PROXY] SSL handshake completed successfully for $host")
            } catch (e: Exception) {
                Log.e(TAG, "[PROXY] SSL handshake failed for $host", e)
                // Don't throw - just log and close connection
                try {
                    clientSocket.close()
                } catch (closeEx: Exception) {
                    // Ignore
                }
                return
            }

            // Now handle HTTPS traffic
            handleHttpsConnection(sslSocket, host, targetPort)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Handle HTTPS connection after SSL handshake
     */
    private suspend fun handleHttpsConnection(
        sslSocket: SSLSocket,
        targetHost: String,
        targetPort: Int
    ) {
        try {
            val input = sslSocket.getInputStream()
            val output = sslSocket.getOutputStream()

            // Read HTTP request from client
            // Read in chunks to handle large requests
            val requestBuffer = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var totalBytesRead = 0
            var bytesRead: Int
            
            // Read until we have the full request headers (ends with \r\n\r\n)
            while (true) {
                bytesRead = input.read(buffer)
                if (bytesRead <= 0) break
                requestBuffer.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                // Check if we've received the end of headers
                val currentData = requestBuffer.toByteArray()
                val dataString = String(currentData)
                if (dataString.contains("\r\n\r\n") || dataString.contains("\n\n")) {
                    break
                }
                
                // Safety limit to prevent memory issues
                if (totalBytesRead > 65536) break // 64KB limit
            }
            
            if (totalBytesRead <= 0) {
                Log.w(TAG, "[PROXY] No data received from client")
                return
            }
            
            val requestData = requestBuffer.toByteArray()
            val requestString = String(requestData)
            
            // Parse request line to get method and path
            val requestLines = requestString.split("\r\n", "\n")
            val requestLine = requestLines.firstOrNull() ?: ""
            val methodAndPath = requestLine.split(" ")
            val method = methodAndPath.getOrNull(0) ?: ""
            val path = methodAndPath.getOrNull(1) ?: ""
            
            Log.i(TAG, "[PROXY] Request: $method $path to $targetHost:$targetPort")
            Log.d(TAG, "[PROXY] Request preview: ${requestString.take(300)}...")

            // Check if this is the Ludoking profile API request
            val isLudokingHost = targetHost == "misc-services.ludokingapi.com" || 
                                 targetHost.contains("ludokingapi.com")
            val isProfilePath = path.contains("/api/v3/player/profile") || 
                               requestString.contains("/api/v3/player/profile")
            
            if (isLudokingHost && isProfilePath) {
                Log.i(TAG, "[PROXY] *** LUDOKING PROFILE API REQUEST DETECTED ***")
                Log.i(TAG, "[PROXY] Method: $method, Path: $path")
                // Extract Bearer token
                extractBearerToken(requestString)
            } else if (isLudokingHost) {
                Log.d(TAG, "[PROXY] Ludoking request but not profile API - Method: $method, Path: $path")
            }

            // Forward request to real server
            val realServerSocket = Socket(targetHost, targetPort)
            val realOutput = realServerSocket.getOutputStream()
            realOutput.write(requestData)
            realOutput.flush()

            // Forward response back to client
            scope.launch {
                try {
                    val realInput = realServerSocket.getInputStream()
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (realInput.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                        output.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error forwarding response", e)
                } finally {
                    realServerSocket.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling HTTPS connection", e)
        }
    }

    /**
     * Handle plain HTTP request (non-CONNECT)
     */
    private suspend fun handleHttpRequest(
        clientSocket: Socket,
        reader: BufferedReader,
        writer: PrintWriter,
        requestLine: String
    ) {
        // For now, just close - we mainly handle HTTPS
        writer.println("HTTP/1.1 501 Not Implemented\r\n\r\n")
        clientSocket.close()
    }

    /**
     * Extract Bearer token from HTTP request
     */
    private fun extractBearerToken(requestString: String) {
        try {
            Log.i(TAG, "[TOKEN] Attempting to extract token from request")
            val lines = requestString.split("\r\n", "\n")
            Log.d(TAG, "[TOKEN] Request has ${lines.size} lines")
            
            var foundAuth = false
            for (line in lines) {
                if (line.startsWith("authorization:", ignoreCase = true) ||
                    line.startsWith("Authorization:", ignoreCase = true)) {
                    foundAuth = true
                    Log.i(TAG, "[TOKEN] Found Authorization header: ${line.take(50)}...")
                    val token = line.substringAfter("Bearer ", "").trim()
                    if (token.isNotEmpty()) {
                        Log.i(TAG, "[TOKEN] *** TOKEN EXTRACTED: ${token.take(30)}... ***")
                        onTokenExtracted(token)
                        break
                    } else {
                        Log.w(TAG, "[TOKEN] Authorization header found but no Bearer token")
                    }
                }
            }
            if (!foundAuth) {
                Log.w(TAG, "[TOKEN] No Authorization header found in request")
                Log.d(TAG, "[TOKEN] Request headers: ${lines.take(10).joinToString("\n")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[TOKEN] Error extracting token", e)
        }
    }

    /**
     * Read a line from InputStream without buffering (to avoid consuming SSL handshake data)
     */
    private fun readLineUnbuffered(inputStream: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = inputStream.read()
            if (byte == -1) {
                return if (line.isEmpty()) null else line.toString()
            }
            if (byte == '\n'.code) {
                // Remove \r if present
                if (line.isNotEmpty() && line.last() == '\r') {
                    line.setLength(line.length - 1)
                }
                return line.toString()
            }
            line.append(byte.toChar())
        }
    }

    /**
     * Forward CONNECT request directly without MITM (for Unity ads, etc.)
     */
    private suspend fun forwardConnectDirectly(
        clientSocket: Socket,
        targetHost: String,
        targetPort: Int,
        inputStream: InputStream,
        outputStream: OutputStream
    ) {
        try {
            // Read remaining CONNECT headers
            var line: String?
            while (true) {
                line = readLineUnbuffered(inputStream)
                if (line == null || line.isEmpty()) break
            }
            
            // Connect to real server
            val realServerSocket = Socket(targetHost, targetPort)
            
            // Send 200 Connection Established
            val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
            outputStream.write(response.toByteArray())
            outputStream.flush()
            
            // Forward data bidirectionally
            scope.launch {
                try {
                    val realInput = realServerSocket.getInputStream()
                    val clientInput = clientSocket.getInputStream()
                    val realOutput = realServerSocket.getOutputStream()
                    
                    // Client -> Server
                    launch {
                        try {
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (clientInput.read(buffer).also { len = it } != -1) {
                                realOutput.write(buffer, 0, len)
                                realOutput.flush()
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "[PROXY] Client->Server forwarding ended", e)
                        } finally {
                            realServerSocket.shutdownOutput()
                        }
                    }
                    
                    // Server -> Client
                    try {
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (realInput.read(buffer).also { len = it } != -1) {
                            outputStream.write(buffer, 0, len)
                            outputStream.flush()
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "[PROXY] Server->Client forwarding ended", e)
                    } finally {
                        realServerSocket.close()
                        clientSocket.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[PROXY] Error in direct forwarding", e)
                    try {
                        realServerSocket.close()
                        clientSocket.close()
                    } catch (closeEx: Exception) {
                        // Ignore
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[PROXY] Error forwarding directly", e)
            try {
                clientSocket.close()
            } catch (closeEx: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Parse host:port from target string
     */
    private fun parseHostPort(target: String): Pair<String, Int> {
        val parts = target.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 443
        return Pair(host, port)
    }

}
