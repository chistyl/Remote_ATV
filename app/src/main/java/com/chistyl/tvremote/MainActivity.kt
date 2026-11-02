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

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private val io = Executors.newSingleThreadExecutor()
    private var discovery: TvDiscoveryManager? = null
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
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devices)
        root.addView(TextView(this).apply {
            text = "目前版本先完成裝置探索。配對、TLS 與遙控指令尚未接通。"
            textSize = 12f; setTextColor(0xFF778196.toInt()); gravity = Gravity.BOTTOM
            setPadding(0, 28, 0, 0)
        })
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
        discovery?.stop(); io.shutdownNow(); super.onDestroy()
    }
}
