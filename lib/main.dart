import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:permission_handler/permission_handler.dart';

// Keep this display label aligned with pubspec.yaml; tools/version_consistency.py verifies it.
const String appVersionLabel = '4.18.0 (Build 49)';

// Android TV Remote v2 reports IME selection offsets as Unicode code points.
// Flutter TextSelection uses UTF-16 code-unit offsets. Convert explicitly so
// emoji and other supplementary-plane characters do not shift the cursor.
int _codePointToUtf16Offset(String text, int codePointOffset) {
  final points = text.runes.toList(growable: false);
  final count = codePointOffset.clamp(0, points.length).toInt();
  return String.fromCharCodes(points.take(count)).length;
}

void main() => runApp(const RemoteAtvApp());

class RemoteAtvApp extends StatelessWidget {
  const RemoteAtvApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Remote ATV',
    theme: ThemeData.dark(useMaterial3: true).copyWith(
      scaffoldBackgroundColor: const Color(0xFF101114),
      colorScheme: const ColorScheme.dark(primary: Color(0xFFB9F36A), surface: Color(0xFF202126)),
      textTheme: ThemeData.dark().textTheme.apply(fontFamily: 'OpenSans'),
    ),
    home: const RemoteHome(),
  );
}

class _UnavailableSetting extends StatelessWidget {
  const _UnavailableSetting();
  @override
  Widget build(BuildContext context) => const Padding(padding: EdgeInsets.symmetric(horizontal: 8), child: Text('未支援', style: TextStyle(color: Colors.white38, fontSize: 11)));
}

class TvDevice {
  final String id, name, serviceType, host;
  final int port;
  final bool isIpv4;
  const TvDevice({required this.id, required this.name, required this.serviceType, required this.host, required this.port, required this.isIpv4});
  bool get supportsRemoteV2Pairing => serviceType.contains('androidtvremote2');
  factory TvDevice.fromMap(Map<dynamic, dynamic> m) => TvDevice(
    id: '${m['id']}', name: '${m['name']}', serviceType: '${m['serviceType']}',
    host: '${m['host']}', port: (m['port'] as num).toInt(), isIpv4: m['isIpv4'] == true,
  );
}

class RemoteHome extends StatefulWidget {
  const RemoteHome({super.key});
  @override
  State<RemoteHome> createState() => _RemoteHomeState();
}

class _RemoteHomeState extends State<RemoteHome> {
  static const _methods = MethodChannel('remote_atv/discovery');
  static const _events = EventChannel('remote_atv/discovery_events');
  StreamSubscription<dynamic>? _subscription;
  final Map<String, TvDevice> _devices = {};
  final ValueNotifier<int> _deviceRevision = ValueNotifier<int>(0);
  String deviceName = '尚未連線';
  String discoveryStatus = '尚未搜尋';
  int tab = 0;
  bool scanning = false;
  bool connected = false;
  bool voiceSupported = false;
  bool voiceActive = false;
  bool tvVolumeKnown = false;
  bool tvVolumeMuted = false;
  bool remoteServiceKnown = false;
  bool remoteServiceStarted = false;
  int tvVolumeLevel = 0;
  int tvVolumeMax = 0;
  bool hapticFeedback = true;
  bool naturalHold = true;
  String remoteLayout = 'tap';
  String? selectedHost;
  String? _pairedServiceName;
  String? _pairedServiceType;
  int _reconnectAttempts = 0;
  static const int _maxReconnectAttempts = 12;
  Timer? _reconnectTimer;
  bool _reconnectInFlight = false;
  bool _manualDisconnect = false;
  String _tvInputText = '';
  int _tvSelectionStart = 0;
  int _tvSelectionEnd = 0;
  bool _tvInputActive = false;
  final ValueNotifier<int> _imeRevision = ValueNotifier<int>(0);
  final Set<int> _pressedKeyCodes = <int>{};
  final List<String> _diagnosticLogs = <String>[];
  Map<dynamic, dynamic> _connectionDiagnostics = <dynamic, dynamic>{};
  double _touchTravel = 0;
  int? _touchAxis; // 0 = horizontal, 1 = vertical
  int _touchDirection = 0;

  void _addDiagnostic(String message) {
    final now = DateTime.now();
    final stamp = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
    _diagnosticLogs.insert(0, '[$stamp] $message');
    if (_diagnosticLogs.length > 200) _diagnosticLogs.removeLast();
  }

  @override
  void initState() {
    super.initState();
    _subscription = _events.receiveBroadcastStream().listen(_onDiscoveryEvent, onError: (e) {
      if (mounted) setState(() => discoveryStatus = '探索錯誤：$e');
    });
    _loadUiSettings();
    _loadSavedDevice();
  }
  @override
  void dispose() { _reconnectTimer?.cancel(); _subscription?.cancel(); _deviceRevision.dispose(); _imeRevision.dispose(); _methods.invokeMethod('stopDiscovery'); super.dispose(); }

  void _onDiscoveryEvent(dynamic event) {
    if (!mounted || event is! Map) return;
    final type = '${event['type'] ?? 'unknown'}';
    final detail = event['message'] ?? event['state'] ?? event['supported'] ?? '';
    if (type != 'volume_state' && type != 'ime_status') _addDiagnostic('事件 $type $detail');
    if (event['type'] == 'devices') {
      final list = (event['devices'] as List? ?? []);
      setState(() {
        _devices.clear();
        for (final item in list) { if (item is Map) { final d = TvDevice.fromMap(item); _devices[d.id] = d; } }
        discoveryStatus = _devices.isEmpty ? '正在搜尋電視…' : '找到 ${_devices.length} 台裝置';
      });
      _deviceRevision.value++;
      _refreshPairedEndpointFromDiscovery();
    } else if (event['type'] == 'status') {
      setState(() => discoveryStatus = '${event['message'] ?? ''}');
      _deviceRevision.value++;
    } else if (event['type'] == 'pairing') {
      final state = '${event['state'] ?? ''}';
      final message = '${event['message'] ?? ''}';
      if (state == 'progress') {
        final stage = '${event['stage'] ?? '配對處理'}';
        _addDiagnostic('配對階段：$stage；$message；裝置=${event['device'] ?? ''}；位址=${event['host'] ?? ''}');
        if (mounted) setState(() => deviceName = '配對中：$stage');
      } else if (state == 'paired') {
        _addDiagnostic('配對完成：$message');
        if (mounted) setState(() => deviceName = '${event['device'] ?? 'Android TV'}（已配對）');
        _notice(message);
      } else if (state == 'failed') {
        _addDiagnostic('配對失敗：階段=${event['stage'] ?? ''}；$message');
        _notice(message);
      }
    } else if (event['type'] == 'voice_capability') {
      if (mounted) setState(() => voiceSupported = event['supported'] == true);
    } else if (event['type'] == 'voice') {
      if (mounted) setState(() => voiceActive = event['active'] == true);
      if (event['active'] != true && '${event['message'] ?? ''}'.contains('中斷')) _notice('${event['message']}');
    } else if (event['type'] == 'remote_service_state') {
      if (mounted) setState(() {
        remoteServiceKnown = event['known'] == true;
        remoteServiceStarted = event['started'] == true;
      });
    } else if (event['type'] == 'volume_state') {
      if (mounted) setState(() {
        tvVolumeKnown = event['known'] == true;
        tvVolumeLevel = (event['level'] as num?)?.toInt() ?? 0;
        tvVolumeMax = (event['max'] as num?)?.toInt() ?? 0;
        tvVolumeMuted = event['muted'] == true;
      });
    } else if (event['type'] == 'volume_adjustment_signal') {
      // The TV's adjustment message has no documented payload; state remains
      // authoritative only when a RemoteSetVolumeLevel update arrives.
    } else if (event['type'] == 'ime_status') {
      setState(() {
        _tvInputActive = event['active'] == true;
        _tvInputText = '${event['text'] ?? ''}';
        _tvSelectionStart = (event['start'] as num?)?.toInt() ?? 0;
        _tvSelectionEnd = (event['end'] as num?)?.toInt() ?? _tvSelectionStart;
      });
      _imeRevision.value++;
    } else if (event['type'] == 'remote') {
      final state = '${event['state'] ?? ''}';
      final message = '${event['message'] ?? ''}';
      if (mounted) setState(() {
        connected = state == 'connected';
        if (state == 'disconnected') { voiceSupported = false; voiceActive = false; tvVolumeKnown = false; tvVolumeLevel = 0; tvVolumeMax = 0; tvVolumeMuted = false; remoteServiceKnown = false; remoteServiceStarted = false; }
        if (state == 'connected') { deviceName = '${event['device'] ?? 'Android TV'}（已連線）'; _reconnectAttempts = 0; }
        if (state == 'disconnected') { deviceName = '${event['device'] ?? 'Android TV'}（已配對／未連線）'; _tvInputActive = false; _tvInputText = ''; _tvSelectionStart = 0; _tvSelectionEnd = 0; _imeRevision.value++; }
      });
      if (state == 'disconnected') {
        final securityFailure = message.contains('憑證') || message.contains('TLS') || message.contains('身分驗證');
        if (!securityFailure && !_manualDisconnect) _scheduleReconnect();
        else if (securityFailure) _notice('$message\n安全驗證未通過，已停止自動重連；請確認電視是否重設或重新配對。');
      }
    }
  }

  Future<void> _startDiscovery() async {
    setState(() { scanning = true; _devices.clear(); discoveryStatus = '正在搜尋 Android TV…'; });
    try { await _methods.invokeMethod('startDiscovery'); }
    on PlatformException catch (e) { setState(() => discoveryStatus = '無法啟動搜尋：${e.message}'); }
  }
  Future<void> _stopDiscovery() async {
    await _methods.invokeMethod('stopDiscovery');
    if (mounted) setState(() { scanning = false; discoveryStatus = _devices.isEmpty ? '沒有找到電視' : '搜尋完成，共 ${_devices.length} 台'; });
  }
  Future<void> _selectDevice(TvDevice d) async {
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _reconnectAttempts = 0;
    Navigator.pop(context);
    if (!d.supportsRemoteV2Pairing) {
      _notice('此電視只公告 Remote v1 服務（${d.host}:${d.port}）。目前版本尚未實作 Remote v1 配對，因此不會錯誤啟動 v2 配對流程。');
      return;
    }
    await _beginPairing(d);
  }

  Future<void> _beginPairing(TvDevice d) async {
    if (!mounted) return;
    setState(() => deviceName = '正在連接電視：${d.name}');
    _addDiagnostic('使用者選擇電視：${d.name}；位址=${d.host}；服務=${d.serviceType}；連接埠=${d.port}');
    _notice('正在連接「${d.name}」，請稍候；電視應顯示配對碼。');
    try {
      // Do not gate pairing on a separate TCP probe. Some Android TV builds
      // accept the TLS pairing socket while a preliminary plain TCP probe is
      // rejected or races with the TV service startup.
      setState(() => deviceName = '正在建立安全配對：${d.name}');
      await _methods.invokeMethod('beginPairing', {
        'host': d.host, 'port': d.port, 'name': d.name, 'serviceType': d.serviceType,
      }).timeout(const Duration(seconds: 45));
      if (!mounted) return;
      _addDiagnostic('電視已完成配對初始化，等待輸入 PIN');
      final controller = TextEditingController();
      final pin = await showDialog<String>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          backgroundColor: const Color(0xFF202126),
          title: const Text('輸入電視配對碼'),
          content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('請查看「${d.name}」電視畫面，輸入顯示的 6 位數配對碼。', style: const TextStyle(color: Colors.white70)),
            const SizedBox(height: 16),
            TextField(
              controller: controller, autofocus: true, maxLength: 6,
              textCapitalization: TextCapitalization.characters,
              keyboardType: TextInputType.visiblePassword,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 25, letterSpacing: 8, fontWeight: FontWeight.bold),
              decoration: const InputDecoration(hintText: 'A1B2C3', counterText: '', border: OutlineInputBorder()),
            ),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.pop(dialogContext, controller.text.trim()), child: const Text('確認配對')),
          ],
        ),
      );
      controller.dispose();
      if (pin == null) {
        await _methods.invokeMethod('cancelPairing');
        if (mounted) setState(() => deviceName = '尚未連線');
        return;
      }
      if (!RegExp(r'^[0-9a-fA-F]{6}$').hasMatch(pin)) {
        await _methods.invokeMethod('cancelPairing');
        if (mounted) { setState(() => deviceName = '尚未連線'); _notice('配對碼格式錯誤，請輸入電視顯示的 6 位數十六進位代碼（0–9、A–F）。'); }
        return;
      }
      setState(() => deviceName = '正在確認配對碼：${d.name}');
      await _methods.invokeMethod('finishPairing', {'pin': pin.toUpperCase()}).timeout(const Duration(seconds: 30));
      if (mounted) {
        setState(() { deviceName = '${d.name}（已配對，正在連線）'; selectedHost = d.host; });
        _pairedServiceName = d.name;
        _pairedServiceType = d.serviceType;
        _manualDisconnect = false;
        await _connectRemote(d.host, d.name);
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => deviceName = '尚未連線');
        _addDiagnostic('配對平台錯誤：${e.code}；${e.message ?? ''}');
        _notice(e.message ?? '配對失敗，請重新搜尋電視');
      }
    } on TimeoutException catch (e) {
      if (mounted) {
        setState(() => deviceName = '配對逾時，請重新搜尋');
        _addDiagnostic('配對流程逾時：$e');
        _notice('電視在期限內沒有完成配對回應。請確認電視仍停留在配對畫面，再重新選擇電視。');
      }
    } catch (e) {
      if (mounted) { setState(() => deviceName = '尚未連線'); _addDiagnostic('配對例外：$e'); _notice('配對失敗：$e'); }
    }
  }

  Future<void> _loadUiSettings() async {
    try {
      final value = await _methods.invokeMethod<dynamic>('getUiSettings');
      if (value is Map && mounted) setState(() {
        hapticFeedback = value['haptic'] != false;
        naturalHold = value['naturalHold'] != false;
        remoteLayout = value['remoteLayout'] == 'swipe' ? 'swipe' : 'tap';
      });
    } catch (_) { }
  }

  Future<void> _saveUiSettings() async {
    try { await _methods.invokeMethod('setUiSettings', {'haptic': hapticFeedback, 'naturalHold': naturalHold, 'remoteLayout': remoteLayout}); }
    catch (e) { if (mounted) _notice('設定儲存失敗：$e'); }
  }

  Future<void> _loadSavedDevice() async {
    try {
      final saved = await _methods.invokeMethod<dynamic>('getPairedDevice');
      if (saved is Map && saved['host'] != null && mounted) {
        final host = '${saved['host']}';
        final name = '${saved['name'] ?? 'Android TV'}';
        _pairedServiceName = '${saved['serviceName'] ?? name}';
        _pairedServiceType = '${saved['serviceType'] ?? '_androidtvremote2._tcp.'}';
        final port = (saved['port'] as num?)?.toInt() ?? 6466;
        setState(() { selectedHost = host; deviceName = '$name（已配對／正在連線）'; });
        await _connectRemote(host, name, port: port, quiet: true);
        // Refresh mDNS endpoint in background; the pinned certificate is not changed.
        _startDiscovery();
      }
    } catch (_) { /* First launch or no saved pairing. */ }
  }

  Future<void> _refreshPairedEndpointFromDiscovery() async {
    final serviceName = _pairedServiceName;
    if (serviceName == null) return;
    final matches = _devices.values.where((d) =>
        d.name == serviceName && d.supportsRemoteV2Pairing &&
        (_pairedServiceType == null || d.serviceType == _pairedServiceType)).toList();
    final uniqueHosts = matches.map((d) => d.host).toSet();
    if (uniqueHosts.length != 1 || matches.isEmpty) {
      if (uniqueHosts.length > 1 && mounted) _notice('搜尋到多台同名電視，請手動選擇，避免連線到錯誤裝置。');
      return;
    }
    final d = matches.first;
    try {
      final accepted = await _methods.invokeMethod<bool>('updatePairedEndpoint', {
        'host': d.host, 'port': 6466, 'serviceName': serviceName, 'serviceType': d.serviceType,
      });
      if (accepted == true && selectedHost != d.host) {
        selectedHost = d.host;
        if (!connected && !_manualDisconnect) _scheduleReconnect(immediate: true);
      }
    } catch (_) { }
  }

  void _scheduleReconnect({bool immediate = false}) {
    if (!mounted || _manualDisconnect || connected ||
        _reconnectAttempts >= _maxReconnectAttempts || _reconnectInFlight) return;
    // Avoid duplicate timers when both the socket event and connect call report
    // the same failure.
    if (_reconnectTimer?.isActive == true) return;
    final seconds = [2, 4, 8, 16, 30][_reconnectAttempts.clamp(0, 4)];
    final delay = immediate ? Duration.zero : Duration(seconds: seconds);
    _reconnectAttempts++;
    if (mounted) setState(() => deviceName =
        '連線中斷，重新搜尋並嘗試連線（${_reconnectAttempts}/$_maxReconnectAttempts）');
    _reconnectTimer = Timer(delay, () async {
      _reconnectTimer = null;
      if (!mounted || _manualDisconnect || connected || _reconnectInFlight) return;
      _reconnectInFlight = true;
      try {
        // Refresh mDNS advertisements before using the saved endpoint. This
        // handles TVs that received a new DHCP address after standby/reboot.
        await _startDiscovery();
        await Future<void>.delayed(const Duration(milliseconds: 1800));
        if (!mounted || _manualDisconnect) return;
        await _refreshPairedEndpointFromDiscovery();
        final saved = await _methods.invokeMethod<dynamic>('getPairedDevice');
        if (saved is! Map || saved['host'] == null || !mounted || _manualDisconnect) return;
        final host = '${saved['host']}';
        final name = '${saved['name'] ?? 'Android TV'}';
        final port = (saved['port'] as num?)?.toInt() ?? 6466;
        selectedHost = host;
        await _connectRemote(host, name, port: port, quiet: true);
      } catch (e) {
        if (mounted) setState(() => discoveryStatus = '重新搜尋中：$e');
      } finally {
        _reconnectInFlight = false;
        if (mounted && !_manualDisconnect && !connected &&
            _reconnectAttempts < _maxReconnectAttempts) {
          _scheduleReconnect();
        } else if (mounted && !connected && _reconnectAttempts >= _maxReconnectAttempts) {
          setState(() => deviceName = '暫時無法連線，仍可手動搜尋或重新配對');
        }
      }
    });
  }

  Future<void> _connectRemote(String host, String name, {int port = 6466, bool quiet = false}) async {
    try {
      await _methods.invokeMethod('connectRemote', {'host': host, 'port': port, 'name': name});
      if (mounted) setState(() { connected = true; deviceName = '$name（已連線）'; selectedHost = host; _reconnectAttempts = 0; _reconnectTimer?.cancel(); _reconnectTimer = null; });
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() { connected = false; deviceName = '$name（已配對／未連線）'; });
        final detail = e.message ?? '遙控連線失敗';
        final securityFailure = detail.contains('憑證') || detail.contains('TLS') || detail.contains('身分驗證');
        if (!quiet) _notice(detail);
        else if (!securityFailure && _reconnectAttempts < 3) _scheduleReconnect();
        else if (securityFailure) _notice('$detail\n安全驗證未通過，已停止自動重連。');
      }
    }
  }

  Future<void> _toggleVoice() async {
    if (!connected || !voiceSupported) { _notice('目前電視未提供 Remote v2 語音功能'); return; }
    if (voiceActive) {
      try { await _methods.invokeMethod('stopVoice'); }
      on PlatformException catch (e) { _notice(e.message ?? '停止語音失敗'); }
      return;
    }
    final permission = await Permission.microphone.request();
    if (!permission.isGranted) { _notice('需要允許麥克風權限才能使用電視語音搜尋'); return; }
    try {
      await _methods.invokeMethod('startVoice');
    } on PlatformException catch (e) {
      if (mounted) _notice(e.message ?? '無法啟動語音輸入');
    }
  }

  Future<void> _sendKey(int code, {int direction = 3}) async {
    if (!connected) { _addDiagnostic('按鍵 $code 未送出：目前未連線'); _notice('請先搜尋並配對 Android TV'); return; }
    try {
      await _methods.invokeMethod('sendKey', {'keyCode': code, 'direction': direction});
      _addDiagnostic('按鍵已交給傳輸層：keyCode=$code direction=$direction（不代表電視已執行）');
    } on PlatformException catch (e) {
      _addDiagnostic('按鍵傳送失敗：keyCode=$code code=${e.code} message=${e.message}');
      if (mounted) _notice(e.message ?? '按鍵傳送失敗');
    }
  }

  void _notice(String message) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message), behavior: SnackBarBehavior.floating));

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Remote ATV', style: TextStyle(fontWeight: FontWeight.w600)), actions: [IconButton(tooltip: voiceActive ? '停止語音' : '語音輸入', onPressed: !connected || !voiceSupported ? null : _toggleVoice, icon: Icon(voiceActive ? Icons.mic : Icons.mic_none, color: voiceActive ? const Color(0xFFB9F36A) : null)), IconButton(onPressed: _showDevices, icon: const Icon(Icons.cast_connected)), const SizedBox(width: 8)]),
    body: SafeArea(child: Column(children: [_deviceBar(), Expanded(child: IndexedStack(index: tab, children: [_remotePage(), _touchPage(), _settingsPage()]))])),
    bottomNavigationBar: NavigationBar(backgroundColor: const Color(0xFF191A1F), selectedIndex: tab, onDestinationSelected: (v) => setState(() => tab = v), destinations: const [
      NavigationDestination(icon: Icon(Icons.gamepad_outlined), selectedIcon: Icon(Icons.gamepad), label: '遙控器'),
      NavigationDestination(icon: Icon(Icons.touch_app_outlined), selectedIcon: Icon(Icons.touch_app), label: '觸控板'),
      NavigationDestination(icon: Icon(Icons.settings_outlined), selectedIcon: Icon(Icons.settings), label: '設定'),
    ]),
  );
  Widget _deviceBar() => Padding(padding: const EdgeInsets.fromLTRB(16, 4, 16, 12), child: InkWell(borderRadius: BorderRadius.circular(16), onTap: _showDevices, child: Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: const Color(0xFF202126), borderRadius: BorderRadius.circular(16)), child: Row(children: [const Icon(Icons.tv, color: Color(0xFFB9F36A)), const SizedBox(width: 12), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(deviceName, style: const TextStyle(fontWeight: FontWeight.w600)), const Text('點選以搜尋或連接電視', style: TextStyle(color: Colors.white60, fontSize: 12))])), const Icon(Icons.chevron_right)]))));
  Widget _topRemoteActions() => Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
    _roundIcon('power.svg', '電源'), _roundIcon('input.svg', '輸入來源'), _roundIcon('keyboard.svg', '鍵盤'), _roundIcon('home.svg', '首頁'), _roundIcon('return.svg', '返回'),
  ]);

  Widget _directionPad(double size) {
    final center = size * 0.39;
    final arrow = (size * 0.15).clamp(30.0, 42.0);
    return Container(width: size, height: size, decoration: BoxDecoration(color: const Color(0xFF202126), shape: BoxShape.circle, border: Border.all(color: Colors.white10)), child: Stack(alignment: Alignment.center, children: [
      Positioned(top: 4, child: _holdSurface(19, Padding(padding: const EdgeInsets.all(6), child: Icon(Icons.keyboard_arrow_up, size: arrow)), semanticLabel: '方向上')),
      Positioned(bottom: 4, child: _holdSurface(20, Padding(padding: const EdgeInsets.all(6), child: Icon(Icons.keyboard_arrow_down, size: arrow)), semanticLabel: '方向下')),
      Positioned(left: 4, child: _holdSurface(21, Padding(padding: const EdgeInsets.all(6), child: Icon(Icons.keyboard_arrow_left, size: arrow)), semanticLabel: '方向左')),
      Positioned(right: 4, child: _holdSurface(22, Padding(padding: const EdgeInsets.all(6), child: Icon(Icons.keyboard_arrow_right, size: arrow)), semanticLabel: '方向右')),
      Container(width: center, height: center, decoration: const BoxDecoration(color: Color(0xFFB9F36A), shape: BoxShape.circle), child: Semantics(button: true, label: '確認', child: TextButton(onPressed: () { if (hapticFeedback) HapticFeedback.lightImpact(); _sendKey(23); }, child: Text('OK', style: TextStyle(color: const Color(0xFF151515), fontSize: (size * .075).clamp(17.0, 22.0), fontWeight: FontWeight.bold))))),
    ]));
  }

  Widget _volumeControls() => Column(mainAxisSize: MainAxisSize.min, children: [
    Row(mainAxisAlignment: MainAxisAlignment.center, children: [_assetPill('volume.svg', '音量－', 25), const SizedBox(width: 10), _assetPill('mute.svg', '靜音', 164), const SizedBox(width: 10), _assetPill('volume.svg', '音量＋', 24)]),
    const SizedBox(height: 6), _volumeStatus(), const SizedBox(height: 4), _remoteServiceStatus(),
  ]);

  Widget _channelControls() => Row(mainAxisAlignment: MainAxisAlignment.center, children: [_textPill('頻道－', 167), const SizedBox(width: 12), _textPill('上一頻道', 229), const SizedBox(width: 12), _textPill('頻道＋', 166)]);

  Widget _mediaControls() => Row(mainAxisAlignment: MainAxisAlignment.center, children: [
    _compactAssetPill('skip-previous.svg', '上一段', 88), const SizedBox(width: 4), _compactAssetPill('rewind.svg', '倒轉', 89), const SizedBox(width: 4), _compactAssetPill('play-pause.svg', '播放', 85), const SizedBox(width: 4), _compactAssetPill('fast-forward.svg', '快轉', 90), const SizedBox(width: 4), _compactAssetPill('skip-next.svg', '下一段', 87),
  ]);

  Widget _remotePage() => LayoutBuilder(builder: (context, constraints) {
    final wide = constraints.maxWidth > constraints.maxHeight && constraints.maxWidth > 600;
    final padSize = (wide ? constraints.maxHeight - 95 : constraints.maxWidth - 44).clamp(160.0, 286.0);
    if (wide) return Padding(padding: const EdgeInsets.fromLTRB(18, 8, 18, 12), child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
      Expanded(flex: 5, child: SingleChildScrollView(child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [_topRemoteActions(), const SizedBox(height: 10), _directionPad(padSize)]))),
      const SizedBox(width: 18),
      Expanded(flex: 4, child: SingleChildScrollView(child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [_volumeControls(), const SizedBox(height: 22), _channelControls(), const SizedBox(height: 22), _mediaControls()]))),
    ]));
    return SingleChildScrollView(padding: const EdgeInsets.fromLTRB(18, 6, 18, 24), child: Column(children: [
      _topRemoteActions(), const SizedBox(height: 20), _directionPad(padSize), const SizedBox(height: 20), _volumeControls(), const SizedBox(height: 14), _channelControls(), const SizedBox(height: 14), _mediaControls(),
    ]));
  });

  void _beginTouchGesture() {
    _touchTravel = 0;
    _touchAxis = null;
    _touchDirection = 0;
  }

  void _handleTouchGesture(DragUpdateDetails details) {
    final dx = details.delta.dx;
    final dy = details.delta.dy;
    if (_touchAxis == null) {
      if (dx.abs() < 3 && dy.abs() < 3) return;
      _touchAxis = dx.abs() >= dy.abs() ? 0 : 1;
      _touchDirection = _touchAxis == 0 ? (dx >= 0 ? 1 : -1) : (dy >= 0 ? 1 : -1);
    }
    final delta = _touchAxis == 0 ? dx : dy;
    // Lock the direction for this gesture; ignore small opposite-direction jitter.
    if (delta * _touchDirection <= 0) return;
    _touchTravel += delta.abs();
    const stepDistance = 48.0;
    var sent = 0;
    while (_touchTravel >= stepDistance && sent < 4) {
      _touchTravel -= stepDistance;
      final keyCode = _touchAxis == 0
          ? (_touchDirection > 0 ? 22 : 21)
          : (_touchDirection > 0 ? 20 : 19);
      if (hapticFeedback) HapticFeedback.selectionClick();
      _sendKey(keyCode);
      sent++;
    }
  }

  Widget _remoteServiceStatus() {
    if (!connected) return const Text('電視遙控服務：未連線', style: TextStyle(color: Colors.white38, fontSize: 11));
    if (!remoteServiceKnown) return const Text('電視遙控服務：等待狀態回報…', style: TextStyle(color: Colors.white38, fontSize: 11));
    return Row(mainAxisAlignment: MainAxisAlignment.center, children: [
      Icon(remoteServiceStarted ? Icons.check_circle_outline : Icons.info_outline,
          size: 13, color: remoteServiceStarted ? const Color(0xFFB9F36A) : Colors.orangeAccent),
      const SizedBox(width: 5),
      Text(remoteServiceStarted ? '電視遙控服務：已啟動' : '電視遙控服務：未啟動',
          style: const TextStyle(color: Colors.white54, fontSize: 11)),
    ]);
  }

  Widget _volumeStatus() {
    if (!connected) return const Text('電視音量狀態：尚未連線', style: TextStyle(color: Colors.white38, fontSize: 11));
    if (!tvVolumeKnown || tvVolumeMax <= 0) return const Text('等待電視回報音量狀態…', style: TextStyle(color: Colors.white38, fontSize: 11));
    final percent = (tvVolumeLevel / tvVolumeMax * 100).round();
    return Row(mainAxisAlignment: MainAxisAlignment.center, children: [
      Icon(tvVolumeMuted ? Icons.volume_off : Icons.volume_up, size: 14, color: tvVolumeMuted ? Colors.orangeAccent : Colors.white54),
      const SizedBox(width: 6),
      Text(tvVolumeMuted ? '已靜音' : '電視音量 $tvVolumeLevel / $tvVolumeMax（$percent%）',
          style: const TextStyle(color: Colors.white54, fontSize: 11)),
    ]);
  }

  Widget _touchPage() => LayoutBuilder(builder: (context, constraints) { final landscape = constraints.maxWidth > constraints.maxHeight; return Padding(padding: EdgeInsets.all(landscape ? 10 : 20), child: Column(children: [if (!landscape) ...[const SizedBox(height: 8), const Text('觸控板', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)), const SizedBox(height: 16)], Expanded(child: GestureDetector(
      onTap: () => _sendKey(23),
      onPanStart: (_) => _beginTouchGesture(),
      onPanUpdate: _handleTouchGesture,
      child: Container(width: double.infinity, decoration: BoxDecoration(color: const Color(0xFF202126), borderRadius: BorderRadius.circular(28), border: Border.all(color: Colors.white12)), child: const Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Icon(Icons.swipe, size: 54, color: Color(0xFFB9F36A)), SizedBox(height: 12), Text('滑動移動・點擊確認・長按連續操作'), Text('需先完成電視配對及連線', style: TextStyle(color: Colors.white54, fontSize: 12))]))))), const SizedBox(height: 8), Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [IconButton(onPressed: () => _sendKey(4), icon: const Icon(Icons.arrow_back)), IconButton(onPressed: () => _sendKey(3), icon: const Icon(Icons.home)), IconButton(onPressed: () => _sendKey(82), icon: const Icon(Icons.menu))])])); });
  Widget _settingsPage() => ListView(padding: const EdgeInsets.all(16), children: [
    _setting(Icons.dashboard_outlined, 'Remote layout', remoteLayout == 'tap' ? 'Tap based control' : 'Swipe based touchpad', _showRemoteLayout),
    _setting(Icons.tune, 'Behavior settings', 'Haptic feedback、按住操作行為', _showBehaviorSettings),
    _setting(Icons.devices, 'Connected devices', connected ? deviceName : 'No connected device', _showConnectedDevices),
    _setting(Icons.tv, 'TV settings', 'Brightness、Picture mode、HDR 等電視控制', _showTvSettings),
    _setting(Icons.bug_report_outlined, '連線診斷', '查看配對、連線、按鍵傳送及錯誤記錄', _showDiagnostics),
    _setting(Icons.info_outline, 'About Remote ATV', '獨立開發版本 $appVersionLabel'),
  ]);

  Future<void> _refreshConnectionDiagnostics() async {
    try {
      final value = await _methods.invokeMapMethod<dynamic, dynamic>('getConnectionDiagnostics');
      if (mounted && value != null) setState(() => _connectionDiagnostics = value);
    } on PlatformException catch (e) {
      _addDiagnostic('讀取連線健康資訊失敗：${e.message}');
    }
  }

  String _durationLabel(dynamic value) {
    final ms = (value as num?)?.toInt() ?? 0;
    if (ms <= 0) return '—';
    final seconds = ms ~/ 1000;
    return seconds >= 60 ? '${seconds ~/ 60} 分 ${seconds % 60} 秒' : '$seconds 秒';
  }

  void _showDiagnostics() {
    _addDiagnostic('使用者開啟連線診斷');
    _refreshConnectionDiagnostics();
    showDialog<void>(context: context, builder: (ctx) => StatefulBuilder(builder: (ctx, refreshDialog) => AlertDialog(
      title: const Text('連線診斷記錄'),
      content: SizedBox(width: 560, height: 480, child: Column(children: [
        Row(children: [
          Expanded(child: Text('版本：$appVersionLabel\n連線：${connected ? "已連線" : "未連線"}\n裝置：$deviceName\n搜尋：$discoveryStatus\n遙控服務：${!connected ? "未連線" : !remoteServiceKnown ? "等待回報" : remoteServiceStarted ? "已啟動" : "未啟動"}\n音量：${!connected ? "未連線" : !tvVolumeKnown ? "等待回報" : tvVolumeMuted ? "靜音" : "$tvVolumeLevel / $tvVolumeMax"}', style: const TextStyle(fontSize: 12, color: Colors.white70))),
          IconButton(tooltip: '更新連線資訊', onPressed: () async { await _refreshConnectionDiagnostics(); refreshDialog(() {}); }, icon: const Icon(Icons.refresh)),
          IconButton(tooltip: '複製記錄', onPressed: () { final d = _connectionDiagnostics; final summary = '連線健康資訊：\n${d.entries.map((e) => '${e.key}: ${e.value}').join('\n')}\n\n事件記錄：\n${_diagnosticLogs.reversed.join('\n')}'; Clipboard.setData(ClipboardData(text: summary)); _notice('診斷記錄已複製'); }, icon: const Icon(Icons.copy)),
          IconButton(tooltip: '清除記錄', onPressed: () { setState(() => _diagnosticLogs.clear()); _addDiagnostic('記錄已清除'); Navigator.pop(ctx); _showDiagnostics(); }, icon: const Icon(Icons.delete_outline)),
        ]),
        const Divider(),
        Align(alignment: Alignment.centerLeft, child: Text('連線健康資訊：TLS ${((_connectionDiagnostics['tlsHandshakeMs'] as num?)?.toInt() ?? -1) >= 0 ? '${_connectionDiagnostics['tlsHandshakeMs']}' : '—'} ms｜連線時間 ${_durationLabel(_connectionDiagnostics['uptimeMs'])}\n位址 ${_connectionDiagnostics['host'] ?? '—'}:${_connectionDiagnostics['port'] ?? '—'}｜協商功能 ${(_connectionDiagnostics['negotiatedFeatures'] as List?)?.join(', ') ?? '—'}\n接收訊息 ${_connectionDiagnostics['inboundFrames'] ?? 0}｜電視 Ping ${_connectionDiagnostics['pingRequests'] ?? 0}｜Ping 回應 ${_connectionDiagnostics['pingResponses'] ?? 0}\n音量更新 ${_connectionDiagnostics['volumeUpdates'] ?? 0}｜IME 更新 ${_connectionDiagnostics['imeUpdates'] ?? 0}｜RemoteStart ${_connectionDiagnostics['remoteStartMessages'] ?? 0}\n最後接收 ${_durationLabel(_connectionDiagnostics['lastInboundAgeMs'])} 前｜最近斷線：${_connectionDiagnostics['lastDisconnectReason'] ?? '—'}', style: const TextStyle(fontSize: 11, color: Colors.white60))),
        const Divider(),
        Expanded(child: _diagnosticLogs.isEmpty
          ? const Center(child: Text('目前沒有診斷記錄'))
          : ListView.builder(itemCount: _diagnosticLogs.length, itemBuilder: (context, index) => Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: SelectableText(_diagnosticLogs[index], style: const TextStyle(fontSize: 11, fontFamily: 'monospace'))))),
      ])),
      actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('關閉'))],
    )));
  }

  Widget _setting(IconData icon, String title, String subtitle, [VoidCallback? action]) => Card(color: const Color(0xFF202126), child: ListTile(leading: Icon(icon, color: const Color(0xFFB9F36A)), title: Text(title), subtitle: Text(subtitle), trailing: const Icon(Icons.chevron_right), onTap: action ?? () {}));

  void _showRemoteLayout() {
    showModalBottomSheet(context: context, backgroundColor: const Color(0xFF202126), builder: (ctx) => SafeArea(child: Padding(padding: const EdgeInsets.all(20), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Text('Remote layout', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
      const SizedBox(height: 8),
      RadioListTile<String>(value: 'tap', groupValue: remoteLayout, title: const Text('Tap based control'), subtitle: const Text('Tap based control, as found on physical remote.'), onChanged: (v) { setState(() => remoteLayout = v!); _saveUiSettings(); Navigator.pop(ctx); }),
      RadioListTile<String>(value: 'swipe', groupValue: remoteLayout, title: const Text('Swipe based touchpad'), subtitle: const Text('Swipe based touchpad, quick and convenient.'), onChanged: (v) { setState(() => remoteLayout = v!); _saveUiSettings(); Navigator.pop(ctx); }),
    ]))));
  }

  void _showBehaviorSettings() {
    showModalBottomSheet(context: context, backgroundColor: const Color(0xFF202126), isScrollControlled: true, builder: (ctx) => StatefulBuilder(builder: (ctx, refresh) => SafeArea(child: Padding(padding: const EdgeInsets.all(20), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Text('Behavior settings', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
      SwitchListTile(title: const Text('Haptic feedback'), subtitle: const Text('A light vibration feedback when remote buttons are pressed'), value: hapticFeedback, onChanged: (v) { setState(() => hapticFeedback = v); refresh(() {}); _saveUiSettings(); }),
      SwitchListTile(title: const Text('Natural press and hold'), subtitle: const Text('Try to mimic physical remote natural behavior on Press and Hold volume, channel and navigation keys'), value: naturalHold, onChanged: (v) { setState(() => naturalHold = v); refresh(() {}); _saveUiSettings(); }),
    ])))));
  }

  void _showConnectedDevices() {
    _methods.invokeMethod<dynamic>('getPairedDevice').then((saved) {
      if (!mounted) return;
      if (saved is! Map || saved['host'] == null) { _notice('No connected device'); return; }
      final currentName = '${saved['name'] ?? 'Android TV'}';
      final controller = TextEditingController(text: currentName);
      showModalBottomSheet(context: context, backgroundColor: const Color(0xFF202126), builder: (ctx) => SafeArea(child: Padding(padding: const EdgeInsets.all(20), child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Text('Connected devices', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
        ListTile(leading: const Icon(Icons.tv, color: Color(0xFFB9F36A)), title: Text(currentName), subtitle: Text('${saved['host']}')),
        TextField(controller: controller, decoration: const InputDecoration(labelText: 'Device nickname')),
        const SizedBox(height: 12),
        Row(children: [Expanded(child: OutlinedButton(onPressed: () async { final name = controller.text.trim(); if (name.isEmpty) return; await _methods.invokeMethod('renamePairedDevice', {'nickname': name}); if (mounted) setState(() => deviceName = connected ? '$name（已連線）' : '$name（已配對／未連線）'); if (ctx.mounted) Navigator.pop(ctx); }, child: const Text('儲存名稱'))), const SizedBox(width: 10), Expanded(child: FilledButton.tonal(onPressed: () async { final ok = await showDialog<bool>(context: ctx, builder: (d) => AlertDialog(title: const Text('Delete this device?'), content: const Text('確定要刪除此裝置的配對資料嗎？'), actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: const Text('取消')), FilledButton(onPressed: () => Navigator.pop(d, true), child: const Text('刪除'))])); if (ok == true) { _manualDisconnect = true; _reconnectTimer?.cancel(); _reconnectTimer = null; _reconnectAttempts = 0; _pairedServiceName = null; _pairedServiceType = null; _reconnectAttempts = 0; await _methods.invokeMethod('deletePairedDevice'); if (mounted) setState(() { connected = false; selectedHost = null; deviceName = '尚未連線'; }); if (ctx.mounted) Navigator.pop(ctx); } }, child: const Text('刪除裝置')))]),
      ]))));
    }).catchError((_) { if (mounted) _notice('無法讀取已配對裝置'); });
  }

  void _showTvSettings() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF202126),
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('TV settings', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
              const SizedBox(height: 6),
              const Text('依原版 APK 圖示資產整理。僅啟用目前 Remote v2 按鍵協定可直接傳送的控制。', style: TextStyle(color: Colors.white60, fontSize: 12)),
              const SizedBox(height: 18),
              _tvSettingRow('brightness.svg', 'Brightness', '以電視 Remote 按鍵調整亮度', Row(mainAxisSize: MainAxisSize.min, children: [
                IconButton(tooltip: 'Brightness down', onPressed: connected ? () => _sendKey(220) : null, icon: const Icon(Icons.remove_circle_outline)),
                IconButton(tooltip: 'Brightness up', onPressed: connected ? () => _sendKey(221) : null, icon: const Icon(Icons.add_circle_outline)),
              ])),
              const Divider(color: Colors.white12),
              _tvSettingRow('picture_mode.svg', 'Picture mode', '需電視廠牌／型號專屬控制協定', const _UnavailableSetting()),
              _tvSettingRow('color_temperature.svg', 'Color temperature', '需電視廠牌／型號專屬控制協定', const _UnavailableSetting()),
              _tvSettingRow('hdr.svg', 'HDR', '需電視廠牌／型號專屬控制協定', const _UnavailableSetting()),
              _tvSettingRow('power_savings.svg', 'Power savings', '需電視廠牌／型號專屬控制協定', const _UnavailableSetting()),
              _tvSettingRow('light_sensor.svg', 'Light sensor', '需電視廠牌／型號專屬控制協定', const _UnavailableSetting()),
              const SizedBox(height: 12),
              if (!connected) const Text('請先連接 Android TV，才能使用亮度按鍵。', style: TextStyle(color: Color(0xFFFFCC80), fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _tvSettingRow(String asset, String title, String subtitle, Widget trailing) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 9),
    child: Row(children: [
      SizedBox(width: 30, height: 30, child: SvgPicture.asset('assets/images/$asset')),
      const SizedBox(width: 12),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
        Text(subtitle, style: const TextStyle(color: Colors.white54, fontSize: 11)),
      ])),
      trailing,
    ]),
  );
  Widget _roundIcon(String asset, String label) => Semantics(
    button: true,
    label: label,
    child: InkWell(
      borderRadius: BorderRadius.circular(18),
      splashColor: const Color(0x33B9F36A),
      highlightColor: const Color(0x18B9F36A),
      onTap: () {
        if (hapticFeedback) HapticFeedback.lightImpact();
        if (asset == 'power.svg') { _sendKey(26); }
        else if (asset == 'home.svg') { _sendKey(3); }
        else if (asset == 'return.svg') { _sendKey(4); }
        else if (asset == 'keyboard.svg') { _showTextInput(); }
        else if (asset == 'input.svg') { _sendKey(82); }
      },
      child: SizedBox(
        width: 64,
        height: 76,
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Container(width: 48, height: 48, decoration: const BoxDecoration(color: Color(0xFF25262B), shape: BoxShape.circle), padding: const EdgeInsets.all(13), child: SvgPicture.asset('assets/images/$asset')),
          const SizedBox(height: 5),
          Text(label, style: const TextStyle(fontSize: 10, color: Colors.white70)),
        ]),
      ),
    ),
  );
  // Android TV Remote v2 key direction: START_LONG=1, END_LONG=2, SHORT=3.
  // Physical-remote style hold is intended for navigation, volume and channel keys.
  bool _supportsNaturalHold(int keyCode) => const {
    19, 20, 21, 22, // D-pad
    24, 25,         // Volume
    166, 167,       // Channel
  }.contains(keyCode);

  void _setKeyPressed(int keyCode, bool pressed) {
    if (!mounted) return;
    setState(() {
      if (pressed) {
        _pressedKeyCodes.add(keyCode);
      } else {
        _pressedKeyCodes.remove(keyCode);
      }
    });
  }

  Widget _holdSurface(int keyCode, Widget child, {String? semanticLabel}) {
    final supportsHold = _supportsNaturalHold(keyCode);
    final pressed = _pressedKeyCodes.contains(keyCode);
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (_) => _setKeyPressed(keyCode, true),
      onTapUp: (_) => _setKeyPressed(keyCode, false),
      onTapCancel: () => _setKeyPressed(keyCode, false),
      onTap: () {
        if (hapticFeedback) HapticFeedback.lightImpact();
        _sendKey(keyCode, direction: 3);
      },
      onLongPressStart: supportsHold ? (_) {
        _setKeyPressed(keyCode, true);
        if (hapticFeedback) HapticFeedback.lightImpact();
        // When natural hold is disabled, a long press remains one short press.
        _sendKey(keyCode, direction: naturalHold ? 1 : 3);
      } : null,
      onLongPressEnd: supportsHold ? (_) {
        if (naturalHold) _sendKey(keyCode, direction: 2);
        _setKeyPressed(keyCode, false);
      } : null,
      child: Semantics(
        button: true,
        label: semanticLabel,
        child: AnimatedScale(
          scale: pressed ? 0.96 : 1.0,
          duration: const Duration(milliseconds: 85),
          curve: Curves.easeOut,
          child: child,
        ),
      ),
    );
  }

  Widget _textPill(String label, int keyCode) => _holdSurface(
    keyCode,
    Container(
      constraints: const BoxConstraints(minWidth: 82),
      height: 54,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: BoxDecoration(color: const Color(0xFF25262B), borderRadius: BorderRadius.circular(16)),
      child: Center(child: Text(label, style: const TextStyle(fontSize: 11, color: Colors.white70))),
    ),
    semanticLabel: label,
  );

  Widget _compactAssetPill(String asset, String label, int keyCode) => _holdSurface(
    keyCode,
    Container(
      width: 56,
      height: 62,
      decoration: BoxDecoration(color: const Color(0xFF25262B), borderRadius: BorderRadius.circular(14)),
      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        SvgPicture.asset('assets/images/$asset', width: 24, height: 24),
        const SizedBox(height: 3),
        Text(label, style: const TextStyle(fontSize: 9, color: Colors.white70)),
      ]),
    ),
    semanticLabel: label,
  );

  Widget _assetPill(String asset, String label, int keyCode) => _holdSurface(
    keyCode,
    Container(
      width: 76,
      height: 66,
      decoration: BoxDecoration(color: const Color(0xFF25262B), borderRadius: BorderRadius.circular(16)),
      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        SvgPicture.asset('assets/images/$asset', width: 25, height: 25),
        const SizedBox(height: 4),
        Text(label, style: const TextStyle(fontSize: 10, color: Colors.white70)),
      ]),
    ),
    semanticLabel: label,
  );

  Future<void> _showTextInput() async {
    if (!connected) { _notice('請先連接 Android TV，並在電視上開啟文字輸入欄位'); return; }
    final controller = TextEditingController();
    final focusNode = FocusNode();
    await showDialog<void>(context: context, builder: (ctx) => StatefulBuilder(builder: (ctx, refresh) => AlertDialog(
      backgroundColor: const Color(0xFF202126),
      title: const Text('輸入文字至電視'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        TextField(
          controller: controller, focusNode: focusNode, autofocus: true, minLines: 1, maxLines: 4,
          textInputAction: TextInputAction.newline,
          decoration: const InputDecoration(hintText: '請輸入文字', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 12),
        Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
          IconButton(tooltip: '游標左移', onPressed: () => _sendKeyboardKey(21), icon: const Icon(Icons.keyboard_arrow_left)),
          IconButton(tooltip: '刪除前一字元', onPressed: () => _sendKeyboardKey(67), icon: const Icon(Icons.backspace_outlined)),
          IconButton(tooltip: '游標右移', onPressed: () => _sendKeyboardKey(22), icon: const Icon(Icons.keyboard_arrow_right)),
          IconButton(tooltip: '確認／換行', onPressed: () => _sendKeyboardKey(66), icon: const Icon(Icons.keyboard_return)),
        ]),
        ValueListenableBuilder<int>(
          valueListenable: _imeRevision,
          builder: (context, revision, child) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _tvInputActive
                  ? '電視端已回報文字（${_tvInputText.runes.length} 字）｜選取範圍 $_tvSelectionStart–$_tvSelectionEnd'
                  : '等待電視輸入框狀態回報；目前不會將手機草稿誤認為電視文字。',
                style: const TextStyle(color: Colors.white54, fontSize: 11),
              ),
              if (_tvInputActive) ...[
                const SizedBox(height: 6),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(9),
                  decoration: BoxDecoration(color: const Color(0xFF17181C), borderRadius: BorderRadius.circular(8)),
                  child: Text(_tvInputText.isEmpty ? '（電視輸入框目前為空）' : _tvInputText,
                    maxLines: 3, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, color: Colors.white70)),
                ),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: () {
                      final start = _codePointToUtf16Offset(_tvInputText, _tvSelectionStart);
                      final end = _codePointToUtf16Offset(_tvInputText, _tvSelectionEnd);
                      controller.value = TextEditingValue(
                        text: _tvInputText,
                        selection: TextSelection(baseOffset: start, extentOffset: end),
                      );
                      focusNode.requestFocus();
                    },
                    icon: const Icon(Icons.sync, size: 16),
                    label: const Text('套用電視端文字至輸入框'),
                  ),
                ),
              ],
            ],
          ),
        ),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('關閉')),
        FilledButton(onPressed: () async {
          final text = controller.text;
          if (text.isEmpty) return;
          try {
            await _methods.invokeMethod('sendText', {'text': text});
            controller.clear();
            if (mounted) _notice('已送出文字');
            focusNode.requestFocus();
          } on PlatformException catch (e) {
            if (mounted) _notice(e.message ?? '文字輸入失敗；請確認電視目前有開啟文字輸入欄位');
          }
        }, child: const Text('傳送文字')),
      ],
    )));
    controller.dispose();
    focusNode.dispose();
  }

  Future<void> _sendKeyboardKey(int keyCode) async {
    try {
      await _methods.invokeMethod('sendKey', {'keyCode': keyCode, 'direction': 3});
    } on PlatformException catch (e) {
      if (mounted) _notice(e.message ?? '鍵盤按鍵傳送失敗');
    }
  }

  void _showDevices() {
    showModalBottomSheet(context: context, backgroundColor: const Color(0xFF202126), isScrollControlled: true, builder: (sheetContext) => StatefulBuilder(builder: (context, setSheetState) {
      // Parent state drives the discovered list; timer-free stream updates rebuild this sheet via its own notifier workaround.
      return ValueListenableBuilder<int>(valueListenable: _deviceRevision, builder: (context, revision, child) => SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(20, 18, 20, 24), child: Column(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 38, height: 4, decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(4))), const SizedBox(height: 18),
        const Icon(Icons.tv, size: 42, color: Color(0xFFB9F36A)), const SizedBox(height: 10), const Text('搜尋 Android TV', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
        const SizedBox(height: 6), Text(discoveryStatus, style: const TextStyle(color: Colors.white60)), const SizedBox(height: 12),
        if (_devices.isEmpty) const Padding(padding: EdgeInsets.symmetric(vertical: 18), child: Text('請確認手機與電視連接相同 Wi-Fi 網路', textAlign: TextAlign.center, style: TextStyle(color: Colors.white54)))
        else ..._devices.values.map((d) => ListTile(
          leading: Icon(Icons.tv, color: d.supportsRemoteV2Pairing ? const Color(0xFFB9F36A) : Colors.white38),
          title: Text(d.name),
          subtitle: Text('${d.host}:${d.port}  •  ${d.supportsRemoteV2Pairing ? 'Remote v2 · 可進行配對檢查' : 'Remote v1 · 尚未支援配對'}'),
          trailing: Icon(d.supportsRemoteV2Pairing ? Icons.chevron_right : Icons.info_outline, color: Colors.white54),
          onTap: () => _selectDevice(d),
        )),
        const SizedBox(height: 8), Row(children: [Expanded(child: OutlinedButton(onPressed: scanning ? () async { await _stopDiscovery(); setSheetState(() {}); } : null, child: const Text('停止搜尋'))), const SizedBox(width: 10), Expanded(child: FilledButton.icon(onPressed: () async { await _startDiscovery(); setSheetState(() {}); }, icon: const Icon(Icons.search), label: const Text('重新搜尋')))]),
      ]))));
    }));
    _startDiscovery();
  }
}
