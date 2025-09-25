/// Enumeration of supported health data types for querying health information.
///
/// This enum defines the various types of health data that can be requested
/// and retrieved from health platforms like HealthKit (iOS) or Health Connect (Android).
enum HealthDataType {
  /// Step count data - number of steps taken
  stepCount('stepCount');

  const HealthDataType(this.identifier);

  /// The string identifier used for platform communication
  final String identifier;
}
