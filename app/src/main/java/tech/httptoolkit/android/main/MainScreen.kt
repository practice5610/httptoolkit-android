package tech.httptoolkit.android.main

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.httptoolkit.android.ProxyConfig
import tech.httptoolkit.android.R
import tech.httptoolkit.android.main.ConnectionState.*
import tech.httptoolkit.android.ui.AppConstants
import tech.httptoolkit.android.ui.DmSansFontFamily

data class MainScreenState(
    val connectionState: ConnectionState,
    val proxyConfig: ProxyConfig?,
    val lastProxy: ProxyConfig?
)

data class MainScreenActions(
    val onConnect: () -> Unit,
    val onReconnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onRecoverAfterFailure: () -> Unit
)

@Composable
fun MainScreen(
    screenState: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        LandscapeMainScreen(screenState = screenState, actions = actions, modifier = modifier)
    } else {
        PortraitMainScreen(screenState = screenState, actions = actions, modifier = modifier)
    }
}

@Composable
private fun PortraitMainScreen(
    screenState: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppConstants.spacingNormal)
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(
                when (screenState.connectionState) {
                    DISCONNECTED -> R.string.disconnected_status
                    CONNECTING -> R.string.connecting_status
                    CONNECTED -> R.string.connected_status
                    DISCONNECTING -> R.string.disconnecting_status
                    FAILED -> R.string.failed_status
                }
            ),
            fontSize = AppConstants.textSizeHeading,
            fontFamily = DmSansFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        when (screenState.connectionState) {
            DISCONNECTED -> Text(
                text = stringResource(R.string.disconnected_details_ludo),
                fontSize = AppConstants.textSizeBodyLarge,
                fontFamily = DmSansFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            CONNECTED -> Text(
                text = stringResource(R.string.connected_details_ludo),
                fontSize = AppConstants.textSizeBodyLarge,
                fontFamily = DmSansFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            FAILED -> Text(
                text = stringResource(R.string.failed_details),
                fontSize = AppConstants.textSizeBodyLarge,
                fontFamily = DmSansFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            else -> {}
        }
        Spacer(modifier = Modifier.weight(1f))
        if (screenState.connectionState != CONNECTING && screenState.connectionState != DISCONNECTING) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = AppConstants.spacingLarge, topEnd = AppConstants.spacingLarge),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppConstants.spacingMedium),
                    verticalArrangement = Arrangement.spacedBy(AppConstants.spacingSmall)
                ) {
                    when (screenState.connectionState) {
                        DISCONNECTED -> {
                            Button(
                                onClick = actions.onConnect,
                                modifier = Modifier.fillMaxWidth().height(AppConstants.buttonHeight),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(stringResource(R.string.connect_button), fontFamily = DmSansFontFamily, fontWeight = FontWeight.Bold)
                            }
                            if (screenState.lastProxy != null) {
                                OutlinedButton(
                                    onClick = actions.onReconnect,
                                    modifier = Modifier.fillMaxWidth().height(AppConstants.buttonHeight)
                                ) {
                                    Text(stringResource(R.string.reconnect_button), fontFamily = DmSansFontFamily)
                                }
                            }
                        }
                        CONNECTED -> Button(
                            onClick = actions.onDisconnect,
                            modifier = Modifier.fillMaxWidth().height(AppConstants.buttonHeight),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(stringResource(R.string.disconnect_button), fontFamily = DmSansFontFamily, fontWeight = FontWeight.Bold)
                        }
                        FAILED -> Button(
                            onClick = actions.onRecoverAfterFailure,
                            modifier = Modifier.fillMaxWidth().height(AppConstants.buttonHeight),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(stringResource(R.string.try_again_button), fontFamily = DmSansFontFamily, fontWeight = FontWeight.Bold)
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeMainScreen(
    screenState: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(AppConstants.spacingLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(
                    when (screenState.connectionState) {
                        DISCONNECTED -> R.string.disconnected_status
                        CONNECTING -> R.string.connecting_status
                        CONNECTED -> R.string.connected_status
                        DISCONNECTING -> R.string.disconnecting_status
                        FAILED -> R.string.failed_status
                    }
                ),
                fontSize = AppConstants.textSizeHeading,
                fontFamily = DmSansFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            when (screenState.connectionState) {
                DISCONNECTED -> Text(stringResource(R.string.disconnected_details_ludo), fontFamily = DmSansFontFamily, color = MaterialTheme.colorScheme.onBackground)
                CONNECTED -> Text(stringResource(R.string.connected_details_ludo), fontFamily = DmSansFontFamily, color = MaterialTheme.colorScheme.onBackground)
                FAILED -> Text(stringResource(R.string.failed_details), fontFamily = DmSansFontFamily, color = MaterialTheme.colorScheme.onBackground)
                else -> {}
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(AppConstants.spacingLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (screenState.connectionState != CONNECTING && screenState.connectionState != DISCONNECTING) {
                when (screenState.connectionState) {
                    DISCONNECTED -> {
                        Button(onClick = actions.onConnect, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.connect_button), fontFamily = DmSansFontFamily)
                        }
                        if (screenState.lastProxy != null) {
                            OutlinedButton(onClick = actions.onReconnect, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.reconnect_button), fontFamily = DmSansFontFamily)
                            }
                        }
                    }
                    CONNECTED -> Button(onClick = actions.onDisconnect, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.disconnect_button), fontFamily = DmSansFontFamily)
                    }
                    FAILED -> Button(onClick = actions.onRecoverAfterFailure, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.try_again_button), fontFamily = DmSansFontFamily)
                    }
                    else -> {}
                }
            }
        }
    }
}
