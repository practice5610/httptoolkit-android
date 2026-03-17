package tech.httptoolkit.android.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tech.httptoolkit.android.ui.AppConstants
import tech.httptoolkit.android.ui.DmSansFontFamily

@Composable
fun ApiKeyInputScreen(
    onValidateApiKey: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    var apiKey by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppConstants.spacingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ludoking VPN",
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = DmSansFontFamily,
            modifier = Modifier.padding(bottom = AppConstants.spacingMedium)
        )

        Text(
            text = "Enter your API key to continue",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = DmSansFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = AppConstants.spacingLarge)
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text("Paste your API key here") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    if (apiKey.isNotBlank()) {
                        onValidateApiKey(apiKey)
                    }
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AppConstants.spacingSmall),
            enabled = !isLoading
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AppConstants.spacingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showPassword,
                onCheckedChange = { showPassword = it }
            )
            Text(
                text = "Show API key",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = AppConstants.spacingSmall)
            )
        }

        if (errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppConstants.spacingMedium),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(AppConstants.spacingSmall)
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(AppConstants.spacingMedium)
                )
            }
        }

        Button(
            onClick = {
                keyboardController?.hide()
                if (apiKey.isNotBlank()) {
                    onValidateApiKey(apiKey)
                }
            },
            enabled = apiKey.isNotBlank() && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(AppConstants.buttonHeight)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(AppConstants.spacingSmall))
                Text("Validating...")
            } else {
                Text("Validate & Continue")
            }
        }
    }
}
