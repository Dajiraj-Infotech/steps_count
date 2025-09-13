import 'timezone_type.dart';

/// Model representing a step count entry with timestamp
class TimelineModel {
  /// Number of steps recorded
  final int stepCount;

  /// Timestamp when the steps were recorded (in milliseconds since epoch)
  final int timestamp;

  /// Creates a new TimelineModel instance
  const TimelineModel({required this.stepCount, required this.timestamp});

  /// Creates a TimelineModel from a Map (typically from native platform)
  factory TimelineModel.fromMap(Map<String, dynamic> map) {
    return TimelineModel(
      stepCount: (map['step_count'] as num?)?.toInt() ?? 0,
      timestamp: (map['timestamp'] as num?)?.toInt() ?? 0,
    );
  }

  /// Converts this TimelineModel to a Map
  Map<String, dynamic> toMap() {
    return {'step_count': stepCount, 'timestamp': timestamp};
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
    return 'TimelineModel(stepCount: $stepCount, timestamp: $timestamp, dateTime: $dateTime)';
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is TimelineModel &&
        other.stepCount == stepCount &&
        other.timestamp == timestamp;
  }

  @override
  int get hashCode => stepCount.hashCode ^ timestamp.hashCode;
}
