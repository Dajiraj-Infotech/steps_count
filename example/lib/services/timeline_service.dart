import 'package:flutter/material.dart';
import 'package:steps_count/steps_count.dart';
import 'package:intl/intl.dart';

/// Service class for managing timeline data operations
class TimelineService {
  static final TimelineService _instance = TimelineService._internal();
  factory TimelineService() => _instance;
  TimelineService._internal();

  final StepsCount _stepsCountPlugin = StepsCount();

  Future<List<TimelineModel>> getTimelineData({
    DateTime? startDate,
    DateTime? endDate,
    TimeOfDay? startTime,
    TimeOfDay? endTime,
    TimeZoneType timeZone = TimeZoneType.local,
  }) async {
    try {
      // Apply time information if available
      DateTime? effectiveStartDate = startDate;
      DateTime? effectiveEndDate = endDate;

      if (startDate != null && startTime != null) {
        effectiveStartDate = DateTime(
          startDate.year,
          startDate.month,
          startDate.day,
          startTime.hour,
          startTime.minute,
        );
      }

      if (endDate != null && endTime != null) {
        effectiveEndDate = DateTime(
          endDate.year,
          endDate.month,
          endDate.day,
          endTime.hour,
          endTime.minute,
        );
      }

      final timeline = await _stepsCountPlugin.getTimeline(
        startDate: effectiveStartDate,
        endDate: effectiveEndDate,
        timeZone: timeZone,
      );

      return timeline.reversed.toList();
    } catch (e) {
      debugPrint('Error fetching timeline data: $e');
      return [];
    }
  }

  /// Calculates total steps for all entries before the given timestamp
  int getTotalStepsForDate(List<TimelineModel> entries, int currentTimestamp) {
    return entries
        .where((entry) => entry.timestamp <= currentTimestamp)
        .fold(0, (total, entry) => total + entry.stepCount);
  }

  /// Formats date for display
  String formatDisplayDate(DateTime date) {
    final dateFormat = DateFormat('dd/MM/yyyy hh:mm:ss a');
    return dateFormat.format(date);
  }

  /// Formats date for display with timezone support
  String formatDisplayDateWithTimezone(DateTime date, TimeZoneType timeZone) {
    final dateFormat = DateFormat('dd/MM/yyyy hh:mm:ss a');
    switch (timeZone) {
      case TimeZoneType.local:
        return dateFormat.format(date);
      case TimeZoneType.utc:
        return dateFormat.format(date.toUtc());
    }
  }
}
