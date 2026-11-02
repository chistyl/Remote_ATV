package com.chistyl.tvremote.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Computes the Android TV Remote v2 six-hex-character pairing secret.
 * The first byte of SHA-256 is the check byte; the remaining four PIN bytes are hashed.
 */
object PairingSecret {
    fun validateCode(code: String): Boolean =
        code.length == 6 && code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun calculate(
        clientCertificate: X509Certificate,
        serverCertificate: X509Certificate,
        code: String
    ): ByteArray {
        require(validateCode(code)) { "配對碼必須是 6 位十六進位字元" }
        val clientKey = clientCertificate.publicKey as? java.security.interfaces.RSAPublicKey
            ?: error("Client certificate must use RSA")
        val serverKey = serverCertificate.publicKey as? java.security.interfaces.RSAPublicKey
            ?: error("TV certificate must use RSA")

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsignedEvenBytes(clientKey.modulus))
        digest.update(unsignedEvenBytes(clientKey.publicExponent))
        digest.update(unsignedEvenBytes(serverKey.modulus))
        digest.update(unsignedEvenBytes(serverKey.publicExponent))
        digest.update(code.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())

        val result = digest.digest()
        require((result[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) {
            "配對碼檢查碼不符，請確認電視畫面上的配對碼"
        }
        return result
    }

    private fun unsignedEvenBytes(value: BigInteger): ByteArray {
        val hex = value.toString(16).let { if (it.length % 2 == 0) it else "0$it" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
