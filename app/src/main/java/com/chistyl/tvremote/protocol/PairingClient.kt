package com.chistyl.tvremote.protocol

import com.google.polo.wire.protobuf.PoloProto
import java.io.Closeable
import java.io.EOFException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Blocking Android TV Remote v2 pairing state machine.
 *
 * Call from an IO/background thread. The supplied SSLSocket must already be configured
 * with the app's persistent client key pair and a trust strategy suitable for the
 * Android TV self-signed server certificate.
 */
class PairingClient(private val socket: SSLSocket) : Closeable {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private var pairingStarted = false
    private var pairingFinished = false

    fun begin(): X509Certificate {
        check(!pairingStarted) { "Pairing has already started" }
        socket.soTimeout = 10_000
        socket.startHandshake()
        send(
            baseMessage().setPairingRequest(
                PoloProto.PairingRequest.newBuilder()
                    .setServiceName("atvremote")
                    .setClientName("TV Remote")
            ).build()
        )
        while (true) {
            val message = receive()
            ensureOk(message)
            when {
                message.hasPairingRequestAck() -> send(optionsMessage())
                message.hasOptions() -> send(configurationMessage())
                message.hasConfigurationAck() -> {
                    pairingStarted = true
                    val peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: error("TV did not present an X.509 certificate")
                    return peer
                }
                else -> error("Unexpected pairing message during setup")
            }
        }
    }

    fun finish(code: String, clientCertificate: X509Certificate, serverCertificate: X509Certificate) {
        check(pairingStarted) { "Call begin() before finish()" }
        check(!pairingFinished) { "Pairing has already finished" }
        val secret = PairingSecret.calculate(clientCertificate, serverCertificate, code)
        send(baseMessage().setSecret(PoloProto.Secret.newBuilder().setSecret(com.google.protobuf.ByteString.copyFrom(secret))).build())
        while (true) {
            val message = receive()
            ensureOk(message)
            if (message.hasSecretAck()) {
                pairingFinished = true
                return
            }
            error("Unexpected pairing message while verifying PIN")
        }
    }

    private fun optionsMessage(): PoloProto.OuterMessage =
        baseMessage().setOptions(
            PoloProto.Options.newBuilder()
                .setPreferredRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                .addInputEncodings(
                    PoloProto.Options.Encoding.newBuilder()
                        .setType(PoloProto.Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                        .setSymbolLength(6)
                )
        ).build()

    private fun configurationMessage(): PoloProto.OuterMessage =
        baseMessage().setConfiguration(
            PoloProto.Configuration.newBuilder()
                .setClientRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                .setEncoding(
                    PoloProto.Options.Encoding.newBuilder()
                        .setType(PoloProto.Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                        .setSymbolLength(6)
                )
        ).build()

    private fun baseMessage(): PoloProto.OuterMessage.Builder =
        PoloProto.OuterMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PoloProto.OuterMessage.Status.STATUS_OK)

    private fun send(message: PoloProto.OuterMessage) {
        ProtobufFrameCodec.writeFrame(output, message.toByteArray())
    }

    private fun receive(): PoloProto.OuterMessage =
        PoloProto.OuterMessage.parseFrom(ProtobufFrameCodec.readFrame(input))

    private fun ensureOk(message: PoloProto.OuterMessage) {
        check(message.status == PoloProto.OuterMessage.Status.STATUS_OK) {
            "TV pairing rejected with status ${message.status}"
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
