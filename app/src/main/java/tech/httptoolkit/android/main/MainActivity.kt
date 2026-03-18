package tech.httptoolkit.android.main

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import tech.httptoolkit.android.*
import tech.httptoolkit.android.ca.INSTALL_CERT_REQUEST
import tech.httptoolkit.android.ca.generateOrLoad
import tech.httptoolkit.android.ca.launchInstallCaIntent
import tech.httptoolkit.android.ca.saveCertToDownloads
import tech.httptoolkit.android.ca.whereIsCertTrusted
import tech.httptoolkit.android.intercept.LudoInterceptorConfig
import tech.httptoolkit.android.ui.HttpToolkitTheme

const val START_VPN_REQUEST = 123
const val ENABLE_NOTIFICATIONS_REQUEST = 101

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
}

private val PROMPTED_CERT_SETUP_SUPPORTED = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

class MainActivity : ComponentActivity(), CoroutineScope by MainScope() {

    private lateinit var app: HttpToolkitApplication
    private var localBroadcastManager: LocalBroadcastManager? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                VPN_STARTED_BROADCAST -> {
                    mainState = ConnectionState.CONNECTED
                    currentProxyConfig = intent.getParcelableExtra(IntentExtras.PROXY_CONFIG_EXTRA)
                    launchLudoKing()
                }
                VPN_STOPPED_BROADCAST -> {
                    mainState = ConnectionState.DISCONNECTED
                    currentProxyConfig = null
                }
            }
        }
    }

    private var mainState: ConnectionState by mutableStateOf(if (isVpnActive()) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
    private var currentProxyConfig: ProxyConfig? by mutableStateOf(activeVpnConfig())
    private var lastPauseTime = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(VPN_STARTED_BROADCAST)
            addAction(VPN_STOPPED_BROADCAST)
        })
        app = application as HttpToolkitApplication

        setContent {
            HttpToolkitTheme {
                MainScreen(
                    screenState = MainScreenState(
                        connectionState = mainState,
                        proxyConfig = currentProxyConfig,
                        lastProxy = app.lastProxy
                    ),
                    actions = MainScreenActions(
                        onConnect = { connect() },
                        onReconnect = { reconnect() },
                        onDisconnect = { disconnect() },
                        onRecoverAfterFailure = { recoverAfterFailure() }
                    )
                )
            }
        }

        if (app.popVpnKilledState()) {
            val batteryOptimizationsDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (getSystemService(this@MainActivity, PowerManager::class.java))?.isIgnoringBatteryOptimizations(packageName) == true
            } else false
            if (!batteryOptimizationsDisabled) showVpnKilledAlert()
        }
    }

    override fun onPause() {
        super.onPause()
        lastPauseTime = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager?.unregisterReceiver(broadcastReceiver)
    }

    private fun connect() {
        if (mainState != ConnectionState.DISCONNECTED && mainState != ConnectionState.FAILED) return
        mainState = ConnectionState.CONNECTING
        launch {
            withContext(Dispatchers.IO) {
                try {
                    val ca = generateOrLoad(this@MainActivity)
                    val config = ProxyConfig(
                        "127.0.0.1",
                        LudoInterceptorConfig.LOCAL_PROXY_PORT,
                        ca.certificate
                    )
                    withContext(Dispatchers.Main) {
                        currentProxyConfig = config
                        connectToVpn(config)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connect failed", e)
                    mainState = ConnectionState.FAILED
                }
            }
        }
    }

    private fun reconnect() {
        val last = app.lastProxy ?: return
        mainState = ConnectionState.CONNECTING
        currentProxyConfig = last
        connectToVpn(last)
    }

    private fun disconnect() {
        currentProxyConfig = null
        mainState = ConnectionState.DISCONNECTING
        startService(Intent(this, ProxyVpnService::class.java).apply { action = STOP_VPN_ACTION })
    }

    private fun recoverAfterFailure() {
        currentProxyConfig = null
        mainState = ConnectionState.DISCONNECTED
    }

    private fun connectToVpn(config: ProxyConfig) {
        mainState = ConnectionState.CONNECTING
        currentProxyConfig = config
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, START_VPN_REQUEST)
        } else {
            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
        }
    }

    private fun startVpn() {
        val config = currentProxyConfig ?: return
        mainState = ConnectionState.CONNECTING
        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = START_VPN_ACTION
            putExtra(IntentExtras.PROXY_CONFIG_EXTRA, config)
            putExtra(IntentExtras.INTERCEPTED_PORTS_EXTRA, intArrayOf(443))
        })
    }

    private fun launchLudoKing() {
        val launchIntent = packageManager.getLaunchIntentForPackage(LudoInterceptorConfig.TARGET_PACKAGE)
        if (launchIntent == null) {
            Log.w(TAG, "Target package not installed: ${LudoInterceptorConfig.TARGET_PACKAGE}")
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target package", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val config = currentProxyConfig
        val resultOk = resultCode == RESULT_OK ||
            (requestCode == INSTALL_CERT_REQUEST && config != null && whereIsCertTrusted(config.certificate) != null) ||
            (requestCode == ENABLE_NOTIFICATIONS_REQUEST && areNotificationsEnabled())

        if (resultOk) {
            when (requestCode) {
                START_VPN_REQUEST -> if (config != null) ensureCertificateTrusted(config)
                INSTALL_CERT_REQUEST -> ensureNotificationsEnabled()
                ENABLE_NOTIFICATIONS_REQUEST -> startVpn()
            }
        } else if (requestCode == START_VPN_REQUEST && System.currentTimeMillis() - lastPauseTime < 200 && resultCode == RESULT_CANCELED) {
            showActiveVpnFailureAlert()
            mainState = ConnectionState.DISCONNECTED
        } else if (requestCode == INSTALL_CERT_REQUEST && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && config != null) {
            launch { promptToManuallyInstallCert(config.certificate, repeatPrompt = true) }
        } else if (requestCode == ENABLE_NOTIFICATIONS_REQUEST) {
            requestNotificationPermission(true)
        } else if (requestCode in listOf(START_VPN_REQUEST, INSTALL_CERT_REQUEST)) {
            mainState = ConnectionState.FAILED
        }
    }

    private fun ensureCertificateTrusted(proxyConfig: ProxyConfig) {
        val cert = proxyConfig.certificate
        if (whereIsCertTrusted(cert) != null) {
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
            return
        }
        if (PROMPTED_CERT_SETUP_SUPPORTED) {
            launch { promptToAutoInstallCert(cert) }
        } else {
            launch { promptToManuallyInstallCert(cert) }
        }
    }

    private suspend fun promptToAutoInstallCert(certificate: Certificate) {
        withContext(Dispatchers.Main) {
            val keyguard = getSystemService(this@MainActivity, KeyguardManager::class.java)
            val deviceSecured = keyguard?.isDeviceSecure == true
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Install CA certificate")
                .setIcon(R.drawable.ic_info_circle)
                .setMessage(
                    "To intercept HTTPS for Ludo King, install the interceptor CA. " +
                        (if (!deviceSecured) "A device PIN/pattern may be required." else "Your device PIN may be required.")
                )
                .setPositiveButton("Install") { _, _ ->
                    launchInstallCaIntent(this@MainActivity, certificate as X509Certificate)
                }
                .setNeutralButton("Skip") { _, _ -> onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null) }
                .setNegativeButton("Cancel") { _, _ -> disconnect() }
                .setCancelable(false)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun promptToManuallyInstallCert(cert: Certificate, repeatPrompt: Boolean = false) {
        if (!repeatPrompt) {
            saveCertToDownloads(this, cert as X509Certificate)
        }
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Install CA manually")
                .setIcon(R.drawable.ic_exclamation_triangle)
                .setMessage(
                    Html.fromHtml(
                        "Open <b>Encryption &amp; credentials</b> in Security settings, then " +
                            "<b>Install a certificate</b> → <b>CA certificate</b>, and select the Ludoking CA from Downloads.",
                        Html.FROM_HTML_MODE_LEGACY
                    )
                )
                .setPositiveButton("Open settings") { _, _ ->
                    startActivityForResult(Intent(Settings.ACTION_SECURITY_SETTINGS), INSTALL_CERT_REQUEST)
                }
                .setNeutralButton("Skip") { _, _ -> onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null) }
                .setNegativeButton("Cancel") { _, _ -> disconnect() }
                .setCancelable(false)
                .show()
        }
    }

    private fun ensureNotificationsEnabled() {
        if (areNotificationsEnabled()) {
            onActivityResult(ENABLE_NOTIFICATIONS_REQUEST, RESULT_OK, null)
        } else {
            requestNotificationPermission(false)
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
        ) return false
        val nm = getSystemService(this, NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(VPN_NOTIFICATION_CHANNEL_ID)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun requestNotificationPermission(previouslyRejected: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!previouslyRejected) {
                notificationPermissionHandler.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        showNotificationPermissionRequiredPrompt {
            startActivityForResult(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }, ENABLE_NOTIFICATIONS_REQUEST)
        }
    }

    private fun showNotificationPermissionRequiredPrompt(nextStep: () -> Unit) {
        var handled = false
        val runNextStepOnce = {
            if (!handled) {
                handled = true
                nextStep()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Notification permission")
            .setMessage("Allow notifications for VPN status.")
            .setPositiveButton("OK") { _, _ -> runNextStepOnce() }
            .setOnDismissListener { runNextStepOnce() }
            .show()
    }

    private val notificationPermissionHandler =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && areNotificationsEnabled()) {
                onActivityResult(ENABLE_NOTIFICATIONS_REQUEST, RESULT_OK, null)
            } else {
                requestNotificationPermission(true)
            }
        }

    private fun showVpnKilledAlert() {
        MaterialAlertDialogBuilder(this)
            .setTitle("App was stopped")
            .setMessage("Disable battery optimization for this app to keep interception running.")
            .setPositiveButton("Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun showActiveVpnFailureAlert() {
        MaterialAlertDialogBuilder(this)
            .setTitle("VPN failed")
            .setMessage("Another VPN may be active. Turn it off and try again.")
            .setPositiveButton("VPN settings") { _, _ ->
                startActivity(Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Settings.ACTION_VPN_SETTINGS else Settings.ACTION_WIRELESS_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
