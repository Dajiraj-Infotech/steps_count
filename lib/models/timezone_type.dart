/// Enum representing timezone types for timeline data
enum TimeZoneType {
  /// Local timezone (device timezone)
  local,

  /// UTC timezone (Coordinated Universal Time)
  utc;

  /// Returns true if this is local timezone
  bool get isLocal => this == TimeZoneType.local;

  /// Returns true if this is UTC timezone
  bool get isUtc => this == TimeZoneType.utc;

  /// Converts the enum to a boolean for backward compatibility
  /// Returns true for local, false for UTC
  bool toBool() => isLocal;

  /// Creates TimeZoneType from boolean
  /// true = local, false = UTC
  static TimeZoneType fromBool(bool isLocal) {
    return isLocal ? TimeZoneType.local : TimeZoneType.utc;
  }

  /// Returns a human-readable string representation
  String get displayName {
    switch (this) {
      case TimeZoneType.local:
        return 'Local';
      case TimeZoneType.utc:
        return 'UTC';
    }
  }

  @override
  String toString() => displayName;
}
