import 'package:flutter/material.dart';
import '../utils/app_utils.dart';

/// A callback function type for date selection
typedef DateSelectedCallback = void Function(DateTime? date);

/// A callback function type for time selection
typedef TimeSelectedCallback = void Function(TimeOfDay? time);

/// A callback function type for clearing all selections
typedef ClearSelectionCallback = void Function();

/// A reusable widget for date and time selection with callbacks
class DateTimeSelector extends StatelessWidget {
  /// The selected start date
  final DateTime? startDate;

  /// The selected end date
  final DateTime? endDate;

  /// The selected start time
  final TimeOfDay? startTime;

  /// The selected end time
  final TimeOfDay? endTime;

  /// Callback when start date is selected
  final DateSelectedCallback? onStartDateSelected;

  /// Callback when end date is selected
  final DateSelectedCallback? onEndDateSelected;

  /// Callback when start time is selected
  final TimeSelectedCallback? onStartTimeSelected;

  /// Callback when end time is selected
  final TimeSelectedCallback? onEndTimeSelected;

  /// Callback when clear selection is pressed
  final ClearSelectionCallback? onClearSelection;

  /// Whether to show the clear button
  final bool showClearButton;

  /// Custom title for the selector
  final String? title;

  /// Custom styling for the container
  final BoxDecoration? containerDecoration;

  /// Custom text style for the title
  final TextStyle? titleStyle;

  final Widget child;

  const DateTimeSelector({
    super.key,
    this.startDate,
    this.endDate,
    this.startTime,
    this.endTime,
    this.onStartDateSelected,
    this.onEndDateSelected,
    this.onStartTimeSelected,
    this.onEndTimeSelected,
    this.onClearSelection,
    this.showClearButton = true,
    this.title,
    this.containerDecoration,
    this.titleStyle,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(15),
      decoration:
          containerDecoration ??
          BoxDecoration(
            color: Colors.blue.shade50,
            borderRadius: BorderRadius.circular(15),
            border: Border.all(color: Colors.blue.shade200, width: 1),
          ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                title ?? 'Date & Time Selection',
                style:
                    titleStyle ??
                    Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: Colors.blue.shade800,
                    ),
              ),
              if (showClearButton && _hasAnySelection())
                GestureDetector(
                  onTap: onClearSelection,
                  child: const Text(
                    'Clear filters',
                    style: TextStyle(color: Colors.red),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _buildDateSelector(
                  label: 'Start Date',
                  date: startDate,
                  onTap: () => _selectStartDate(context),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _buildDateSelector(
                  label: 'End Date',
                  date: endDate,
                  onTap: () => _selectEndDate(context),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _buildTimeSelector(
                  label: 'Start Time',
                  time: startTime,
                  onTap: () => _selectStartTime(context),
                  isEnabled: startDate != null,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _buildTimeSelector(
                  label: 'End Time',
                  time: endTime,
                  onTap: () => _selectEndTime(context),
                  isEnabled: endDate != null,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          child,
        ],
      ),
    );
  }

  /// Checks if any date or time is selected
  bool _hasAnySelection() {
    return startDate != null ||
        endDate != null ||
        startTime != null ||
        endTime != null;
  }

  /// Shows a message that date selection is required before time selection
  void _showDateRequiredMessage(BuildContext context, String dateType) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Please select $dateType first before selecting time'),
        backgroundColor: Colors.orange,
        duration: const Duration(seconds: 2),
      ),
    );
  }

  /// Handles start date selection
  Future<void> _selectStartDate(BuildContext context) async {
    final picked = await AppUtils.selectDateTime(
      context: context,
      initialDate: startDate ?? DateTime.now(),
    );
    if (picked != null && picked != startDate) {
      onStartDateSelected?.call(picked);
      onStartTimeSelected?.call(TimeOfDay(hour: 0, minute: 0));
    }
  }

  /// Handles end date selection
  Future<void> _selectEndDate(BuildContext context) async {
    final picked = await AppUtils.selectDateTime(
      context: context,
      initialDate: endDate ?? DateTime.now(),
      firstDate: startDate,
      lastDate: DateTime.now(),
    );
    if (picked != null && picked != endDate) {
      onEndDateSelected?.call(picked);
      onEndTimeSelected?.call(TimeOfDay(hour: 23, minute: 59));
    }
  }

  /// Handles start time selection
  Future<void> _selectStartTime(BuildContext context) async {
    if (startDate == null) {
      _showDateRequiredMessage(context, 'Start Date');
      return;
    }

    final picked = await AppUtils.selectTime(
      context: context,
      initialTime: startTime,
    );
    if (picked != null && picked != startTime) {
      onStartTimeSelected?.call(picked);
    }
  }

  /// Handles end time selection
  Future<void> _selectEndTime(BuildContext context) async {
    if (endDate == null) {
      _showDateRequiredMessage(context, 'End Date');
      return;
    }

    final picked = await AppUtils.selectTime(
      context: context,
      initialTime: endTime,
    );
    if (picked != null && picked != endTime) {
      onEndTimeSelected?.call(picked);
    }
  }

  /// Builds a date selector widget
  Widget _buildDateSelector({
    required String label,
    required DateTime? date,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Colors.blue.shade300),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: Colors.blue.shade700,
                fontWeight: FontWeight.w500,
              ),
            ),
            const SizedBox(height: 4),
            Row(
              children: [
                Icon(
                  Icons.calendar_today,
                  size: 16,
                  color: Colors.blue.shade600,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    date != null ? AppUtils.formatDate(date) : 'Select date',
                    style: TextStyle(
                      fontSize: 14,
                      color: date != null
                          ? Colors.black87
                          : Colors.grey.shade600,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// Builds a time selector widget
  Widget _buildTimeSelector({
    required String label,
    required TimeOfDay? time,
    required VoidCallback onTap,
    required bool isEnabled,
  }) {
    return GestureDetector(
      onTap: isEnabled ? onTap : null,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: isEnabled ? Colors.white : Colors.grey.shade100,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isEnabled ? Colors.blue.shade300 : Colors.grey.shade400,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: isEnabled ? Colors.blue.shade700 : Colors.grey.shade500,
                fontWeight: FontWeight.w500,
              ),
            ),
            const SizedBox(height: 4),
            Row(
              children: [
                Icon(
                  Icons.access_time,
                  size: 16,
                  color: isEnabled
                      ? Colors.blue.shade600
                      : Colors.grey.shade400,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    time != null
                        ? AppUtils.formatTime(time)
                        : (isEnabled ? 'Select time' : 'Select date first'),
                    style: TextStyle(
                      fontSize: 14,
                      color: time != null
                          ? Colors.black87
                          : (isEnabled
                                ? Colors.grey.shade600
                                : Colors.grey.shade500),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
