#!/usr/bin/env python3
"""Test Gradle Wrapper bootstrap using an isolated fake Flutter SDK.

This verifies the preparation script without downloading Flutter/Gradle and
without modifying the real project's android/ directory.
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

SOURCE_ROOT = Path(__file__).resolve().parents[1]
PREPARE_SOURCE = SOURCE_ROOT / "tools" / "prepare_android.py"


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="remote_atv_wrapper_test_") as temp:
        base = Path(temp)
        project = base / "project"
        sdk = base / "fake_flutter_sdk"
        (project / "tools").mkdir(parents=True)
        (project / "android").mkdir()
        (sdk / "bin").mkdir(parents=True)
        (sdk / "packages" / "flutter_tools" / "gradle").mkdir(parents=True)
        shutil.copy2(PREPARE_SOURCE, project / "tools" / "prepare_android.py")

        calls = base / "flutter_calls.txt"
        fake_flutter_py = sdk / "bin" / "fake_flutter.py"
        fake_flutter_py.write_text(
            "from pathlib import Path\n"
            "import sys\n"
            f"Path({str(calls)!r}).open('a').write('called\\n')\n"
            "args = sys.argv[1:]\n"
            "if not args or args[0] != 'create':\n"
            "    raise SystemExit('unexpected fake flutter command: ' + repr(args))\n"
            "dest = Path(args[-1]) / 'android'\n"
            "(dest / 'gradle' / 'wrapper').mkdir(parents=True, exist_ok=True)\n"
            "(dest / 'gradlew').write_text('#!/bin/sh\\nexit 0\\n')\n"
            "(dest / 'gradlew.bat').write_text('@echo off\\r\\nexit /b 0\\r\\n')\n"
            "(dest / 'gradle' / 'wrapper' / 'gradle-wrapper.jar').write_bytes(b'fake-wrapper-jar')\n"
            "(dest / 'gradle' / 'wrapper' / 'gradle-wrapper.properties').write_text('distributionUrl=https\\\\://services.gradle.org/distributions/gradle-8.10.2-all.zip\\n')\n",
            encoding="utf-8",
        )
        if os.name == "nt":
            fake_flutter = sdk / "bin" / "flutter.bat"
            fake_flutter.write_text(
                "@echo off\r\n"
                f'"{sys.executable}" "%~dp0fake_flutter.py" %*\r\n',
                encoding="utf-8",
            )
        else:
            fake_flutter = sdk / "bin" / "flutter"
            fake_flutter.write_text(
                "#!/usr/bin/env python3\n"
                "from pathlib import Path\n"
                "import runpy\n"
                f"runpy.run_path({str(fake_flutter_py)!r}, run_name='__main__')\n",
                encoding="utf-8",
            )
            fake_flutter.chmod(0o755)

        env = os.environ.copy()
        env["PATH"] = str(sdk / "bin") + os.pathsep + env.get("PATH", "")
        cmd = [sys.executable, str(project / "tools" / "prepare_android.py")]
        first = subprocess.run(cmd, cwd=project, env=env, text=True, capture_output=True)
        if first.returncode:
            fail("第一次 Wrapper 準備失敗：\n" + first.stdout + first.stderr)

        android = project / "android"
        required = [android / "gradlew", android / "gradlew.bat", android / "gradle/wrapper/gradle-wrapper.jar", android / "gradle/wrapper/gradle-wrapper.properties", android / "local.properties"]
        missing = [str(p.relative_to(project)) for p in required if not p.is_file()]
        if missing:
            fail("缺少產生檔案：" + ", ".join(missing))

        wrapper_text = (android / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
        if "gradle-8.7-all.zip" not in wrapper_text:
            fail("Wrapper 沒有固定為 Gradle 8.7")
        local_text = (android / "local.properties").read_text(encoding="utf-8")
        if "flutter.sdk=" not in local_text or "fake_flutter_sdk" not in local_text:
            fail("local.properties 未正確寫入 Flutter SDK 路徑")

        # Second invocation should be idempotent and must not call flutter create again.
        second = subprocess.run(cmd, cwd=project, env=env, text=True, capture_output=True)
        if second.returncode:
            fail("第二次 Wrapper 準備失敗：\n" + second.stdout + second.stderr)
        call_count = len(calls.read_text(encoding="utf-8").splitlines()) if calls.exists() else 0
        if call_count != 1:
            fail(f"Wrapper 準備不是冪等的；flutter create 呼叫次數={call_count}")

        print("[PASS] 首次執行可產生 Gradle Wrapper 所需檔案")
        print("[PASS] Gradle Wrapper 版本固定為 8.7")
        print("[PASS] local.properties 正確記錄 Flutter SDK 路徑")
        print("[PASS] 第二次執行不會重複產生 Wrapper")
        print("通過 4 / 4 項（使用隔離的模擬 Flutter SDK；非真正 Gradle 編譯）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
