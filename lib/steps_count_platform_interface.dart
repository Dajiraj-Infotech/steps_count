import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'steps_count_method_channel.dart';
import 'models/timeline_model.dart';
import 'models/timezone_type.dart';
import 'models/health_data_type.dart';

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

  Future<int> getTodaysCount() {
    throw UnimplementedError('getTodaysCount() has not been implemented.');
  }

  Future<int> getStepCounts({DateTime? startDate, DateTime? endDate}) {
    throw UnimplementedError('getStepCount() has not been implemented.');
  }

  Future<List<TimelineModel>> getTimeline({
    DateTime? startDate,
    DateTime? endDate,
    TimeZoneType timeZone = TimeZoneType.local,
  }) {
    throw UnimplementedError('getTimeline() has not been implemented.');
  }

  Future<bool> isHealthKitAvailable() {
    throw UnimplementedError(
      'isHealthKitAvailable() has not been implemented.',
    );
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

  Future<List<String>> getAvailableDataTypes() {
    throw UnimplementedError(
      'getAvailableDataTypes() has not been implemented.',
    );
  }
}
