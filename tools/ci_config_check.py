#!/usr/bin/env python3
"""Static checks for the GitHub Actions APK workflow (not a YAML parser)."""
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
workflow = root / ".github/workflows/android-apk.yml"
if not workflow.is_file():
    print("FAIL: GitHub Actions workflow missing")
    raise SystemExit(1)
s = workflow.read_text(encoding="utf-8")
checks = {
    "workflow_dispatch trigger": "workflow_dispatch:" in s,
    "version consistency check": "python tools/version_consistency.py" in s,
    "pinned Flutter version": "flutter-version: '3.27.4'" in s,
    "Java 17 setup": "java-version: '17'" in s,
    "build step captures output": "tee ci-reports/build.log" in s,
    "APK artifact is success-gated": "if: success() && steps.build.outcome == 'success'" in s,
    "diagnostics artifact always uploaded": "if: always()" in s and "remote-atv-build-diagnostics" in s,
    "APK inspection report included": "ci-reports/apk-inspection.json" in s,
    "artifact upload uses v4": "actions/upload-artifact@v4" in s,
    "APK artifact has 14-day retention": "retention-days: 14" in s,
    "dependency resolution has a named step": "id: dependencies" in s and "flutter pub get" in s,
    "dependency log is captured": "tee ci-reports/pub-get.log" in s,
    "resolved lockfile is archived": "ci-reports/pubspec.lock" in s and "pubspec-lock.sha256" in s,
    "dependency outcome appears in report": "DEPENDENCY_OUTCOME" in s and "Dependency resolution outcome:" in s,
    "wrapper bootstrap test exists": (root / "tools/test_prepare_android.py").is_file(),
    "CI prepares the real Gradle Wrapper before dependency resolution": "Prepare and verify real Gradle Wrapper" in s and s.index("Prepare and verify real Gradle Wrapper") < s.index("Resolve Flutter dependencies"),
    "CI records wrapper preparation outcome": "WRAPPER_OUTCOME" in s and "Wrapper preparation outcome:" in s,
    "CI archives wrapper preparation logs": "wrapper-test.log" in s and "wrapper-prepare.log" in s and "gradle-compatibility.log" in s,
    "CI verifies generated wrapper distribution": "gradle-8.7-all.zip" in s and "gradle-wrapper.jar" in s,
    "Unix build script runs wrapper bootstrap test": "python3 tools/test_prepare_android.py" in (root / "tools/build_debug.sh").read_text(encoding="utf-8"),
    "Windows build script runs wrapper bootstrap test": "python tools/test_prepare_android.py" in (root / "tools/build_debug.ps1").read_text(encoding="utf-8"),
}
for name, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
print(f"通過 {sum(checks.values())} / {len(checks)} 項")
raise SystemExit(0 if all(checks.values()) else 1)
