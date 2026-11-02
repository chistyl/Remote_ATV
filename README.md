# TV Remote — Android TV Remote rebuild

以使用者提供的 Remote_ATV_5.0.5 APK 作為功能參考，重新建立 Android TV / Google TV 遙控器。

## 開發基準
- GitHub 為唯一主要版本管理與交付來源。
- 開發分支：`dev/tvremote-rebuild-0.2.0`
- 目前版本：0.4.0-dev
- Android 原生 Kotlin 專案。
- 依公開 Android TV Remote 協定重新實作，不直接複製原 App 的品牌素材或專有程式碼。

## 目前已建立
- Android 原生專案結構
- Android NSD/mDNS 搜尋 `_androidtvremote2._tcp.` 與 `_androidtvremote._tcp.`
- Wi-Fi Multicast Lock
- 手動輸入電視 IP / 配對連接埠
- TCP 連線診斷（僅代表 TCP 可達，不代表 TLS 或配對成功）
- 深色基礎介面

## 0.3.0-dev protocol foundation

## 0.4.0-dev TLS pairing integration
- Android TV Remote v2 與 Polo protobuf schema，透過 Gradle Protobuf Lite 自動產生 Java 類別
- Protobuf varint 長度前綴 framing 與邊界測試
- 六位十六進位配對碼格式驗證
- RSA 公鑰摘要與 pairing secret 計算、檢查碼驗證
- PairingRequest → Options → Configuration → ConfigurationAck 配對初始化狀態機
- Secret / SecretAck 驗證流程（由已配置雙向 TLS 的 SSLSocket 提供連線）

## 尚未完成
- Android Keystore RSA 2048 用戶端身分與憑證保存
- 配對 TLS Socket、PIN 驗證後保存 TV 憑證 SHA-256 pin
- 已配對裝置的憑證 pin 驗證 TLS Socket 工廠
- 手機畫面已串接「開始配對 → 輸入 6 位 PIN → 確認」流程
- 配對完成後 TLS 6466 遙控通道與 RemoteConfigure / Ping / KeyInject

> TLS 配對與憑證 pinning 已加入程式碼，但尚未經真實 Android TV 裝置驗證；不可視為已確認可配對。
- RemoteKeyInject、音量、媒體、HDMI 指令
- Touchpad、Live Text Input、語音
- 多語系、裝置管理及實機測試

## 建置
需求：Android Studio、JDK 17、Android SDK 35。開啟專案根目錄後執行 Gradle Sync，再建置 `:app:assembleDebug`。Protobuf Lite 類別由 Gradle Plugin 自動產生。

## 測試注意
手機與 Android TV 必須位於可互通的同一區域網路。若 mDNS 搜尋不到，先檢查 AP/client isolation、路由器 multicast 設定及 TV Remote Service 狀態。可手動輸入 IP 進行 TCP 診斷。

## 版本規則
- 每個階段都在 GitHub 建立 commit。
- 完成一個可驗證里程碑後才提升版本號。
- 未經實機測試的功能不得標示為「已完成」。
- APK 與原始碼以 GitHub Releases / Actions artifacts 作為正式交付管道。
