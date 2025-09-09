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
  Future<int> getStepCount() async {
    final result = await methodChannel.invokeMethod<int>('getStepCount');
    return result ?? 0;
  }
}
