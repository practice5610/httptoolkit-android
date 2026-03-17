package tech.httptoolkit.android

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "LudokingVPN"

/**
 * Intercepts and processes Bearer tokens extracted from HTTPS requests
 */
class TokenInterceptor(
    private val tokenService: TokenService
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Handle extracted token
     */
    fun onTokenExtracted(token: String, sourceUrl: String = Constants.LUDOKING_PROFILE_API_URL) {
        Log.i(TAG, "[INTERCEPTOR] Token intercepted from: $sourceUrl")
        Log.i(TAG, "[INTERCEPTOR] Token length: ${token.length}")
        
        scope.launch {
            try {
                Log.i(TAG, "[INTERCEPTOR] Calling tokenService.saveToken()")
                tokenService.saveToken(token, sourceUrl)
            } catch (e: Exception) {
                Log.e(TAG, "[INTERCEPTOR] Error saving token", e)
            }
        }
    }
}
