# Remote ATV 5.0 Rebuild Plan

## Working branch
- Branch: `rebuild/remote-atv-5`
- Starting point: `main`
- Existing source baseline: Flutter app `4.18.0+49` (not the compiled 5.0.5 APK)

## Rebuild rules
1. Do not patch or reverse-engineer the compiled 5.0.5 APK for production changes.
2. Preserve existing working protocol code only after reviewing its Dart and Android native implementation.
3. Keep UI, settings persistence, device discovery, pairing, and remote transport in separate modules.
4. Do not remove a widget by nulling callbacks or replacing runtime strings; remove the corresponding menu model/action safely.
5. Defaults are applied only on first install or a one-time migration. Later user choices must persist.
6. Android 9 (API 28) is the minimum TV validation target. Newer Android TV / Google TV compatibility is secondary.
7. A successful CI build is not equivalent to real-device validation.

## Product requirements
- Default navigation mode: D-pad.
- Default quick access: None.
- Keep both D-pad and touchpad modes available.
- Keep device discovery, pairing, reconnect, remote keys, volume, voice/input capabilities where supported, and diagnostics.
- Remove the settings entries: Share with friends, Rate and Review, Feedback, Activate Premium.
- Disable automatic update checks, update notifications, forced update prompts, and update actions.
- TV-first focus navigation: visible focus state, predictable D-pad traversal, no focus loss after scrolling or dialogs.
- Traditional Chinese first; retain English and Vietnamese localization support.
- HDMI input switching is capability-dependent and must be shown as unsupported when the connected TV does not expose a supported API.

## Current source audit
- `pubspec.yaml` reports version `4.18.0+49`.
- `lib/main.dart` contains the current Flutter UI and uses MethodChannel `remote_atv/discovery` plus EventChannel `remote_atv/discovery_events`.
- Existing UI settings currently default to `remoteLayout = 'tap'`; the requested D-pad default is not implemented in this source snapshot.
- The current UI settings bridge persists haptic feedback, natural hold, and remote layout. It does not yet expose the requested quick-access preference in the inspected section.
- Android manifest includes network, Wi-Fi, multicast, and microphone permissions.
- Android app config currently has minSdk 23, targetSdk 35, versionCode 49, versionName 4.18.0.
- The source snapshot is not the same source version as the compiled 5.0.5 APK. Treat 5.0.5 as a behavior/reference target, not as proof that every 5.0.5 feature exists in this repository.

## Implementation sequence
1. Inventory Dart screens, Android native classes, channels, assets, localization, and CI workflows.
2. Establish a clean Flutter build and baseline tests before changing behavior.
3. Extract persistent settings into a typed settings model with versioned one-time migration.
4. Rebuild the TV remote screen and focus model; add automated widget tests for D-pad traversal.
5. Implement the D-pad and touchpad layouts and quick-access None behavior.
6. Remove the four unwanted settings entries at the source/model level.
7. Remove update-check and update-prompt code paths, including background startup hooks.
8. Review Android 9 networking, permissions, pairing/TLS, reconnect, and hardware-key behavior.
9. Build signed APK and source archive; validate install, launch, focus navigation, pairing, and remote actions on a physical Android 9 TV.

## Acceptance checklist
- [ ] Clean dependency resolution and Flutter analysis pass.
- [ ] Unit/widget tests pass.
- [ ] D-pad is the first-run default and remains selected after restart.
- [ ] Quick access is None on first run and remains user-configurable.
- [ ] Four unwanted settings entries are absent; settings page still opens and scrolls.
- [ ] No automatic update checks or update prompts occur.
- [ ] All focusable controls can be reached and activated with a TV remote.
- [ ] Android 9 installation and runtime tests pass on physical hardware.
- [ ] APK signature/install verification and source archive integrity pass.
