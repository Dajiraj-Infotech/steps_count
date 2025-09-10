import 'package:flutter/material.dart';

class AppUtils {
  /// Formats a DateTime object to DD/MM/YYYY format
  static String formatDate(DateTime date) {
    return '${date.day}/${date.month}/${date.year}';
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
}

extension DateTimeExtension on DateTime {
  DateTime startOfDay() {
    return DateTime.utc(year, month, day, 0, 0, 0, 0, 0);
  }

  DateTime endOfDay() {
    return DateTime.utc(year, month, day, 23, 59, 59, 999, 999);
  }
}
