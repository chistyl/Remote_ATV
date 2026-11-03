# Remote ATV V5.0

獨立 Flutter + Android Kotlin Android TV 遙控器專案。

## V5.0 重製版本

- GitHub Actions 新增獨立的「Prepare and verify real Gradle Wrapper」階段。
- 在 `flutter pub get` 前，先使用 CI 實際安裝的 Flutter SDK 產生 Android Gradle Wrapper。
- 驗證實際產生的 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar` 與 Gradle 8.7 distribution URL。
- 保存 Wrapper 模擬測試、實際產生、Gradle 相容性檢查三份獨立日誌。
- 建置報告新增 Wrapper preparation outcome，失敗時可判斷流程停在 Wrapper、套件解析或 APK 編譯階段。
- 保留原有 Debug APK 與診斷 Artifact；只有 APK 建置及驗證成功才上傳 APK Artifact。
- 不更動 Android TV Remote v2 配對、TLS、按鍵、IME、語音及重連協定。
- 版本統一為 `5.0.0+50`（Android `versionCode 50`、`versionName 5.0.0`）。
- 預設導航為 D-pad 方向鍵，保留觸控板模式。
- 舊版 `tap` 設定相容轉換為 D-pad，既有 `swipe` 設定予以保留。
- 快速存取預設為 None。
- 已移除分享、評分、意見回饋及 Premium 推廣選單項目（原始碼設定頁未包含這些項目）。
- 此重製分支未包含自動更新檢查及更新提示流程。

## GitHub Actions 建置

1. 將整個專案推送到 GitHub repository 的 `main` 或 `master` 分支。
2. 開啟 **Actions** → **Android APK Build**，或使用 **Run workflow** 手動執行。
3. 工作流程依序執行版本檢查、實際 Gradle Wrapper 準備、Flutter 套件解析、Dart 分析及 APK 編譯。
4. 成功時下載 `remote-atv-debug-apk`。
5. 失敗時下載 `remote-atv-build-diagnostics`，優先查看 `wrapper-prepare.log`、`gradle-compatibility.log`、`pub-get.log`、`build.log` 與 `build-report.txt`。

## 本機建置

先安裝 Flutter SDK、Android SDK、Python 3、JDK 17 或更新版本。

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File tools/build_debug.ps1
```

macOS／Linux：

```bash
chmod +x tools/build_debug.sh
./tools/build_debug.sh
```

APK 輸出：`build/app/outputs/flutter-apk/app-debug.apk`。

## 驗證限制

CI 將執行版本一致性檢查、Android Gradle Wrapper 準備、Flutter 套件解析、Dart 靜態分析、APK 編譯與 APK 檢查。CI 成功仍不等於 Android 9 電視實機通過；配對、TLS、按鍵及焦點操作須在實機確認。
