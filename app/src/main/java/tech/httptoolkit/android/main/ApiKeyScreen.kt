package tech.httptoolkit.android.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tech.httptoolkit.android.R
import tech.httptoolkit.android.intercept.LudoInterceptorConfig
import tech.httptoolkit.android.ui.AppConstants
import tech.httptoolkit.android.ui.DmSansFontFamily
import java.util.concurrent.TimeUnit

private val JSON = "application/json; charset=utf-8".toMediaType()
private val okHttp = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()

/**
 * Result of calling the verify API.
 */
sealed class VerifyResult {
    object Success : VerifyResult()
    data class Error(val message: String) : VerifyResult()
}

fun verifyApiKey(baseUrl: String, apiKey: String): VerifyResult {
    val url = baseUrl.trimEnd('/') + "/api/verify"
    val body = JSONObject().put("apiKey", apiKey).toString()
    return try {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .build()
        val response = okHttp.newCall(request).execute()
        when (response.code) {
            200 -> VerifyResult.Success
            401 -> VerifyResult.Error("invalid")
            else -> VerifyResult.Error("network")
        }
    } catch (e: Exception) {
        VerifyResult.Error("network")
    }
}

@Composable
fun ApiKeyScreen(
    defaultServerBaseUrl: String,
    onVerified: (apiKey: String, serverBaseUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeyInput by remember { mutableStateOf("") }
    var serverUrlInput by remember { mutableStateOf(defaultServerBaseUrl) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorInvalid = stringResource(R.string.api_key_error_invalid)
    val errorNetwork = stringResource(R.string.api_key_error_network)
    val submitLabel = stringResource(R.string.api_key_submit)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppConstants.spacingNormal)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.api_key_screen_title),
            fontSize = AppConstants.textSizeHeading,
            fontFamily = DmSansFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.api_key_screen_details),
            fontSize = AppConstants.textSizeBodyLarge,
            fontFamily = DmSansFontFamily,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it; errorMessage = null },
            label = { Text(stringResource(R.string.api_key_hint), fontFamily = DmSansFontFamily) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppConstants.spacingSmall),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(AppConstants.spacingMedium))

        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = { serverUrlInput = it; errorMessage = null },
            label = { Text(stringResource(R.string.server_url_hint), fontFamily = DmSansFontFamily) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppConstants.spacingSmall),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(AppConstants.spacingSmall))
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                fontFamily = DmSansFontFamily,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (loading) return@Button
                val key = apiKeyInput.trim()
                val baseUrl = serverUrlInput.trim().ifBlank { defaultServerBaseUrl }
                if (key.isBlank()) {
                    errorMessage = errorInvalid
                    return@Button
                }
                errorMessage = null
                loading = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { verifyApiKey(baseUrl, key) }
                    when (result) {
                        is VerifyResult.Success -> onVerified(key, baseUrl)
                        is VerifyResult.Error -> {
                            errorMessage = when (result.message) {
                                "invalid" -> errorInvalid
                                else -> errorNetwork
                            }
                        }
                    }
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(AppConstants.buttonHeight),
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                if (loading) "…" else submitLabel,
                fontFamily = DmSansFontFamily,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
