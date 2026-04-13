## 0.0.3

* **BREAKING (iOS behavior)**: Step queries now default to **Apple device sources only**
  (HealthKit samples with `com.apple.*` bundle IDs, excluding user-entered samples).
  Existing apps require no Dart changes; pass `includeAllSources: true` to restore
  the previous all-sources total, or `sourceBundleIdentifiers` to select apps.
* **NEW**: `getStepSources()` lists HealthKit step contributors (iOS); empty on Android.

## 0.0.2

* **NEW: iOS Support** - Added complete HealthKit integration for iOS devices
* **NEW: HealthKit Integration** - Native iOS health data access with privacy compliance
* **NEW: iOS Methods** - Added iOS-specific methods:
  - `isHealthKitAvailable()` - Check HealthKit availability
  - `requestHealthKitPermissions()` - Request HealthKit permissions
  - `checkHealthKitPermissionStatus()` - Check multiple permission statuses
  - `checkSingleHealthKitPermissionStatus()` - Check single permission status
* **NEW: Cross-platform Support** - Plugin now works on both Android and iOS
* **IMPROVED: API Reference** - Added platform-specific method documentation

## 0.0.1

* Initial release of Steps Count Flutter plugin
* Android step counting with background service support
* Background service management (start/stop/status)
* Timeline data retrieval with timezone support
* Auto-restart service on device boot
* Comprehensive permission handling examples
* MIT license
