package tech.httptoolkit.android.intercept

import android.net.Uri
import android.util.Log

private const val AUTH_TAG = "LUDO_AUTH"
private const val SOCKET_TAG = "LUDO_SOCKET"

/**
 * Checks if the request is for the Ludoking profile endpoint and logs the Authorization header.
 */
fun extractAndLogLudoAuth(host: String?, path: String?, headers: Map<String, List<String>>) {
    if (host != LudoInterceptorConfig.TARGET_HOST) return
    val pathWithoutQuery = path?.substringBefore('?') ?: return
    if (pathWithoutQuery != LudoInterceptorConfig.TARGET_PATH) return
    val auth = firstHeaderValue(headers, "Authorization")
    Log.i(AUTH_TAG, "Authorization=$auth")
}

/**
 * Logs WebSocket handshake details for Ludo King socket requests.
 * Header Upgrade can be absent, so we do not require it.
 */
fun extractAndLogLudoSocketHandshake(
    method: String?,
    host: String?,
    path: String?,
    headers: Map<String, List<String>>
) {
    if (!method.equals("GET", ignoreCase = true)) return
    if (host != LudoInterceptorConfig.SOCKET_HOST) return
    val requestPath = path ?: return
    val pathWithoutQuery = requestPath.substringBefore('?')
    if (!pathWithoutQuery.startsWith(LudoInterceptorConfig.SOCKET_PATH_PREFIX)) return

    val fullUrl = buildHttpsUrl(host, requestPath)
    val token = Uri.parse(fullUrl).getQueryParameter("t")

    // Optional header check: useful for debugging handshake shape without enforcing it.
    val upgradeHeader = firstHeaderValue(headers, "Upgrade")
    if (upgradeHeader == null || upgradeHeader.equals("websocket", ignoreCase = true)) {
        Log.i(SOCKET_TAG, "URL=$fullUrl")
        Log.i(SOCKET_TAG, "TOKEN=$token")
    }
}

private fun firstHeaderValue(headers: Map<String, List<String>>, headerName: String): String? {
    return headers.entries.firstOrNull { it.key.equals(headerName, ignoreCase = true) }?.value?.firstOrNull()
}

private fun buildHttpsUrl(host: String, path: String): String {
    return if (path.startsWith("http://") || path.startsWith("https://")) {
        path
    } else {
        "https://$host$path"
    }
}
