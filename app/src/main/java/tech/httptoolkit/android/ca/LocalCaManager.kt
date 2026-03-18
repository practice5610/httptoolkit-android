package tech.httptoolkit.android.ca

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
private val TAG = "LocalCaManager"

private fun ensureBouncyCastleProvider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(BouncyCastleProvider())
    }
}

data class GeneratedCa(val keyPair: KeyPair, val certificate: X509Certificate)

@Volatile
var lastGeneratedCa: GeneratedCa? = null
    private set

private const val CA_CERT_FILE = "ludo_ca_cert.der"
private const val CA_KEY_FILE = "ludo_ca_key.der"
private const val CA_SUBJECT = "CN=Ludoking Interceptor CA"
private const val KEY_SIZE = 2048
private const val VALIDITY_DAYS_CA = 365 * 10
private const val VALIDITY_DAYS_LEAF = 365

fun generateOrLoad(context: Context): GeneratedCa {
    ensureBouncyCastleProvider()
    val filesDir = context.filesDir
    val certFile = File(filesDir, CA_CERT_FILE)
    val keyFile = File(filesDir, CA_KEY_FILE)

    if (certFile.exists() && keyFile.exists()) {
        try {
            val certBytes = certFile.readBytes()
            val keyBytes = keyFile.readBytes()
            val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate
            val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
            val privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            val publicKey = cert.publicKey
            val keyPair = KeyPair(publicKey, privateKey)
            setLoadedCa(GeneratedCa(keyPair, cert))
            Log.i(TAG, "Loaded existing CA from storage")
            return lastGeneratedCa!!
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load CA, regenerating", e)
        }
    }

    val keyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(KEY_SIZE)
    }.generateKeyPair()

    val now = Date()
    val endDate = Date(now.time + VALIDITY_DAYS_CA * 24L * 60 * 60 * 1000)
    val serial = BigInteger.valueOf(System.currentTimeMillis())

    val issuer = X500Name(CA_SUBJECT)
    val certBuilder = JcaX509v3CertificateBuilder(
        issuer,
        serial,
        now,
        endDate,
        issuer,
        keyPair.public
    )
    certBuilder.addExtension(
        Extension.basicConstraints,
        true,
        BasicConstraints(true)
    )

    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
    val cert = JcaX509CertificateConverter()
        .getCertificate(certBuilder.build(signer))

    certFile.writeBytes(cert.encoded)
    keyFile.writeBytes(keyPair.private.encoded)
    lastGeneratedCa = GeneratedCa(keyPair, cert)
    Log.i(TAG, "Generated and persisted new CA")
    return lastGeneratedCa!!
}

private fun setLoadedCa(ca: GeneratedCa) {
    lastGeneratedCa = ca
}

private val leafSerial = AtomicLong(System.currentTimeMillis())

fun issueServerCert(ca: GeneratedCa, hostname: String): Pair<java.security.PrivateKey, X509Certificate> {
    ensureBouncyCastleProvider()
    val keyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(KEY_SIZE)
    }.generateKeyPair()

    val now = Date()
    val endDate = Date(now.time + VALIDITY_DAYS_LEAF * 24L * 60 * 60 * 1000)
    val serial = BigInteger.valueOf(leafSerial.incrementAndGet())

    val issuer = X500Name(ca.certificate.subjectX500Principal.name)
    val subject = X500Name("CN=$hostname")
    val certBuilder = JcaX509v3CertificateBuilder(
        issuer,
        serial,
        now,
        endDate,
        subject,
        keyPair.public
    )
    certBuilder.addExtension(
        Extension.subjectAlternativeName,
        false,
        GeneralNames(GeneralName(GeneralName.dNSName, hostname))
    )

    val signer = JcaContentSignerBuilder("SHA256withRSA").build(ca.keyPair.private)
    val cert = JcaX509CertificateConverter()
        .getCertificate(certBuilder.build(signer))

    return keyPair.private to cert
}
