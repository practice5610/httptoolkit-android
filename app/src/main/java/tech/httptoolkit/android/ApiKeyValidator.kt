package tech.httptoolkit.android

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val TAG = formatTag("tech.httptoolkit.android.ApiKeyValidator")

data class ApiKeyValidationResult(
    val isValid: Boolean,
    val message: String? = null
)

suspend fun validateApiKey(apiKey: String, backendUrl: String): ApiKeyValidationResult {
    return withContext(Dispatchers.IO) {
        try {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val requestBody = JSONObject().apply {
                put("apiKey", apiKey)
            }.toString().toRequestBody("application/json".toMediaType())

            val url = "$backendUrl/api/v1/api-keys/validate"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            Log.i(TAG, "Validating API key with backend: $url")

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.i(TAG, "API key validation response: ${response.code} - $responseBody")

            if (response.code == 200) {
                val jsonResponse = JSONObject(responseBody)
                val valid = jsonResponse.optBoolean("valid", false)
                val message = jsonResponse.optString("message", null)

                if (valid) {
                    ApiKeyValidationResult(true, message ?: "API key is valid")
                } else {
                    ApiKeyValidationResult(false, message ?: "Invalid API key")
                }
            } else {
                ApiKeyValidationResult(false, "Server error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating API key", e)
            ApiKeyValidationResult(false, "Network error: ${e.message}")
        }
    }
}
