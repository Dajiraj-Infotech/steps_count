
import 'steps_count_platform_interface.dart';

class StepsCount {
  Future<String?> getPlatformVersion() {
    return StepsCountPlatform.instance.getPlatformVersion();
  }
}
