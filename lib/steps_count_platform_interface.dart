import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'steps_count_method_channel.dart';
import 'models/timeline_model.dart';
import 'models/timezone_type.dart';
import 'models/health_data_type.dart';
import 'models/step_source_info.dart';
import 'models/step_tracking_status.dart';

abstract class StepsCountPlatform extends PlatformInterface {
  /// Constructs a StepsCountPlatform.
  StepsCountPlatform() : super(token: _token);

  static final Object _token = Object();

  static StepsCountPlatform _instance = MethodChannelStepsCount();

  /// The default instance of [StepsCountPlatform] to use.
  ///
  /// Defaults to [MethodChannelStepsCount].
  static StepsCountPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [StepsCountPlatform] when
  /// they register themselves.
  static set instance(StepsCountPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<void> startBackgroundService() {
    throw UnimplementedError(
      'startBackgroundService() has not been implemented.',
    );
  }

  Future<void> stopBackgroundService() {
    throw UnimplementedError(
      'stopBackgroundService() has not been implemented.',
    );
  }

  Future<bool> isServiceRunning() {
    throw UnimplementedError('isServiceRunning() has not been implemented.');
  }

  /// On iOS, step data is filtered by default to **Apple device sources only**
  /// (see HealthKit). Pass [includeAllSources] to restore the previous
  /// all-sources sum, or [sourceBundleIdentifiers] to restrict to specific apps.
  Future<int> getTodaysCount({
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    throw UnimplementedError('getTodaysCount() has not been implemented.');
  }

  Future<int> getStepCounts({
    DateTime? startDate,
    DateTime? endDate,
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    throw UnimplementedError('getStepCount() has not been implemented.');
  }

  Future<List<TimelineModel>> getTimeline({
    DateTime? startDate,
    DateTime? endDate,
    TimeZoneType timeZone = TimeZoneType.local,
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    throw UnimplementedError('getTimeline() has not been implemented.');
  }

  Future<bool> isHealthKitAvailable() {
    throw UnimplementedError(
      'isHealthKitAvailable() has not been implemented.',
    );
  }

  /// Starts the HealthKit step observer (iOS only).
  ///
  /// Call this only after HealthKit permission is granted. On iOS 26+, starting
  /// the observer before permission can block the main thread for a long time.
  /// On Android this is a no-op and returns true.
  Future<bool> startStepObserver() {
    throw UnimplementedError('startStepObserver() has not been implemented.');
  }

  Future<bool> requestHealthKitPermissions({
    required List<HealthDataType> dataTypes,
  }) {
    throw UnimplementedError(
      'requestHealthKitPermissions() has not been implemented.',
    );
  }

  Future<Map<String, bool>> checkHealthKitPermissionStatus({
    required List<HealthDataType> dataTypes,
  }) {
    throw UnimplementedError(
      'checkHealthKitPermissionStatus() has not been implemented.',
    );
  }

  Future<bool> checkSingleHealthKitPermissionStatus({
    required HealthDataType dataType,
  }) {
    throw UnimplementedError(
      'checkSingleHealthKitPermissionStatus() has not been implemented.',
    );
  }

  /// Returns all timeline entries recorded strictly after [lastSyncTimestamp]
  /// (milliseconds since epoch).
  ///
  /// On Android this queries SQLite directly with `WHERE timestamp > lastSyncTimestamp`.
  /// On iOS this queries HealthKit with a predicate starting from [lastSyncTimestamp].
  Future<List<TimelineModel>> getTimelineAfter({
    int? lastSyncTimestamp,
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    throw UnimplementedError('getTimelineAfter() has not been implemented.');
  }

  /// Lists HealthKit step sources (iOS). On Android returns an empty list.
  ///
  /// Optional [startDate] / [endDate] limit which samples are considered (both
  /// must be non-null to apply the range).
  Future<List<StepSourceInfo>> getStepSources({
    DateTime? startDate,
    DateTime? endDate,
  }) {
    throw UnimplementedError('getStepSources() has not been implemented.');
  }

  /// Exports the local steps database file.
  ///
  /// Returns the absolute path of the exported database file on success,
  /// or null if the operation is not supported or fails.
  ///
  /// On Android, this copies the internal SQLite database to an accessible location.
  /// On iOS, this returns null as HealthKit data is not stored in a local SQLite DB.
  Future<String?> exportStepsDatabase() {
    throw UnimplementedError('exportStepsDatabase() has not been implemented.');
  }

  /// Returns a health/observability snapshot of step tracking (service state,
  /// sensor/permission availability, and staleness). Primarily meaningful on
  /// Android; see [StepTrackingStatus].
  Future<StepTrackingStatus> getTrackingStatus() {
    throw UnimplementedError('getTrackingStatus() has not been implemented.');
  }
}
