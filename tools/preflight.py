#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []
def add(name, ok, detail=""):
    checks.append((name, bool(ok), detail))

add("Flutter 專案入口", all((ROOT / p).is_file() for p in ["pubspec.yaml", "lib/main.dart", "android/app/src/main/kotlin/com/chistyl/remoteatv/MainActivity.kt"]))
try:
    import re
    pub = (ROOT / "pubspec.yaml").read_text(encoding="utf-8")
    gradle = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")
    version = re.search(r"^version:\s*([^+\s]+)\+(\d+)", pub, re.M)
    code = re.search(r"versionCode\s+(\d+)", gradle)
    name = re.search(r'versionName\s+"([^"]+)"', gradle)
    version_ok = bool(version and code and name and version.group(1) == name.group(1) and version.group(2) == code.group(1))
    add("Flutter / Android 版本一致", version_ok, f"pubspec={version.group(0).split(':',1)[1].strip() if version else 'missing'}; Android={name.group(1) if name else 'missing'}+{code.group(1) if code else 'missing'}")
except Exception as e:
    add("Flutter / Android 版本一致", False, str(e))

manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for permission in ["INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE", "RECORD_AUDIO"]:
    pass
add("網路／Wi-Fi／麥克風權限", all(x in manifest for x in ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE", "android.permission.CHANGE_WIFI_MULTICAST_STATE", "android.permission.RECORD_AUDIO"]))
add("Android Gradle Wrapper", all((ROOT / p).is_file() for p in ["android/gradlew", "android/gradlew.bat", "android/gradle/wrapper/gradle-wrapper.jar", "android/gradle/wrapper/gradle-wrapper.properties"]))
add("Flutter SDK", shutil.which("flutter") is not None, shutil.which("flutter") or "未安裝／未加入 PATH")
add("Dart SDK", shutil.which("dart") is not None, shutil.which("dart") or "未安裝／未加入 PATH")
add("Java", shutil.which("java") is not None, shutil.which("java") or "未安裝／未加入 PATH")
settings_gradle = (ROOT / "android/settings.gradle").read_text(encoding="utf-8")
root_gradle = (ROOT / "android/build.gradle").read_text(encoding="utf-8")
repo_policy_ok = "RepositoriesMode.FAIL_ON_PROJECT_REPOS" in settings_gradle and "allprojects" not in root_gradle and "repositories" not in root_gradle
add("Gradle Repository 宣告一致", repo_policy_ok, "使用 settings.gradle 集中管理 repositories，避免 FAIL_ON_PROJECT_REPOS 衝突" if repo_policy_ok else "請勿同時使用 FAIL_ON_PROJECT_REPOS 與 root allprojects.repositories")
add("建置腳本", all((ROOT / p).is_file() for p in ["tools/build_debug.ps1", "tools/build_debug.sh", "tools/prepare_android.py", "tools/verify_apk.py", "tools/install_debug.ps1", "tools/install_debug.sh", "tools/check_adb_device.py", "tools/device_install_check.py", "tools/gradle_config_check.py", "tools/version_consistency.py", ".github/workflows/android-apk.yml"]))

version_label = version.group(1) if 'version' in locals() and version else "unknown"
print(f"Remote ATV {version_label} 建置預檢")
print("=" * 58)
for name, ok, detail in checks:
    print(f"[{ 'PASS' if ok else 'WARN' }] {name}" + (f": {detail}" if detail else ""))
passed = sum(ok for _, ok, _ in checks)
print("-" * 58)
print(f"通過 {passed} / {len(checks)} 項。")
if passed != len(checks):
    print("注意：預檢不等同 Dart 分析、APK 編譯或實體電視測試。")
