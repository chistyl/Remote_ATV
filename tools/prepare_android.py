#!/usr/bin/env python3
"""Prepare Gradle Wrapper and local.properties using the installed Flutter SDK."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"


def fail(message: str, code: int = 2) -> None:
    print(f"錯誤：{message}", file=sys.stderr)
    raise SystemExit(code)


flutter = shutil.which("flutter")
if not flutter:
    fail("找不到 Flutter。請先安裝 Flutter SDK 並將 flutter/bin 加入 PATH。")
flutter_path = Path(flutter).resolve()
# Flutter executable is <sdk>/bin/flutter (or flutter.bat).
sdk = flutter_path.parent.parent
if not (sdk / "packages" / "flutter_tools" / "gradle").is_dir():
    fail(f"無法由 Flutter 執行檔定位 SDK：{sdk}")

wrapper_dir = ANDROID / "gradle" / "wrapper"
required = [ANDROID / "gradlew", ANDROID / "gradlew.bat", wrapper_dir / "gradle-wrapper.jar", wrapper_dir / "gradle-wrapper.properties"]
if not all(p.is_file() for p in required):
    print("尚未附帶 Gradle Wrapper，使用目前安裝的 Flutter SDK 產生相容的 Wrapper…")
    with tempfile.TemporaryDirectory(prefix="remote_atv_wrapper_") as temp:
        project = Path(temp) / "wrapper_project"
        cmd = [flutter, "create", "--platforms=android", "--project-name", "remote_atv_wrapper", str(project)]
        result = subprocess.run(cmd, cwd=ROOT)
        if result.returncode:
            fail("flutter create 無法產生 Gradle Wrapper。請先執行 flutter doctor -v 並修復 Flutter 環境。", result.returncode)
        src_wrapper = project / "android"
        (ANDROID / "gradle").mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_wrapper / "gradlew", ANDROID / "gradlew")
        shutil.copy2(src_wrapper / "gradlew.bat", ANDROID / "gradlew.bat")
        shutil.copytree(src_wrapper / "gradle" / "wrapper", wrapper_dir, dirs_exist_ok=True)
    try:
        (ANDROID / "gradlew").chmod(0o755)
    except OSError:
        pass

# Pin the wrapper to Gradle 8.7, the documented minimum for AGP 8.6.1.
# This avoids inheriting a newer Gradle distribution from a different Flutter
# template, which could make the generated project inconsistent with this app.
wrapper_properties = wrapper_dir / "gradle-wrapper.properties"
wrapper_properties.write_text(
    "distributionBase=GRADLE_USER_HOME\n"
    "distributionPath=wrapper/dists\n"
    "zipStoreBase=GRADLE_USER_HOME\n"
    "zipStorePath=wrapper/dists\n"
    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-all.zip\n",
    encoding="utf-8",
)

# Gradle settings.gradle reads this file to locate the SDK.
local_properties = ANDROID / "local.properties"
sdk_value = str(sdk).replace("\\", "/").replace(":", r"\:")
local_properties.write_text(f"flutter.sdk={sdk_value}\n", encoding="utf-8")

missing = [str(p.relative_to(ROOT)) for p in required if not p.is_file()]
if missing:
    fail("Wrapper 檔案仍缺少：" + ", ".join(missing))
print(f"Android 建置環境已準備：Flutter SDK = {sdk}")
print("Gradle Wrapper 與 android/local.properties 已就緒。")
