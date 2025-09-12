import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'steps_count_platform_interface.dart';

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
}
