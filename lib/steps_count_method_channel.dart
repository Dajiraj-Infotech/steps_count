import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'steps_count_platform_interface.dart';

/// An implementation of [StepsCountPlatform] that uses method channels.
class MethodChannelStepsCount extends StepsCountPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('steps_count');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
