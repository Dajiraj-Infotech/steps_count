import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'steps_count_method_channel.dart';

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

  Future<int> getStepCount() {
    throw UnimplementedError('getStepCount() has not been implemented.');
  }

  Future<bool> isServiceRunning() {
    throw UnimplementedError('isServiceRunning() has not been implemented.');
  }
}
