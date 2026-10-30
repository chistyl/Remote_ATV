# Remote ATV V4.18

獨立 Flutter + Android Kotlin Android TV 遙控器專案。

## V4.18 更新

- GitHub Actions 新增獨立的「Prepare and verify real Gradle Wrapper」階段。
- 在 `flutter pub get` 前，先使用 CI 實際安裝的 Flutter SDK 產生 Android Gradle Wrapper。
- 驗證實際產生的 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar` 與 Gradle 8.7 distribution URL。
- 保存 Wrapper 模擬測試、實際產生、Gradle 相容性檢查三份獨立日誌。
- 建置報告新增 Wrapper preparation outcome，失敗時可判斷流程停在 Wrapper、套件解析或 APK 編譯階段。
- 保留原有 Debug APK 與診斷 Artifact；只有 APK 建置及驗證成功才上傳 APK Artifact。
- 不更動 Android TV Remote v2 配對、TLS、按鍵、IME、語音及重連協定。
- 版本統一為 `4.18.0+49`（Android `versionCode 49`、`versionName 4.18.0`）。

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

本版可在目前環境執行 Python 靜態檢查、Wrapper 模擬測試及 Shell 語法檢查。**目前工作環境沒有 Flutter SDK，也沒有 Gradle Wrapper，因此沒有執行真正 Gradle Wrapper 下載、Gradle Sync、`flutter analyze`、APK 編譯或實體電視測試。** GitHub Actions 的實際編譯結果仍須在 repository 執行後確認；目前也尚未將本版推送至 GitHub。
