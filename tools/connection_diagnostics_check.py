#!/usr/bin/env python3
"""Static guard for Remote ATV connection diagnostics; not a device test."""
from pathlib import Path
import re
import sys
ROOT = Path(__file__).resolve().parents[1]
kotlin = (ROOT / 'android/app/src/main/kotlin/com/chistyl/remoteatv/MainActivity.kt').read_text(encoding='utf-8')
dart = (ROOT / 'lib/main.dart').read_text(encoding='utf-8')
checks = {
    'MethodChannel exposes health snapshot': 'getConnectionDiagnostics' in kotlin and "'getConnectionDiagnostics'" in dart,
    'TLS handshake timing recorded': 'remoteTlsHandshakeMs' in kotlin and 'SystemClock.elapsedRealtime() - tlsStartedAt' in kotlin,
    'Connection uptime recorded': 'remoteConnectedAtMs' in kotlin and '"uptimeMs" to uptime' in kotlin,
    'Inbound frames and last inbound timestamp recorded': 'remoteInboundFrameCount += 1' in kotlin and '"lastInboundAgeMs" to lastInboundAge' in kotlin,
    'TV-originated ping request/response counters': 'remotePingRequestCount += 1' in kotlin and 'remotePingResponseCount += 1' in kotlin,
    'Volume and IME update counters': 'remoteVolumeUpdateCount += 1' in kotlin and 'remoteImeUpdateCount += 1' in kotlin,
    'Negotiated feature names exposed': '"negotiatedFeatures" to featureNames' in kotlin,
    'Disconnect reason exposed': '"lastDisconnectReason" to remoteLastDisconnectReason' in kotlin,
    'UI offers refresh and copy': "tooltip: '更新連線資訊'" in dart and "tooltip: '複製記錄'" in dart,
    'No invented client ping request added': 'remotePingRequestMessage(' not in kotlin,
}
for label, ok in checks.items():
    print(f"[{'PASS' if ok else 'FAIL'}] {label}")
print(f"通過 {sum(checks.values())} / {len(checks)} 項")
if not all(checks.values()):
    sys.exit(1)
print('注意：此為原始碼靜態檢查，不代表已完成 APK 編譯或實機連線測試。')
