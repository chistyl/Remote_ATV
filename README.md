# TV Remote — Android TV Remote rebuild

以使用者提供的 Remote_ATV_5.0.5 APK 作為功能參考，重新建立 Android TV / Google TV 遙控器。

## 開發基準
- GitHub 為唯一主要版本管理與交付來源。
- 開發分支：`dev/tvremote-rebuild-0.2.0`
- 目前版本：0.2.0-dev
- Android 原生 Kotlin 專案。
- 依公開 Android TV Remote 協定重新實作，不直接複製原 App 的品牌素材或專有程式碼。

## 目前已建立
- Android 原生專案結構
- Android NSD/mDNS 搜尋 `_androidtvremote2._tcp.` 與 `_androidtvremote._tcp.`
- Wi-Fi Multicast Lock
- 手動輸入電視 IP / 配對連接埠
- TCP 連線診斷（僅代表 TCP 可達，不代表 TLS 或配對成功）
- 深色基礎介面

## 尚未完成
- Android TV Remote protobuf schema 與封包序列化
- PairingRequest / PairingConfiguration / PairingSecret
- TLS 憑證建立、保存及配對信任
- RemoteKeyInject、音量、媒體、HDMI 指令
- Touchpad、Live Text Input、語音
- 多語系、裝置管理及實機測試

## 建置
需求：Android Studio、JDK 17、Android SDK 35。開啟專案根目錄後執行 Gradle Sync，再建置 `:app:assembleDebug`。

## 測試注意
手機與 Android TV 必須位於可互通的同一區域網路。若 mDNS 搜尋不到，先檢查 AP/client isolation、路由器 multicast 設定及 TV Remote Service 狀態。可手動輸入 IP 進行 TCP 診斷。

## 版本規則
- 每個階段都在 GitHub 建立 commit。
- 完成一個可驗證里程碑後才提升版本號。
- 未經實機測試的功能不得標示為「已完成」。
- APK 與原始碼以 GitHub Releases / Actions artifacts 作為正式交付管道。
