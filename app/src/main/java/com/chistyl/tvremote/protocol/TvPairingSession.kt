package com.chistyl.tvremote.protocol

import com.chistyl.tvremote.security.TvTlsIdentity
import java.io.Closeable
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Owns the pairing socket across the two user-driven steps:
 * 1) startPairing() asks the TV to display its six-character pairing code.
 * 2) finishPairing(code) verifies the code and stores the TV certificate pin.
 *
 * Run all methods on a background thread; do not call from the Android main thread.
 */
class TvPairingSession(private val identity: TvTlsIdentity, private val host: String) : Closeable {
    private var socket: SSLSocket? = null
    private var client: PairingClient? = null
    private var serverCertificate: X509Certificate? = null

    fun startPairing(): String {
        check(socket == null) { "Pairing session already started" }
        identity.clientCertificate()
        val opened = identity.createPairingSocket(host)
        socket = opened
        val pairing = PairingClient(opened)
        client = pairing
        return try {
            serverCertificate = pairing.begin()
            "電視已進入配對模式，請輸入電視畫面上的 6 位配對碼"
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun finishPairing(code: String): String {
        val pairing = client ?: error("請先開始配對")
        val cert = serverCertificate ?: error("尚未取得電視憑證")
        return try {
            pairing.finish(code.trim().uppercase(), identity.clientCertificate(), cert)
            identity.savePairedTv(host, cert)
            close()
            "配對成功，已安全保存此電視的憑證"
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        runCatching { client?.close() }
        client = null
        socket = null
        serverCertificate = null
    }
}