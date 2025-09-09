import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'steps_count_platform_interface.dart';

/// An implementation of [StepsCountPlatform] that uses method channels.
class MethodChannelStepsCount extends StepsCountPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('steps_count');

  @override
  Future<bool> startBackgroundService() async {
    final result = await methodChannel.invokeMethod<bool>(
      'startBackgroundService',
    );
    return result ?? false;
  }

  @override
  Future<bool> forceStopBackgroundService() async {
    final result = await methodChannel.invokeMethod<bool>(
      'forceStopBackgroundService',
    );
    return result ?? false;
  }

  @override
  Future<int> getStepCount() async {
    final result = await methodChannel.invokeMethod<int>('getStepCount');
    return result ?? 0;
  }

  @override
  Future<bool> isServiceRunning() async {
    final result = await methodChannel.invokeMethod<bool>('isServiceRunning');
    return result ?? false;
  }
}
