import 'package:flutter/material.dart';
import 'package:steps_count/steps_count.dart';
import 'package:steps_count_example/services/timeline_service.dart';

/// Page for displaying timeline data with step counts and timestamps
class TimelinePage extends StatefulWidget {
  final DateTime? startDate;
  final DateTime? endDate;

  const TimelinePage({super.key, this.startDate, this.endDate});

  @override
  State<TimelinePage> createState() => _TimelinePageState();
}

class _TimelinePageState extends State<TimelinePage> {
  final TimelineService _timelineService = TimelineService();
  List<TimelineModel> _timelineData = [];
  bool _isLoading = true;
  String? _errorMessage;
  TimeZoneType _selectedTimeZone = TimeZoneType.local;

  @override
  void initState() {
    super.initState();
    _loadTimelineData();
  }

  Future<void> _loadTimelineData() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    
    try {
      final timelineData = await _timelineService.getTimelineData(
        startDate: widget.startDate,
        endDate: widget.endDate,
      );

      setState(() {
        _timelineData = timelineData;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = 'Failed to load timeline data: $e';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Timeline'),
        centerTitle: true,
        forceMaterialTransparency: true,
        actions: [_buildTimezoneToggle(), const SizedBox(width: 8)],
      ),
      body: SafeArea(child: _buildBody()),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return _buildLoadingState();
    }

    if (_errorMessage != null) {
      return _buildErrorState();
    }

    if (_timelineData.isEmpty) {
      return _buildEmptyState();
    }

    return _buildTimelineList();
  }

  Widget _buildLoadingState() {
    return const Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          CircularProgressIndicator(),
          SizedBox(height: 16),
          Text(
            'Loading timeline data...',
            style: TextStyle(fontSize: 16, color: Colors.grey),
          ),
        ],
      ),
    );
  }

  Widget _buildErrorState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.error_outline_rounded,
              size: 64,
              color: Colors.red.shade300,
            ),
            const SizedBox(height: 16),
            Text(
              'Error',
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w600,
                color: Colors.red.shade700,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _errorMessage!,
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, color: Colors.grey.shade600),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _loadTimelineData,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Retry'),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.blue,
                foregroundColor: Colors.white,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.timeline_rounded, size: 64, color: Colors.grey.shade300),
            const SizedBox(height: 16),
            Text(
              'No Timeline Data',
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w600,
                color: Colors.grey.shade700,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _getEmptyStateMessage(),
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, color: Colors.grey.shade600),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: () => Navigator.of(context).pop(),
              icon: const Icon(Icons.arrow_back_rounded),
              label: const Text('Go Back'),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.blue,
                foregroundColor: Colors.white,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTimelineList() {
    return Column(
      children: [
        // Summary header
        _buildSummaryHeader(),
        const SizedBox(height: 10),
        // Timeline list
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.only(bottom: 16),
            itemCount: _timelineData.length,
            itemBuilder: (context, index) => _buildTimelineItem(index),
          ),
        ),
      ],
    );
  }

  Widget _buildTimelineItem(int index) {
    final timeline = _timelineData[index];
    final formattedDate = _timelineService.formatDisplayDateWithTimezone(
      timeline.getDateTime(_selectedTimeZone),
      _selectedTimeZone,
    );
    final totalSteps = _timelineService.getTotalStepsForDate(
      _timelineData,
      timeline.timestamp,
    );
    final currentSteps = timeline.stepCount;

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [Colors.white, Colors.blue.shade50],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.blue.shade200, width: 1.5),
        boxShadow: [
          BoxShadow(
            color: Colors.blue.shade100.withValues(alpha: 0.5),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          // Content
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Date and time
                Row(
                  children: [
                    Icon(
                      Icons.access_time_rounded,
                      size: 16,
                      color: Colors.blue.shade600,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Row(
                        children: [
                          Text(
                            formattedDate,
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              color: Colors.blue.shade800,
                            ),
                          ),
                          const SizedBox(width: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: _selectedTimeZone == TimeZoneType.local
                                  ? Colors.green.shade100
                                  : Colors.orange.shade100,
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(
                                color: _selectedTimeZone == TimeZoneType.local
                                    ? Colors.green.shade300
                                    : Colors.orange.shade300,
                              ),
                            ),
                            child: Text(
                              _selectedTimeZone == TimeZoneType.local
                                  ? 'LOCAL'
                                  : 'UTC',
                              style: TextStyle(
                                fontSize: 10,
                                fontWeight: FontWeight.w700,
                                color: _selectedTimeZone == TimeZoneType.local
                                    ? Colors.green.shade700
                                    : Colors.orange.shade700,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),

                // Steps info
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.blue.shade100,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: Colors.blue.shade300),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            Icons.timeline_rounded,
                            size: 14,
                            color: Colors.blue.shade700,
                          ),
                          const SizedBox(width: 4),
                          Text(
                            'Before: ${totalSteps - currentSteps}',
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                              color: Colors.blue.shade700,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    // Current step count
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.green.shade100,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: Colors.green.shade300),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            Icons.add_rounded,
                            size: 14,
                            color: Colors.green.shade700,
                          ),
                          const SizedBox(width: 4),
                          Text(
                            currentSteps.toString(),
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                              color: Colors.green.shade700,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),

          // Steps badge
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [Colors.blue.shade400, Colors.blue.shade600],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(12),
              boxShadow: [
                BoxShadow(
                  color: Colors.blue.shade300.withValues(alpha: 0.5),
                  blurRadius: 4,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.directions_walk_rounded,
                  color: Colors.white,
                  size: 20,
                ),
                const SizedBox(height: 4),
                Text(
                  totalSteps.toString(),
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSummaryHeader() {
    final totalSteps = _timelineData.fold(
      0,
      (sum, item) => sum + item.stepCount,
    );
    final totalEntries = _timelineData.length;
    final dateRange = _getDateRangeText();

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [Colors.blue.shade50, Colors.blue.shade100],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.blue.shade200),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Timeline Summary',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                        color: Colors.blue.shade800,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      dateRange,
                      style: TextStyle(
                        fontSize: 14,
                        color: Colors.blue.shade600,
                      ),
                    ),
                  ],
                ),
              ),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.blue.shade200,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  Icons.timeline_rounded,
                  color: Colors.blue.shade800,
                  size: 24,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: _buildSummaryItem(
                  'Total Steps',
                  totalSteps.toString(),
                  Icons.directions_walk_rounded,
                  Colors.green,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _buildSummaryItem(
                  'Entries',
                  totalEntries.toString(),
                  Icons.list_rounded,
                  Colors.orange,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSummaryItem(
    String label,
    String value,
    IconData icon,
    Color color,
  ) {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.2)),
      ),
      child: Column(
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(height: 4),
          Text(
            value,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w700,
              color: color,
            ),
          ),
          Text(
            label,
            style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
          ),
        ],
      ),
    );
  }

  Widget _buildTimezoneToggle() {
    return PopupMenuButton<TimeZoneType>(
      initialValue: _selectedTimeZone,
      onSelected: (TimeZoneType value) {
        setState(() {
          _selectedTimeZone = value;
        });
        _loadTimelineData();
      },
      itemBuilder: (BuildContext context) => [
        PopupMenuItem<TimeZoneType>(
          value: TimeZoneType.local,
          child: Row(
            children: [
              Icon(
                Icons.location_on_rounded,
                size: 20,
                color: _selectedTimeZone == TimeZoneType.local
                    ? Colors.green.shade600
                    : Colors.grey.shade600,
              ),
              const SizedBox(width: 12),
              Text(
                'Local Time',
                style: TextStyle(
                  fontWeight: _selectedTimeZone == TimeZoneType.local
                      ? FontWeight.w600
                      : FontWeight.normal,
                  color: _selectedTimeZone == TimeZoneType.local
                      ? Colors.green.shade700
                      : Colors.black87,
                ),
              ),
              if (_selectedTimeZone == TimeZoneType.local) ...[
                const Spacer(),
                Icon(
                  Icons.check_rounded,
                  size: 20,
                  color: Colors.green.shade600,
                ),
              ],
            ],
          ),
        ),
        PopupMenuItem<TimeZoneType>(
          value: TimeZoneType.utc,
          child: Row(
            children: [
              Icon(
                Icons.public_rounded,
                size: 20,
                color: _selectedTimeZone == TimeZoneType.utc
                    ? Colors.orange.shade600
                    : Colors.grey.shade600,
              ),
              const SizedBox(width: 12),
              Text(
                'UTC Time',
                style: TextStyle(
                  fontWeight: _selectedTimeZone == TimeZoneType.utc
                      ? FontWeight.w600
                      : FontWeight.normal,
                  color: _selectedTimeZone == TimeZoneType.utc
                      ? Colors.orange.shade700
                      : Colors.black87,
                ),
              ),
              if (_selectedTimeZone == TimeZoneType.utc) ...[
                const Spacer(),
                Icon(
                  Icons.check_rounded,
                  size: 20,
                  color: Colors.orange.shade600,
                ),
              ],
            ],
          ),
        ),
      ],
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Colors.blue.shade100,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: Colors.blue.shade300),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              _selectedTimeZone == TimeZoneType.local
                  ? Icons.location_on_rounded
                  : Icons.public_rounded,
              size: 16,
              color: Colors.blue.shade700,
            ),
            const SizedBox(width: 6),
            Text(
              _selectedTimeZone == TimeZoneType.local ? 'Local' : 'UTC',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: Colors.blue.shade700,
              ),
            ),
            const SizedBox(width: 4),
            Icon(
              Icons.arrow_drop_down_rounded,
              size: 16,
              color: Colors.blue.shade700,
            ),
          ],
        ),
      ),
    );
  }

  String _getDateRangeText() {
    if (widget.startDate == null && widget.endDate == null) {
      return 'All available data';
    }

    final start = widget.startDate != null
        ? _timelineService.formatDisplayDateWithTimezone(
            widget.startDate!,
            _selectedTimeZone,
          )
        : 'Beginning';
    final end = widget.endDate != null
        ? _timelineService.formatDisplayDateWithTimezone(
            widget.endDate!,
            _selectedTimeZone,
          )
        : 'Now';

    return '$start - $end';
  }

  String _getEmptyStateMessage() {
    if (widget.startDate != null || widget.endDate != null) {
      return 'No step data found for the selected date range. Try adjusting your filters or check if the step counting service is running.';
    }
    return 'No step data available. Make sure the step counting service is running and has recorded some data.';
  }
}
