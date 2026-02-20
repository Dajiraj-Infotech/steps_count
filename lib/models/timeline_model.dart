import 'timezone_type.dart';

/// Model representing a step count entry with timestamp
class TimelineModel {
  /// Unique identifier — matches the SQLite uuid column on Android
  /// and the HealthKit/Health Connect UUID on iOS/Android health platforms.
  final String? uuid;

  /// Number of steps recorded
  final int stepCount;

  /// Timestamp when the steps were recorded (in milliseconds since epoch)
  final int timestamp;

  /// Creates a new TimelineModel instance
  const TimelineModel({
    this.uuid,
    required this.stepCount,
    required this.timestamp,
  });

  /// Creates a TimelineModel from a Map (typically from native platform)
  factory TimelineModel.fromMap(Map<String, dynamic> map) {
    return TimelineModel(
      uuid: map['uuid'] as String?,
      stepCount: (map['step_count'] as num?)?.toInt() ?? 0,
      timestamp: (map['timestamp'] as num?)?.toInt() ?? 0,
    );
  }

  /// Converts this TimelineModel to a Map
  Map<String, dynamic> toMap() {
    return {
      if (uuid != null) 'uuid': uuid,
      'step_count': stepCount,
      'timestamp': timestamp,
    };
  }

  /// Gets the DateTime representation of the timestamp in local timezone
  DateTime get dateTime => DateTime.fromMillisecondsSinceEpoch(timestamp);

  /// Gets the DateTime representation of the timestamp in UTC
  DateTime get dateTimeUtc =>
      DateTime.fromMillisecondsSinceEpoch(timestamp, isUtc: true);

  /// Gets the DateTime representation based on the specified timezone type
  DateTime getDateTime(TimeZoneType timeZone) {
    switch (timeZone) {
      case TimeZoneType.local:
        return dateTime;
      case TimeZoneType.utc:
        return dateTimeUtc;
    }
  }

  @override
  String toString() {
    return 'TimelineModel(uuid: $uuid, stepCount: $stepCount, timestamp: $timestamp, dateTime: $dateTime)';
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is TimelineModel &&
        other.uuid == uuid &&
        other.stepCount == stepCount &&
        other.timestamp == timestamp;
  }

  @override
  int get hashCode => uuid.hashCode ^ stepCount.hashCode ^ timestamp.hashCode;
}
