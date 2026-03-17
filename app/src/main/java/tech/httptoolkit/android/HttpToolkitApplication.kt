package tech.httptoolkit.android

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.beust.klaxon.Klaxon
import io.sentry.Sentry

private const val VPN_START_TIME_PREF = "vpn-start-time"
private const val APP_CRASHED_PREF = "app-crashed"
private const val HTTP_TOOLKIT_PREFERENCES_NAME = "tech.httptoolkit.android"
private const val LAST_PROXY_CONFIG_PREF_KEY = "last-proxy-config"

private val isProbablyEmulator =
    Build.FINGERPRINT.startsWith("generic")
        || Build.FINGERPRINT.startsWith("unknown")
        || Build.MODEL.contains("google_sdk")
        || Build.MODEL.contains("sdk_gphone")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for x86")
        || Build.BOARD == "QC_Reference_Phone"
        || Build.MANUFACTURER.contains("Genymotion")
        || Build.HOST.startsWith("Build")
        || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
        || Build.PRODUCT == "google_sdk"

private val bootTime = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())

class HttpToolkitApplication : Application() {

    private lateinit var prefs: SharedPreferences
    private var vpnWasKilled: Boolean = false

    var vpnShouldBeRunning: Boolean
        get() = prefs.getLong(VPN_START_TIME_PREF, -1) > bootTime
        set(value) {
            prefs.edit { putLong(VPN_START_TIME_PREF, if (value) System.currentTimeMillis() else -1) }
        }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(HTTP_TOOLKIT_PREFERENCES_NAME, MODE_PRIVATE)

        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            prefs.edit { putBoolean(APP_CRASHED_PREF, true) }
        }

        val appCrashed = prefs.getBoolean(APP_CRASHED_PREF, false)
        prefs.edit { putBoolean(APP_CRASHED_PREF, false) }

        vpnWasKilled = vpnShouldBeRunning && !isVpnActive() && !appCrashed && !isProbablyEmulator
        if (vpnWasKilled) {
            Sentry.captureMessage("VPN killed in the background")
        }

        Log.i(TAG, "App created")
    }

    fun popVpnKilledState(): Boolean {
        return vpnWasKilled.also {
            vpnWasKilled = false
            vpnShouldBeRunning = false
        }
    }

    var lastProxy: ProxyConfig?
        get() {
            val serialized = prefs.getString(LAST_PROXY_CONFIG_PREF_KEY, null) ?: return null
            return try {
                Klaxon().converter(CertificateConverter).parse<ProxyConfig>(serialized)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse last proxy config", e)
                null
            }
        }
        set(proxyConfig) {
            prefs.edit {
                if (proxyConfig != null) {
                    putString(LAST_PROXY_CONFIG_PREF_KEY, Klaxon().converter(CertificateConverter).toJsonString(proxyConfig))
                } else {
                    remove(LAST_PROXY_CONFIG_PREF_KEY)
                }
            }
        }
}
