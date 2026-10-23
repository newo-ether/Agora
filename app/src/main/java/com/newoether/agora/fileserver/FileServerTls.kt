package com.newoether.agora.fileserver

import java.math.BigInteger
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Builds a TLS [ServerSocket] for the file server from an ephemeral self-signed certificate.
 *
 * Unlike the WebUI's persisted identity, the file server is a transient LAN share, so a fresh
 * certificate per start is acceptable: the user is told to accept the browser warning once per
 * session. Uses the same BouncyCastle builder API already vendored in the app.
 */
internal object FileServerTls {

    fun createServerSocket(port: Int): ServerSocket {
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
            .generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Agora File Server")
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - CLOCK_SKEW_MILLIS),
            Date(now + VALIDITY_MILLIS),
            subject,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)

        val password = CharArray(0)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("agora-fileserver", keyPair.private, password, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        val sslContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return sslContext.serverSocketFactory.createServerSocket(port)
    }

    private const val CLOCK_SKEW_MILLIS = 24L * 60 * 60 * 1000
    private const val VALIDITY_MILLIS = 365L * 24 * 60 * 60 * 1000
}
