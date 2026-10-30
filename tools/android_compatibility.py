#!/usr/bin/env python3
"""Static Android/Android TV compatibility audit; does not replace device testing."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
checks = []
def check(label, ok, detail=''):
    checks.append((label, bool(ok), detail))

manifest_path = ROOT / 'android/app/src/main/AndroidManifest.xml'
manifest_text = manifest_path.read_text(encoding='utf-8')
manifest = ET.fromstring(manifest_text)
ns = {'a': 'http://schemas.android.com/apk/res/android'}
permissions = {node.attrib.get('{%s}name' % ns['a']) for node in manifest.findall('uses-permission')}
required = {
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.ACCESS_WIFI_STATE',
    'android.permission.CHANGE_WIFI_MULTICAST_STATE',
    'android.permission.RECORD_AUDIO',
}
check('AndroidManifest XML 格式', True)
check('Remote v2 網路與 mDNS 權限', required.issubset(permissions), '缺少：' + ', '.join(sorted(required - permissions)) if not required.issubset(permissions) else '必要權限已宣告')
app = manifest.find('application')
activity = app.find('activity') if app is not None else None
check('啟動 Activity 已匯出', activity is not None and activity.attrib.get('{%s}exported' % ns['a']) == 'true')
check('Launcher intent-filter', activity is not None and any(x.attrib.get('{%s}name' % ns['a']) == 'android.intent.action.MAIN' for x in activity.findall('./intent-filter/action')))

app_gradle = (ROOT / 'android/app/build.gradle').read_text(encoding='utf-8')
settings = (ROOT / 'android/settings.gradle').read_text(encoding='utf-8')
kotlin = ROOT / 'android/app/src/main/kotlin/com/chistyl/remoteatv/MainActivity.kt'
kotlin_text = kotlin.read_text(encoding='utf-8') if kotlin.is_file() else ''
min_sdk = re.search(r'minSdk\s+(\d+)', app_gradle)
target_sdk = re.search(r'targetSdk\s+(\d+)', app_gradle)
check('最低 Android API 設定', bool(min_sdk and int(min_sdk.group(1)) >= 23), min_sdk.group(1) if min_sdk else '未設定')
check('Target SDK 設定', bool(target_sdk and int(target_sdk.group(1)) >= 33), target_sdk.group(1) if target_sdk else '未設定')
check('JVM 17 編譯設定', 'jvmTarget = "17"' in app_gradle and 'JavaVersion.VERSION_17' in app_gradle)
check('Android Gradle Plugin 設定', bool(re.search(r'com.android.application" version "8\.', settings)))
check('NSD 服務探索實作', 'NsdManager' in kotlin_text and 'discoverServices' in kotlin_text)
check('Android TV Remote 服務類型', '_androidtvremote._tcp.' in kotlin_text and '_androidtvremote2._tcp.' in kotlin_text)
check('Wi-Fi Multicast Lock 管理', 'createMulticastLock' in kotlin_text and 'android.permission.CHANGE_WIFI_MULTICAST_STATE' in permissions)
check('Remote v2 TLS 連線埠程式碼', '6466' in kotlin_text)

print('Remote ATV Android 相容性靜態檢查')
print('=' * 64)
for label, ok, detail in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {label}" + (f'：{detail}' if detail else ''))
failed = [label for label, ok, _ in checks if not ok]
print('-' * 64)
print(f'通過 {len(checks)-len(failed)} / {len(checks)} 項。')
if failed:
    print('未通過項目：' + '、'.join(failed))
    sys.exit(1)
print('注意：此工具只檢查原始碼設定，不代表已在實際 Android TV 上完成安裝、探索、配對或控制測試。')
