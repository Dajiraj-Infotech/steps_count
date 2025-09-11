import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:steps_count/steps_count.dart';
import 'package:steps_count_example/utils/app_utils.dart';
import 'package:steps_count_example/widgets/common_button.dart';
import 'package:steps_count_example/widgets/date_time_selector.dart';
import 'package:steps_count_example/widgets/step_count_card.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
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

  // Time selection variables
  TimeOfDay? _startTime;
  TimeOfDay? _endTime;

  @override
  void initState() {
    super.initState();
    _stepsChannel.setMethodCallHandler(_methodHandler);
    _checkAllStatus();
    _updateTodayStepCount();
    _updateFilteredStepCount();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    _checkServiceStatus();
  }

  Future<void> _methodHandler(MethodCall call) async {
    if (call.method == "onSensorChanged") {
      _updateTodayStepCount();
      _updateFilteredStepCount();
    }
  }

  Future<void> _checkAllStatus() async {
    await _checkPermissionStatus();
    await _requestPermission();
    await _checkServiceStatus();
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
        await _checkPermissionStatus();
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
      // Today's step count using the new getTodaysCount method
      _todayStepCount = await _stepsCounterPlugin.getTodaysCount();
      debugPrint('Today\'s step count: $_todayStepCount');
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
      DateTime? startDate = _startDate?.toUtc();
      DateTime? endDate = _endDate?.toUtc();

      // Apply time information if available
      if (_startDate != null && _startTime != null) {
        startDate = DateTime(
          _startDate!.year,
          _startDate!.month,
          _startDate!.day,
          _startTime!.hour,
          _startTime!.minute,
        );
      }

      if (_endDate != null && _endTime != null) {
        endDate = DateTime(
          _endDate!.year,
          _endDate!.month,
          _endDate!.day,
          _endTime!.hour,
          _endTime!.minute,
        );
      }

      _stepCount = await _stepsCounterPlugin.getStepCount(
        startDate: startDate,
        endDate: endDate,
      );
      debugPrint('Filtered step count: $_stepCount');
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
      if (!mounted) return;
      setState(() => _isServiceRunning = true);
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
      if (!mounted) return;
      setState(() => _isServiceRunning = false);
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

  bool _isTimeAfter(TimeOfDay time1, TimeOfDay time2) {
    final minutes1 = time1.hour * 60 + time1.minute;
    final minutes2 = time2.hour * 60 + time2.minute;
    return minutes1 < minutes2;
  }

  void _clearDateRange() {
    setState(() {
      _startDate = null;
      _endDate = null;
      _startTime = null;
      _endTime = null;
    });
    _updateTodayStepCount();
    _updateFilteredStepCount();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Steps Count'),
        centerTitle: true,
        forceMaterialTransparency: true,
      ),
      body: _isInitialized ? _buildBody() : _buildLoading(),
    );
  }

  Widget _buildLoading() {
    return const Center(child: CircularProgressIndicator());
  }

  Widget _buildBody() {
    return SafeArea(
      child: Center(
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(20.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                _buildTodayCountContainer(),
                const SizedBox(height: 20),
                _buildDateSelectionSection(),
                const SizedBox(height: 20),
                _buildServiceRequestBtn(),
                const SizedBox(height: 30),
                _buildPermissionBtn(),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDateSelectionSection() {
    return DateTimeSelector(
      startDate: _startDate,
      endDate: _endDate,
      startTime: _startTime,
      endTime: _endTime,
      onStartDateSelected: (date) {
        setState(() {
          _startDate = date;
          if (_endDate != null && _endDate!.isBefore(date!)) {
            _endDate = date;
          }
        });
        _updateFilteredStepCount();
      },
      onEndDateSelected: (date) {
        setState(() => _endDate = date);
        _updateFilteredStepCount();
      },
      onStartTimeSelected: (time) {
        setState(() {
          _startTime = time;
          if (_endTime != null && _isTimeAfter(_endTime!, time!)) {
            _endTime = time;
          }
        });
        _updateFilteredStepCount();
      },
      onEndTimeSelected: (time) {
        setState(() => _endTime = time);
        _updateFilteredStepCount();
      },
      onClearSelection: _clearDateRange,
      child: _buildFilterCountContainer(),
    );
  }

  Widget _buildTodayCountContainer() {
    return StepCountCard(
      title: 'Today\'s Steps',
      subtitle: _hasPermission ? 'Keep it up!' : 'Permission required',
      stepCount: _todayStepCount,
      icon: Icons.directions_walk_rounded,
      primaryColor: Colors.green,
      shadowColor: Colors.green,
      hasPermission: _hasPermission,
    );
  }

  Widget _buildFilterCountContainer() {
    final bool isFiltered = _startDate != null || _endDate != null;
    return StepCountCard(
      title: isFiltered ? 'Filtered Steps' : 'All Time Steps',
      subtitle: isFiltered ? 'Custom date range' : 'Total recorded steps',
      stepCount: _stepCount,
      icon: isFiltered ? Icons.filter_list_rounded : Icons.timeline_rounded,
      primaryColor: Colors.blue,
      shadowColor: Colors.blue,
      hasPermission: _hasPermission,
    );
  }

  Widget _buildServiceRequestBtn() {
    return Row(
      children: [
        Expanded(
          child: CommonButton(
            label: 'Start Service',
            icon: Icons.play_arrow_rounded,
            onPressed: _isServiceRunning ? null : _startService,
            primaryColor: Colors.green.shade500,
            shadowColor: Colors.green,
            isEnabled: !_isServiceRunning,
          ),
        ),
        Expanded(
          child: CommonButton(
            label: 'Stop Service',
            icon: Icons.stop_rounded,
            onPressed: _isServiceRunning ? _stopService : null,
            primaryColor: Colors.red.shade500,
            shadowColor: Colors.red,
            isEnabled: _isServiceRunning,
          ),
        ),
      ],
    );
  }

  Widget _buildPermissionBtn() {
    if (_hasPermission) return const SizedBox.shrink();
    return SizedBox(
      width: double.infinity,
      child: CommonButton(
        label: 'Request Permission',
        icon: Icons.security_rounded,
        onPressed: _requestPermission,
        primaryColor: Colors.orange.shade500,
        shadowColor: Colors.orange,
        isEnabled: true,
      ),
    );
  }
}
