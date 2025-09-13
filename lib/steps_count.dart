import 'steps_count_platform_interface.dart';
import 'models/timeline_model.dart';
import 'models/timezone_type.dart';
export 'models/timeline_model.dart';
export 'models/timezone_type.dart';

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
  /// Example:
  /// ```dart
  /// final todaySteps = await stepsCount.getTodaysCount();
  /// print('Steps today: $todaySteps');
  /// ```
  Future<int> getTodaysCount() {
    return StepsCountPlatform.instance.getTodaysCount();
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
  Future<int> getStepCounts({DateTime? startDate, DateTime? endDate}) {
    return StepsCountPlatform.instance.getStepCounts(
      startDate: startDate,
      endDate: endDate,
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
  }) {
    return StepsCountPlatform.instance.getTimeline(
      startDate: startDate,
      endDate: endDate,
      timeZone: timeZone,
    );
  }
}
