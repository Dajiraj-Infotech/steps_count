import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:steps_count/steps_count.dart';
import 'package:steps_count_example/utils/app_utils.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _stepCount = 0;
  int _todayStepCount = 0;
  bool _isInitialized = false;
  bool _hasPermission = false;
  bool _isServiceRunning = false;
  final _stepsCounterPlugin = StepsCount();
  final _stepsChannel = MethodChannel('steps_count');

  // Date selection variables
  DateTime? _startDate;
  DateTime? _endDate;

  @override
  void initState() {
    super.initState();
    _stepsChannel.setMethodCallHandler(_methodHandler);
    _checkAllStatus();
  }

  Future<void> _methodHandler(MethodCall call) async {
    _checkServiceStatus();
    if (call.method == "onSensorChanged") {
      _updateTodayStepCount();
      _updateFilteredStepCount();
    }
  }

  Future<void> _checkAllStatus() async {
    await _checkPermissionStatus();
    await Future.delayed(const Duration(seconds: 1));
    await _checkServiceStatus();
    _updateTodayStepCount();
    _updateFilteredStepCount();
    _isInitialized = true;
    setState(() {});
  }

  Future<void> _checkServiceStatus() async {
    _isServiceRunning = await _stepsCounterPlugin.isServiceRunning();
    setState(() {});
  }

  Future<void> _checkPermissionStatus() async {
    final activityRecognitionStatus =
        await Permission.activityRecognition.status;
    final notificationStatus = await Permission.notification.status;
    if (activityRecognitionStatus.isGranted && notificationStatus.isGranted) {
      _hasPermission = true;
    } else {
      _requestPermission();
      _hasPermission = false;
    }
    setState(() {});
  }

  Future<void> _requestPermission() async {
    if (_hasPermission) {
      debugPrint('Permission already granted');
      return;
    }
    try {
      final activityRecognitionStatus = await Permission.activityRecognition
          .request();
      final notificationStatus = await Permission.notification.request();
      if (!mounted) return;
      if (activityRecognitionStatus.isPermanentlyDenied ||
          notificationStatus.isPermanentlyDenied) {
        AppUtils.showSnackBar(
          context,
          'Permission permanently denied',
          color: Colors.red,
        );
        await openAppSettings();
        _requestPermission();
        return;
      }
      if (!mounted) return;
      await _checkPermissionStatus();
      if (!mounted) return;
      if (activityRecognitionStatus.isGranted && notificationStatus.isGranted) {
        _hasPermission = true;
        AppUtils.showSnackBar(context, 'Permission granted!');
      } else {
        _hasPermission = false;
        AppUtils.showSnackBar(context, 'Permission denied', color: Colors.red);
      }
    } catch (e) {
      AppUtils.showSnackBar(
        context,
        'Error requesting permission: $e',
        color: Colors.red,
      );
      debugPrint('Error requesting permission: $e');
    }
    setState(() {});
  }

  Future<void> _updateTodayStepCount() async {
    try {
      // Today's step count
      final now = DateTime.now();
      final todayStartDate = now.startOfDay();
      final todayEndDate = now.endOfDay();
      _todayStepCount = await _stepsCounterPlugin.getStepCount(
        startDate: todayStartDate,
        endDate: todayEndDate,
      );
      if (!mounted) return;
      setState(() {});
    } catch (e) {
      // Silently handle step count errors to avoid spam
      debugPrint('Error updating step count: $e');
    }
  }

  Future<void> _updateFilteredStepCount() async {
    try {
      // Filtered step count
      final startDate = _startDate?.startOfDay();
      final endDate = _endDate?.endOfDay();
      _stepCount = await _stepsCounterPlugin.getStepCount(
        startDate: startDate,
        endDate: endDate,
      );
      if (!mounted) return;
      setState(() {});
    } catch (e) {
      // Silently handle step count errors to avoid spam
      debugPrint('Error updating step count: $e');
    }
  }

  Future<void> _startService() async {
    if (!_hasPermission) {
      AppUtils.showSnackBar(
        context,
        'Please grant permission first',
        color: Colors.red,
      );
      return;
    }
    try {
      await _stepsCounterPlugin.startBackgroundService();
      await _checkServiceStatus();
      if (!mounted) return;
      AppUtils.showSnackBar(context, 'Service started');
      _updateTodayStepCount();
      _updateFilteredStepCount();
    } on PlatformException catch (e) {
      AppUtils.showSnackBar(
        context,
        'Error starting service: ${e.message}',
        color: Colors.red,
      );
      debugPrint('Error starting service: ${e.message}');
    }
  }

  Future<void> _stopService() async {
    if (!_hasPermission) {
      AppUtils.showSnackBar(
        context,
        'Please grant permission first',
        color: Colors.red,
      );
      return;
    }
    try {
      await _stepsCounterPlugin.stopBackgroundService();
      await _checkServiceStatus();
      if (!mounted) return;
      AppUtils.showSnackBar(context, 'Service stopped');
    } on PlatformException catch (e) {
      AppUtils.showSnackBar(
        context,
        'Error stopping service: ${e.message}',
        color: Colors.red,
      );
      debugPrint('Error stopping service: ${e.message}');
    }
  }

  Future<void> _selectStartDate() async {
    final picked = await AppUtils.selectDateTime(
      context: context,
      initialDate: _startDate ?? DateTime.now(),
    );
    if (picked != null && picked != _startDate) {
      _startDate = picked;
      if (_endDate != null && _endDate!.isBefore(picked)) {
        _endDate = picked;
      }
      setState(() {});
      _updateFilteredStepCount();
    }
  }

  Future<void> _selectEndDate() async {
    final picked = await AppUtils.selectDateTime(
      context: context,
      initialDate: _endDate ?? DateTime.now(),
      firstDate: _startDate,
      lastDate: DateTime.now(),
    );
    if (picked != null && picked != _endDate) {
      setState(() => _endDate = picked);
      _updateFilteredStepCount();
    }
  }

  void _clearDateRange() {
    setState(() {
      _startDate = null;
      _endDate = null;
    });
    _updateTodayStepCount();
    _updateFilteredStepCount();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Steps Count'), centerTitle: true),
      body: _isInitialized ? _buildBody() : _buildLoading(),
    );
  }

  Widget _buildLoading() {
    return const Center(child: CircularProgressIndicator());
  }

  Widget _buildBody() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _buildDateSelectionSection(),
            const SizedBox(height: 20),
            Row(
              children: [
                Expanded(child: _buildTodayCountContainer()),
                const SizedBox(width: 10),
                Expanded(child: _buildFilterCountContainer()),
              ],
            ),
            const SizedBox(height: 30),
            _buildServiceRequestBtn(),
            const SizedBox(height: 30),
            _buildPermissionBtn(),
          ],
        ),
      ),
    );
  }

  Widget _buildDateSelectionSection() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
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
                'Date Range Selection',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: Colors.blue.shade800,
                ),
              ),
              if (_startDate != null || _endDate != null)
                TextButton(
                  onPressed: _clearDateRange,
                  child: const Text(
                    'Clear date range',
                    style: TextStyle(color: Colors.red),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 15),
          Row(
            children: [
              Expanded(
                child: _buildDateSelector(
                  label: 'Start Date',
                  date: _startDate,
                  onTap: _selectStartDate,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _buildDateSelector(
                  label: 'End Date',
                  date: _endDate,
                  onTap: _selectEndDate,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

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

  Widget _buildTodayCountContainer() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 30, vertical: 20),
      decoration: BoxDecoration(
        color: _hasPermission ? Colors.green.shade100 : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: _hasPermission ? Colors.green : Colors.grey,
          width: 2,
        ),
      ),
      child: Column(
        children: [
          Text(
            'Today\'s Steps',
            style: Theme.of(context).textTheme.titleLarge,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 10),
          Text(
            '$_todayStepCount',
            style: Theme.of(context).textTheme.headlineLarge?.copyWith(
              color: _hasPermission
                  ? Colors.green.shade700
                  : Colors.grey.shade600,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFilterCountContainer() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 30, vertical: 20),
      decoration: BoxDecoration(
        color: _hasPermission ? Colors.green.shade100 : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: _hasPermission ? Colors.green : Colors.grey,
          width: 2,
        ),
      ),
      child: Column(
        children: [
          Text(
            _startDate != null || _endDate != null
                ? 'Steps\n(Filtered)'
                : 'Steps\n(All Time)',
            style: Theme.of(context).textTheme.titleLarge,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 10),
          Text(
            '$_stepCount',
            style: Theme.of(context).textTheme.headlineLarge?.copyWith(
              color: _hasPermission
                  ? Colors.green.shade700
                  : Colors.grey.shade600,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildServiceRequestBtn() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        ElevatedButton.icon(
          onPressed: _isServiceRunning ? null : _startService,
          icon: const Icon(Icons.play_arrow),
          label: const Text('Start Service'),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.green,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
        ),
        ElevatedButton.icon(
          onPressed: _isServiceRunning ? _stopService : null,
          icon: const Icon(Icons.stop),
          label: const Text('Stop Service'),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.red,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
        ),
      ],
    );
  }

  Widget _buildPermissionBtn() {
    if (_hasPermission) return const SizedBox.shrink();
    return ElevatedButton(
      onPressed: _requestPermission,
      style: ElevatedButton.styleFrom(
        backgroundColor: Colors.green,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      ),
      child: const Text('Request Permission'),
    );
  }
}
