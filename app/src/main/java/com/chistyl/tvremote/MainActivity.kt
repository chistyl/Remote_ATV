package com.chistyl.tvremote

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import android.widget.LinearLayout
import android.widget.TextView
import com.chistyl.tvremote.discovery.TvDevice
import com.chistyl.tvremote.discovery.TvDiscoveryManager
import com.chistyl.tvremote.protocol.TvPairingSession
import com.chistyl.tvremote.protocol.RemoteControlClient
import com.chistyl.tvremote.security.TvTlsIdentity

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private val io = Executors.newSingleThreadExecutor()
    private var discovery: TvDiscoveryManager? = null
    private var pairingSession: TvPairingSession? = null
    private var remoteClient: RemoteControlClient? = null
    private lateinit var pinInput: EditText
    private val seen = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090B10.toInt()
        window.navigationBarColor = 0xFF090B10.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFF090B10.toInt())
        }
        root.addView(TextView(this).apply {
            text = "TV Remote"; textSize = 28f; setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Android TV 遙控器 · 開發預覽"; textSize = 14f
            setTextColor(0xFF9BA4B5.toInt()); setPadding(0, 6, 0, 22)
        })
        status = TextView(this).apply {
            text = "尚未搜尋裝置"; textSize = 14f
            setTextColor(0xFF72A7FF.toInt()); setPadding(0, 8, 0, 14)
        }
        root.addView(status)
        root.addView(Button(this).apply { text = "搜尋 Android TV"; setOnClickListener { startDiscovery() } })
        root.addView(TextView(this).apply {
            text = "手動輸入電視 IP（mDNS 搜尋不到時使用）"; textSize = 14f
            setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 24, 0, 8)
        })
        ipInput = EditText(this).apply {
            hint = "例如 192.168.1.100"; singleLine = true
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF778196.toInt())
            setPadding(16, 8, 16, 8); setBackgroundColor(0xFF171C26.toInt())
        }
        root.addView(ipInput, LinearLayout.LayoutParams(-1, -2))
        portInput = EditText(this).apply {
            hint = "配對連接埠（預設 6467）"; setText("6467"); singleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF778196.toInt())
            setPadding(16, 8, 16, 8); setBackgroundColor(0xFF171C26.toInt())
        }
        root.addView(portInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        root.addView(Button(this).apply { text = "測試配對服務連線"; setOnClickListener { testPairingPort() } })
        root.addView(Button(this).apply { text = "開始電視配對"; setOnClickListener { startTvPairing() } })
        pinInput = EditText(this).apply {
            hint = "輸入電視顯示的 6 位配對碼"
            singleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF778196.toInt())
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFF171C26.toInt())
        }
        root.addView(pinInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        root.addView(Button(this).apply { text = "確認配對碼"; setOnClickListener { finishTvPairing() } })
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devices)
        root.addView(TextView(this).apply {
            text = "已配對後可連線遙控。方向鍵、返回、首頁、音量與播放控制。"
            textSize = 12f; setTextColor(0xFF778196.toInt()); gravity = Gravity.BOTTOM
            setPadding(0, 28, 0, 0)
        })
        root.addView(Button(this).apply {
            text = "連線遙控（TLS 6466）"
            setOnClickListener { connectRemote() }
        })
        root.addView(buildRemotePad())
        setContentView(root)
    }

    private fun startDiscovery() {
        seen.clear(); devices.removeAllViews(); discovery?.stop()
        discovery = TvDiscoveryManager(this, ::addDevice) { message -> runOnUiThread { status.text = message } }
        discovery?.start()
    }

    private fun testPairingPort() {
        val host = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            Toast.makeText(this, "請輸入正確的 IP 與連接埠", Toast.LENGTH_SHORT).show(); return
        }
        status.text = "正在測試 $host:$port …"
        io.execute {
            val result = runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
                "TCP 連線成功：$host:$port（尚未完成 TLS／配對）"
            }.getOrElse { "TCP 連線失敗：${it.localizedMessage ?: it.javaClass.simpleName}" }
            runOnUiThread { status.text = result }
        }
    }

    private fun startTvPairing() {
        val host = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            Toast.makeText(this, "請輸入正確的電視 IP 與配對連接埠", Toast.LENGTH_SHORT).show()
            return
        }
        pairingSession?.close()
        val session = TvPairingSession(TvTlsIdentity(this), host, port)
        pairingSession = session
        status.text = "正在建立 TLS 配對連線…"
        io.execute {
            val result = runCatching { session.startPairing() }
            runOnUiThread {
                status.text = result.getOrElse {
                    pairingSession = null
                    "配對初始化失敗：${it.localizedMessage ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    private fun finishTvPairing() {
        val session = pairingSession
        if (session == null) {
            Toast.makeText(this, "請先按「開始電視配對」", Toast.LENGTH_SHORT).show()
            return
        }
        val code = pinInput.text.toString().trim()
        if (code.length != 6) {
            Toast.makeText(this, "請輸入 6 位配對碼", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "正在驗證配對碼…"
        io.execute {
            val result = runCatching { session.finishPairing(code) }
            runOnUiThread {
                status.text = result.getOrElse {
                    "配對失敗：${it.localizedMessage ?: it.javaClass.simpleName}"
                }
                pairingSession = null
                if (result.isSuccess) pinInput.text.clear()
            }
        }
    }
    private fun connectRemote() {
        val host = ipInput.text.toString().trim()
        if (host.isBlank()) {
            Toast.makeText(this, "請先選取電視或輸入 IP", Toast.LENGTH_SHORT).show()
            return
        }
        val tlsIdentity = TvTlsIdentity(this)
        if (!tlsIdentity.isPaired(host)) {
            Toast.makeText(this, "此電視尚未完成配對，請先輸入 PIN 配對", Toast.LENGTH_SHORT).show()
            return
        }
        remoteClient?.close()
        val client = RemoteControlClient(tlsIdentity, host) { message ->
            runOnUiThread { status.text = message }
        }
        remoteClient = client
        status.text = "正在連線電視遙控通道…"
        io.execute {
            val result = runCatching { client.connect() }
            runOnUiThread {
                status.text = if (result.isSuccess) "遙控器已連線" else "遙控連線失敗：" + (result.exceptionOrNull()?.localizedMessage ?: "未知錯誤")
                if (result.isFailure) remoteClient = null
            }
        }
    }

    private fun buildRemotePad(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        fun addRow(vararg keys: Pair<String, Int>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            keys.forEach { (label, code) ->
                row.addView(Button(this).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener {
                        val client = remoteClient
                        if (client == null) Toast.makeText(this@MainActivity, "請先連線遙控", Toast.LENGTH_SHORT).show()
                        else runCatching { client.sendKey(code) }.onFailure { status.text = "指令失敗：" + it.localizedMessage }
                    }
                }, LinearLayout.LayoutParams(0, 54, 1f).apply { setMargins(4, 4, 4, 4) })
            }
            panel.addView(row)
        }
        addRow("返回" to 4, "▲" to 19, "首頁" to 3)
        addRow("◀" to 21, "確認" to 23, "▶" to 22)
        addRow("音量－" to 25, "▼" to 20, "音量＋" to 24)
        addRow("靜音" to 164, "播放／暫停" to 85, "電源" to 26)
        return panel
    }

    private fun addDevice(device: TvDevice) = runOnUiThread {
        val key = "${device.name}|${device.endpoint}"
        if (!seen.add(key)) return@runOnUiThread
        status.text = "找到 ${seen.size} 台裝置"
        devices.addView(TextView(this).apply {
            text = "${device.name}\n${device.endpoint}\n${device.serviceType}"
            textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setPadding(16, 18, 16, 18)
            setBackgroundColor(0xFF171C26.toInt()); isClickable = true
            setOnClickListener {
                ipInput.setText(device.host?.hostAddress.orEmpty())
                portInput.setText((device.port ?: 6467).toString())
                status.text = "已選取 ${device.name}；可測試配對服務 TCP 連線"
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
    }

    override fun onDestroy() {
        discovery?.stop(); pairingSession?.close(); remoteClient?.close(); io.shutdownNow(); super.onDestroy()
    }
}
