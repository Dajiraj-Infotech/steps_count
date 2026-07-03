import 'package:flutter_test/flutter_test.dart';
import 'package:steps_count/steps_count.dart';

void main() {
  group('TimelineModel', () {
    test('parses interval fields and defaults start to end when absent', () {
      final live = TimelineModel.fromMap({
        'uuid': 'abc',
        'step_count': 120,
        'timestamp': 2000,
        'start_timestamp': 1000,
        'source': 'live',
        'flags': 0,
      });
      expect(live.uuid, 'abc');
      expect(live.stepCount, 120);
      expect(live.timestamp, 2000);
      expect(live.startTimestamp, 1000);
      expect(live.source, 'live');
      expect(live.isEstimated, isFalse);

      // A legacy/point row with no start_timestamp: start falls back to end.
      final legacy = TimelineModel.fromMap({'step_count': 5, 'timestamp': 42});
      expect(legacy.startTimestamp, 42);
      expect(legacy.source, '');
      expect(legacy.flags, 0);
    });

    test('gap and boot_gap rows are flagged as estimated', () {
      final gap = TimelineModel.fromMap({
        'step_count': 3000,
        'timestamp': 9000,
        'start_timestamp': 1000,
        'source': 'gap',
      });
      expect(gap.isEstimated, isTrue);

      final bootGap = TimelineModel.fromMap({
        'step_count': 100,
        'timestamp': 5,
        'source': 'boot_gap',
      });
      expect(bootGap.isEstimated, isTrue);
    });

    test('toMap round-trips through fromMap', () {
      const original = TimelineModel(
        uuid: 'u1',
        stepCount: 50,
        timestamp: 3000,
        startTimestamp: 2500,
        source: 'live',
        flags: 2,
      );
      final restored = TimelineModel.fromMap(original.toMap());
      expect(restored.stepCount, original.stepCount);
      expect(restored.startTimestamp, original.startTimestamp);
      expect(restored.source, original.source);
      expect(restored.flags, original.flags);
    });
  });

  group('StepTrackingStatus', () {
    test('parses a full map', () {
      final status = StepTrackingStatus.fromMap({
        'serviceRunning': true,
        'trackingEnabled': true,
        'sensorAvailable': true,
        'permissionGranted': false,
        'notificationsGranted': true,
        'lastEventAgeMs': 1234,
        'lastProgressAgeMs': 5678,
      });
      expect(status.serviceRunning, isTrue);
      expect(status.permissionGranted, isFalse);
      expect(status.lastEventAgeMs, 1234);
      expect(status.lastProgressAgeMs, 5678);
    });

    test('defaults are safe for an empty map', () {
      final status = StepTrackingStatus.fromMap(const {});
      expect(status.serviceRunning, isFalse);
      expect(status.sensorAvailable, isFalse);
      expect(status.lastEventAgeMs, -1);
      expect(status.lastProgressAgeMs, -1);
    });
  });
}
