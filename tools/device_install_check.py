#!/usr/bin/env python3
"""Static checks for ADB device preflight and install scripts."""
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
checks = {
    "ADB checker exists": (ROOT / "tools/check_adb_device.py").is_file(),
    "ADB checker checks authorization": "unauthorized" in (ROOT / "tools/check_adb_device.py").read_text(encoding="utf-8"),
    "ADB checker checks Android API": "ro.build.version.sdk" in (ROOT / "tools/check_adb_device.py").read_text(encoding="utf-8"),
    "ADB checker handles multiple devices": "多台已授權裝置" in (ROOT / "tools/check_adb_device.py").read_text(encoding="utf-8"),
    "Windows installer verifies APK": "tools/verify_apk.py" in (ROOT / "tools/install_debug.ps1").read_text(encoding="utf-8"),
    "Windows installer verifies package and process": "pm path com.chistyl.remoteatv" in (ROOT / "tools/install_debug.ps1").read_text(encoding="utf-8") and "pidof com.chistyl.remoteatv" in (ROOT / "tools/install_debug.ps1").read_text(encoding="utf-8"),
    "Shell installer supports explicit serial": "--serial" in (ROOT / "tools/install_debug.sh").read_text(encoding="utf-8"),
    "Shell installer captures logcat on failure": "logcat -d -t 400" in (ROOT / "tools/install_debug.sh").read_text(encoding="utf-8"),
}
for label, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {label}")
print(f"通過 {sum(checks.values())} / {len(checks)} 項")
raise SystemExit(0 if all(checks.values()) else 1)
