$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw '找不到 Python 3，請先安裝 Python。' }
python tools/test_prepare_android.py
if ($LASTEXITCODE -ne 0) { throw 'Gradle Wrapper 自動準備測試失敗' }
python tools/prepare_android.py
if ($LASTEXITCODE -ne 0) { throw 'Android 建置環境準備失敗' }
python tools/preflight.py
if ($LASTEXITCODE -ne 0) { throw '建置預檢失敗' }
python tools/version_consistency.py
if ($LASTEXITCODE -ne 0) { throw '版本資訊一致性檢查失敗' }
python tools/android_compatibility.py
if ($LASTEXITCODE -ne 0) { throw 'Android TV 相容性靜態檢查失敗' }
python tools/connection_diagnostics_check.py
if ($LASTEXITCODE -ne 0) { throw '連線診斷靜態檢查失敗' }
python tools/gradle_config_check.py
if ($LASTEXITCODE -ne 0) { throw 'Gradle Repository／Plugin 設定檢查失敗' }
python tools/gradle_compatibility.py
if ($LASTEXITCODE -ne 0) { throw 'Gradle／Kotlin／SDK／JDK 相容性檢查失敗' }
python tools/ci_config_check.py
if ($LASTEXITCODE -ne 0) { throw 'GitHub Actions 設定檢查失敗' }
python tools/device_install_check.py
if ($LASTEXITCODE -ne 0) { throw 'ADB 安裝流程靜態檢查失敗' }
if (-not (Get-Command flutter -ErrorAction SilentlyContinue)) { throw '找不到 Flutter。請先安裝 Flutter SDK 並加入 PATH。' }
Write-Host '執行 Flutter 環境資訊檢查（此步驟的選用元件警告不會直接中止建置）…'
flutter doctor -v
Write-Host '取得 Flutter 套件…'
flutter pub get
if ($LASTEXITCODE -ne 0) { throw 'flutter pub get 失敗' }
Write-Host '執行 Dart 靜態分析…'
flutter analyze
if ($LASTEXITCODE -ne 0) { throw 'flutter analyze 失敗' }
Write-Host '建置 Debug APK…'
flutter build apk --debug
if ($LASTEXITCODE -ne 0) { throw 'APK 建置失敗' }
$Apk = Join-Path $Root 'build/app/outputs/flutter-apk/app-debug.apk'
if (-not (Test-Path $Apk)) { throw '建置命令結束，但找不到 APK 輸出檔。' }
$Inspection = Join-Path $Root 'build/apk-inspection.json'
python tools/verify_apk.py $Apk --report $Inspection
if ($LASTEXITCODE -ne 0) { throw 'APK 結構驗證失敗' }
$Hash = (Get-FileHash -Algorithm SHA256 $Apk).Hash
$Size = (Get-Item $Apk).Length
Write-Host "APK 建置完成：$Apk"
Write-Host "檔案大小：$Size bytes"
Write-Host "SHA-256：$Hash"
