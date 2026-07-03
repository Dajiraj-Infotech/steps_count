import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:steps_count/steps_count_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final platform = MethodChannelStepsCount();
  const channel = MethodChannel('steps_count');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  MethodCall? lastCall;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    lastCall = null;
  });

  void mock(dynamic response) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      lastCall = call;
      return response;
    });
  }

  test('getTodaysCount returns the native count', () async {
    mock(4321);
    expect(await platform.getTodaysCount(), 4321);
    expect(lastCall?.method, 'getTodaysCount');
  });

  test('getTodaysCount coerces a null response to 0', () async {
    mock(null);
    expect(await platform.getTodaysCount(), 0);
  });

  test('getTimeline maps native rows into TimelineModel with interval fields',
      () async {
    mock(<dynamic>[
      {
        'uuid': 'a',
        'step_count': 10,
        'timestamp': 2000,
        'start_timestamp': 1000,
        'source': 'gap',
        'flags': 1,
      },
    ]);
    final timeline = await platform.getTimeline();
    expect(timeline, hasLength(1));
    expect(timeline.first.stepCount, 10);
    expect(timeline.first.startTimestamp, 1000);
    expect(timeline.first.isEstimated, isTrue);
  });

  test('getTrackingStatus parses the native status map', () async {
    mock(<dynamic, dynamic>{
      'serviceRunning': true,
      'sensorAvailable': true,
      'lastProgressAgeMs': 999,
    });
    final status = await platform.getTrackingStatus();
    expect(status.serviceRunning, isTrue);
    expect(status.sensorAvailable, isTrue);
    expect(status.lastProgressAgeMs, 999);
    expect(lastCall?.method, 'getTrackingStatus');
  });
}
