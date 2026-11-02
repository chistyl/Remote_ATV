package com.chistyl.tvremote

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import com.chistyl.tvremote.discovery.TvDevice
import com.chistyl.tvremote.discovery.TvDiscoveryManager
import com.chistyl.tvremote.protocol.TvPairingSession
import com.chistyl.tvremote.protocol.RemoteControlClient
import com.chistyl.tvremote.security.TvTlsIdentity

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var topHeader: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var devices: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var textInput: EditText
    private lateinit var pageHost: FrameLayout
    private val io = Executors.newSingleThreadExecutor()
    private var discovery: TvDiscoveryManager? = null
    private var pairingSession: TvPairingSession? = null
    private var remoteClient: RemoteControlClient? = null
    private val seen = linkedSetOf<String>()
    private var selectedHost = ""
    private var selectedName = ""
    private var activePage = PAGE_REMOTE
    private var remoteSubpage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(BG)
        }

        topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topHeader.addView(TextView(this).apply {
            text = "TV Remote"
            textSize = 23f
            setTextColor(WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        topHeader.addView(chipButton("裝置", false) { showPage(PAGE_DEVICES) }, LinearLayout.LayoutParams(dp(76), dp(42)))
        topHeader.addView(chipButton("設定", false) { showPage(PAGE_SETTINGS) }, LinearLayout.LayoutParams(dp(76), dp(42)).apply { leftMargin = dp(8) })
        root.addView(topHeader)

        status = TextView(this).apply {
            text = "尚未連線電視"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(2), dp(3), 0, dp(12))
        }
        root.addView(status)

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(PANEL, 18)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("遙控器" to PAGE_REMOTE, "觸控板" to PAGE_TOUCH, "鍵盤" to PAGE_KEYBOARD, "輸入源" to PAGE_INPUT).forEach { (label, page) ->
            navBar.addView(chipButton(label, page == activePage) { showPage(page) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        root.addView(navBar)

        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showPage(PAGE_REMOTE)
    }

    private fun showPage(page: Int) {
        activePage = page
        if (!::pageHost.isInitialized) return
        val isRemote = page == PAGE_REMOTE
        topHeader.visibility = if (isRemote) View.GONE else View.VISIBLE
        status.visibility = if (isRemote) View.GONE else View.VISIBLE
        navBar.visibility = if (isRemote) View.GONE else View.VISIBLE
        topHeader.setPadding(dp(18), dp(12), dp(18), 0)
        status.setPadding(dp(18), dp(3), dp(18), dp(12))
        navBar.setPadding(dp(4), dp(4), dp(4), dp(4))
        pageHost.setPadding(if (isRemote) dp(12) else dp(18), if (isRemote) dp(8) else dp(4), if (isRemote) dp(12) else dp(18), if (isRemote) dp(8) else 0)
        pageHost.removeAllViews()
        val view = when (page) {
            PAGE_DEVICES -> buildDevicePage()
            PAGE_TOUCH -> buildTouchPage()
            PAGE_KEYBOARD -> buildKeyboardPage()
            PAGE_INPUT -> buildInputPage()
            PAGE_SETTINGS -> buildSettingsPage()
            else -> buildRemotePage()
        }
        pageHost.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun page(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        setPadding(0, 0, 0, dp(12))
        addView(content)
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(2), dp(10))
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(WHITE)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(dp(2), dp(14), dp(2), dp(10))
    }

    private fun buildDevicePage(): View {
        val c = column()
        c.addView(heading("連線裝置"))
        c.addView(TextView(this).apply {
            text = if (selectedHost.isBlank()) "搜尋同一個 Wi-Fi 網路中的 Android TV / Google TV" else "目前選取：$selectedName  ·  $selectedHost"
            textSize = 13f; setTextColor(MUTED); setPadding(dp(2), 0, dp(2), dp(12))
        })
        c.addView(chipButton("搜尋附近電視", true) { startDiscovery() }, LinearLayout.LayoutParams(-1, dp(50)))
        c.addView(heading("搜尋結果"))
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(devices)
        c.addView(heading("手動連線"))
        ipInput = field("電視 IP 位址", selectedHost)
        c.addView(ipInput, fieldParams())
        portInput = field("配對連接埠", "6467").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        c.addView(portInput, fieldParams())
        c.addView(chipButton("測試配對服務連線", false) { testPairingPort() }, buttonParams())
        c.addView(chipButton("開始配對", true) { startTvPairing() }, buttonParams())
        pinInput = field("輸入電視畫面顯示的 6 位配對碼", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        c.addView(pinInput, fieldParams())
        c.addView(chipButton("確認配對碼", true) { finishTvPairing() }, buttonParams())
        c.addView(chipButton("連線遙控", true) { connectRemote() }, buttonParams())
        return page(c)
    }

    private fun buildRemotePage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }

        // Header: settings, selected TV/status, power
        val header = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val settings = remoteCircle("⚙", 0, 58).apply { background = null; setOnClickListener { showPage(PAGE_SETTINGS) } }
        val device = TextView(this).apply {
            text = "●  1  ▾"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            isFocusable = true
            isClickable = true
            setOnClickListener { showPage(PAGE_DEVICES) }
        }
        val power = remoteCircle("⏻", 26, 58, true).apply { background = null; textSize = 34f }
        topHeader.addView(settings, LinearLayout.LayoutParams(0, dp(62), 1f))
        topHeader.addView(device, LinearLayout.LayoutParams(0, dp(62), 1.5f))
        topHeader.addView(power, LinearLayout.LayoutParams(0, dp(62), 1f))
        root.addView(header)

        // Media controls stay in the same place on both remote pages.
        val media = horizontal().apply { gravity = Gravity.CENTER }
        listOf("◀|", "◀◀", "▶Ⅱ", "▶▶", "|▶").forEachIndexed { i, label ->
            val code = listOf(88, 89, 85, 90, 87)[i]
            media.addView(remoteCircle(label, code, 62), LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
        root.addView(media, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(4) })

        val center = FrameLayout(this)
        if (remoteSubpage == 0) {
            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF9A4CCB.toInt(), 0xFF477ED8.toInt())
                ).apply { cornerRadius = dp(500).toFloat() }
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val inner = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(0xFF292929.toInt())
                    cornerRadius = dp(500).toFloat()
                }
            }
            val arrowUp = remoteArrow("⌃", 19)
            val arrowDown = remoteArrow("⌄", 20)
            val arrowLeft = remoteArrow("‹", 21)
            val arrowRight = remoteArrow("›", 22)
            val ok = TextView(this).apply {
                text = ""
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF9650C5.toInt(), 0xFF4385D9.toInt())
                ).apply { shape = GradientDrawable.OVAL }
                setOnClickListener { sendKey(23) }
            }
            val okInner = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(0xFF292929.toInt())
                    shape = GradientDrawable.OVAL
                }
            }
            val okWrap = FrameLayout(this).apply {
                addView(okInner, FrameLayout.LayoutParams(-1, -1).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
                addView(ok, FrameLayout.LayoutParams(-1, -1))
                setOnClickListener { sendKey(23) }
            }
            inner.addView(arrowUp, FrameLayout.LayoutParams(dp(76), dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(34) })
            inner.addView(arrowDown, FrameLayout.LayoutParams(dp(76), dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(34) })
            inner.addView(arrowLeft, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = dp(28) })
            inner.addView(arrowRight, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = dp(28) })
            inner.addView(okWrap, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
            outer.addView(inner, LinearLayout.LayoutParams(-1, -1))
            center.addView(outer, FrameLayout.LayoutParams(-1, -1))
        } else {
            val grid = android.widget.GridLayout(this).apply {
                columnCount = 3
                rowCount = 4
                alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
                useDefaultMargins = false
                setPadding(dp(28), dp(8), dp(28), dp(8))
            }
            val labels = listOf("1","2","3","4","5","6","7","8","9","TV","0","Menu")
            val codes = listOf(8,9,10,11,12,13,14,15,16,170,7,82)
            labels.forEachIndexed { i, label ->
                val key = remoteNumberKey(label, codes[i])
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = android.widget.GridLayout.spec(i % 3, 1f)
                    rowSpec = android.widget.GridLayout.spec(i / 3, 1f)
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                grid.addView(key, lp)
            }
            center.addView(grid, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(center, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(14); bottomMargin = dp(8)
        })

        val dots = horizontal().apply { gravity = Gravity.CENTER }
        repeat(2) { index ->
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (remoteSubpage == index) WHITE else 0xFF555555.toInt())
                    shape = GradientDrawable.OVAL
                }
                isClickable = true
                setOnClickListener { remoteSubpage = index; showPage(PAGE_REMOTE) }
            }
            dots.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                leftMargin = dp(7); rightMargin = dp(7); topMargin = dp(2); bottomMargin = dp(8)
            })
        }
        root.addView(dots)

        // Fixed bottom controls: volume/channel rails and six quick actions.
        val bottom = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val volumeRail = remoteRail("+", "VOL", "−", 24, 25)
        bottom.addView(volumeRail, LinearLayout.LayoutParams(dp(70), dp(230)))
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val row1 = horizontal().apply { gravity = Gravity.CENTER }
        row1.addView(remoteCircle("▦", 0, 62).apply { setOnClickListener { showPage(PAGE_KEYBOARD) } }, weightedCircle())
        row1.addView(remoteCircle("⌂", 3, 62), weightedCircle())
        row1.addView(remoteCircle(if (remoteSubpage == 0) "⠿" else "✥", 0, 62).apply {
            setOnClickListener { remoteSubpage = 1 - remoteSubpage; showPage(PAGE_REMOTE) }
        }, weightedCircle())
        val row2 = horizontal().apply { gravity = Gravity.CENTER }
        row2.addView(remoteCircle("◁))", 164, 62), weightedCircle())
        row2.addView(remoteCircle("🎙", 219, 62), weightedCircle())
        row2.addView(remoteCircle("↶", 4, 62), weightedCircle())
        quick.addView(row1)
        quick.addView(row2)
        bottom.addView(quick, LinearLayout.LayoutParams(0, dp(230), 1f))
        bottom.addView(remoteRail("⌃", "CH", "⌄", 166, 167), LinearLayout.LayoutParams(dp(70), dp(230)))
        root.addView(bottom)
        return root
    }

    private fun weightedCircle() = LinearLayout.LayoutParams(0, dp(78), 1f).apply {
        leftMargin = dp(3); rightMargin = dp(3); topMargin = dp(3); bottomMargin = dp(3)
    }

    private fun remoteCircle(label: String, keyCode: Int, size: Int, red: Boolean = false): TextView {
        val v = TextView(this).apply {
            text = label
            textSize = if (label.length > 2) 14f else 25f
            gravity = Gravity.CENTER
            setTextColor(if (red) 0xFFFF514B.toInt() else WHITE)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0xFF303033.toInt())
                shape = GradientDrawable.OVAL
            }
            if (keyCode != 0) setOnTouchListener { view, event ->
                val client = remoteClient
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (client == null) toast("請先連線電視")
                        else {
                            view.isPressed = true
                            runCatching { client.startLongPress(keyCode) }.onFailure { showError("按鍵傳送失敗", it) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        if (client != null) runCatching { client.endLongPress(keyCode) }.onFailure { showError("按鍵傳送失敗", it) }
                        true
                    }
                    else -> true
                }
            }
        }
        return v
    }

    private fun remoteArrow(label: String, keyCode: Int): TextView =
        remoteCircle(label, keyCode, 64).apply {
            textSize = 42f
            background = null
        }

    private fun remoteNumberKey(label: String, keyCode: Int): View =
        TextView(this).apply {
            text = label
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener { sendKey(keyCode) }
        }

    private fun remoteRail(top: String, title: String, bottom: String, topKey: Int, bottomKey: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = dp(40).toFloat()
            }
            addView(remoteCircle(top, topKey, 44).apply { background = null; textSize = 28f }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(WHITE)
            }, LinearLayout.LayoutParams(-1, dp(38)))
            addView(remoteCircle(bottom, bottomKey, 44).apply { background = null; textSize = 28f }, LinearLayout.LayoutParams(-1, 0, 1f))
        }

    private fun buildTouchPage(): View {
        val c = column()
        c.addView(heading("觸控板"))
        c.addView(TextView(this).apply {
            text = "滑動移動焦點，點一下確認；此協定以方向鍵模擬滑動，不是絕對座標滑鼠"
            textSize = 12f; setTextColor(MUTED)
        })
        c.addView(RemoteTouchpadView(this) { keyCode ->
            val client = remoteClient
            if (client == null) toast("請先連線電視")
            else runCatching { client.sendKey(keyCode) }.onFailure { showError("觸控操作失敗", it) }
        }, LinearLayout.LayoutParams(-1, dp(310)).apply { topMargin = dp(16) })
        c.addView(TextView(this).apply {
            text = "點擊：確認　雙指／座標滑鼠：參考協定不支援"
            textSize = 12f; setTextColor(MUTED); setPadding(0, dp(10), 0, 0)
        })
        return page(c)
    }

    private fun buildKeyboardPage(): View {
        val c = column()
        c.addView(heading("鍵盤與文字輸入"))
        c.addView(TextView(this).apply {
            text = "先在電視上選取文字輸入欄位，再於此輸入並傳送"
            textSize = 13f; setTextColor(MUTED)
        })
        textInput = field("輸入要傳送到電視的文字", "")
        textInput.minLines = 3
        textInput.maxLines = 5
        textInput.isSingleLine = false
        c.addView(textInput, LinearLayout.LayoutParams(-1, dp(112)).apply { topMargin = dp(16) })
        c.addView(chipButton("傳送文字到電視", true) { sendTextToTv() }, buttonParams())
        c.addView(chipButton("開啟手機鍵盤", false) {
            textInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, buttonParams())
        return page(c)
    }

    private fun buildInputPage(): View {
        val c = column()
        c.addView(heading("電視輸入源"))
        c.addView(TextView(this).apply {
            text = "使用電視支援的輸入源切換按鍵；HDMI 選項依電視韌體顯示"
            textSize = 13f; setTextColor(MUTED)
        })
        c.addView(chipButton("輸入源／HDMI", true) { sendKey(178) }, buttonParams())
        c.addView(chipButton("電視／直播", false) { sendKey(170) }, buttonParams())
        c.addView(heading("頻道"))
        val row = horizontal()
        row.addView(remoteKey("頻道－", 166), weighted())
        row.addView(remoteKey("頻道＋", 167), weighted())
        c.addView(row)
        return page(c)
    }

    private fun buildSettingsPage(): View {
        val c = column()
        c.addView(heading("設定"))
        c.addView(settingLine("連線狀態", if (remoteClient == null) "尚未連線" else "已連線"))
        c.addView(settingLine("目前電視", if (selectedName.isBlank()) selectedHost.ifBlank { "尚未選取" } else selectedName))
        c.addView(chipButton("選擇／配對電視", false) { showPage(PAGE_DEVICES) }, buttonParams())
        c.addView(chipButton("重新連線", true) { connectRemote() }, buttonParams())
        c.addView(chipButton("中斷連線", false) {
            remoteClient?.close()
            remoteClient = null
            status.text = "已中斷遙控連線"
            showPage(PAGE_SETTINGS)
        }, buttonParams())
        c.addView(heading("操作說明"))
        c.addView(settingLine("遙控器", "方向鍵、確認、返回、首頁、選單、電源"))
        c.addView(settingLine("觸控板", "滑動轉換為方向鍵；點擊為確認"))
        c.addView(settingLine("鍵盤", "需先在電視端聚焦文字輸入欄"))
        c.addView(settingLine("輸入源", "透過電視支援的 Input 按鍵切換"))
        return page(c)
    }

    private fun sendTextToTv() {
        val client = remoteClient
        val value = textInput.text.toString()
        if (client == null) toast("請先連線電視")
        else if (!client.canSendText) toast("請先在電視端開啟並選取文字輸入欄位")
        else runCatching { client.sendText(value) }
            .onSuccess { textInput.text.clear(); status.text = "文字已送出" }
            .onFailure { showError("文字傳送失敗", it) }
    }

    private fun settingLine(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 14f; setTextColor(WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = value; textSize = 12f; setTextColor(MUTED); gravity = Gravity.END
            })
        }.also { /* caller adds spacing through the parent layout */ }

    private fun remoteKey(label: String, keyCode: Int, dpad: Boolean = false, primary: Boolean = false): View {
        val v = TextView(this).apply {
            text = label
            textSize = if (dpad) 19f else 12f
            setTextColor(if (primary) Color.WHITE else WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = rounded(if (primary) ACCENT else PANEL_2, if (dpad) 24 else 16)
            setPadding(dp(5), dp(8), dp(5), dp(8))
            contentDescription = label
            setOnTouchListener { view, event ->
                val client = remoteClient
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (client == null) {
                            toast("請先連線電視")
                        } else {
                            view.isPressed = true
                            runCatching { client.startLongPress(keyCode) }.onFailure { showError("按鍵傳送失敗", it) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        if (client != null) runCatching { client.endLongPress(keyCode) }.onFailure { showError("按鍵傳送失敗", it) }
                        true
                    }
                    else -> true
                }
            }
        }
        v.setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) FOCUS else if (primary) ACCENT else PANEL_2, if (dpad) 24 else 16)
        }
        return v
    }

    private fun startDiscovery() {
        if (!::devices.isInitialized) return
        seen.clear()
        devices.removeAllViews()
        discovery?.stop()
        status.text = "正在搜尋 Android TV／Google TV…"
        discovery = TvDiscoveryManager(this, ::addDevice) { message -> runOnUiThread { status.text = message } }
        discovery?.start()
    }

    private fun addDevice(device: TvDevice) = runOnUiThread {
        val key = "${device.name}|${device.endpoint}"
        if (!seen.add(key)) return@runOnUiThread
        status.text = "找到 ${seen.size} 台裝置"
        val card = TextView(this).apply {
            text = "${device.name}\n${device.endpoint}"
            textSize = 14f
            setTextColor(WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(PANEL, 14)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                selectedHost = device.host?.hostAddress.orEmpty()
                selectedName = device.name
                ipInput.setText(selectedHost)
                portInput.setText((device.port ?: 6467).toString())
                status.text = "已選取 ${device.name}"
                showPage(PAGE_DEVICES)
            }
        }
        devices.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun testPairingPort() {
        val host = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) { toast("請輸入正確的 IP 與連接埠"); return }
        status.text = "正在測試 $host:$port…"
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
        if (host.isBlank() || port == null || port !in 1..65535) { toast("請輸入正確的電視 IP 與配對連接埠"); return }
        selectedHost = host
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
        if (session == null) { toast("請先開始電視配對"); return }
        val code = pinInput.text.toString().trim()
        if (code.length != 6) { toast("請輸入 6 位配對碼"); return }
        status.text = "正在驗證配對碼…"
        io.execute {
            val result = runCatching { session.finishPairing(code) }
            runOnUiThread {
                status.text = result.getOrElse { "配對失敗：${it.localizedMessage ?: it.javaClass.simpleName}" }
                pairingSession = null
                if (result.isSuccess) pinInput.text.clear()
            }
        }
    }

    private fun connectRemote() {
        val host = ipInputOrSelected()
        if (host.isBlank()) { toast("請先選取電視或輸入 IP"); showPage(PAGE_DEVICES); return }
        val tlsIdentity = TvTlsIdentity(this)
        if (!tlsIdentity.isPaired(host)) { toast("此電視尚未完成配對，請先配對"); showPage(PAGE_DEVICES); return }
        remoteClient?.close()
        val client = RemoteControlClient(tlsIdentity, host) { message -> runOnUiThread { status.text = message } }
        remoteClient = client
        status.text = "正在連線電視遙控通道…"
        io.execute {
            val result = runCatching { client.connect() }
            runOnUiThread {
                status.text = if (result.isSuccess) "已連線：${selectedName.ifBlank { host }}" else "遙控連線失敗：${result.exceptionOrNull()?.localizedMessage ?: "未知錯誤"}"
                if (result.isFailure) remoteClient = null
            }
        }
    }

    private fun ipInputOrSelected(): String =
        if (::ipInput.isInitialized && ipInput.text.toString().isNotBlank()) ipInput.text.toString().trim() else selectedHost

    private fun sendKey(code: Int) {
        val client = remoteClient
        if (client == null) toast("請先連線電視")
        else runCatching { client.sendKey(code) }.onFailure { showError("按鍵傳送失敗", it) }
    }

    private fun field(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine(true)
        textSize = 14f
        setTextColor(WHITE)
        setHintTextColor(MUTED)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(PANEL_2, 12)
    }

    private fun chipButton(label: String, primary: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else WHITE)
            isFocusable = true
            isClickable = true
            background = rounded(if (primary) ACCENT else PANEL_2, 14)
            setOnClickListener { action() }
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) FOCUS else if (primary) ACCENT else PANEL_2, 14)
            }
        }

    private fun horizontal(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }

    private fun fieldParams() = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) }
    private fun buttonParams() = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun showError(prefix: String, error: Throwable) {
        runOnUiThread { status.text = "$prefix：${error.localizedMessage ?: error.javaClass.simpleName}" }
    }

    override fun onDestroy() {
        discovery?.stop()
        pairingSession?.close()
        remoteClient?.close()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val PAGE_REMOTE = 0
        private const val PAGE_TOUCH = 1
        private const val PAGE_KEYBOARD = 2
        private const val PAGE_INPUT = 3
        private const val PAGE_DEVICES = 4
        private const val PAGE_SETTINGS = 5
        private const val BG = 0xFF10131B.toInt()
        private const val PANEL = 0xFF1B202B.toInt()
        private const val PANEL_2 = 0xFF272E3B.toInt()
        private const val ACCENT = 0xFF2878D0.toInt()
        private const val FOCUS = 0xFF4A9BFF.toInt()
        private const val WHITE = 0xFFF4F6FA.toInt()
        private const val MUTED = 0xFF9BA6B8.toInt()
    }
}
