package com.chistyl.tvremote.protocol

import com.chistyl.tvremote.proto.RemoteDeviceInfo
import com.chistyl.tvremote.proto.RemoteDirection
import com.chistyl.tvremote.proto.RemoteKeyInject
import com.chistyl.tvremote.proto.RemoteMessage
import com.chistyl.tvremote.security.TvTlsIdentity
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/** Android TV Remote v2 TLS control channel. All socket I/O runs off the UI thread. */
class RemoteControlClient(
    private val identity: TvTlsIdentity,
    private val host: String,
    private val onStatus: (String) -> Unit = {}
) : Closeable {
    private var socket: SSLSocket? = null
    private var reader: Thread? = null
    private val ready = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var started = false
    @Volatile private var negotiatedFeatures = 0

    fun connect(timeoutSeconds: Long = 12): Boolean {
        check(socket == null) { "Remote client is already connected" }
        val opened = identity.createRemoteSocket(host)
        socket = opened
        opened.soTimeout = 0
        reader = Thread({ readLoop(opened) }, "tv-remote-reader").apply { isDaemon = true; start() }
        val ok = ready.await(timeoutSeconds, TimeUnit.SECONDS)
        if (!ok || !started) {
            close()
            throw IllegalStateException("TV remote channel did not complete RemoteStart handshake")
        }
        return true
    }

    fun sendKey(keyCode: Int, direction: Int = DIRECTION_SHORT) {
        check(started && !closed.get()) { "TV remote is not connected" }
        check((negotiatedFeatures and FEATURE_KEY) != 0) { "This TV does not advertise key injection support" }
        val key = RemoteKeyInject.newBuilder()
            .setKeyCodeValue(keyCode)
            .setDirectionValue(direction)
            .build()
        send(RemoteMessage.newBuilder().setRemoteKeyInject(key).build())
    }

    fun startLongPress(keyCode: Int) = sendKey(keyCode, DIRECTION_START_LONG)

    fun endLongPress(keyCode: Int) = sendKey(keyCode, DIRECTION_END_LONG)

    private fun readLoop(activeSocket: SSLSocket) {
        try {
            while (!closed.get()) {
                val message = RemoteMessage.parseFrom(ProtobufFrameCodec.readFrame(activeSocket.inputStream))
                when {
                    message.hasRemoteConfigure() -> {
                        negotiatedFeatures = message.remoteConfigure.code1 and REQUESTED_FEATURES
                        val response = RemoteMessage.newBuilder().setRemoteConfigure(
                            com.chistyl.tvremote.proto.RemoteConfigure.newBuilder()
                                .setCode1(negotiatedFeatures)
                                .setDeviceInfo(RemoteDeviceInfo.newBuilder()
                                    .setUnknown1(1)
                                    .setUnknown2("1")
                                    .setPackageName("atvremote")
                                    .setAppVersion("1.0.0"))
                        ).build()
                        send(response)
                    }
                    message.hasRemoteSetActive() -> send(
                        RemoteMessage.newBuilder().setRemoteSetActive(
                            com.chistyl.tvremote.proto.RemoteSetActive.newBuilder().setActive(negotiatedFeatures)
                        ).build()
                    )
                    message.hasRemoteStart() -> {
                        started = message.remoteStart.started
                        if (started) {
                            onStatus("遙控通道已啟動")
                            ready.countDown()
                        } else {
                            onStatus("電視未啟動遙控服務")
                            ready.countDown()
                        }
                    }
                    message.hasRemotePingRequest() -> send(
                        RemoteMessage.newBuilder().setRemotePingResponse(
                            com.chistyl.tvremote.proto.RemotePingResponse.newBuilder().setVal1(message.remotePingRequest.val1)
                        ).build()
                    )
                    message.hasRemoteError() -> onStatus("電視回報遙控錯誤")
                    else -> Unit
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) onStatus("遙控連線中斷：${error.localizedMessage ?: error.javaClass.simpleName}")
        } finally {
            ready.countDown()
            close()
        }
    }

    private fun send(message: RemoteMessage) {
        val activeSocket = socket ?: throw IllegalStateException("Remote socket is not connected")
        synchronized(writeLock) {
            check(!closed.get()) { "Remote socket is closed" }
            ProtobufFrameCodec.writeFrame(activeSocket.outputStream, message.toByteArray())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        started = false
        runCatching { socket?.close() }
        socket = null
        ready.countDown()
    }

    companion object {
        // PING | KEY | IME | POWER | VOLUME | APP_LINK, matching androidtvremote2.
        const val REQUESTED_FEATURES = 615
        const val DIRECTION_START_LONG = 1
        const val DIRECTION_END_LONG = 2
        const val DIRECTION_SHORT = 3
        private const val FEATURE_KEY = 2
    }
}