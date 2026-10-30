#!/usr/bin/env python3
"""Guard against Gradle repository declarations that conflict with Flutter plugins."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
settings = (ROOT / "android/settings.gradle").read_text(encoding="utf-8")
root_gradle = (ROOT / "android/build.gradle").read_text(encoding="utf-8")
app_gradle = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")

checks = {
    "settings.gradle provides plugin management repositories": "pluginManagement" in settings and "repositories { google(); mavenCentral(); gradlePluginPortal() }" in settings,
    "settings.gradle does not block repositories added by Flutter plugins": "FAIL_ON_PROJECT_REPOS" not in settings,
    "root build.gradle does not redeclare project repositories": "allprojects" not in root_gradle and "repositories" not in root_gradle,
    "Android application plugin is configured": 'com.android.application" version "8.6.1"' in settings,
    "Kotlin Android plugin is configured": 'org.jetbrains.kotlin.android" version "2.0.21"' in settings,
    "Flutter Gradle plugin is applied in app module": 'id "dev.flutter.flutter-gradle-plugin"' in app_gradle,
}
for name, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
print(f"通過 {sum(checks.values())} / {len(checks)} 項")
if not all(checks.values()):
    sys.exit(1)
print("注意：此為 Gradle 設定靜態檢查，並非實際 Gradle Sync 或 APK 編譯。")
