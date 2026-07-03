import 'timezone_type.dart';

/// Model representing a step count entry with timestamp
class TimelineModel {
  /// Unique identifier: matches the SQLite uuid column on Android
  /// and the HealthKit/Health Connect UUID on iOS/Android health platforms.
  final String? uuid;

  /// Number of steps recorded
  final int stepCount;

  /// Timestamp when the steps were recorded (in milliseconds since epoch).
  ///
  /// On Android this is the END of the interval the steps are attributed to
  /// (see [startTimestamp]). On iOS it is the HealthKit sample start time.
  final int timestamp;

  /// Start of the interval the steps are attributed to (milliseconds since
  /// epoch). On Android, a recovered catch-up window spans [startTimestamp]
  /// to [timestamp]; for a normal live entry the two are close. Falls back to
  /// [timestamp] when the platform does not provide it.
  final int startTimestamp;

  /// How the entry was recorded (Android): `live` (attributed to real event
  /// times), `gap`/`boot_gap` (a recovered downtime window spread across the
  /// interval), or `legacy` (a pre-interval point row). Empty on iOS.
  final String source;

  /// Bit flags on the entry (Android): bit 1 = clock-clamped, bit 2 = low
  /// sensor accuracy. 0 when not applicable.
  final int flags;

  /// True when the entry is a recovered/estimated window rather than a live
  /// reading (i.e. [source] is `gap` or `boot_gap`).
  bool get isEstimated => source == 'gap' || source == 'boot_gap';

  /// Creates a new TimelineModel instance
  const TimelineModel({
    this.uuid,
    required this.stepCount,
    required this.timestamp,
    int? startTimestamp,
    this.source = '',
    this.flags = 0,
  }) : startTimestamp = startTimestamp ?? timestamp;

  /// Creates a TimelineModel from a Map (typically from native platform)
  factory TimelineModel.fromMap(Map<String, dynamic> map) {
    final timestamp = (map['timestamp'] as num?)?.toInt() ?? 0;
    return TimelineModel(
      uuid: map['uuid'] as String?,
      stepCount: (map['step_count'] as num?)?.toInt() ?? 0,
      timestamp: timestamp,
      startTimestamp: (map['start_timestamp'] as num?)?.toInt() ?? timestamp,
      source: (map['source'] as String?) ?? '',
      flags: (map['flags'] as num?)?.toInt() ?? 0,
    );
  }

  /// Converts this TimelineModel to a Map
  Map<String, dynamic> toMap() {
    return {
      if (uuid != null) 'uuid': uuid,
      'step_count': stepCount,
      'timestamp': timestamp,
      'start_timestamp': startTimestamp,
      if (source.isNotEmpty) 'source': source,
      'flags': flags,
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
