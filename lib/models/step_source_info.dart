/// A HealthKit contributor for step count (iOS). On Android this is unused;
/// [getStepSources] returns an empty list.
class StepSourceInfo {
  /// Localized display name (e.g. "iPhone", "Apple Watch").
  final String name;

  /// App or system bundle identifier (e.g. `com.apple.Health`).
  final String bundleIdentifier;

  const StepSourceInfo({
    required this.name,
    required this.bundleIdentifier,
  });

  factory StepSourceInfo.fromMap(Map<String, dynamic> map) {
    return StepSourceInfo(
      name: map['name'] as String? ?? '',
      bundleIdentifier: map['bundleIdentifier'] as String? ?? '',
    );
  }

  Map<String, dynamic> toMap() => {
        'name': name,
        'bundleIdentifier': bundleIdentifier,
      };
}
