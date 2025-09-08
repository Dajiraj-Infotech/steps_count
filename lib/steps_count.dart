
import 'steps_count_platform_interface.dart';

class StepsCount {
  Future<bool> startBackgroundService() {
    return StepsCountPlatform.instance.startBackgroundService();
  }

  Future<bool> forceStopBackgroundService() {
    return StepsCountPlatform.instance.forceStopBackgroundService();
  }

  Future<int> getStepCount() {
    return StepsCountPlatform.instance.getStepCount();
  }

  Future<bool> isServiceRunning() {
    return StepsCountPlatform.instance.isServiceRunning();
  }

  Future<bool> checkPermission() {
    return StepsCountPlatform.instance.checkPermission();
  }

  Future<bool> requestPermission() {
    return StepsCountPlatform.instance.requestPermission();
  }
}
