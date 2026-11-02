# TV Remote — Android TV Remote rebuild

以使用者提供的 Remote_ATV_5.0.5 APK 作為功能參考，重新建立 Android TV / Google TV 遙控器。

## 開發基準
- GitHub 為唯一主要版本管理與交付來源。
- 開發分支：`dev/tvremote-rebuild-0.2.0`
- 目前版本：0.5.0-dev
- Android 原生 Kotlin 專案。
- 依公開 Android TV Remote 協定重新實作，不直接複製原 App 的品牌素材或專有程式碼。

## 已建立的功能程式
- Android NSD/mDNS 搜尋 `_androidtvremote2._tcp.` 與 `_androidtvremote._tcp.`
- Wi-Fi Multicast Lock、手動輸入電視 IP、TCP 診斷
- Android TV Remote v2 與 Google Polo pairing protobuf schema
- Protobuf varint 長度前綴 framing 與邊界測試
- 六位十六進位配對碼驗證、RSA pairing secret 計算
- PairingRequest → Options → Configuration → ConfigurationAck
- Secret / SecretAck 配對流程
- Android Keystore RSA 2048 用戶端身分與憑證保存
- PIN 驗證成功後保存 TV 憑證 SHA-256 pin；後續 TLS 連線檢查憑證
- TLS 6466 遙控連線與 RemoteConfigure / RemoteSetActive / RemoteStart 初始化
- 遙控訊息 framing、Ping 回應
- 方向鍵、確認、返回、首頁、音量、靜音、播放暫停與電源按鍵 UI

## 尚待驗證／後續開發
- GitHub Actions APK 建置結果與安裝驗證
- TLS 雙向憑證及 PIN 配對的真機相容性驗證
- 6466 遙控通道真機按鍵回應測試
- Touchpad、Live Text Input、語音、多語系、裝置管理、自動重連
- HDMI 切換（需確認目標電視廠牌及系統是否提供可用控制介面）

> 注意：配對與遙控核心已接入程式碼，但尚未經真實 Android TV 裝置驗證。未驗證功能不代表已可正常使用。

## 建置
需求：Android Studio、JDK 17、Android SDK 35。開啟專案根目錄後執行 Gradle Sync，再建置 `:app:assembleDebug`。Protobuf Lite 類別由 Gradle Plugin 自動產生。

## 測試注意
手機與 Android TV 必須位於可互通的同一區域網路。若 mDNS 搜尋不到，檢查 AP/client isolation、路由器 multicast 設定及 TV Remote Service 狀態。配對使用 TCP 6467，遙控通道使用 TCP 6466。

## 版本規則
- 每個階段都在 GitHub 建立 commit。
- 完成一個可驗證里程碑後才提升版本號。
- 未經實機測試的功能不得標示為「已完成」。
- APK 與原始碼以 GitHub Releases / Actions artifacts 作為正式交付管道。

## Third-party notices
請參閱 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
