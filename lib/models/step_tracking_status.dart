/// Health/observability snapshot of step tracking, returned by
/// `StepsCount.getTrackingStatus()`.
///
/// This is primarily meaningful on **Android**, where a foreground service
/// drives a hardware sensor. On **iOS** most fields are not applicable
/// (there is no background service); [sensorAvailable] reflects HealthKit
/// availability and the rest default to safe values.
class StepTrackingStatus {
  /// Whether the Android background service is currently running.
  final bool serviceRunning;

  /// Whether the user has tracking enabled (Android persisted preference).
  final bool trackingEnabled;

  /// Whether a hardware step counter (Android) or HealthKit (iOS) is available.
  final bool sensorAvailable;

  /// Whether the step/activity permission is granted.
  final bool permissionGranted;

  /// Whether notifications are enabled (the foreground-service notification is
  /// hidden when false, so the user cannot see that tracking is on).
  final bool notificationsGranted;

  /// Milliseconds since the last sensor event of any kind, or -1 if unknown.
  final int lastEventAgeMs;

  /// Milliseconds since the last forward progress (an accepted positive step
  /// delta), or -1 if unknown. A large value while [serviceRunning] is true can
  /// indicate a frozen-but-delivering sensor hub.
  final int lastProgressAgeMs;

  const StepTrackingStatus({
    required this.serviceRunning,
    required this.trackingEnabled,
    required this.sensorAvailable,
    required this.permissionGranted,
    required this.notificationsGranted,
    required this.lastEventAgeMs,
    required this.lastProgressAgeMs,
  });

  factory StepTrackingStatus.fromMap(Map<String, dynamic> map) {
    return StepTrackingStatus(
      serviceRunning: map['serviceRunning'] as bool? ?? false,
      trackingEnabled: map['trackingEnabled'] as bool? ?? false,
      sensorAvailable: map['sensorAvailable'] as bool? ?? false,
      permissionGranted: map['permissionGranted'] as bool? ?? false,
      notificationsGranted: map['notificationsGranted'] as bool? ?? false,
      lastEventAgeMs: (map['lastEventAgeMs'] as num?)?.toInt() ?? -1,
      lastProgressAgeMs: (map['lastProgressAgeMs'] as num?)?.toInt() ?? -1,
    );
  }

  @override
  String toString() =>
      'StepTrackingStatus(serviceRunning: $serviceRunning, trackingEnabled: '
      '$trackingEnabled, sensorAvailable: $sensorAvailable, permissionGranted: '
      '$permissionGranted, notificationsGranted: $notificationsGranted, '
      'lastEventAgeMs: $lastEventAgeMs, lastProgressAgeMs: $lastProgressAgeMs)';
}
