import 'steps_count_platform_interface.dart';

class StepsCount {
  Future<void> startBackgroundService() {
    return StepsCountPlatform.instance.startBackgroundService();
  }

  Future<void> stopBackgroundService() {
    return StepsCountPlatform.instance.stopBackgroundService();
  }

  Future<int> getStepCount({DateTime? startDate, DateTime? endDate}) {
    return StepsCountPlatform.instance.getStepCount(
      startDate: startDate, 
      endDate: endDate
    );
  }

  Future<bool> isServiceRunning() {
    return StepsCountPlatform.instance.isServiceRunning();
  }

  Future<int> getTodaysCount() {
    return StepsCountPlatform.instance.getTodaysCount();
  }
}
