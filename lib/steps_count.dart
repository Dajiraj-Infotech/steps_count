
import 'steps_count_platform_interface.dart';

class StepsCount {
  Future<void> startBackgroundService() {
    return StepsCountPlatform.instance.startBackgroundService();
  }

  Future<void> stopBackgroundService() {
    return StepsCountPlatform.instance.stopBackgroundService();
  }

  Future<int> getStepCount() {
    return StepsCountPlatform.instance.getStepCount();
  }
}
