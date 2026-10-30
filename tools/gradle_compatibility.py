#!/usr/bin/env python3
"""Static compatibility guard for Android Gradle, Kotlin, SDK and wrapper."""
from pathlib import Path
import re, sys
ROOT = Path(__file__).resolve().parents[1]
settings = (ROOT / "android/settings.gradle").read_text(encoding="utf-8")
app = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")
pub = (ROOT / "pubspec.yaml").read_text(encoding="utf-8")
checks=[]
def check(label, ok, detail=""):
    checks.append((label, bool(ok), detail))

def version(pattern, text):
    m=re.search(pattern,text)
    return tuple(map(int,m.groups())) if m else None

agp=version(r'com\.android\.application" version "(\d+)\.(\d+)\.(\d+)"', settings)
kotlin=version(r'org\.jetbrains\.kotlin\.android" version "(\d+)\.(\d+)\.(\d+)"', settings)
target=version(r'targetSdk\s+(\d+)', app)
code=version(r'versionCode\s+(\d+)', app)
name=re.search(r'versionName\s+"([^"]+)"', app)
flutter_ver=re.search(r'^version:\s*([^+\s]+)\+(\d+)',pub,re.M)
check("Android Gradle Plugin 可解析", agp is not None, str(agp) if agp else "settings.gradle version pattern not found")
check("Kotlin Gradle Plugin 可解析", kotlin is not None, str(kotlin) if kotlin else "settings.gradle version pattern not found")
check("Target SDK 可解析", target is not None, str(target[0]) if target else "targetSdk pattern not found")
check("API 35 使用 AGP 8.6+", bool(agp and target and (target[0] < 35 or agp >= (8,6,0))), f"AGP={agp}; targetSdk={target[0] if target else 'unknown'}")
check("Kotlin 2.0.21 基準版本", bool(kotlin and kotlin >= (2,0,20)), f"Kotlin={kotlin}")
check("Flutter / Android versionName 一致", bool(flutter_ver and name and flutter_ver.group(1)==name.group(1)), f"Flutter={flutter_ver.group(1) if flutter_ver else 'missing'}; Android={name.group(1) if name else 'missing'}")
check("Flutter / Android versionCode 一致", bool(flutter_ver and code and int(flutter_ver.group(2))==code[0]), f"Flutter={flutter_ver.group(2) if flutter_ver else 'missing'}; Android={code[0] if code else 'missing'}")
wrapper=ROOT/"android/gradle/wrapper/gradle-wrapper.properties"
if wrapper.exists():
    w=wrapper.read_text(encoding="utf-8")
    m=re.search(r'gradle-(\d+)\.(\d+)(?:\.(\d+))?-',w)
    gv=tuple(int(x or 0) for x in m.groups()) if m else None
    check("Gradle Wrapper 符合 AGP 8.6.1 (Gradle 8.7)", bool(gv and gv == (8,7,0)), f"Gradle={gv or 'unknown'}")
else:
    check("Gradle Wrapper 檔案", False, "尚未產生；先執行 tools/prepare_android.py")
java_version = None
try:
    import subprocess
    result = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=10)
    text = result.stderr + result.stdout
    jm = re.search(r'version \"?(\d+)', text)
    java_version = int(jm.group(1)) if jm else None
except (OSError, subprocess.SubprocessError):
    pass
check("JDK 17 以上", bool(java_version and java_version >= 17), f"Java={java_version or 'not detected'}")
for label,ok,detail in checks:
    print(f"[{'PASS' if ok else 'WARN'}] {label}" + (f": {detail}" if detail else ""))
print(f"通過 {sum(x[1] for x in checks)} / {len(checks)} 項")
if not all(x[1] for x in checks):
    print("此為靜態相容性檢查；仍需在具備 Flutter／Android SDK 的環境執行 Gradle Sync 與 APK 編譯。")
    sys.exit(1)
