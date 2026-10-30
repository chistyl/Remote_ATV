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
echo "使用 Gradle Wrapper 直接建置 Android Debug APK…"
(
  cd "$ROOT/android"
  ./gradlew --no-daemon :app:assembleDebug
)
EXPECTED="$ROOT/build/app/outputs/flutter-apk/app-debug.apk"
mkdir -p "$(dirname "$EXPECTED")"
if [ ! -s "$EXPECTED" ]; then
  echo "Flutter 預設 APK 路徑不存在，搜尋 Gradle 實際輸出…"
  FOUND="$(find "$ROOT/build" "$ROOT/android/app/build" -type f -name 'app-debug.apk' -size +0c -print -quit 2>/dev/null || true)"
  if [ -z "$FOUND" ]; then
    echo "錯誤：Gradle 執行後仍找不到 app-debug.apk。輸出目錄如下："
    find "$ROOT/build" "$ROOT/android/app/build" -maxdepth 7 -type f \( -name '*.apk' -o -name '*.aab' \) -print 2>/dev/null || true
    exit 3
  fi
  cp "$FOUND" "$EXPECTED"
  echo "已將 APK 標準化至：$EXPECTED"
fi
python3 tools/verify_apk.py "$EXPECTED" --report "$ROOT/build/apk-inspection.json"
printf '\nAPK 建置完成：%s\n' "$EXPECTED"
printf '檔案大小：%s bytes\n' "$(wc -c < "$EXPECTED" | tr -d ' ')"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$EXPECTED"; fi
