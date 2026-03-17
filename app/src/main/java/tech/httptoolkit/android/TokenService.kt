package tech.httptoolkit.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "LudokingVPN"

/**
 * Service for saving tokens to backend API
 */
class TokenService(private val context: Context) {
    
    private var onTokenSaved: (() -> Unit)? = null

    fun setOnTokenSavedCallback(callback: () -> Unit) {
        this.onTokenSaved = callback
    }

    suspend fun saveToken(token: String, sourceUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as HttpToolkitApplication
                val apiKey = app.apiKey
                val backendUrl = app.backendUrl

                if (apiKey == null) {
                    Log.e(TAG, "API key not found, cannot save token")
                    return@withContext false
                }

                val httpClient = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val requestBody = JSONObject().apply {
                    put("token", token)
                    put("sourceUrl", sourceUrl)
                    put("metadata", JSONObject().apply {
                        put("capturedAt", System.currentTimeMillis())
                    })
                }.toString().toRequestBody("application/json".toMediaType())

                val url = "$backendUrl/api/v1/tokens/save"
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("X-API-KEY", apiKey)
                    .build()

                Log.i(TAG, "[SERVICE] Saving token to backend: $url")
                Log.d(TAG, "[SERVICE] API Key: ${apiKey.take(10)}...")

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.i(TAG, "[SERVICE] Token save response: ${response.code}")
                Log.d(TAG, "[SERVICE] Response body: $responseBody")

                if (response.code == 201 || response.code == 200) {
                    val jsonResponse = JSONObject(responseBody)
                    val success = jsonResponse.optBoolean("success", false)
                    
                    if (success) {
                        Log.i(TAG, "Token saved successfully")
                        onTokenSaved?.invoke()
                        return@withContext true
                    } else {
                        Log.e(TAG, "Token save failed: ${jsonResponse.optString("message", "Unknown error")}")
                        return@withContext false
                    }
                } else {
                    Log.e(TAG, "Token save failed with status: ${response.code}")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving token", e)
                return@withContext false
            }
        }
    }
}
