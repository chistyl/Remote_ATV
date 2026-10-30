#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
command -v python3 >/dev/null 2>&1 || { echo "錯誤：找不到 Python 3。" >&2; exit 2; }
python3 tools/test_prepare_android.py
python3 tools/prepare_android.py
python3 tools/preflight.py
python3 tools/version_consistency.py
python3 tools/android_compatibility.py
python3 tools/connection_diagnostics_check.py
python3 tools/gradle_config_check.py
python3 tools/gradle_compatibility.py
python3 tools/ci_config_check.py
python3 tools/device_install_check.py
command -v flutter >/dev/null 2>&1 || { echo "錯誤：找不到 Flutter SDK。" >&2; exit 2; }
echo "執行 Flutter 環境資訊檢查（選用元件警告不會直接中止建置）…"
flutter doctor -v || echo "注意：flutter doctor 有警告，將繼續執行實際建置步驟。"
echo "取得 Flutter 套件…"
flutter pub get
echo "執行 Dart 靜態分析…"
flutter analyze
echo "建置 Debug APK…"
flutter build apk --debug
APK="$ROOT/build/app/outputs/flutter-apk/app-debug.apk"
test -f "$APK" || { echo "錯誤：找不到 APK 輸出檔。" >&2; exit 3; }
python3 tools/verify_apk.py "$APK" --report "$ROOT/build/apk-inspection.json"
printf '\nAPK 建置完成：%s\n' "$APK"
printf '檔案大小：%s bytes\n' "$(wc -c < "$APK" | tr -d ' ')"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$APK"; fi
