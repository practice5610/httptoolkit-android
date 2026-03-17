package tech.httptoolkit.android

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.spec.PKCS8EncodedKeySpec
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.security.auth.x500.X500Principal

private val TAG = formatTag("tech.httptoolkit.android.CACertificateGenerator")

/**
 * Generates a self-signed CA certificate for HTTPS interception
 */
class CACertificateGenerator(private val context: Context) {

    private val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    private val certificateFactory = CertificateFactory.getInstance("X.509")

    init {
        // Register BouncyCastle provider if not already registered
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        keyPairGenerator.initialize(2048, SecureRandom())
    }

    /**
     * Generate a new CA certificate and key pair
     * Returns the certificate as X509Certificate
     */
    fun generateCACertificate(): X509Certificate {
        Log.i(TAG, "Generating new CA certificate")

        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public

        // Certificate serial number
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        
        // Validity period (10 years)
        val calendar = Calendar.getInstance()
        val notBefore = calendar.time
        calendar.add(Calendar.YEAR, 10)
        val notAfter = calendar.time

        // Subject and issuer (same for self-signed CA)
        val subject = X500Name("CN=Ludoking VPN CA, O=Ludoking VPN, C=US")
        val issuer = subject

        // Build certificate using BouncyCastle
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            publicKey
        )

        // Add extensions for CA
        // Basic constraints: this is a CA
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(true)
        )

        // Key usage: keyCertSign, cRLSign
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        // Sign certificate
        // Try without specifying provider first, fallback to BC if needed
        val signer = try {
            JcaContentSignerBuilder("SHA256withRSA")
                .build(privateKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create signer with default provider, trying BC", e)
            JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(privateKey)
        }

        val certHolder = certBuilder.build(signer)
        val certificate = try {
            JcaX509CertificateConverter()
                .getCertificate(certHolder)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert certificate with default provider, trying BC", e)
            JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(certHolder)
        }
        
        Log.i(TAG, "CA certificate generated successfully")
        return certificate
    }

    /**
     * Save certificate to file for user installation
     */
    fun saveCertificateToFile(certificate: X509Certificate): File {
        val certFile = File(context.getExternalFilesDir(null), "ludoking-vpn-ca.crt")
        FileOutputStream(certFile).use { fos ->
            fos.write(certificate.encoded)
        }
        Log.i(TAG, "Certificate saved to: ${certFile.absolutePath}")
        return certFile
    }

    /**
     * Get or generate CA certificate and key pair
     * Returns Pair of (certificate, privateKey)
     */
    fun getOrGenerateCAKeyPair(): Pair<X509Certificate, PrivateKey> {
        val certFile = File(context.filesDir, "ca_certificate.der")
        val keyFile = File(context.filesDir, "ca_private_key.der")
        
        return if (certFile.exists() && keyFile.exists()) {
            try {
                Log.i(TAG, "Loading existing CA certificate and key")
                val certificate = certificateFactory.generateCertificate(certFile.inputStream()) as X509Certificate
                val privateKey = loadPrivateKey()
                if (privateKey != null) {
                    return Pair(certificate, privateKey)
                } else {
                    Log.w(TAG, "Could not load private key, regenerating")
                    generateAndSaveCAKeyPair()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load existing certificate/key, generating new one", e)
                generateAndSaveCAKeyPair()
            }
        } else {
            Log.i(TAG, "No existing certificate/key found, generating new one")
            generateAndSaveCAKeyPair()
        }
    }
    
    /**
     * Get or generate CA certificate (backward compatibility)
     */
    fun getOrGenerateCACertificate(): X509Certificate {
        return getOrGenerateCAKeyPair().first
    }
    
    private fun generateCACertificateAndKey(): Pair<X509Certificate, PrivateKey> {
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public
        
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val calendar = Calendar.getInstance()
        val notBefore = calendar.time
        calendar.add(Calendar.YEAR, 10)
        val notAfter = calendar.time
        
        val subject = X500Name("CN=Ludoking VPN CA, O=Ludoking VPN, C=US")
        val issuer = subject
        
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serialNumber, notBefore, notAfter, subject, publicKey
        )
        
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        certBuilder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        
        val signer = try {
            JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        } catch (e: Exception) {
            JcaContentSignerBuilder("SHA256withRSA").setProvider(BouncyCastleProvider.PROVIDER_NAME).build(privateKey)
        }
        
        val certHolder = certBuilder.build(signer)
        val certificate = try {
            JcaX509CertificateConverter().getCertificate(certHolder)
        } catch (e: Exception) {
            JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(certHolder)
        }
        
        return Pair(certificate, privateKey)
    }

    private fun generateAndSaveCAKeyPair(): Pair<X509Certificate, PrivateKey> {
        val (certificate, privateKey) = generateCACertificateAndKey()
        saveCAKeyPair(certificate, privateKey)
        return Pair(certificate, privateKey)
    }
    
    private fun saveCAKeyPair(certificate: X509Certificate, privateKey: PrivateKey) {
        val certFile = File(context.filesDir, "ca_certificate.der")
        FileOutputStream(certFile).use { fos ->
            fos.write(certificate.encoded)
        }
        
        // Save private key in PKCS8 format (in production, use AndroidKeyStore!)
        val keyFile = File(context.filesDir, "ca_private_key.der")
        FileOutputStream(keyFile).use { fos ->
            fos.write(privateKey.encoded) // PKCS8 encoded format
        }
        
        Log.i(TAG, "CA certificate and key saved to internal storage")
    }
    
    private fun loadPrivateKey(): PrivateKey? {
        return try {
            val keyFile = File(context.filesDir, "ca_private_key.der")
            if (!keyFile.exists()) return null
            
            val keyBytes = keyFile.readBytes()
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load private key", e)
            null
        }
    }
}
