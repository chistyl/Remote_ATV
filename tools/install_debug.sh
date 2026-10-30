#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
APK="$ROOT/build/app/outputs/flutter-apk/app-debug.apk"
test -f "$APK" || { echo "找不到 APK，請先執行 tools/build_debug.sh。" >&2; exit 2; }
command -v adb >/dev/null 2>&1 || { echo "找不到 adb，請安裝 Android SDK Platform-Tools。" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "找不到 Python 3。" >&2; exit 2; }
SERIAL=""
NO_LAUNCH=0
while (($#)); do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || { echo "--serial 需要裝置序號" >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    --no-launch) NO_LAUNCH=1; shift ;;
    -h|--help) echo "用法：$0 [--serial SERIAL] [--no-launch]"; exit 0 ;;
    *) echo "未知參數：$1" >&2; exit 2 ;;
  esac
done
python3 tools/verify_apk.py "$APK"
CHECK_ARGS=(tools/check_adb_device.py)
[[ -n "$SERIAL" ]] && CHECK_ARGS+=(--serial "$SERIAL")
DEVICE_SERIAL="$(python3 "${CHECK_ARGS[@]}" --print-serial)"
python3 "${CHECK_ARGS[@]}"
DIAG_DIR="$ROOT/install-diagnostics"
mkdir -p "$DIAG_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="$DIAG_DIR/install-$STAMP.txt"
MODEL="$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model | tr -d '\r')"
SDK="$(adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
ABI="$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
{
  echo "Timestamp: $(date -Is)"
  echo "Serial: $DEVICE_SERIAL"
  echo "Model: $MODEL"
  echo "Android API: $SDK"
  echo "ABI: $ABI"
  echo "APK: $APK"
} > "$REPORT"
if ! adb -s "$DEVICE_SERIAL" install -r -t "$APK" 2>&1 | tee -a "$REPORT"; then
  adb -s "$DEVICE_SERIAL" logcat -d -t 400 > "$DIAG_DIR/logcat-$STAMP.txt" || true
  echo "APK 安裝失敗，診斷已保存：$REPORT" >&2
  exit 4
fi
PACKAGE_PATH="$(adb -s "$DEVICE_SERIAL" shell pm path com.chistyl.remoteatv | tr -d '\r')"
[[ -n "$PACKAGE_PATH" ]] || { echo "安裝命令成功，但 Package Manager 找不到套件。" >&2; exit 5; }
echo "Package path: $PACKAGE_PATH" >> "$REPORT"
if ((NO_LAUNCH == 0)); then
  if ! adb -s "$DEVICE_SERIAL" shell monkey -p com.chistyl.remoteatv 1 2>&1 | tee -a "$REPORT"; then
    adb -s "$DEVICE_SERIAL" logcat -d -t 400 > "$DIAG_DIR/logcat-$STAMP.txt" || true
    echo "APK 已安裝，但啟動指令失敗。" >&2
    exit 6
  fi
  sleep 2
  PID="$(adb -s "$DEVICE_SERIAL" shell pidof com.chistyl.remoteatv | tr -d '\r')"
  if [[ -z "$PID" ]]; then
    adb -s "$DEVICE_SERIAL" logcat -d -t 400 > "$DIAG_DIR/logcat-$STAMP.txt" || true
    echo "已送出啟動指令，但未偵測到 App 程序；請查看 install-diagnostics。" >&2
    exit 7
  fi
  echo "Process ID: $PID" >> "$REPORT"
fi
echo "安裝完成。裝置：$DEVICE_SERIAL"
((NO_LAUNCH == 1)) || echo "App 啟動程序：$PID"
echo "診斷報告：$REPORT"
