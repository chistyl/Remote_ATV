# Remote ATV V5.0

## 版本 5.0.6（Build 52）

- 版本統一為 `5.0.6+52`（Android `versionCode 52`、`versionName 5.0.6`）。

以 Remote ATV 5.0.5 APK 為功能與介面參考，重新建置的 Flutter + Android Kotlin Android TV 遙控器版本。

## V5.0.6（Build 52）

- Android applicationId 對齊原始 5.0.5：`tech.simha.androidtvremote`。
- 因重新簽署，安裝前需先解除安裝舊版 5.0.5；舊版私有資料與配對紀錄可能無法保留。
- 第一頁遙控器、第二頁設定、第三頁觸控板。
- 保留 Android TV Remote v2 配對、TLS、按鍵、IME 文字輸入、語音及重連功能。
- 觸控板支援雙軸方向滑動、點擊確認、長按確認及返回／首頁／選單快捷操作。
- Android TV Remote v2 傳輸層以 KEY 事件控制焦點；不是絕對座標滑鼠游標。
- 預設導航為 D-pad 方向鍵，保留既有 swipe 操作偏好。
- 快速存取預設為 None。
- 已移除分享、評分、意見回饋及 Premium 推廣選單項目。

## 建置與驗證

CI 使用 Flutter 3.27.4、Java 17，執行版本一致性檢查、Gradle Wrapper 準備、套件解析、Dart 分析、APK 編譯與 APK 完整性檢查。

CI 成功不代表實機已驗收；配對、TLS、方向操作、觸控板、長按及快捷鍵仍需在 Android TV 實機測試。
