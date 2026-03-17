package tech.httptoolkit.android

import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.security.auth.x500.X500Principal

private const val TAG = "LudokingVPN"

/**
 * Generates fake SSL certificates for MITM proxy
 */
class FakeCertificateGenerator(
    private val caCertificate: X509Certificate,
    private val caPrivateKey: PrivateKey
) {
    
    init {
        // Register BouncyCastle provider if not already registered
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Generate a fake certificate for the given hostname, signed by the CA
     */
    fun generateFakeCertificate(hostname: String): Pair<X509Certificate, KeyPair> {
        Log.i(TAG, "[CERT] Generating fake certificate for: $hostname")
        
        // Generate key pair for the fake certificate
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Certificate serial number
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis() + hostname.hashCode().toLong())
        
        // Validity period (1 year)
        val calendar = Calendar.getInstance()
        val notBefore = calendar.time
        calendar.add(Calendar.YEAR, 1)
        val notAfter = calendar.time
        
        // Subject: CN = hostname
        val subject = X500Name("CN=$hostname")
        val issuer = X500Name(caCertificate.subjectX500Principal.name)
        
        // Build certificate
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        // Add Subject Alternative Name extension
        val generalNames = GeneralNames(
            GeneralName(GeneralName.dNSName, hostname)
        )
        certBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            generalNames
        )
        
        // Add Key Usage extension (digitalSignature, keyEncipherment)
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        
        // Add Extended Key Usage extension (serverAuth)
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            true,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )
        
        // Sign certificate with CA private key
        val signer = try {
            JcaContentSignerBuilder("SHA256withRSA")
                .build(caPrivateKey)
        } catch (e: Exception) {
            Log.w(TAG, "[CERT] Failed with default provider, trying BC", e)
            JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caPrivateKey)
        }
        
        val certHolder = certBuilder.build(signer)
        val certificate = try {
            JcaX509CertificateConverter()
                .getCertificate(certHolder)
        } catch (e: Exception) {
            Log.w(TAG, "[CERT] Failed to convert with default provider, trying BC", e)
            JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(certHolder)
        }
        
        Log.i(TAG, "[CERT] Fake certificate generated successfully for: $hostname")
        return Pair(certificate, keyPair)
    }
}
