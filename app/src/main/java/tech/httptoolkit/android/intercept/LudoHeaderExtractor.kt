package tech.httptoolkit.android.intercept

import android.util.Log

private const val TAG = "LUDO_AUTH"

/**
 * Checks if the request is for the Ludoking profile endpoint and logs the Authorization header.
 */
fun extractAndLogLudoAuth(host: String?, path: String?, headers: Map<String, List<String>>) {
    if (host != LudoInterceptorConfig.TARGET_HOST) return
    val pathWithoutQuery = path?.substringBefore('?') ?: return
    if (pathWithoutQuery != LudoInterceptorConfig.TARGET_PATH) return
    val auth = headers["Authorization"]?.firstOrNull()
        ?: headers["authorization"]?.firstOrNull()
    Log.i(TAG, "Authorization=$auth")
}
