import 'package:flutter/material.dart';

class AppUtils {
  /// Formats a DateTime object to DD/MM/YYYY format
  static String formatDate(DateTime date) {
    return '${date.day}/${date.month}/${date.year}';
  }

  /// Formats a TimeOfDay object to HH:MM format
  static String formatTime(TimeOfDay time) {
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
  }

  /// Shows a snackbar with the given message and optional color
  static void showSnackBar(
    BuildContext context,
    String message, {
    Color color = Colors.green,
  }) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message), backgroundColor: color));
  }

  /// Gets a formatted date range string
  static String getFormattedDateRange(DateTime startDate, DateTime endDate) {
    return '${formatDate(startDate)} to ${formatDate(endDate)}';
  }

  static Future<DateTime?> selectDateTime({
    required BuildContext context,
    DateTime? initialDate,
    DateTime? firstDate,
    DateTime? lastDate,
  }) async {
    final firstDateValue =
        firstDate ?? DateTime.now().subtract(const Duration(days: 30));
    return await showDatePicker(
      context: context,
      initialDate: initialDate ?? DateTime.now(),
      firstDate: firstDateValue,
      lastDate: lastDate ?? DateTime.now(),
    );
  }

  static Future<TimeOfDay?> selectTime({
    required BuildContext context,
    TimeOfDay? initialTime,
  }) async {
    return await showTimePicker(
      context: context,
      initialTime: initialTime ?? TimeOfDay.now(),
    );
  }

  static DateTime? applyTimeToDate(DateTime? date, TimeOfDay? time) {
    if (date == null || time == null) return date;
    return DateTime(date.year, date.month, date.day, time.hour, time.minute);
  }
}
