import 'package:flutter_test/flutter_test.dart';
import 'package:steps_count/steps_count.dart';
import 'package:steps_count/steps_count_platform_interface.dart';
import 'package:steps_count/steps_count_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockStepsCountPlatform
    with MockPlatformInterfaceMixin
    implements StepsCountPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final StepsCountPlatform initialPlatform = StepsCountPlatform.instance;

  test('$MethodChannelStepsCount is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelStepsCount>());
  });

  test('getPlatformVersion', () async {
    StepsCount stepsCountPlugin = StepsCount();
    MockStepsCountPlatform fakePlatform = MockStepsCountPlatform();
    StepsCountPlatform.instance = fakePlatform;

    expect(await stepsCountPlugin.getPlatformVersion(), '42');
  });
}
