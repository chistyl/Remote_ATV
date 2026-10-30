#!/usr/bin/env python3
"""Check ADB authorization and Android API compatibility before installing."""
from __future__ import annotations
import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.chistyl.remoteatv"
parser = argparse.ArgumentParser(description="Check one authorized Android device via ADB")
parser.add_argument("--serial", help="ADB device serial; required when multiple devices are connected")
parser.add_argument("--print-serial", action="store_true", help="print only the selected serial")
args = parser.parse_args()

def fail(message: str, code: int = 2) -> None:
    print(f"錯誤：{message}", file=sys.stderr)
    raise SystemExit(code)

adb = shutil.which("adb")
if not adb:
    fail("找不到 adb。請安裝 Android SDK Platform-Tools 並將 platform-tools 加入 PATH。")
try:
    result = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True, timeout=15)
except (OSError, subprocess.SubprocessError) as exc:
    fail(f"無法執行 adb devices：{exc}")
if result.returncode != 0:
    fail(result.stderr.strip() or "adb devices 執行失敗")
rows = []
for line in result.stdout.splitlines()[1:]:
    parts = line.split()
    if len(parts) >= 2 and parts[1] in {"device", "unauthorized", "offline"}:
        rows.append((parts[0], parts[1], " ".join(parts[2:])))
authorized = [row for row in rows if row[1] == "device"]
if args.serial:
    matches = [row for row in authorized if row[0] == args.serial]
    if not matches:
        state = next((row[1] for row in rows if row[0] == args.serial), "not_found")
        fail(f"指定裝置 {args.serial} 不可用，ADB 狀態：{state}。請確認 USB 偵錯已開啟並授權。")
    selected = matches[0]
else:
    if len(authorized) == 0:
        if any(row[1] == "unauthorized" for row in rows):
            fail("裝置尚未授權。請解鎖裝置並接受『允許 USB 偵錯』提示。")
        fail("沒有已授權的 ADB 裝置。請連接裝置並開啟 USB 偵錯。")
    if len(authorized) > 1:
        fail("偵測到多台已授權裝置，請使用 --serial 指定目標：" + ", ".join(row[0] for row in authorized))
    selected = authorized[0]
serial = selected[0]

def adb_out(*command: str) -> str:
    r = subprocess.run([adb, "-s", serial, *command], capture_output=True, text=True, timeout=20)
    if r.returncode != 0:
        fail(r.stderr.strip() or f"adb {' '.join(command)} 失敗")
    return (r.stdout or "").strip()

sdk_text = adb_out("shell", "getprop", "ro.build.version.sdk")
try:
    sdk = int(sdk_text)
except ValueError:
    fail(f"無法讀取 Android API Level：{sdk_text!r}")
gradle = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")
match = re.search(r"minSdk\s+(\d+)", gradle)
if not match:
    fail("無法從 android/app/build.gradle 讀取 minSdk")
min_sdk = int(match.group(1))
if sdk < min_sdk:
    fail(f"目標裝置 Android API {sdk} 低於 App 最低需求 API {min_sdk}。", 3)
model = adb_out("shell", "getprop", "ro.product.model") or "Unknown"
abi = adb_out("shell", "getprop", "ro.product.cpu.abi") or "Unknown"
if args.print_serial:
    print(serial)
else:
    print("ADB 裝置檢查通過")
    print(f"Serial：{serial}")
    print(f"Model：{model}")
    print(f"Android API：{sdk}（App minSdk {min_sdk}）")
    print(f"Primary ABI：{abi}")
    print(f"Package：{PACKAGE}")
    print("注意：此檢查只確認 ADB 授權與最低 API 相容性，實際安裝及啟動仍需執行後續步驟。")
