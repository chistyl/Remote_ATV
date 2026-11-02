package com.chistyl.tvremote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Persistent AndroidKeyStore identity and certificate-pinned TLS sockets for Android TV Remote.
 *
 * First pairing uses a temporary trust-on-first-use manager. The TV certificate is not persisted
 * until the six-character pairing secret has been acknowledged by the TV. Reconnects require
 * an exact SHA-256 certificate match saved after successful pairing.
 */
class TvTlsIdentity(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tv_remote_trust", Context.MODE_PRIVATE)

    fun clientCertificate(): X509Certificate {
        val keyStore = loadKeyStore()
        val existing = keyStore.getCertificate(CLIENT_ALIAS) as? X509Certificate
        if (existing != null) return existing

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            CLIENT_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=TV Remote"))
            .setCertificateSerialNumber(java.math.BigInteger.valueOf(now.coerceAtLeast(1)))
            .setCertificateNotBefore(Date(now - 60_000))
            .setCertificateNotAfter(Date(now + CERT_VALIDITY_MS))
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
        return loadKeyStore().getCertificate(CLIENT_ALIAS) as? X509Certificate
            ?: error("Android Keystore did not return the generated client certificate")
    }

    fun createPairingSocket(host: String, port: Int = PAIRING_PORT, timeoutMs: Int = 8_000): SSLSocket {
        val socket = createContext(TrustMode.Pairing).socketFactory.createSocket() as SSLSocket
        socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 10_000
        socket.enabledProtocols = supportedProtocols(socket)
        return socket
    }

    fun createRemoteSocket(host: String, port: Int = REMOTE_PORT, timeoutMs: Int = 8_000): SSLSocket {
        val socket = createContext(TrustMode.Pinned(host)).socketFactory.createSocket() as SSLSocket
        socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 10_000
        socket.enabledProtocols = supportedProtocols(socket)
        socket.startHandshake()
        return socket
    }

    /** Call only after PairingClient.finish() received SecretAck. */
    fun savePairedTv(host: String, serverCertificate: X509Certificate) {
        val fingerprint = sha256(serverCertificate.encoded)
        check(prefs.edit().putString("tv_cert_${host}", fingerprint).commit()) {
            "Unable to persist trusted TV certificate"
        }
    }

    fun isPaired(host: String): Boolean = prefs.contains("tv_cert_${host}")

    fun forgetTv(host: String) {
        prefs.edit().remove("tv_cert_${host}").apply()
    }

    private fun createContext(mode: TrustMode): SSLContext {
        val clientStore = loadKeyStore()
        clientCertificate()
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientStore, null)
        }.keyManagers
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                throw java.security.cert.CertificateException("Client certificate validation is not used here")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("TV certificate chain is empty")
                when (mode) {
                    TrustMode.Pairing -> {
                        // The pairing code cryptographically validates this certificate before it is trusted.
                    }
                    is TrustMode.Pinned -> {
                        val expected = prefs.getString("tv_cert_${mode.host}", null)
                            ?: throw java.security.cert.CertificateException("TV is not paired; pair it first")
                        val actual = sha256(chain[0].encoded)
                        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))) {
                            throw java.security.cert.CertificateException("TV certificate changed; refusing TLS connection")
                        }
                    }
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), java.security.SecureRandom())
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun supportedProtocols(socket: SSLSocket): Array<String> {
        val available = socket.supportedProtocols.toSet()
        return listOf("TLSv1.3", "TLSv1.2").filter { it in available }.toTypedArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private sealed interface TrustMode {
        data object Pairing : TrustMode
        data class Pinned(val host: String) : TrustMode
    }

    companion object {
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CLIENT_ALIAS = "tvremote-client-identity"
        private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
