import 'steps_count_platform_interface.dart';
import 'models/timeline_model.dart';
import 'models/timezone_type.dart';
import 'models/health_data_type.dart';
import 'models/step_source_info.dart';
export 'models/timeline_model.dart';
export 'models/timezone_type.dart';
export 'models/health_data_type.dart';
export 'models/step_source_info.dart';

/// A Flutter plugin for counting steps and managing background step tracking services.
///
/// This class provides methods to:
/// - Start and stop background step counting services
/// - Retrieve step counts for specific date ranges
/// - Get detailed timeline data with timestamps
/// - Check service status
class StepsCount {
  /// Starts the background step counting service.
  ///
  /// This method initiates the background service that continuously monitors
  /// and records step data even when the app is not in the foreground.
  ///
  /// Throws [PlatformException] if the service cannot be started.
  Future<void> startBackgroundService() {
    return StepsCountPlatform.instance.startBackgroundService();
  }

  /// Stops the background step counting service.
  ///
  /// This method stops the background service that monitors step data.
  /// After calling this method, step counting will only occur when the
  /// app is in the foreground (if supported by the platform).
  ///
  /// Throws [PlatformException] if the service cannot be stopped.
  Future<void> stopBackgroundService() {
    return StepsCountPlatform.instance.stopBackgroundService();
  }

  /// Checks if the background step counting service is currently running.
  ///
  /// Returns `true` if the background service is active and monitoring steps,
  /// `false` otherwise.
  ///
  /// This can be useful for UI updates to show the current service status
  /// or to conditionally start/stop the service.
  Future<bool> isServiceRunning() {
    return StepsCountPlatform.instance.isServiceRunning();
  }

  /// Gets the step count for today (current date).
  ///
  /// Returns the total number of steps recorded for the current day.
  /// The day boundary is determined by the device's local timezone.
  ///
  /// Returns `0` if no steps have been recorded today or if step data
  /// is not available.
  ///
  /// On **iOS**, the default is **Apple device sources only** (excludes typical
  /// third-party apps and user-entered samples). Use [includeAllSources] for
  /// the legacy combined total, or [sourceBundleIdentifiers] to pick apps.
  ///
  /// Example:
  /// ```dart
  /// final todaySteps = await stepsCount.getTodaysCount();
  /// print('Steps today: $todaySteps');
  /// ```
  Future<int> getTodaysCount({
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    return StepsCountPlatform.instance.getTodaysCount(
      includeAllSources: includeAllSources,
      sourceBundleIdentifiers: sourceBundleIdentifiers,
    );
  }

  /// Gets the total step count for a specified date range.
  ///
  /// Parameters:
  /// - [startDate]: The start date for the range (inclusive). If null,
  ///   defaults to the beginning of available data.
  /// - [endDate]: The end date for the range (inclusive). If null,
  ///   defaults to the current date.
  ///
  /// Returns the sum of all steps recorded within the specified date range.
  ///
  /// Example:
  /// ```dart
  /// // Get steps for the last 7 days
  /// final weekSteps = await stepsCount.getStepCounts(
  ///   startDate: DateTime.now().subtract(Duration(days: 7)),
  ///   endDate: DateTime.now(),
  /// );
  /// ```
  ///
  /// Note: Date boundaries are determined by the device's local timezone.
  ///
  /// On **iOS**, filtering matches [getTodaysCount].
  Future<int> getStepCounts({
    DateTime? startDate,
    DateTime? endDate,
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    return StepsCountPlatform.instance.getStepCounts(
      startDate: startDate,
      endDate: endDate,
      includeAllSources: includeAllSources,
      sourceBundleIdentifiers: sourceBundleIdentifiers,
    );
  }

  /// Gets detailed timeline data with step counts and timestamps.
  ///
  /// This method returns a list of [TimelineModel] objects, each containing
  /// a step count and its corresponding timestamp. This is useful for creating
  /// charts, graphs, or detailed step history displays.
  ///
  /// Parameters:
  /// - [startDate]: The start date for the timeline (inclusive). If null,
  ///   defaults to the beginning of available data.
  /// - [endDate]: The end date for the timeline (inclusive). If null,
  ///   defaults to the current date.
  /// - [timeZone]: The timezone type for timestamp interpretation.
  ///   Defaults to [TimeZoneType.local].
  ///
  /// Returns a list of [TimelineModel] objects ordered by timestamp.
  ///
  /// Example:
  /// ```dart
  /// // Get timeline for the last month in local timezone
  /// final timeline = await stepsCount.getTimeline(
  ///   startDate: DateTime.now().subtract(Duration(days: 30)),
  ///   endDate: DateTime.now(),
  ///   timeZone: TimeZoneType.local,
  /// );
  ///
  /// for (final entry in timeline) {
  ///   print('${entry.dateTime}: ${entry.stepCount} steps');
  /// }
  /// ```
  Future<List<TimelineModel>> getTimeline({
    DateTime? startDate,
    DateTime? endDate,
    TimeZoneType timeZone = TimeZoneType.local,
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    return StepsCountPlatform.instance.getTimeline(
      startDate: startDate,
      endDate: endDate,
      timeZone: timeZone,
      includeAllSources: includeAllSources,
      sourceBundleIdentifiers: sourceBundleIdentifiers,
    );
  }

  /// Checks if HealthKit is available on the device.
  ///
  /// Returns `true` if HealthKit is available, `false` otherwise.
  ///
  /// This can be useful to check if HealthKit can be used for step counting.
  Future<bool> isHealthKitAvailable() {
    return StepsCountPlatform.instance.isHealthKitAvailable();
  }

  /// Starts the HealthKit step observer (iOS only).
  ///
  /// Call this only after HealthKit permission is granted. On iOS 26+, starting
  /// the observer in [register(with:)] when authorization is not determined
  /// can block the main thread for a long time and cause a white screen.
  /// On Android this is a no-op and returns true.
  Future<bool> startStepObserver() {
    return StepsCountPlatform.instance.startStepObserver();
  }

  /// Requests HealthKit permissions for the specified health data types.
  ///
  /// This method prompts the user to grant permissions for accessing the
  /// specified health data types. The user will see a system dialog where
  /// they can choose which data types to allow or deny.
  ///
  /// Parameters:
  /// - [dataTypes]: List of health data types to request permissions for.
  ///
  /// Returns `true` if the permission request was successfully initiated,
  /// `false` otherwise. Note that this doesn't indicate whether permissions
  /// were granted - use [checkHealthKitPermissionStatus] to verify the actual
  /// permission status after requesting.
  Future<bool> requestHealthKitPermissions({
    required List<HealthDataType> dataTypes,
  }) {
    return StepsCountPlatform.instance.requestHealthKitPermissions(
      dataTypes: dataTypes,
    );
  }

  /// Checks the permission status for multiple health data types.
  ///
  /// This method returns the current permission status for each of the
  /// specified health data types without prompting the user.
  ///
  /// Parameters:
  /// - [dataTypes]: List of health data types to check permissions for.
  ///
  /// Returns a map where keys are data type identifiers and values are
  /// boolean indicating whether permission is granted (true) or not (false).
  ///
  /// Example:
  /// ```dart
  /// final permissions = await stepsCount.checkHealthKitPermissionStatus(
  ///   dataTypes: [
  ///     HealthDataType.stepCount,
  ///     HealthDataType.distanceWalkingRunning,
  ///   ],
  /// );
  ///
  /// for (final entry in permissions.entries) {
  ///   print('${entry.key}: ${entry.value ? "Authorized" : "Not Authorized"}');
  ///   if (entry.value) {
  ///     print('✓ Access granted for ${entry.key}');
  ///   }
  /// }
  /// ```
  Future<Map<String, bool>> checkHealthKitPermissionStatus({
    required List<HealthDataType> dataTypes,
  }) {
    return StepsCountPlatform.instance.checkHealthKitPermissionStatus(
      dataTypes: dataTypes,
    );
  }

  /// Checks the permission status for a single health data type.
  ///
  /// This is a convenience method for checking the permission status of
  /// just one health data type.
  ///
  /// Parameters:
  /// - [dataType]: The health data type to check permission for.
  ///
  /// Returns `true` if permission is granted, `false` otherwise.
  ///
  /// Example:
  /// ```dart
  /// final hasStepPermission = await stepsCount.checkSingleHealthKitPermissionStatus(
  ///   dataType: HealthDataType.stepCount,
  /// );
  ///
  /// if (hasStepPermission) {
  ///   final steps = await stepsCount.getTodaysCount();
  ///   print('Today\'s steps: $steps');
  /// } else {
  ///   // Request permission
  ///   await stepsCount.requestHealthKitPermissions(
  ///     dataTypes: [HealthDataType.stepCount],
  ///   );
  /// }
  /// ```
  Future<bool> checkSingleHealthKitPermissionStatus({
    required HealthDataType dataType,
  }) {
    return StepsCountPlatform.instance.checkSingleHealthKitPermissionStatus(
      dataType: dataType,
    );
  }

  /// Exports the local steps database file.
  ///
  /// Returns the absolute path of the exported database file on success,
  /// or null if the operation is not supported or fails.
  ///
  /// On Android, this copies the internal SQLite database to an accessible location.
  /// On iOS, this returns null as HealthKit data is not stored in a local SQLite DB.
  Future<String?> exportStepsDatabase() {
    return StepsCountPlatform.instance.exportStepsDatabase();
  }

  /// Returns all timeline entries recorded strictly after [lastSyncTimestamp]
  /// (milliseconds since epoch, **UTC**).
  ///
  /// - If [lastSyncTimestamp] is **non-null**, only entries with a timestamp
  ///   strictly greater than that value are returned.
  /// - If [lastSyncTimestamp] is **null**, the entire timeline is returned.
  ///
  /// Queries the native data source directly:
  /// - **Android**: SQLite with `WHERE timestamp > lastSyncTimestamp` (or no filter if null)
  /// - **iOS**: HealthKit predicate anchored at [lastSyncTimestamp] (or epoch 0 if null)
  ///
  /// Returns a list of [TimelineModel] objects ordered by timestamp ascending.
  ///
  /// Example:
  /// ```dart
  /// // Only new entries since last sync
  /// final newEntries = await stepsCount.getTimelineAfter(
  ///   lastSyncTimestamp: lastSyncMs,
  /// );
  ///
  /// // All entries (first-time fetch)
  /// final all = await stepsCount.getTimelineAfter();
  /// ```
  Future<List<TimelineModel>> getTimelineAfter({
    int? lastSyncTimestamp,
    bool includeAllSources = false,
    List<String>? sourceBundleIdentifiers,
  }) {
    return StepsCountPlatform.instance.getTimelineAfter(
      lastSyncTimestamp: lastSyncTimestamp,
      includeAllSources: includeAllSources,
      sourceBundleIdentifiers: sourceBundleIdentifiers,
    );
  }

  /// Returns contributors that have written step data to HealthKit (**iOS**).
  ///
  /// Use [StepSourceInfo.bundleIdentifier] with [getTodaysCount],
  /// [getStepCounts], [getTimeline], and [getTimelineAfter] via
  /// `sourceBundleIdentifiers`. On **Android** this returns an empty list.
  ///
  /// If both [startDate] and [endDate] are set, only sources with samples in
  /// that range are listed. If either is null, all sources for step count are
  /// returned (iOS).
  Future<List<StepSourceInfo>> getStepSources({
    DateTime? startDate,
    DateTime? endDate,
  }) {
    return StepsCountPlatform.instance.getStepSources(
      startDate: startDate,
      endDate: endDate,
    );
  }
}
