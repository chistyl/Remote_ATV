#!/usr/bin/env python3
"""Validate Flutter APK structure and optionally emit reproducible metadata."""
from __future__ import annotations
from pathlib import Path
from zipfile import ZipFile, BadZipFile
import argparse
import hashlib
import json
import sys

parser = argparse.ArgumentParser(description="Verify a Flutter Android APK")
parser.add_argument("apk", type=Path, help="APK file to inspect")
parser.add_argument("--report", type=Path, help="write inspection metadata as JSON")
args = parser.parse_args()
apk = args.apk.expanduser().resolve()
if not apk.is_file() or apk.stat().st_size == 0:
    print(f"錯誤：APK 不存在或為空：{apk}", file=sys.stderr)
    raise SystemExit(3)
try:
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    with ZipFile(apk) as zf:
        bad = zf.testzip()
        names = set(zf.namelist())
        if bad:
            raise ValueError(f"ZIP CRC 驗證失敗：{bad}")
        required = {"AndroidManifest.xml", "classes.dex", "resources.arsc"}
        missing = sorted(required - names)
        if missing:
            raise ValueError("APK 缺少必要項目：" + ", ".join(missing))
        dex_count = sum(1 for n in names if n.startswith("classes") and n.endswith(".dex"))
        native_libs = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
        abis = sorted({n.split("/")[1] for n in native_libs if len(n.split("/")) >= 3})
        flutter_engine = [n for n in native_libs if n.endswith("/libflutter.so")]
        flutter_app = [n for n in native_libs if n.endswith("/libapp.so")]
        flutter_assets = sorted(n for n in names if n.startswith("assets/flutter_assets/"))
        if not flutter_engine:
            raise ValueError("APK 缺少 Flutter Engine：lib/<abi>/libflutter.so")
        kernel_blob = "assets/flutter_assets/kernel_blob.bin" in names
        if not flutter_app and not kernel_blob:
            raise ValueError("APK 缺少 Dart 執行載荷：libapp.so 或 assets/flutter_assets/kernel_blob.bin")
        if not flutter_assets:
            raise ValueError("APK 缺少 assets/flutter_assets/，不像完整 Flutter APK")
        metadata = {
            "file": apk.name,
            "size_bytes": apk.stat().st_size,
            "sha256": digest.hexdigest(),
            "zip_crc": "passed",
            "dex_count": dex_count,
            "abis": abis,
            "native_libraries": native_libs,
            "flutter_engine_libraries": flutter_engine,
            "flutter_app_libraries": flutter_app,
            "dart_payload": "AOT libapp.so" if flutter_app else "debug kernel_blob.bin",
            "flutter_asset_file_count": len(flutter_assets),
            "flutter_asset_manifest": next((n for n in ("assets/flutter_assets/AssetManifest.bin", "assets/flutter_assets/AssetManifest.json") if n in names), None),
        }
    if args.report:
        report = args.report.expanduser().resolve()
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("APK 結構驗證通過")
    print(f"檔案：{apk}")
    print(f"大小：{metadata['size_bytes']} bytes")
    print(f"SHA-256：{metadata['sha256']}")
    print(f"ABI：{', '.join(abis) if abis else '未找到'}")
    print(f"DEX 數量：{dex_count}；Flutter assets：{len(flutter_assets)}")
except (BadZipFile, OSError, ValueError) as exc:
    print(f"APK 驗證失敗：{exc}", file=sys.stderr)
    raise SystemExit(4)
