#!/usr/bin/env python3
"""Verify app version labels stay aligned across Flutter, Android and CI."""
from pathlib import Path
import re
import sys
ROOT = Path(__file__).resolve().parents[1]
checks = []
def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))

pub = (ROOT / "pubspec.yaml").read_text(encoding="utf-8")
gradle = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")
dart = (ROOT / "lib/main.dart").read_text(encoding="utf-8")
readme = (ROOT / "README.zh-TW.md").read_text(encoding="utf-8")
workflow = (ROOT / ".github/workflows/android-apk.yml").read_text(encoding="utf-8")
pm = re.search(r"^version:\s*([^+\s]+)\+(\d+)", pub, re.M)
gm = re.search(r'versionName\s+"([^"]+)"', gradle)
gc = re.search(r'versionCode\s+(\d+)', gradle)
dm = re.search(r"const String appVersionLabel = '([^']+)'", dart)
if pm:
    version, build = pm.group(1), pm.group(2)
else:
    version, build = "", ""
check("pubspec version 可解析", pm is not None)
check("Android versionName 一致", bool(pm and gm and gm.group(1) == version), f"pubspec={version}; gradle={gm.group(1) if gm else 'missing'}")
check("Android versionCode 一致", bool(pm and gc and gc.group(1) == build), f"pubspec={build}; gradle={gc.group(1) if gc else 'missing'}")
check("Flutter UI 版本標籤一致", bool(pm and dm and dm.group(1) == f"{version} (Build {build})"), dm.group(1) if dm else "missing")
check("README 版本一致", bool(pm and f"# Remote ATV V{version.rsplit('.', 1)[0]}" in readme and f"`{version}+{build}`" in readme), f"expected={version}+{build}")
check("CI 報告版本由 pubspec 動態讀取", 'APP_VERSION="$(sed -n' in workflow and 'APP_BUILD="$(sed -n' in workflow and "Remote ATV ${APP_VERSION} CI Build Report" in workflow)
for name, ok, detail in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f": {detail}" if detail else ""))
print(f"通過 {sum(ok for _, ok, _ in checks)} / {len(checks)} 項")
raise SystemExit(0 if all(ok for _, ok, _ in checks) else 1)
