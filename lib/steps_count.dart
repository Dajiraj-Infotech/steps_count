import 'steps_count_platform_interface.dart';
import 'models/timeline_model.dart';
import 'models/timezone_type.dart';
export 'models/timeline_model.dart';
export 'models/timezone_type.dart';

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

  Future<List<TimelineModel>> getTimeline({
    DateTime? startDate,
    DateTime? endDate,
    TimeZoneType timeZone = TimeZoneType.local,
  }) {
    return StepsCountPlatform.instance.getTimeline(
      startDate: startDate,
      endDate: endDate,
      timeZone: timeZone,
    );
  }
}
