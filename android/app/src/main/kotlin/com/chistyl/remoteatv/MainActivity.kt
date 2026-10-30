package com.chistyl.remoteatv

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val KEYCODE_MAX = 1000

class MainActivity : FlutterActivity() {
    private val methodChannelName = "remote_atv/discovery"
    private val eventChannelName = "remote_atv/discovery_events"
    private lateinit var nsdManager: NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val devices = ConcurrentHashMap<String, Map<String, Any?>>()
    private var eventSink: EventChannel.EventSink? = null
    private var scanning = false
    @Volatile private var pairingSocket: SSLSocket? = null
    @Volatile private var pairingDeviceName: String = "Android TV"
    @Volatile private var pairingServiceType: String = "_androidtvremote2._tcp."
    @Volatile private var pairingHost: String = ""
    @Volatile private var pairingStage: String = "尚未開始"
    @Volatile private var remoteStage: String = "尚未開始"
    private val pairingLock = Any()
    @Volatile private var remoteSocket: SSLSocket? = null
    @Volatile private var remoteReader: Thread? = null
    private val remoteLock = Any()
    private var remoteDeviceName: String = "Android TV"
    @Volatile private var remoteNegotiatedFeatures: Int = 0
    @Volatile private var remoteHost: String = ""
    @Volatile private var remotePort: Int = 6466
    @Volatile private var remoteConnectedAtMs: Long = 0L
    @Volatile private var remoteTlsHandshakeMs: Long = -1L
    @Volatile private var remoteLastInboundAtMs: Long = 0L
    @Volatile private var remoteInboundFrameCount: Long = 0L
    @Volatile private var remotePingRequestCount: Long = 0L
    @Volatile private var remotePingResponseCount: Long = 0L
    @Volatile private var remoteVolumeUpdateCount: Long = 0L
    @Volatile private var remoteImeUpdateCount: Long = 0L
    @Volatile private var remoteStartCount: Long = 0L
    @Volatile private var remoteLastDisconnectReason: String = "尚無斷線記錄"
    @Volatile private var tvVolumeLevel: Int = -1
    @Volatile private var tvVolumeMax: Int = -1
    @Volatile private var tvVolumeMuted: Boolean = false
    @Volatile private var tvVolumeKnown: Boolean = false
    @Volatile private var pendingVoiceLatch: CountDownLatch? = null
    @Volatile private var pendingVoiceSessionId: Int? = null
    @Volatile private var activeVoiceSessionId: Int? = null
    @Volatile private var voiceRecorder: AudioRecord? = null
    @Volatile private var voiceThread: Thread? = null
    private val voiceStopping = AtomicBoolean(false)
    @Volatile private var imeCounter: Int = 0
    @Volatile private var imeFieldCounter: Int = 0
    // PING | KEY | IME | VOICE | POWER | VOLUME | APP_LINK
    private val remoteFeatures = 1 or 2 or 4 or 8 or 32 or 64 or 512

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { eventSink = events }
                override fun onCancel(arguments: Any?) { eventSink = null }
            })
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startDiscovery" -> { startDiscovery(); result.success(true) }
                    "stopDiscovery" -> { stopDiscovery(); result.success(true) }
                    "getDevices" -> result.success(devices.values.toList())
                    "probeDevice" -> {
                        val host = call.argument<String>("host")
                        val port = call.argument<Int>("port")
                        if (host.isNullOrBlank() || port == null || port !in 1..65535) {
                            result.error("INVALID_DEVICE", "電視位址或連接埠無效", null)
                        } else probeDevice(host, port, result)
                    }
                    "beginPairing" -> {
                        val host = call.argument<String>("host")
                        val port = call.argument<Int>("port")
                        val name = call.argument<String>("name") ?: "Android TV"
                        val serviceType = call.argument<String>("serviceType") ?: "_androidtvremote2._tcp."
                        if (!serviceType.contains("androidtvremote2")) { result.error("UNSUPPORTED_SERVICE", "目前只支援 Android TV Remote v2 配對", null); return@setMethodCallHandler }
                        if (host.isNullOrBlank() || port == null || port !in 1..65535) {
                            result.error("INVALID_DEVICE", "電視位址或連接埠無效", null)
                        } else beginPairing(host, port, name, serviceType, result)
                    }
                    "finishPairing" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        finishPairing(pin, result)
                    }
                    "cancelPairing" -> { cancelPairing(); result.success(true) }
                    "connectRemote" -> {
                        val host = call.argument<String>("host")
                        val name = call.argument<String>("name") ?: "Android TV"
                        val port = call.argument<Int>("port") ?: 6466
                        if (host.isNullOrBlank()) result.error("INVALID_DEVICE", "電視位址無效", null)
                        else connectRemote(host, port, name, result)
                    }
                    "sendKey" -> {
                        val keyCode = call.argument<Int>("keyCode")
                        val direction = call.argument<Int>("direction") ?: 3
                        if (keyCode == null) result.error("INVALID_KEY", "按鍵代碼無效", null)
                        else try { sendRemoteKey(keyCode, direction); result.success(true) }
                             catch (e: Exception) { result.error("REMOTE_NOT_CONNECTED", e.message, null) }
                    }
                    "getConnectionDiagnostics" -> result.success(connectionDiagnosticsSnapshot())
                    "getVolumeState" -> result.success(mapOf(
                        "known" to tvVolumeKnown,
                        "level" to tvVolumeLevel,
                        "max" to tvVolumeMax,
                        "muted" to tvVolumeMuted
                    ))
                    "startVoice" -> startVoice(result)
                    "stopVoice" -> { stopVoice(); result.success(true) }
                    "sendText" -> {
                        val text = call.argument<String>("text") ?: ""
                        try { sendRemoteText(text); result.success(true) }
                        catch (e: Exception) { result.error("REMOTE_TEXT_FAILED", e.message, null) }
                    }
                    "disconnectRemote" -> { disconnectRemote(); result.success(true) }
                    "getPairedDevice" -> {
                        val prefs = getSharedPreferences("remote_atv", Context.MODE_PRIVATE)
                        val host = prefs.getString("paired_host", null)
                        val name = prefs.getString("paired_nickname", prefs.getString("paired_name", "Android TV"))
                        val serviceName = prefs.getString("paired_name", "Android TV")
                        val serviceType = prefs.getString("paired_service_type", "_androidtvremote2._tcp.")
                        val port = prefs.getInt("remote_port", 6466)
                        result.success(if (host.isNullOrBlank()) null else mapOf("host" to host, "name" to name, "serviceName" to serviceName, "serviceType" to serviceType, "port" to port))
                    }
                    "updatePairedEndpoint" -> {
                        val host = call.argument<String>("host")?.trim().orEmpty()
                        val serviceName = call.argument<String>("serviceName")?.trim().orEmpty()
                        val serviceType = call.argument<String>("serviceType")?.trim().orEmpty()
                        val port = call.argument<Int>("port") ?: 6466
                        val prefs = getSharedPreferences("remote_atv", Context.MODE_PRIVATE)
                        val savedName = prefs.getString("paired_name", null)
                        val savedType = prefs.getString("paired_service_type", "_androidtvremote2._tcp.")
                        if (host.isBlank() || serviceName.isBlank() || serviceName != savedName || serviceType != savedType || !serviceType.contains("androidtvremote2") || port !in 1..65535) {
                            result.success(false)
                        } else {
                            // Update only the network endpoint. Never replace the trusted certificate fingerprint here.
                            prefs.edit().putString("paired_host", host).putInt("remote_port", port).apply()
                            result.success(true)
                        }
                    }
                    "getUiSettings" -> {
                        val prefs = getSharedPreferences("remote_atv", Context.MODE_PRIVATE)
                        result.success(mapOf("haptic" to prefs.getBoolean("haptic_feedback", true),
                            "naturalHold" to prefs.getBoolean("natural_hold", true),
                            "remoteLayout" to prefs.getString("remote_layout", "tap")))
                    }
                    "setUiSettings" -> {
                        val prefs = getSharedPreferences("remote_atv", Context.MODE_PRIVATE)
                        val edit = prefs.edit()
                        call.argument<Boolean>("haptic")?.let { edit.putBoolean("haptic_feedback", it) }
                        call.argument<Boolean>("naturalHold")?.let { edit.putBoolean("natural_hold", it) }
                        call.argument<String>("remoteLayout")?.let { edit.putString("remote_layout", it) }
                        edit.apply(); result.success(true)
                    }
                    "renamePairedDevice" -> {
                        val nickname = call.argument<String>("nickname")?.trim().orEmpty()
                        if (nickname.isBlank()) result.error("INVALID_NICKNAME", "裝置名稱不可空白", null)
                        else { getSharedPreferences("remote_atv", Context.MODE_PRIVATE).edit().putString("paired_nickname", nickname).apply(); result.success(true) }
                    }
                    "deletePairedDevice" -> {
                        disconnectRemote()
                        getSharedPreferences("remote_atv", Context.MODE_PRIVATE).edit()
                            .remove("paired_host").remove("paired_name").remove("paired_service_type").remove("paired_nickname")
                            .remove("remote_port").remove("server_cert_sha256").apply()
                        // Explicit device deletion also removes this app's client identity from Android Keystore.
                        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("remote_atv_client_identity") } }
                        catch (e: Exception) { Log.w("RemoteATV", "Unable to remove pairing key", e) }
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    @Suppress("MissingPermission")
    private fun startDiscovery() {
        stopDiscovery()
        devices.clear()
        scanning = true
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("remote_atv_mdns").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w("RemoteATV", "Multicast lock unavailable", e) }

        listOf("_androidtvremote._tcp.", "_androidtvremote2._tcp.").forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { emitStatus("正在搜尋 $serviceType") }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (scanning && serviceInfo.serviceType.contains("androidtvremote")) resolve(serviceInfo)
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    devices.remove(serviceInfo.serviceName + "|" + serviceInfo.serviceType); emitDevices()
                }
                override fun onDiscoveryStopped(serviceType: String) { }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w("RemoteATV", "Discovery failed for $serviceType: $errorCode")
                    try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) { }
                    emitStatus("搜尋服務啟動失敗（$errorCode）")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) { }
                }
            }
            listeners.add(listener)
            try { nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (e: Exception) { Log.e("RemoteATV", "Unable to start discovery: $type", e) }
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun resolve(service: NsdServiceInfo) {
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w("RemoteATV", "Resolve failed: ${info.serviceName} ($errorCode)")
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    val key = info.serviceName + "|" + info.serviceType
                    devices[key] = mapOf("id" to key, "name" to info.serviceName,
                        "serviceType" to info.serviceType, "host" to host, "port" to info.port,
                        "isIpv4" to (info.host is Inet4Address))
                    val prefs = getSharedPreferences("remote_atv", Context.MODE_PRIVATE)
                    val pairedService = prefs.getString("paired_name", null)
                    val pairedType = prefs.getString("paired_service_type", "_androidtvremote2._tcp.")
                    // A service name alone is not a unique device identity. Only refresh the saved
                    // endpoint when exactly one v2 advertisement matches the saved service identity.
                    if (info.serviceName == pairedService && info.serviceType.contains("androidtvremote2") &&
                        pairedType == info.serviceType) {
                        val matchingHosts = devices.values.filter {
                            it["name"] == pairedService && it["serviceType"] == pairedType
                        }.mapNotNull { it["host"] as? String }.distinct()
                        if (matchingHosts.size == 1) {
                            prefs.edit().putString("paired_host", host).putInt("remote_port", 6466).apply()
                            emitStatus("已確認唯一配對服務並更新網路位址：$host:${info.port}")
                        } else if (matchingHosts.size > 1) {
                            emitStatus("發現多台同名電視，為避免連錯裝置，暫不自動更新已配對位址")
                        }
                    }
                    emitDevices()
                }
            })
        } catch (e: Exception) { Log.w("RemoteATV", "Resolve exception", e) }
    }

    private fun probeDevice(host: String, port: Int, result: MethodChannel.Result) {
        Thread {
            val started = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 3500)
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                runOnUiThread { result.success(mapOf("reachable" to true, "elapsedMs" to elapsedMs, "host" to host, "port" to port)) }
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val detail = when (e) {
                    is java.net.SocketTimeoutException -> "連線逾時：電視未在 3.5 秒內回應此服務連接埠"
                    is java.net.ConnectException -> "連線被拒絕：服務連接埠未開啟或電視端未允許配對"
                    else -> "無法連接電視服務：${e.message ?: e.javaClass.simpleName}"
                }
                runOnUiThread { result.success(mapOf("reachable" to false, "elapsedMs" to elapsedMs, "host" to host, "port" to port, "message" to detail)) }
            }
        }.start()
    }

    private fun beginPairing(host: String, port: Int, name: String, serviceType: String, result: MethodChannel.Result) {
        Thread {
            try {
                cancelPairing()
                pairingDeviceName = name
                pairingServiceType = serviceType
                pairingHost = host
                setPairingStage("TCP 連線", "正在連接電視配對服務")
                emitStatus("正在與 $name 建立加密配對連線…")
                val raw = Socket()
                raw.connect(InetSocketAddress(host, port), 10000)
                raw.soTimeout = 12000
                setPairingStage("TLS 安全握手", "正在建立加密連線")
                val context = createPairingSslContext()
                val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                ssl.enabledProtocols = arrayOf("TLSv1.2")
                ssl.soTimeout = 12000
                ssl.startHandshake()
                pairingSocket = ssl
                setPairingStage("Polo 配對請求", "已建立 TLS，正在送出配對請求")
                val output = ssl.outputStream
                val input = ssl.inputStream

                // Polo protocol: pairing_request -> options -> configuration -> configuration_ack.
                writeProto(output, outerMessage(10, pairingRequest(name)))
                val ack = readProto(input)
                ensureOk(ack, "電視未回應配對請求")
                if (!ack.hasField(11)) throw IllegalStateException("電視回應格式不正確（缺少配對確認）")

                setPairingStage("讀取配對選項", "正在讀取電視提供的配對選項")
                writeProto(output, outerMessage(20, optionsMessage()))
                val options = readProto(input)
                ensureOk(options, "電視無法提供配對選項")
                if (!options.hasField(20)) throw IllegalStateException("電視未提供配對選項")

                setPairingStage("傳送配對設定", "正在傳送配對設定並等待電視確認")
                writeProto(output, outerMessage(30, configurationMessage()))
                val configAck = readProto(input)
                ensureOk(configAck, "電視配對設定失敗")
                if (!configAck.hasField(31)) throw IllegalStateException("電視未確認配對設定")

                setPairingStage("等待使用者輸入 PIN", "已準備完成，請查看電視上的 6 位數配對碼")
                emitPairingEvent("pin_required", "請查看電視畫面上的 6 位數英數／十六進位配對碼", name)
                runOnUiThread { result.success(mapOf("ready" to true, "device" to name)) }
            } catch (e: Exception) {
                Log.e("RemoteATV", "Pairing initialization failed", e)
                cancelPairing()
                val message = friendlyError(e, pairingStage)
                emitPairingEvent("failed", message, pairingDeviceName)
                runOnUiThread { result.error("PAIRING_START_FAILED", message, null) }
            }
        }.start()
    }

    private fun finishPairing(pinRaw: String, result: MethodChannel.Result) {
        Thread {
            try {
                val pin = pinRaw.trim().uppercase()
                if (!Regex("^[0-9A-F]{6}$").matches(pin)) throw IllegalArgumentException("配對碼必須是 6 位數十六進位字元（0–9、A–F）")
                setPairingStage("檢查配對憑證", "正在讀取本機與電視憑證")
                val ssl = pairingSocket ?: throw IllegalStateException("配對連線已中斷，請重新選擇電視")
                val clientCert = getClientCertificate()
                val serverCert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw IllegalStateException("無法讀取電視安全憑證")
                val clientRsa = clientCert.publicKey as? java.security.interfaces.RSAPublicKey
                    ?: throw IllegalStateException("手機憑證不是 RSA 格式")
                val serverRsa = serverCert.publicKey as? java.security.interfaces.RSAPublicKey
                    ?: throw IllegalStateException("電視憑證不是 RSA 格式")

                setPairingStage("本機 PIN 驗證", "正在驗證配對碼；尚未送出確認資料")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                digest.update(bigIntegerBytes(clientRsa.modulus))
                digest.update(bigIntegerBytes(clientRsa.publicExponent))
                digest.update(bigIntegerBytes(serverRsa.modulus))
                digest.update(bigIntegerBytes(serverRsa.publicExponent))
                digest.update(hexToBytes(pin.substring(2)))
                val secret = digest.digest()
                if ((secret[0].toInt() and 0xff) != pin.substring(0, 2).toInt(16)) {
                    throw SecurityException("配對碼驗證失敗，請確認電視上的 6 位數配對碼")
                }

                setPairingStage("送出 PIN 驗證資料", "本機驗證通過，正在送出配對驗證資料")
                writeProto(ssl.outputStream, outerMessage(40, lengthDelimited(1, secret)))
                setPairingStage("等待電視確認配對", "已送出驗證資料，等待電視端回覆")
                val response = readProto(ssl.inputStream)
                ensureOk(response, "電視拒絕配對碼")
                if (!response.hasField(41)) throw SecurityException("電視未確認配對，請重新輸入配對碼")
                val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(serverCert.encoded)
                    .joinToString("") { "%02x".format(it) }
                getSharedPreferences("remote_atv", Context.MODE_PRIVATE).edit()
                    .putString("paired_host", pairingHost).putString("paired_name", pairingDeviceName)
                    .putInt("remote_port", 6466).putString("paired_service_type", pairingServiceType)
                    .putString("server_cert_sha256", fingerprint).apply()
                setPairingStage("配對完成", "電視已確認配對並保存安全憑證")
                emitPairingEvent("paired", "$pairingDeviceName 配對成功", pairingDeviceName)
                synchronized(pairingLock) { try { pairingSocket?.close() } catch (_: Exception) { }; pairingSocket = null }
                runOnUiThread { result.success(mapOf("paired" to true, "device" to pairingDeviceName)) }
            } catch (e: Exception) {
                Log.e("RemoteATV", "Pairing confirmation failed", e)
                cancelPairing()
                val message = friendlyError(e, pairingStage)
                emitPairingEvent("failed", message, pairingDeviceName)
                runOnUiThread { result.error("PAIRING_FAILED", message, null) }
            }
        }.start()
    }

    private fun connectRemote(host: String, port: Int, name: String, result: MethodChannel.Result) {
        Thread {
            try {
                disconnectRemote()
                remoteDeviceName = name
                remoteHost = host
                remotePort = port
                remoteConnectedAtMs = 0L
                remoteTlsHandshakeMs = -1L
                remoteLastInboundAtMs = 0L
                remoteInboundFrameCount = 0L
                remotePingRequestCount = 0L
                remotePingResponseCount = 0L
                remoteVolumeUpdateCount = 0L
                remoteImeUpdateCount = 0L
                remoteStartCount = 0L
                remoteLastDisconnectReason = "連線建立中"
                remoteStage = "TCP 遙控連線"
                emitRemoteEvent("connecting", "正在連接 $name…", name)
                val raw = Socket()
                raw.connect(InetSocketAddress(host, port), 10000)
                raw.soTimeout = 12000
                remoteStage = "檢查手機配對憑證與電視指紋"
                val sslContext = createRemoteSslContext()
                remoteStage = "TLS 遙控安全握手"
                val ssl = sslContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                ssl.enabledProtocols = arrayOf("TLSv1.2")
                ssl.soTimeout = 12000
                val tlsStartedAt = SystemClock.elapsedRealtime()
                ssl.startHandshake()
                remoteTlsHandshakeMs = SystemClock.elapsedRealtime() - tlsStartedAt
                remoteSocket = ssl
                remoteStage = "Remote v2 協定初始化"

                // Remote v2 handshake: TV configure -> client configure -> TV set_active -> client set_active.
                var configureSeen = false
                var activeSeen = false
                var startSeen = false
                var negotiatedFeatures = remoteFeatures
                var handshakeMessages = 0
                while (handshakeMessages < 8) {
                    handshakeMessages++
                    val message = readRemoteFrame(ssl.inputStream)
                    val fields = parseTopLevelFields(message)
                    if (fields.contains(50)) handleVolumeMessage(message)
                    if (fields.contains(40)) handleRemoteStartMessage(message)
                    when {
                        fields.contains(1) -> {
                            configureSeen = true
                            val configure = nestedField(message, 1) ?: ByteArray(0)
                            val supported = firstVarintField(configure, 1)?.toInt() ?: remoteFeatures
                            negotiatedFeatures = remoteFeatures and supported
                            remoteNegotiatedFeatures = negotiatedFeatures
                            if ((negotiatedFeatures and 2) == 0) throw IllegalStateException("這台電視未提供遙控按鍵功能")
                            writeRemoteFrame(ssl.outputStream, remoteConfigureMessage(negotiatedFeatures))
                        }
                        fields.contains(2) -> {
                            activeSeen = true
                            writeRemoteFrame(ssl.outputStream, remoteSetActiveMessage(negotiatedFeatures))
                        }
                        fields.contains(40) -> startSeen = true
                        fields.contains(8) -> {
                            val ping = nestedField(message, 8) ?: ByteArray(0)
                            val value = firstVarintField(ping, 1) ?: 0L
                            writeRemoteFrame(ssl.outputStream, remotePingResponse(value.toInt()))
                        }
                    }
                    if (configureSeen && activeSeen && startSeen) break
                    if (configureSeen && activeSeen && handshakeMessages >= 3) break
                }
                if (!configureSeen || !activeSeen) throw IllegalStateException("電視未完成遙控協定初始化（收到 $handshakeMessages 個初始化訊息；configure=$configureSeen、active=$activeSeen、start=$startSeen）")
                remoteStage = "遙控連線完成"
                remoteConnectedAtMs = SystemClock.elapsedRealtime()
                remoteLastInboundAtMs = remoteConnectedAtMs
                remoteLastDisconnectReason = "尚未斷線"
                ssl.soTimeout = 0
                emitRemoteEvent("connected", "$name 已連線，可以開始遙控", name)
                emitVoiceCapability((negotiatedFeatures and 8) != 0)
                runOnUiThread { result.success(mapOf("connected" to true, "device" to name)) }
                remoteReader = Thread { remoteReadLoop(ssl) }.apply { this.name = "RemoteATV-Reader"; isDaemon = true; start() }
            } catch (e: Exception) {
                Log.e("RemoteATV", "Remote connection failed", e)
                disconnectRemote()
                val message = friendlyError(e, remoteStage)
                remoteLastDisconnectReason = message
                emitRemoteEvent("disconnected", message, name)
                runOnUiThread { result.error("REMOTE_CONNECT_FAILED", message, null) }
            }
        }.start()
    }

    private fun createRemoteSslContext(): SSLContext {
        val alias = "remote_atv_client_identity"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(alias)) throw IllegalStateException("尚未完成配對，請先配對電視")
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)
        val expected = getSharedPreferences("remote_atv", Context.MODE_PRIVATE).getString("server_cert_sha256", null)
            ?: throw IllegalStateException("找不到電視信任憑證，請重新配對")
        val trust = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("電視未提供安全憑證")
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw java.security.cert.CertificateException("電視憑證與配對時不一致，為保護連線已拒絕")
            }
        })
        return SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, trust, SecureRandom()) }
    }

    private fun remoteReadLoop(ssl: SSLSocket) {
        try {
            while (!ssl.isClosed) {
                val msg = readRemoteFrame(ssl.inputStream)
                remoteInboundFrameCount += 1
                remoteLastInboundAtMs = SystemClock.elapsedRealtime()
                val fields = parseTopLevelFields(msg)
                if (fields.contains(8)) {
                    remotePingRequestCount += 1
                    val ping = nestedField(msg, 8) ?: ByteArray(0)
                    val value = firstVarintField(ping, 1) ?: 0L
                    synchronized(remoteLock) { writeRemoteFrame(ssl.outputStream, remotePingResponse(value.toInt())) }
                }
                if (fields.contains(30)) {
                    val voiceBegin = nestedField(msg, 30) ?: ByteArray(0)
                    val sessionId = (firstVarintField(voiceBegin, 1) ?: -1L).toInt()
                    if (sessionId >= 0) {
                        pendingVoiceSessionId = sessionId
                        pendingVoiceLatch?.countDown()
                    }
                }
                if (fields.contains(9)) remotePingResponseCount += 1
                if (fields.contains(40)) { remoteStartCount += 1; handleRemoteStartMessage(msg) }
                if (fields.contains(50)) { remoteVolumeUpdateCount += 1; handleVolumeMessage(msg) }
                if (fields.contains(51)) {
                    // RemoteAdjustVolumeLevel has no defined payload fields in the public
                    // schema. Record its arrival for diagnostics; do not invent semantics.
                    emitVolumeAdjustmentSignal()
                }
                if (fields.contains(21)) {
                    remoteImeUpdateCount += 1
                    val batch = nestedField(msg, 21) ?: ByteArray(0)
                    imeCounter = (firstVarintField(batch, 1) ?: imeCounter.toLong()).toInt()
                    imeFieldCounter = (firstVarintField(batch, 2) ?: imeFieldCounter.toLong()).toInt()
                    // The TV reports the authoritative text and selection in each
                    // RemoteImeBatchEdit.edit_info.text_field_status object.
                    val editInfos = nestedFields(batch, 3)
                    val status = editInfos.asReversed().firstNotNullOfOrNull { info ->
                        nestedField(info, 2)
                    }
                    if (status != null) {
                        val textBytes = nestedField(status, 3) ?: ByteArray(0)
                        val tvText = textBytes.toString(Charsets.UTF_8)
                        val start = (firstVarintField(status, 1) ?: 0L).toInt().coerceAtLeast(0)
                        val end = (firstVarintField(status, 2) ?: start.toLong()).toInt().coerceAtLeast(0)
                        val max = tvText.codePointCount(0, tvText.length)
                        emitImeStatus(true, tvText, start.coerceAtMost(max), end.coerceAtMost(max))
                    }
                }
            }
        } catch (e: Exception) {
            remoteLastDisconnectReason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.i("RemoteATV", "Remote socket closed: ${e.message}")
        } finally {
            synchronized(remoteLock) { if (remoteSocket === ssl) remoteSocket = null }
            emitRemoteEvent("disconnected", "電視遙控連線已中斷：$remoteLastDisconnectReason", remoteDeviceName)
        }
    }

    private fun sendRemoteKey(keyCode: Int, direction: Int) {
        if (keyCode !in 0.. KEYCODE_MAX) throw IllegalArgumentException("按鍵代碼超出有效範圍")
        if (direction !in 1..3) throw IllegalArgumentException("按鍵事件方向無效")
        val ssl = remoteSocket ?: throw IllegalStateException("尚未連接電視，請先連線")
        if (ssl.isClosed || !ssl.isConnected) throw IllegalStateException("遙控連線已中斷，請重新連線")
        val key = ByteArrayOutputStream().apply { write(varintField(1, keyCode)); write(varintField(2, direction)) }.toByteArray()
        val message = lengthDelimited(10, key)
        synchronized(remoteLock) { writeRemoteFrame(ssl.outputStream, message) }
    }

    private fun sendRemoteText(text: String) {
        if (text.isEmpty()) throw IllegalArgumentException("請先輸入文字")
        if (text.length > 1000) throw IllegalArgumentException("單次最多傳送 1000 個字元")
        if ((remoteNegotiatedFeatures and 4) == 0) {
            throw IllegalStateException("這台電視未啟用 IME 文字輸入功能；請先在電視開啟可輸入文字的欄位")
        }
        val ssl = remoteSocket ?: throw IllegalStateException("尚未連接電視，請先連線")
        if (ssl.isClosed || !ssl.isConnected) throw IllegalStateException("遙控連線已中斷，請重新連線")

        // RemoteImeBatchEdit: counters are supplied by the TV through field 21.
        // RemoteEditInfo(insert=1, text_field_status=RemoteImeObject(start,end,value)).
        // RemoteImeObject range is expressed in Unicode code points, not UTF-16 code units.
        // This keeps supplementary characters (for example emoji) from producing an invalid range.
        val finalCodePointIndex = text.codePointCount(0, text.length) - 1
        val imeObject = ByteArrayOutputStream().apply {
            write(varintField(1, finalCodePointIndex))
            write(varintField(2, finalCodePointIndex))
            write(lengthDelimited(3, text.toByteArray(Charsets.UTF_8)))
        }.toByteArray()
        val editInfo = ByteArrayOutputStream().apply {
            write(varintField(1, 1))
            write(lengthDelimited(2, imeObject))
        }.toByteArray()
        val batch = ByteArrayOutputStream().apply {
            write(varintField(1, imeCounter))
            write(varintField(2, imeFieldCounter))
            write(lengthDelimited(3, editInfo))
        }.toByteArray()
        synchronized(remoteLock) { writeRemoteFrame(ssl.outputStream, lengthDelimited(21, batch)) }
    }

    private fun startVoice(result: MethodChannel.Result) {
        Thread {
            try {
                if ((remoteNegotiatedFeatures and 8) == 0) throw IllegalStateException("這台電視未協商語音功能（VOICE）；無法啟動語音輸入")
                val ssl = remoteSocket ?: throw IllegalStateException("尚未連接電視，請先連線")
                if (ssl.isClosed || !ssl.isConnected) throw IllegalStateException("遙控連線已中斷")
                if (activeVoiceSessionId != null) throw IllegalStateException("語音輸入已經啟動")
                pendingVoiceSessionId = null
                val latch = CountDownLatch(1)
                pendingVoiceLatch = latch
                sendRemoteKey(84, 3) // KEYCODE_SEARCH asks the TV to create a voice session.
                if (!latch.await(2, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("電視未在 2 秒內回傳語音工作階段")
                val session = pendingVoiceSessionId ?: throw IllegalStateException("電視未提供有效語音工作階段")
                pendingVoiceLatch = null
                activeVoiceSessionId = session
                // Protocol requires the client to acknowledge RemoteVoiceBegin with the same session id.
                val begin = lengthDelimited(30, varintField(1, session))
                synchronized(remoteLock) { writeRemoteFrame(ssl.outputStream, begin) }
                startAudioCapture(session)
                runOnUiThread { result.success(mapOf("started" to true, "sessionId" to session)) }
                emitVoiceState(true, "語音輸入中")
            } catch (e: Exception) {
                pendingVoiceLatch = null
                stopVoice()
                val message = e.message ?: "無法啟動語音輸入"
                emitVoiceState(false, message)
                runOnUiThread { result.error("VOICE_START_FAILED", message, null) }
            }
        }.start()
    }

    private fun startAudioCapture(sessionId: Int) {
        val sampleRate = 8000
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IllegalStateException("手機不支援 8 kHz 單聲道 PCM 錄音")
        // Read in short, even-sized frames to keep speech latency low while preserving PCM16 alignment.
        val readSize = 4 * 1024
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, readSize * 2))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("無法初始化手機麥克風")
        }
        voiceStopping.set(false)
        voiceRecorder = recorder
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            try { recorder.release() } catch (_: Exception) { }
            voiceRecorder = null
            throw IllegalStateException("手機麥克風啟動失敗：${e.message ?: "未知錯誤"}")
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try { recorder.release() } catch (_: Exception) { }
            voiceRecorder = null
            throw IllegalStateException("手機麥克風未能開始錄音")
        }
        voiceThread = Thread {
            val buffer = ByteArray(readSize)
            try {
                while (!voiceStopping.get() && activeVoiceSessionId == sessionId) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        // PCM 16-bit samples must be sent as complete two-byte samples.
                        val alignedCount = count - (count % 2)
                        if (alignedCount > 0) sendVoicePayload(sessionId, buffer.copyOf(alignedCount))
                    } else if (count < 0 && !voiceStopping.get()) {
                        throw IllegalStateException("麥克風讀取失敗（$count）")
                    }
                }
            } catch (e: Exception) {
                if (!voiceStopping.get()) {
                    Log.e("RemoteATV", "Voice capture failed", e)
                    emitVoiceState(false, "語音錄音中斷：${e.message ?: "未知錯誤"}")
                    stopVoice()
                }
            }
        }.apply { name = "RemoteATV-Voice"; isDaemon = true; start() }
    }

    private fun sendVoicePayload(sessionId: Int, samples: ByteArray) {
        val ssl = remoteSocket ?: throw IllegalStateException("遙控連線已中斷")
        if (ssl.isClosed || !ssl.isConnected) throw IllegalStateException("遙控連線已中斷")
        // Do not pad short reads: zero-padding would transmit fabricated silence and distort speech timing.
        // Every payload is PCM16-aligned and remains below the protocol's 20 KiB limit.
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 20 * 1024, samples.size)
            val alignedEnd = if ((end - offset) % 2 == 0) end else end - 1
            if (alignedEnd <= offset) break
            val chunk = samples.copyOfRange(offset, alignedEnd)
            val voice = ByteArrayOutputStream().apply {
                write(varintField(1, sessionId)); write(lengthDelimited(2, chunk))
            }.toByteArray()
            synchronized(remoteLock) { writeRemoteFrame(ssl.outputStream, lengthDelimited(31, voice)) }
            offset = alignedEnd
        }
    }

    private fun stopVoice() {
        // Stop capture first, then wait briefly for the reader to leave its read/send loop.
        // This preserves ordering: the final audio payload is written before RemoteVoiceEnd.
        voiceStopping.set(true)
        val session = activeVoiceSessionId
        activeVoiceSessionId = null
        val recorder = voiceRecorder
        try { recorder?.stop() } catch (_: Exception) { }
        val worker = voiceThread
        if (worker != null && worker !== Thread.currentThread()) {
            try { worker.join(750) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        try { recorder?.release() } catch (_: Exception) { }
        if (voiceRecorder === recorder) voiceRecorder = null
        if (session != null) {
            val ssl = remoteSocket
            if (ssl != null && !ssl.isClosed) {
                try { synchronized(remoteLock) { writeRemoteFrame(ssl.outputStream, lengthDelimited(32, varintField(1, session))) } }
                catch (e: Exception) { Log.w("RemoteATV", "Unable to send voice end", e) }
            }
        }
        if (voiceThread === worker) voiceThread = null
        emitVoiceState(false, "語音輸入已停止")
    }

    private fun emitVoiceCapability(supported: Boolean) {
        runOnUiThread { eventSink?.success(mapOf("type" to "voice_capability", "supported" to supported)) }
    }
    private fun emitVoiceState(active: Boolean, message: String) {
        runOnUiThread { eventSink?.success(mapOf("type" to "voice", "active" to active, "message" to message)) }
    }

    private fun disconnectRemote() {
        if (remoteSocket != null && remoteLastDisconnectReason == "尚未斷線") {
            remoteLastDisconnectReason = "本機主動中斷或切換連線"
        }
        stopVoice()
        pendingVoiceLatch?.countDown()
        pendingVoiceLatch = null
        pendingVoiceSessionId = null
        synchronized(remoteLock) {
            try { remoteSocket?.close() } catch (_: Exception) { }
            remoteSocket = null
            remoteNegotiatedFeatures = 0
            activeVoiceSessionId = null
            tvVolumeLevel = -1
            tvVolumeMax = -1
            tvVolumeMuted = false
            tvVolumeKnown = false
            imeCounter = 0
            imeFieldCounter = 0
        }
        emitImeStatus(false, "", 0, 0)
        emitVolumeState(false, -1, -1, false)
        emitRemoteServiceState(null)
    }

    private fun remoteConfigureMessage(features: Int): ByteArray {
        val info = ByteArrayOutputStream().apply {
            write(lengthDelimited(4, "1".toByteArray()))
            write(varintField(3, 1))
            write(lengthDelimited(5, "atvremote".toByteArray()))
            write(lengthDelimited(6, "1.7.0".toByteArray()))
        }.toByteArray()
        val config = ByteArrayOutputStream().apply { write(varintField(1, features)); write(lengthDelimited(2, info)) }.toByteArray()
        return lengthDelimited(1, config)
    }
    private fun remoteSetActiveMessage(features: Int): ByteArray = lengthDelimited(2, varintField(1, features))
    private fun remotePingResponse(value: Int): ByteArray = lengthDelimited(9, varintField(1, value))
    private fun writeRemoteFrame(output: java.io.OutputStream, message: ByteArray) {
        val frame = ByteArrayOutputStream().apply { writeVarint(message.size.toLong()); write(message) }.toByteArray()
        output.write(frame); output.flush()
    }
    private fun readRemoteFrame(input: InputStream): ByteArray {
        val size = readVarint(input).toInt()
        if (size <= 0 || size > 1024 * 1024) throw IllegalStateException("電視遙控封包長度異常")
        val bytes = ByteArray(size); var offset = 0
        while (offset < size) { val n = input.read(bytes, offset, size - offset); if (n < 0) throw IllegalStateException("電視已關閉遙控連線"); offset += n }
        return bytes
    }
    private fun parseTopLevelFields(bytes: ByteArray): Set<Int> {
        val fields = mutableSetOf<Int>(); var i = 0
        while (i < bytes.size) {
            val tag = readVarint(bytes, i); i = tag.second
            val field = (tag.first ushr 3).toInt(); val wire = (tag.first and 7).toInt(); fields.add(field)
            when (wire) { 0 -> i = readVarint(bytes, i).second; 1 -> i += 8; 2 -> { val len = readVarint(bytes, i); i = len.second + len.first.toInt() }; 5 -> i += 4; else -> throw IllegalStateException("遙控封包格式錯誤") }
            if (i > bytes.size) throw IllegalStateException("遙控封包資料不完整")
        }
        return fields
    }
    private fun nestedFields(bytes: ByteArray, wanted: Int): List<ByteArray> {
        val found = mutableListOf<ByteArray>()
        var i = 0
        while (i < bytes.size) {
            val tag = readVarint(bytes, i); i = tag.second
            val field = (tag.first ushr 3).toInt(); val wire = (tag.first and 7).toInt()
            when (wire) {
                0 -> i = readVarint(bytes, i).second
                1 -> i += 8
                2 -> {
                    val len = readVarint(bytes, i); i = len.second
                    val end = i + len.first.toInt()
                    if (end > bytes.size) throw IllegalStateException("IME 狀態封包不完整")
                    if (field == wanted) found.add(bytes.copyOfRange(i, end))
                    i = end
                }
                5 -> i += 4
                else -> throw IllegalStateException("IME 狀態封包格式錯誤")
            }
            if (i > bytes.size) throw IllegalStateException("IME 狀態封包長度異常")
        }
        return found
    }
    private fun emitImeStatus(active: Boolean, text: String, start: Int, end: Int) {
        runOnUiThread {
            eventSink?.success(mapOf("type" to "ime_status", "active" to active,
                "text" to text, "start" to start, "end" to end))
        }
    }
    private fun nestedField(bytes: ByteArray, wanted: Int): ByteArray? {
        var i = 0
        while (i < bytes.size) {
            val tag = readVarint(bytes, i); i = tag.second; val field = (tag.first ushr 3).toInt(); val wire = (tag.first and 7).toInt()
            when (wire) { 0 -> i = readVarint(bytes, i).second; 1 -> i += 8; 2 -> { val len = readVarint(bytes, i); i = len.second; val end = i + len.first.toInt(); if (field == wanted) return bytes.copyOfRange(i, end); i = end }; 5 -> i += 4 }
        }
        return null
    }
    private fun firstVarintField(bytes: ByteArray, wanted: Int): Long? {
        var i = 0
        while (i < bytes.size) { val tag = readVarint(bytes, i); i = tag.second; val field = (tag.first ushr 3).toInt(); val wire = (tag.first and 7).toInt(); if (wire == 0) { val v = readVarint(bytes, i); i = v.second; if (field == wanted) return v.first } else if (wire == 2) { val len = readVarint(bytes, i); i = len.second + len.first.toInt() } else if (wire == 1) i += 8 else if (wire == 5) i += 4 }
        return null
    }
    private fun handleRemoteStartMessage(message: ByteArray) {
        // RemoteStart (RemoteMessage field 40): started is field 1.
        // This indicates the remote service state, not guaranteed physical TV power.
        val start = nestedField(message, 40) ?: return
        val started = firstVarintField(start, 1)?.let { it != 0L } ?: return
        emitRemoteServiceState(started)
    }

    private fun emitRemoteServiceState(started: Boolean?) {
        runOnUiThread {
            eventSink?.success(mapOf("type" to "remote_service_state", "known" to (started != null),
                "started" to (started == true)))
        }
    }

    private fun handleVolumeMessage(message: ByteArray) {
        // RemoteSetVolumeLevel is sent by the TV and reports its authoritative
        // volume state: max=field 6, level=field 7, muted=field 8.
        val volume = nestedField(message, 50) ?: return
        val max = (firstVarintField(volume, 6) ?: 0L).toInt().coerceAtLeast(0)
        val level = (firstVarintField(volume, 7) ?: 0L).toInt().coerceAtLeast(0)
        val muted = (firstVarintField(volume, 8) ?: 0L) != 0L
        if (max <= 0) {
            Log.w("RemoteATV", "Ignoring TV volume update with invalid maximum: $max")
            return
        }
        tvVolumeMax = max
        tvVolumeLevel = level.coerceAtMost(max)
        tvVolumeMuted = muted
        tvVolumeKnown = true
        emitVolumeState(true, tvVolumeLevel, tvVolumeMax, tvVolumeMuted)
    }

    private fun emitVolumeState(known: Boolean, level: Int, max: Int, muted: Boolean) {
        runOnUiThread {
            eventSink?.success(mapOf("type" to "volume_state", "known" to known,
                "level" to level, "max" to max, "muted" to muted))
        }
    }
    private fun emitVolumeAdjustmentSignal() {
        runOnUiThread { eventSink?.success(mapOf("type" to "volume_adjustment_signal")) }
    }
    private fun emitRemoteEvent(state: String, message: String, name: String) {
        runOnUiThread { eventSink?.success(mapOf("type" to "remote", "state" to state, "message" to message, "device" to name)) }
    }

    private fun connectionDiagnosticsSnapshot(): Map<String, Any?> {
        val now = SystemClock.elapsedRealtime()
        val connected = remoteSocket?.let { !it.isClosed && it.isConnected } == true
        val uptime = if (connected && remoteConnectedAtMs > 0L) now - remoteConnectedAtMs else 0L
        val lastInboundAge = if (connected && remoteLastInboundAtMs > 0L) now - remoteLastInboundAtMs else -1L
        val features = remoteNegotiatedFeatures
        val featureNames = mutableListOf<String>()
        if ((features and 1) != 0) featureNames.add("PING")
        if ((features and 2) != 0) featureNames.add("KEY")
        if ((features and 4) != 0) featureNames.add("IME")
        if ((features and 8) != 0) featureNames.add("VOICE")
        if ((features and 32) != 0) featureNames.add("POWER")
        if ((features and 64) != 0) featureNames.add("VOLUME")
        if ((features and 512) != 0) featureNames.add("APP_LINK")
        return mapOf(
            "connected" to connected,
            "device" to remoteDeviceName,
            "host" to remoteHost,
            "port" to remotePort,
            "stage" to remoteStage,
            "tlsHandshakeMs" to remoteTlsHandshakeMs,
            "uptimeMs" to uptime,
            "inboundFrames" to remoteInboundFrameCount,
            "pingRequests" to remotePingRequestCount,
            "pingResponses" to remotePingResponseCount,
            "volumeUpdates" to remoteVolumeUpdateCount,
            "imeUpdates" to remoteImeUpdateCount,
            "remoteStartMessages" to remoteStartCount,
            "lastInboundAgeMs" to lastInboundAge,
            "negotiatedFeatures" to featureNames,
            "lastDisconnectReason" to remoteLastDisconnectReason
        )
    }

    private fun createPairingSslContext(): SSLContext {
        val alias = "remote_atv_client_identity"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { }
        })
        return SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, trustAll, SecureRandom()) }
    }

    private fun getClientCertificate(): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getCertificate("remote_atv_client_identity") as? X509Certificate
            ?: throw IllegalStateException("手機安全憑證尚未建立，請重新開始配對")
    }

    private fun pairingRequest(name: String): ByteArray {
        val nested = ByteArrayOutputStream().apply {
            write(lengthDelimited(1, "atvremote".toByteArray(Charsets.UTF_8)))
            write(lengthDelimited(2, name.toByteArray(Charsets.UTF_8)))
        }.toByteArray()
        return nested
    }
    private fun optionsMessage(): ByteArray {
        val encoding = ByteArrayOutputStream().apply { write(varintField(1, 3)); write(varintField(2, 6)) }.toByteArray()
        return ByteArrayOutputStream().apply { write(lengthDelimited(1, encoding)); write(varintField(3, 1)) }.toByteArray()
    }
    private fun configurationMessage(): ByteArray {
        val encoding = ByteArrayOutputStream().apply { write(varintField(1, 3)); write(varintField(2, 6)) }.toByteArray()
        return ByteArrayOutputStream().apply { write(lengthDelimited(1, encoding)); write(varintField(2, 1)) }.toByteArray()
    }
    private fun outerMessage(field: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(varintField(1, 2)); write(varintField(2, 200)); write(lengthDelimited(field, payload))
    }.toByteArray()

    private fun varintField(field: Int, value: Int): ByteArray = ByteArrayOutputStream().apply {
        writeVarint((field shl 3).toLong()); writeVarint(value.toLong())
    }.toByteArray()
    private fun lengthDelimited(field: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeVarint(((field shl 3) or 2).toLong()); writeVarint(payload.size.toLong()); write(payload)
    }.toByteArray()
    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while ((v and -128L) != 0L) { write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        write(v.toInt())
    }
    private fun writeProto(output: java.io.OutputStream, message: ByteArray) {
        val frame = ByteArrayOutputStream().apply { writeVarint(message.size.toLong()); write(message) }.toByteArray()
        output.write(frame); output.flush()
    }
    private fun readProto(input: InputStream): ProtoFields {
        val length = readVarint(input).toInt()
        if (length <= 0 || length > 1024 * 1024) throw IllegalStateException("電視傳回的配對資料長度異常")
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) throw IllegalStateException("電視已中斷配對連線")
            offset += n
        }
        val fields = mutableSetOf<Int>()
        var status = 200
        var i = 0
        while (i < bytes.size) {
            val tag = readVarint(bytes, i); i = tag.second
            val field = (tag.first ushr 3).toInt(); val wire = (tag.first and 7).toInt()
            when (wire) {
                0 -> { val value = readVarint(bytes, i); i = value.second; if (field == 2) status = value.first.toInt() }
                1 -> i += 8
                2 -> { val size = readVarint(bytes, i); i = size.second + size.first.toInt(); fields.add(field) }
                5 -> i += 4
                else -> throw IllegalStateException("無法解析電視配對訊息")
            }
            if (i > bytes.size) throw IllegalStateException("電視配對訊息不完整")
        }
        return ProtoFields(fields, status)
    }
    private data class ProtoFields(val fields: Set<Int>, val status: Int) { fun hasField(field: Int) = fields.contains(field) }
    private fun readVarint(input: InputStream): Long {
        var result = 0L; var shift = 0
        while (shift < 64) { val b = input.read(); if (b < 0) throw IllegalStateException("電視已中斷配對連線"); result = result or ((b and 0x7f).toLong() shl shift); if (b and 0x80 == 0) return result; shift += 7 }
        throw IllegalStateException("配對封包長度格式錯誤")
    }
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var i = start
        while (shift < 64 && i < bytes.size) { val b = bytes[i++].toInt() and 0xff; result = result or ((b and 0x7f).toLong() shl shift); if (b and 0x80 == 0) return Pair(result, i); shift += 7 }
        throw IllegalStateException("配對訊息格式錯誤")
    }
    private fun ensureOk(msg: ProtoFields, fallback: String) {
        if (msg.status != 200) throw SecurityException("$fallback（狀態碼 ${msg.status}）")
    }
    private fun bigIntegerBytes(value: BigInteger): ByteArray {
        var hex = value.toString(16).uppercase()
        if (hex.length % 2 != 0) hex = "0$hex"
        return hexToBytes(hex)
    }
    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    private fun friendlyError(e: Exception, stage: String): String {
        // Keep the complete TLS cause chain: Android providers often put the
        // actionable alert (protocol mismatch, rejected client certificate,
        // no shared cipher, or remote alert) one or two levels below the
        // SSLHandshakeException.
        val causes = generateSequence<Throwable>(e) { current ->
            current.cause?.takeIf { it !== current }
        }.take(5).map { cause ->
            "${cause.javaClass.simpleName}: ${cause.message ?: "無詳細訊息"}"
        }.toList()
        val causeDetail = causes.joinToString(" → ")
        val detail = when (e) {
            is java.net.SocketTimeoutException -> "等待電視回應逾時；請確認電視仍開機、手機與電視位於同一個 Wi-Fi，並檢查路由器是否啟用用戶端隔離。"
            is java.net.ConnectException -> "TCP 連線遭拒絕：$causeDetail"
            is javax.net.ssl.SSLPeerUnverifiedException -> "電視身分驗證失敗：$causeDetail"
            is javax.net.ssl.SSLHandshakeException -> "TLS 握手失敗：$causeDetail"
            is java.security.cert.CertificateException -> "憑證驗證失敗：$causeDetail"
            is SecurityException -> e.message ?: "安全驗證失敗：$causeDetail"
            is IllegalArgumentException -> e.message ?: "輸入資料不正確：$causeDetail"
            else -> causeDetail
        }
        return "失敗階段：$stage。$detail"
    }

    private fun cancelPairing() {
        synchronized(pairingLock) { try { pairingSocket?.close() } catch (_: Exception) { }; pairingSocket = null }
    }
    private fun setPairingStage(stage: String, message: String) {
        pairingStage = stage
        runOnUiThread {
            eventSink?.success(mapOf("type" to "pairing", "state" to "progress",
                "stage" to stage, "message" to message, "device" to pairingDeviceName,
                "host" to pairingHost))
        }
    }

    private fun emitPairingEvent(state: String, message: String, name: String) {
        runOnUiThread { eventSink?.success(mapOf("type" to "pairing", "state" to state, "message" to message, "device" to name, "stage" to pairingStage, "host" to pairingHost)) }
    }

    private fun emitDevices() { runOnUiThread { eventSink?.success(mapOf("type" to "devices", "devices" to devices.values.sortedBy { it["name"].toString() })) } }
    private fun emitStatus(message: String) { runOnUiThread { eventSink?.success(mapOf("type" to "status", "message" to message)) } }
    private fun stopDiscovery() {
        scanning = false
        listeners.toList().forEach { listener -> try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) { } }
        listeners.clear()
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        multicastLock = null
    }
    override fun onDestroy() { cancelPairing(); disconnectRemote(); stopDiscovery(); super.onDestroy() }
}
