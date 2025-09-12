import 'steps_count_platform_interface.dart';

class StepsCount {
  Future<void> startBackgroundService() {
    return StepsCountPlatform.instance.startBackgroundService();
  }

  Future<void> stopBackgroundService() {
    return StepsCountPlatform.instance.stopBackgroundService();
  }

  Future<bool> isServiceRunning() {
    return StepsCountPlatform.instance.isServiceRunning();
  }

  Future<int> getTodaysCount() {
    return StepsCountPlatform.instance.getTodaysCount();
  }

  Future<int> getStepCounts({DateTime? startDate, DateTime? endDate}) {
    return StepsCountPlatform.instance.getStepCounts(
      startDate: startDate,
      endDate: endDate,
    );
  }
}
