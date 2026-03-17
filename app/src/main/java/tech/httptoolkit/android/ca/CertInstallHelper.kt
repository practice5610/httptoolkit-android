package tech.httptoolkit.android.ca

import android.app.Activity
import android.security.KeyChain
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate

private val TAG = "CertInstallHelper"

const val INSTALL_CERT_REQUEST = 456

/**
 * Starts the system CA certificate install flow. Call from Activity.
 * Result is delivered to Activity.onActivityResult with requestCode INSTALL_CERT_REQUEST.
 */
fun launchInstallCaIntent(activity: Activity, cert: X509Certificate) {
    val intent = KeyChain.createInstallIntent().apply {
        putExtra(KeyChain.EXTRA_NAME, "Ludoking Interceptor CA")
        putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
    }
    activity.startActivityForResult(intent, INSTALL_CERT_REQUEST)
}

/**
 * Saves the CA cert to app-private storage and returns the file URI path for "open install" flow.
 * Caller can use Intent(Intent.ACTION_VIEW) with the file to prompt user to install (e.g. Android 11+).
 */
fun saveCertToDownloads(activity: Activity, cert: X509Certificate): android.net.Uri? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "Ludoking_Interceptor_CA.crt")
        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = activity.contentResolver.insert(
        android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
        contentValues
    ) ?: return null
    activity.contentResolver.openFileDescriptor(uri, "w", null)?.use { pfd ->
        java.io.FileOutputStream(pfd.fileDescriptor).use { it.write(cert.encoded) }
    }
    contentValues.clear()
    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
    activity.contentResolver.update(uri, contentValues, null, null)
    return uri
}

fun getCertificateFingerprint(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(cert.publicKey.encoded)
    return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
}

/**
 * Returns "system", "user", or null if the given certificate is not in the device trust store.
 */
fun whereIsCertTrusted(cert: Certificate): String? {
    val keyStore = KeyStore.getInstance("AndroidCAStore")
    keyStore.load(null, null)
    val certData = cert.encoded
    val aliases = keyStore.aliases().toList().filter { alias ->
        val stored = keyStore.getCertificate(alias)
        stored?.encoded?.contentEquals(certData) == true
    }
    Log.i(TAG, "Cert trust aliases: $aliases")
    return when {
        aliases.isEmpty() -> null
        aliases.any { it.startsWith("system:") } -> "system"
        aliases.any { it.startsWith("user:") } -> "user"
        else -> "unknown-store"
    }
}
