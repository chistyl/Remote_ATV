param(
    [string]$Serial = '',
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
$Apk = Join-Path $Root 'build/app/outputs/flutter-apk/app-debug.apk'
if (-not (Test-Path $Apk)) { throw "找不到 APK：$Apk。請先執行 build_debug.ps1。" }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw '找不到 adb。請安裝 Android SDK Platform-Tools 並加入 PATH。' }
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw '找不到 Python 3。' }
python tools/verify_apk.py $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK 結構驗證失敗，停止安裝。' }
$CheckArgs = @('tools/check_adb_device.py')
if ($Serial) { $CheckArgs += @('--serial', $Serial) }
$DeviceSerial = (python @CheckArgs --print-serial).Trim()
if ($LASTEXITCODE -ne 0 -or -not $DeviceSerial) { throw 'ADB 裝置檢查失敗。' }
python @CheckArgs
if ($LASTEXITCODE -ne 0) { throw 'ADB 裝置相容性檢查失敗。' }
$DiagDir = Join-Path $Root 'install-diagnostics'
New-Item -ItemType Directory -Force -Path $DiagDir | Out-Null
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Report = Join-Path $DiagDir "install-$Stamp.txt"
$DeviceInfo = adb -s $DeviceSerial shell getprop ro.product.model
$Sdk = adb -s $DeviceSerial shell getprop ro.build.version.sdk
$Abi = adb -s $DeviceSerial shell getprop ro.product.cpu.abi
"Timestamp: $(Get-Date -Format o)`nSerial: $DeviceSerial`nModel: $DeviceInfo`nAndroid API: $Sdk`nABI: $Abi`nAPK: $Apk" | Set-Content -Encoding UTF8 $Report
adb -s $DeviceSerial install -r -t $Apk 2>&1 | Tee-Object -Append -FilePath $Report
if ($LASTEXITCODE -ne 0) {
    adb -s $DeviceSerial logcat -d -t 400 | Set-Content -Encoding UTF8 (Join-Path $DiagDir "logcat-$Stamp.txt")
    throw "APK 安裝失敗。已保存診斷：$Report"
}
$Installed = adb -s $DeviceSerial shell pm path com.chistyl.remoteatv
if ($LASTEXITCODE -ne 0 -or -not $Installed) { throw 'adb install 回報成功，但 Package Manager 找不到已安裝套件。' }
"Package path: $Installed" | Add-Content -Encoding UTF8 $Report
if (-not $NoLaunch) {
    adb -s $DeviceSerial shell monkey -p com.chistyl.remoteatv 1 2>&1 | Tee-Object -Append -FilePath $Report
    if ($LASTEXITCODE -ne 0) {
        adb -s $DeviceSerial logcat -d -t 400 | Set-Content -Encoding UTF8 (Join-Path $DiagDir "logcat-$Stamp.txt")
        throw "APK 已安裝，但啟動指令失敗。已保存診斷：$Report"
    }
    Start-Sleep -Seconds 2
    $AppProcessId = adb -s $DeviceSerial shell pidof com.chistyl.remoteatv
    if (-not $AppProcessId) {
        adb -s $DeviceSerial logcat -d -t 400 | Set-Content -Encoding UTF8 (Join-Path $DiagDir "logcat-$Stamp.txt")
        throw "已送出啟動指令，但未偵測到 App 程序。請查看 install-diagnostics。"
    }
    "Process ID: $AppProcessId" | Add-Content -Encoding UTF8 $Report
}
Write-Host "安裝完成。裝置：$DeviceSerial"
if (-not $NoLaunch) { Write-Host "App 啟動程序：$AppProcessId" }
Write-Host "診斷報告：$Report"
