## 0.1.0

Major Android reliability rework: eliminates the random single-entry step spikes
(1k / 2k / 40k / 50k) and makes counting trustworthy across reboots, restores,
OEM kills, and clock changes. Sensor-only (no Health Connect). See
`docs/robust_step_counting_spec.md` for the full design.

* **FIXED**: Phantom step spikes. Garbage sensor values (uint32/Infinity glitches)
  are rejected before arithmetic; the baseline is anchored to the boot session
  (`BOOT_COUNT` + `ANDROID_ID`) so a missed reboot, Auto Backup restore, or
  reinstall no longer books an arbitrary jump; and a physical-rate gate quarantines
  impossible deltas instead of capping-and-adding them.
* **FIXED**: Step loss and double counting. Steps and the durable "anchor" now
  advance in one SQLite transaction, with unflushed steps derived from the hardware
  counter, so a process kill loses nothing and a DB failure just retries.
* **NEW**: Honest time attribution. Steps are recorded as intervals
  (`start_timestamp` + `timestamp`) attributed to when they happened, split at local
  midnights, so a catch-up after downtime is spread across the downtime instead of
  dumped at "now". `TimelineModel` gains `startTimestamp`, `source`, `flags`, and
  `isEstimated`.
* **NEW**: `getTrackingStatus()` returns a `StepTrackingStatus` snapshot (service,
  sensor, permission, notification state, and data staleness) to detect degraded
  tracking.
* **IMPROVED**: Lifecycle hardening. Device-protected storage + directBootAware
  service (counts before first unlock, excluded from backup), correct foreground
  service start, a `tracking_enabled` preference honored on boot, an AlarmManager
  restart path, a sensor-silence/frozen-hub watchdog, and a non-destructive DB
  corruption handler.
* **IMPROVED**: Reads are service-less (work when the service is stopped) and run
  off the platform thread; numeric arguments are coerced safely.
* **IMPROVED**: The plugin manifest now declares the service, receiver, permissions,
  and `uses-feature`, so consumer apps no longer need to copy them by hand.

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
