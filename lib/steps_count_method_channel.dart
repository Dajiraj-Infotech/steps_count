import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'steps_count_platform_interface.dart';
import 'models/timeline_model.dart';
import 'models/timezone_type.dart';
import 'models/health_data_type.dart';

/// An implementation of [StepsCountPlatform] that uses method channels.
class MethodChannelStepsCount extends StepsCountPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('steps_count');

  @override
  Future<void> startBackgroundService() async {
    await methodChannel.invokeMethod('startBackgroundService');
  }

  @override
  Future<void> stopBackgroundService() async {
    await methodChannel.invokeMethod('stopBackgroundService');
  }

  @override
  Future<bool> isServiceRunning() async {
    final result = await methodChannel.invokeMethod<bool>('isServiceRunning');
    return result ?? false;
  }

  @override
  Future<int> getTodaysCount() async {
    final result = await methodChannel.invokeMethod<int>('getTodaysCount');
    return result ?? 0;
  }

  @override
  Future<int> getStepCounts({DateTime? startDate, DateTime? endDate}) async {
    final Map<String, dynamic> arguments = {};

    if (startDate != null) {
      arguments['startDate'] = startDate.millisecondsSinceEpoch;
    }

    if (endDate != null) {
      arguments['endDate'] = endDate.millisecondsSinceEpoch;
    }

    final result = await methodChannel.invokeMethod<int>(
      'getStepCount',
      arguments.isEmpty ? null : arguments,
    );
    return result ?? 0;
  }

  @override
  Future<List<TimelineModel>> getTimeline({
    DateTime? startDate,
    DateTime? endDate,
    TimeZoneType timeZone = TimeZoneType.local,
  }) async {
    final Map<String, dynamic> arguments = {
      'timeZone': timeZone.name.toLowerCase(), // Send enum as string
    };

    if (startDate != null) {
      arguments['startDate'] = startDate.millisecondsSinceEpoch;
    }

    if (endDate != null) {
      arguments['endDate'] = endDate.millisecondsSinceEpoch;
    }

    final result = await methodChannel.invokeMethod<List<dynamic>>(
      'getTimeline',
      arguments,
    );

    if (result == null) {
      return [];
    }

    // Convert the result to List<TimelineModel>
    return result.map((item) {
      if (item is Map) {
        return TimelineModel.fromMap(Map<String, dynamic>.from(item));
      }
      // Return empty TimelineModel if item is not a Map
      return const TimelineModel(stepCount: 0, timestamp: 0);
    }).toList();
  }

  @override
  Future<bool> isHealthKitAvailable() async {
    final result = await methodChannel.invokeMethod<bool>(
      'isHealthKitAvailable',
    );
    return result ?? false;
  }

  @override
  Future<bool> requestHealthKitPermissions({
    required List<HealthDataType> dataTypes,
  }) async {
    final arguments = {
      'dataTypes': dataTypes.map((type) => type.identifier).toList(),
    };

    final result = await methodChannel.invokeMethod<bool>(
      'requestHealthKitPermissions',
      arguments,
    );
    return result ?? false;
  }

  @override
  Future<Map<String, bool>> checkHealthKitPermissionStatus({
    required List<HealthDataType> dataTypes,
  }) async {
    final arguments = {
      'dataTypes': dataTypes.map((type) => type.identifier).toList(),
    };

    final result = await methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'checkHealthKitPermissionStatus',
      arguments,
    );

    if (result == null) {
      return {};
    }

    // Convert the result to Map<String, bool>
    return Map<String, bool>.from(result);
  }

  @override
  Future<bool> checkSingleHealthKitPermissionStatus({
    required HealthDataType dataType,
  }) async {
    final arguments = {'dataType': dataType.identifier};

    final result = await methodChannel.invokeMethod<bool>(
      'checkSingleHealthKitPermissionStatus',
      arguments,
    );

    return result ?? false;
  }
}
