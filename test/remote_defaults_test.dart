import 'package:flutter_test/flutter_test.dart';
import 'package:remote_atv/main.dart';

void main() {
  group('Remote ATV first-run defaults', () {
    test('uses D-pad navigation by default', () {
      expect(defaultRemoteLayout, 'dpad');
    });

    test('starts with no quick-access shortcuts', () {
      expect(defaultQuickAccess, 'none');
    });
  });

  group('remote layout migration', () {
    test('preserves the existing touchpad selection', () {
      expect(normalizeRemoteLayout('swipe'), 'swipe');
    });

    test('maps legacy tap preference to D-pad', () {
      expect(normalizeRemoteLayout('tap'), 'dpad');
    });

    test('uses D-pad for missing or invalid values', () {
      expect(normalizeRemoteLayout(null), 'dpad');
      expect(normalizeRemoteLayout('unexpected'), 'dpad');
    });
  });
}
